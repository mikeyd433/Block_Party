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
import com.blockparty.core.model.Business
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
enum class DrawPurpose { FURNITURE, FACADE, SHOPKEEPER, OFFERING }

sealed interface Screen {
    data object Street : Screen
    data object ShopEditor : Screen
    data object OfferingEditor : Screen
    data class Draw(val kind: SpriteKind, val purpose: DrawPurpose) : Screen
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

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.load() }
            world = loaded
            val decoded = withContext(Dispatchers.IO) {
                loaded.sprites.mapNotNull { s -> sprites.decode(s.id)?.let { s.id to it } }
            }
            sprites.images.putAll(decoded)
            fixFloatingBoxes()
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveRequests.consumeAsFlow().collect { repository.save(it) }
        }
    }

    val homeLot: Lot? get() = world?.lot(WorldFactory.HOME_LOT_ID)

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
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            artistName = world?.playerName.orEmpty(),
            createdAtMillis = System.currentTimeMillis(),
        )
        sprites.images[asset.id] = bitmap.asImageBitmap()
        viewModelScope.launch(Dispatchers.IO) { sprites.writeFile(asset.id, bitmap) }
        update { it.copy(sprites = it.sprites + asset) }
        back()
        when (purpose) {
            DrawPurpose.FURNITURE -> placingSpriteId = asset.id
            DrawPurpose.FACADE -> updateBusiness { it.copy(facadeSpriteId = asset.id) }
            DrawPurpose.SHOPKEEPER -> updateBusiness { it.copy(shopkeeperSpriteId = asset.id) }
            DrawPurpose.OFFERING -> updateDraft { it.copy(spriteId = asset.id) }
        }
    }

    fun deleteSprite(id: String) {
        update { w ->
            w.copy(
                sprites = w.sprites.filterNot { it.id == id },
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

    fun removeFurniture(id: String) = updateHomeLot { l -> l.copy(furniture = l.furniture.filterNot { it.id == id }) }

    private fun newId() = UUID.randomUUID().toString()
}

fun List<String>.toggle(id: String): List<String> = if (id in this) this - id else this + id
