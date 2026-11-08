package com.blockparty.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blockparty.app.data.SpriteStore
import com.blockparty.app.data.WorldRepository
import com.blockparty.core.art.ImageOps
import com.blockparty.app.data.StarterSet
import com.blockparty.core.model.Business
import com.blockparty.core.model.DisplaySpot
import com.blockparty.core.sim.DisplayStock
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.npc.NpcGenerator
import com.blockparty.core.npc.StreetCrowd
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.Lot
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.WorldState
import com.blockparty.core.sim.Placement
import com.blockparty.core.world.WorldFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What a new drawing will be used for once it's saved. */
enum class DrawPurpose { FURNITURE, FACADE, SHOPKEEPER, OFFERING, CUSTOMER }

sealed interface Screen {
    data object Street : Screen
    data object ShopEditor : Screen
    data object OfferingEditor : Screen
    data class Draw(val kind: SpriteKind, val purpose: DrawPurpose) : Screen
    /** Display spots of a placed piece's drawing, and what this placed piece shows on them. */
    data class DisplayEditor(val placementId: String) : Screen
}

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WorldRepository(app)
    val sprites = SpriteStore(app)

    var world by mutableStateOf<WorldState?>(null)
        private set

    var backStack by mutableStateOf<List<Screen>>(listOf(Screen.Street))
        private set
    val screen: Screen get() = backStack.last()

    /** Offering being created or edited; kept here so it survives a trip to the drawing screen. */
    var offeringDraft by mutableStateOf<Offering?>(null)
        private set

    /** Furniture sprite the player is currently placing, if any. */
    var placingSpriteId by mutableStateOf<String?>(null)
        private set

    private val saveRequests = Channel<WorldState>(Channel.CONFLATED)

    /** Customers walking the street. Lives here so it survives trips to the editors. */
    val crowd = StreetCrowd()
    var crowdWarmedUp = false

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.load() }
            world = loaded
            val decoded = withContext(Dispatchers.IO) {
                loaded.sprites.mapNotNull { s -> sprites.decode(s.id)?.let { s.id to it } }
            }
            sprites.images.putAll(decoded)
            ensureStarterSet()
            fixFloatingBoxes()
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveRequests.consumeAsFlow().collect { repository.save(it) }
        }
    }

    val homeLot: Lot? get() = world?.lot(WorldFactory.HOME_LOT_ID)

    /** Adds the default starter sprites (and starter customers) the first time a world loads. */
    private suspend fun ensureStarterSet() {
        val current = world ?: return
        if (current.starterSetVersion >= StarterSet.VERSION) return
        val lotWidth = homeLot?.rect?.w ?: WorldFactory.LOT_WIDTH
        val items = withContext(Dispatchers.Default) { StarterSet.generate(lotWidth) }
        val existing = current.sprites.map { it.id }.toSet()
        val added = items.filter { it.asset.id !in existing }
        // Newer starter versions can add display spots to pieces this world already has.
        val newSpots = items.filter { it.asset.id in existing && it.asset.displaySpots.isNotEmpty() }
            .associate { it.asset.id to it.asset.displaySpots }
        withContext(Dispatchers.IO) { added.forEach { sprites.writeFile(it.asset.id, it.bitmap) } }
        added.forEach { sprites.images[it.asset.id] = it.bitmap.asImageBitmap() }
        update { w ->
            val newSprites = added.map { it.asset }
            val npcIds = w.npcs.map { it.spriteId }.toSet()
            val newNpcs = newSprites
                .filter { it.role == CharacterRole.CUSTOMER && it.id !in npcIds }
                .map { NpcGenerator.profile("npc-${it.id}", it.id, w.playerLevel) }
            val upgraded = w.sprites.map { s ->
                newSpots[s.id]?.takeIf { s.displaySpots.isEmpty() }?.let { s.copy(displaySpots = it) } ?: s
            }
            w.copy(sprites = upgraded + newSprites, npcs = w.npcs + newNpcs, starterSetVersion = StarterSet.VERSION)
        }
    }

    /**
     * Furniture turned into 3D before base points were recorded still rests on the bottom of its
     * picture and looks like it's floating. Find the base from the drawing's shape and fix it.
     */
    private suspend fun fixFloatingBoxes() {
        val candidates = world?.sprites.orEmpty().filter {
            it.kind == SpriteKind.FURNITURE && !it.alignToGrid && it.groundX == 0.5f && it.groundY == 1f
        }
        if (candidates.isEmpty()) return
        val fixes = withContext(Dispatchers.Default) {
            candidates.mapNotNull { sprite ->
                val bitmap = sprites.images[sprite.id]?.asAndroidBitmap() ?: return@mapNotNull null
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
                ImageOps.estimateBoxBase(pixels, w, h)?.let { (x, y) -> sprite.id to (x / w to y / h) }
            }.toMap()
        }
        if (fixes.isEmpty()) return
        update { w ->
            w.copy(sprites = w.sprites.map { s ->
                fixes[s.id]?.let { (gx, gy) -> s.copy(groundX = gx, groundY = gy) } ?: s
            })
        }
    }

    private fun update(transform: (WorldState) -> WorldState) {
        val current = world ?: return
        val next = transform(current)
        world = next
        saveRequests.trySend(next)
    }

    private fun updateHomeLot(transform: (Lot) -> Lot) = update { it.updateLot(WorldFactory.HOME_LOT_ID, transform) }

    private fun updateBusiness(transform: (Business) -> Business) =
        updateHomeLot { lot -> lot.copy(business = transform(lot.business ?: Business(name = "My Shop"))) }

    // --- Navigation ---------------------------------------------------------------------------

    fun navigate(to: Screen) {
        backStack = backStack + to
    }

    fun back() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }

    // --- Player & business identity -----------------------------------------------------------

    fun setPlayerName(name: String) = update { it.copy(playerName = name) }
    fun setBusinessName(name: String) = updateBusiness { it.copy(name = name) }
    fun setBusinessDescription(text: String) = updateBusiness { it.copy(description = text) }
    fun setBusinessMode(mode: com.blockparty.core.model.BusinessMode) = updateBusiness { it.copy(mode = mode) }
    fun toggleBusinessTag(tagId: String) = updateBusiness { it.copy(tagIds = it.tagIds.toggle(tagId)) }
    fun setFacade(spriteId: String) = updateBusiness { it.copy(facadeSpriteId = spriteId) }
    fun clearFacade() = updateBusiness { it.copy(facadeSpriteId = null) }
    fun clearShopkeeper() = updateBusiness { it.copy(shopkeeperSpriteId = null) }

    // --- Offerings ----------------------------------------------------------------------------

    fun startOffering(kind: OfferingKind) {
        offeringDraft = Offering(id = newId(), kind = kind, name = "", priceCoins = 10)
        navigate(Screen.OfferingEditor)
    }

    fun editOffering(offering: Offering) {
        offeringDraft = offering
        navigate(Screen.OfferingEditor)
    }

    fun updateDraft(transform: (Offering) -> Offering) {
        offeringDraft = offeringDraft?.let(transform)
    }

    fun saveDraft() {
        val draft = offeringDraft ?: return
        updateBusiness { b ->
            val exists = b.offerings.any { it.id == draft.id }
            b.copy(offerings = if (exists) b.offerings.map { if (it.id == draft.id) draft else it } else b.offerings + draft)
        }
        offeringDraft = null
        back()
    }

    fun deleteDraft() {
        val draft = offeringDraft ?: return
        updateBusiness { b -> b.copy(offerings = b.offerings.filterNot { it.id == draft.id }) }
        offeringDraft = null
        back()
    }

    fun discardDraft() {
        offeringDraft = null
        back()
    }

    // --- Drawings -----------------------------------------------------------------------------

    /** Called by the drawing screen with the finished, cropped, transparent bitmap. */
    fun saveDrawing(bitmap: Bitmap, template: SpriteAsset, purpose: DrawPurpose) {
        val asset = template.copy(
            id = newId(),
            role = when (purpose) {
                DrawPurpose.CUSTOMER -> CharacterRole.CUSTOMER
                DrawPurpose.SHOPKEEPER -> CharacterRole.SHOPKEEPER
                else -> template.role
            },
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            artistName = world?.playerName.orEmpty(),
            createdAtMillis = System.currentTimeMillis(),
        )
        sprites.images[asset.id] = bitmap.asImageBitmap()
        viewModelScope.launch(Dispatchers.IO) { sprites.writeFile(asset.id, bitmap) }
        update { w ->
            // Customers join the street's NPC pool; the game picks their tastes and budget.
            val npcs = if (purpose == DrawPurpose.CUSTOMER) w.npcs + NpcGenerator.profile("npc-${asset.id}", asset.id, w.playerLevel) else w.npcs
            w.copy(sprites = w.sprites + asset, npcs = npcs)
        }
        back()
        when (purpose) {
            DrawPurpose.FURNITURE -> placingSpriteId = asset.id
            DrawPurpose.FACADE -> updateBusiness { it.copy(facadeSpriteId = asset.id) }
            DrawPurpose.SHOPKEEPER -> updateBusiness { it.copy(shopkeeperSpriteId = asset.id) }
            DrawPurpose.OFFERING -> updateDraft { it.copy(spriteId = asset.id) }
            DrawPurpose.CUSTOMER -> Unit
        }
    }

    fun deleteSprite(id: String) {
        update { w ->
            w.copy(
                sprites = w.sprites.filterNot { it.id == id },
                npcs = w.npcs.filterNot { it.spriteId == id },
                street = w.street.copy(lots = w.street.lots.map { lot ->
                    lot.copy(
                        furniture = lot.furniture.filterNot { it.spriteId == id },
                        business = lot.business?.let { b ->
                            b.copy(
                                facadeSpriteId = b.facadeSpriteId.takeUnless { it == id },
                                shopkeeperSpriteId = b.shopkeeperSpriteId.takeUnless { it == id },
                                offerings = b.offerings.map { o -> if (o.spriteId == id) o.copy(spriteId = null) else o },
                            )
                        },
                    )
                }),
            )
        }
        if (placingSpriteId == id) placingSpriteId = null
        if (offeringDraft?.spriteId == id) updateDraft { it.copy(spriteId = null) }
        sprites.images.remove(id)
        viewModelScope.launch(Dispatchers.IO) { sprites.deleteFile(id) }
    }

    // --- Furniture ----------------------------------------------------------------------------

    fun startPlacing(spriteId: String?) {
        placingSpriteId = spriteId
    }

    /** Places the current furniture at a tile. Returns the new placement, or null if it doesn't fit. */
    fun placeFurniture(tileX: Int, tileY: Int): FurniturePlacement? {
        val w = world ?: return null
        val lot = homeLot ?: return null
        val spriteId = placingSpriteId ?: return null
        val rect = Placement.footprint(w.sprite(spriteId), tileX, tileY, flipped = false)
        if (!Placement.canPlace(lot, rect, w::sprite)) return null
        val placement = FurniturePlacement(newId(), spriteId, tileX, tileY)
        updateHomeLot { it.copy(furniture = it.furniture + placement) }
        return placement
    }

    /** Moves a placed piece so its origin is at the tile, if it fits there. */
    fun moveFurniture(id: String, tileX: Int, tileY: Int): Boolean {
        val w = world ?: return false
        val lot = homeLot ?: return false
        val piece = lot.furniture.firstOrNull { it.id == id } ?: return false
        val rect = Placement.footprint(w.sprite(piece.spriteId), tileX, tileY, piece.flipped)
        if (!Placement.canPlace(lot, rect, w::sprite, ignoreId = id)) return false
        updateHomeLot { l -> l.copy(furniture = l.furniture.map { if (it.id == id) it.copy(x = tileX, y = tileY) else it }) }
        return true
    }

    /** Mirrors a piece (which transposes its footprint). Returns false if the mirrored piece wouldn't fit. */
    fun flipFurniture(id: String): Boolean {
        val w = world ?: return false
        val lot = homeLot ?: return false
        val piece = lot.furniture.firstOrNull { it.id == id } ?: return false
        val rect = Placement.footprint(w.sprite(piece.spriteId), piece.x, piece.y, !piece.flipped)
        if (!Placement.canPlace(lot, rect, w::sprite, ignoreId = id)) return false
        updateHomeLot { l -> l.copy(furniture = l.furniture.map { if (it.id == id) it.copy(flipped = !it.flipped) else it }) }
        return true
    }

    /** Switches a furniture drawing between leaning into the grid and standing upright. */
    fun setAlignToGrid(spriteId: String, align: Boolean) = update { w ->
        w.copy(sprites = w.sprites.map { if (it.id == spriteId) it.copy(alignToGrid = align) else it })
    }

    /** Replaces a furniture drawing's display spots (shared by every placed copy). */
    fun setDisplaySpots(spriteId: String, spots: List<DisplaySpot>) = update { w ->
        w.copy(sprites = w.sprites.map { if (it.id == spriteId) it.copy(displaySpots = spots) else it })
    }

    /** Puts a product on a spot of one placed piece, or empties the spot (null). */
    fun setStock(placementId: String, spotId: String, offeringId: String?) = updateHomeLot { l ->
        l.copy(furniture = l.furniture.map { p ->
            if (p.id != placementId) p
            else p.copy(stock = if (offeringId == null) p.stock - spotId else p.stock + (spotId to offeringId))
        })
    }

    /** Fills a placed display piece's empty spots with the shop's products. */
    fun autoFillStock(placementId: String) {
        val w = world ?: return
        val lot = homeLot ?: return
        val piece = lot.furniture.firstOrNull { it.id == placementId } ?: return
        val spots = w.sprite(piece.spriteId)?.displaySpots.orEmpty()
        val products = lot.business?.offerings.orEmpty()
            .filter { it.kind == com.blockparty.core.model.OfferingKind.PRODUCT }.map { it.id }
        val filled = DisplayStock.autoFill(spots, piece.stock, products)
        updateHomeLot { l -> l.copy(furniture = l.furniture.map { if (it.id == placementId) it.copy(stock = filled) else it }) }
    }

    fun removeFurniture(id: String) = updateHomeLot { l -> l.copy(furniture = l.furniture.filterNot { it.id == id }) }

    private fun newId() = UUID.randomUUID().toString()
}

fun List<String>.toggle(id: String): List<String> = if (id in this) this - id else this + id
