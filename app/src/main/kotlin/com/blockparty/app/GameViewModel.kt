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
import com.blockparty.core.model.Review
import com.blockparty.core.npc.CrowdEvent
import com.blockparty.core.npc.Applicant
import com.blockparty.core.npc.Staffing
import com.blockparty.core.model.DisplaySpot
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.sim.DisplayStock
import com.blockparty.core.sim.Economy
import com.blockparty.core.sim.Lots
import com.blockparty.core.model.LotState
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
enum class DrawPurpose { FURNITURE, FACADE, SHOPKEEPER, OFFERING, CUSTOMER, EMPLOYEE }

sealed interface Screen {
    data object Street : Screen
    data object ShopEditor : Screen
    data object OfferingEditor : Screen
    data class Draw(val kind: SpriteKind, val purpose: DrawPurpose) : Screen
    data object JobBoard : Screen
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
            // Bills are settled at local midnight; check now and then for the day changing.
            while (true) {
                settleEconomy()
                kotlinx.coroutines.delay(30_000)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveRequests.consumeAsFlow().collect { repository.save(it) }
        }
    }

    /** The shop the player is looking at / editing. */
    var currentLotId by mutableStateOf(WorldFactory.HOME_LOT_ID)
        private set

    val currentLot: Lot?
        get() = world?.let { w ->
            w.lot(currentLotId) ?: w.street.lots.firstOrNull { it.state == LotState.OWNED } ?: w.street.lots.firstOrNull()
        }

    fun selectLot(lotId: String) {
        currentLotId = lotId
        placingSpriteId = null
    }

    /** Messages to show the player (bills while away, debt warnings, closures). */
    var notices by mutableStateOf<List<String>>(emptyList())
        private set

    fun dismissNotices() {
        notices = emptyList()
    }

    /** Test hook: replace the whole world. */
    @androidx.annotation.VisibleForTesting
    fun debugReplaceWorld(w: WorldState) = update { w }

    /** Today in the street's timezone, as days since 1970-01-01. */
    fun today(): Long {
        val zoneId = world?.street?.timeZoneId?.takeIf { it.isNotBlank() }
        val zone = zoneId?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        return java.time.LocalDate.now(zone).toEpochDay()
    }

    /** Charges rent and wages for any days that have passed. */
    fun settleEconomy() {
        val current = world ?: return
        val result = Economy.settle(current, today())
        if (result.world != current) update { result.world }
        if (result.notices.isNotEmpty()) notices = notices + result.notices
    }

    /** Fills a product back up to its shelf capacity, paying the supplier. */
    fun restock(offeringId: String) {
        val w = world ?: return
        val lot = currentLot ?: return
        val offering = lot.business?.offerings?.firstOrNull { it.id == offeringId } ?: return
        val (units, cost) = Economy.restockNeed(offering, lot, w::sprite)
        if (units <= 0) return
        update { world ->
            Economy.spendOnSupplies(world, today(), cost).updateLot(lot.id) { l ->
                l.copy(business = l.business?.let { b ->
                    b.copy(offerings = b.offerings.map { if (it.id == offeringId) it.copy(stock = it.stock + units) else it })
                })
            }
        }
    }

    // --- Employees ----------------------------------------------------------------------------

    /** Today's job board (applicants not already working on this street). */
    fun applicants(): List<Applicant> = world?.let { Staffing.applicants(it, today()) }.orEmpty()

    /** Hires an applicant into the current shop. Returns an error message, or null on success. */
    fun hire(applicant: Applicant): String? {
        val business = currentLot?.business ?: return "Open a shop first."
        if (business.employees.size >= Staffing.MAX_EMPLOYEES) return "A shop can have at most ${Staffing.MAX_EMPLOYEES} employees."
        updateBusiness { it.copy(employees = it.employees + applicant.hire(today())) }
        return null
    }

    fun fire(employeeId: String) = updateBusiness { b -> b.copy(employees = b.employees.filterNot { it.id == employeeId }) }

    fun giveRaise(employeeId: String) = updateBusiness { b ->
        b.copy(employees = b.employees.map { if (it.id == employeeId) Staffing.raise(it) else it })
    }

    /**
     * Stockers top up products that are running low (below half their shelf space), paying the
     * supplier. Clumsy ones sometimes drop a unit.
     */
    private fun stockersRestock(lotId: String) {
        val w = world ?: return
        val lot = w.lot(lotId) ?: return
        val business = lot.business ?: return
        val stockers = business.employees.filter { it.role == com.blockparty.core.model.EmployeeRole.STOCKER }
        if (stockers.isEmpty()) return
        val waste = stockers.maxOf { Staffing.wasteChance(it) }
        for (o in business.offerings.filter { it.kind == OfferingKind.PRODUCT }) {
            val cap = Economy.capacity(o, lot, w::sprite)
            if (o.stock * 2 >= cap) continue
            val (units, cost) = Economy.restockNeed(o, lot, w::sprite)
            if (units <= 0 || (world?.coins ?: 0) < cost) continue
            val dropped = if (kotlin.random.Random.nextFloat() < waste) 1 else 0
            update { world ->
                Economy.spendOnSupplies(world, today(), cost).updateLot(lotId) { l ->
                    l.copy(business = l.business?.let { b ->
                        b.copy(offerings = b.offerings.map { if (it.id == o.id) it.copy(stock = it.stock + units - dropped) else it })
                    })
                }
            }
        }
    }

    fun restockAll() {
        currentLot?.business?.offerings?.filter { it.kind == OfferingKind.PRODUCT }?.forEach { restock(it.id) }
    }

    /** Total units and coins to restock every product. */
    fun restockAllNeed(): Pair<Int, Long> {
        val w = world ?: return 0 to 0L
        val lot = currentLot ?: return 0 to 0L
        return lot.business?.offerings.orEmpty().map { Economy.restockNeed(it, lot, w::sprite) }
            .fold(0 to 0L) { acc, (u, c) -> acc.first + u to acc.second + c }
    }

    /** Adds the default starter sprites (and starter customers) the first time a world loads. */
    private suspend fun ensureStarterSet() {
        val current = world ?: return
        if (current.starterSetVersion >= StarterSet.VERSION) return
        val lotWidth = currentLot?.rect?.w ?: WorldFactory.LOT_WIDTH
        val items = withContext(Dispatchers.Default) { StarterSet.generate(lotWidth) }
        val existing = current.sprites.map { it.id }.toSet()
        val added = items.filter { it.asset.id !in existing }
        // Newer starter versions can set up pieces this world already has (display spots,
        // furniture type, seat height), without overriding anything the player changed.
        val newSetup = items.filter { it.asset.id in existing }.associate { it.asset.id to it.asset }
        withContext(Dispatchers.IO) { added.forEach { sprites.writeFile(it.asset.id, it.bitmap) } }
        added.forEach { sprites.images[it.asset.id] = it.bitmap.asImageBitmap() }
        update { w ->
            val newSprites = added.map { it.asset }
            val npcIds = w.npcs.map { it.spriteId }.toSet()
            val newNpcs = newSprites
                .filter { it.role == CharacterRole.CUSTOMER && it.id !in npcIds }
                .map { NpcGenerator.profile("npc-${it.id}", it.id, w.playerLevel) }
            val upgraded = w.sprites.map { s ->
                val fresh = newSetup[s.id] ?: return@map s
                var out = s
                if (s.displaySpots.isEmpty() && fresh.displaySpots.isNotEmpty()) out = out.copy(displaySpots = fresh.displaySpots)
                if (s.furnitureType == FurnitureType.DECOR && fresh.furnitureType != FurnitureType.DECOR) {
                    out = out.copy(furnitureType = fresh.furnitureType, seatHeight = fresh.seatHeight)
                }
                out
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

    private fun updateCurrentLot(transform: (Lot) -> Lot) {
        val id = currentLot?.id ?: return
        update { it.updateLot(id, transform) }
    }

    private fun updateBusiness(transform: (Business) -> Business) =
        updateCurrentLot { lot ->
            val business = lot.business
            if (lot.state != LotState.OWNED || business == null) lot else lot.copy(business = transform(business))
        }

    // --- Lots: buying, closing, merging ---------------------------------------------------------

    /** Buys a vacant lot and opens a new shop there. Returns an error message, or null on success. */
    fun buyLot(lotId: String): String? {
        val w = world ?: return null
        return when (val r = Lots.buy(w, lotId, today())) {
            is Lots.Result.Ok -> { update { r.world }; selectLot(lotId); null }
            is Lots.Result.Failed -> r.reason
        }
    }

    /** Closes the current shop: furniture to storage, lot vacant. */
    fun closeCurrentBusiness() {
        val id = currentLot?.id ?: return
        update { Economy.closeBusiness(it, id) }
        back()
    }

    /** Expands the current shop into a neighbouring lot. Returns an error message, or null on success. */
    fun expandInto(otherLotId: String): String? {
        val w = world ?: return null
        val id = currentLot?.id ?: return null
        return when (val r = Lots.merge(w, id, otherLotId, today())) {
            is Lots.Result.Ok -> { update { r.world }; null }
            is Lots.Result.Failed -> r.reason
        }
    }

    // --- Navigation ---------------------------------------------------------------------------

    fun navigate(to: Screen) {
        backStack = backStack + to
    }

    fun back() {
        if (backStack.size > 1) backStack = backStack.dropLast(1)
    }

    // --- Player & business identity -----------------------------------------------------------

    /** Records sales and reviews from the shops: coins, revenue, customers served and reputation. */
    fun recordEvents(events: List<CrowdEvent>) {
        if (events.isEmpty()) return
        update { w ->
            var out = w
            for (e in events) {
                out = when (e) {
                    is CrowdEvent.Sale -> {
                        val day = today()
                        // Services use up supplies each time they're performed.
                        val serviceSupplies = e.items.filter { it.kind == OfferingKind.SERVICE }.sumOf { item ->
                            out.lot(e.lotId)?.business?.offerings?.firstOrNull { it.id == item.id }?.supplyTier?.serviceCost ?: 0L
                        }
                        val sold = e.items.groupingBy { it.id }.eachCount()
                        val booked = Economy.book(out.copy(coins = out.coins + e.total), day) { it.copy(sales = it.sales + e.total) }
                        Economy.spendOnSupplies(booked, day, serviceSupplies).updateLot(e.lotId) { lot ->
                            lot.copy(business = lot.business?.let { b ->
                                b.copy(
                                    revenueTotal = b.revenueTotal + e.total,
                                    customersServed = b.customersServed + 1,
                                    itemsSold = b.itemsSold + e.items.size,
                                    offerings = b.offerings.map { o ->
                                        val n = sold[o.id] ?: 0
                                        if (n > 0 && o.kind == OfferingKind.PRODUCT) o.copy(stock = (o.stock - n).coerceAtLeast(0)) else o
                                    },
                                )
                            })
                        }
                    }
                    is CrowdEvent.Review -> out.updateLot(e.lotId) { lot ->
                        lot.copy(business = lot.business?.let { b ->
                            val review = Review(newId(), e.customer.id, e.customer.name, e.stars, e.text, System.currentTimeMillis())
                            b.copy(reviews = (listOf(review) + b.reviews).take(Business.MAX_REVIEWS))
                        })
                    }
                    is CrowdEvent.Visit -> out
                }
            }
            out
        }
        events.filterIsInstance<CrowdEvent.Sale>().map { it.lotId }.distinct().forEach(::stockersRestock)
    }

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
        offeringDraft = Offering(id = newId(), kind = kind, name = "", priceCoins = 10, stock = 0)
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
                DrawPurpose.EMPLOYEE -> CharacterRole.EMPLOYEE
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
            DrawPurpose.CUSTOMER, DrawPurpose.EMPLOYEE -> Unit
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
        val lot = currentLot ?: return null
        val spriteId = placingSpriteId ?: return null
        val rect = Placement.footprint(w.sprite(spriteId), tileX, tileY, flipped = false)
        if (!Placement.canPlace(lot, rect, w::sprite)) return null
        val placement = FurniturePlacement(newId(), spriteId, tileX, tileY)
        updateCurrentLot { it.copy(furniture = it.furniture + placement) }
        // Use up a stored copy first, if there is one.
        val stored = w.storage.indexOf(spriteId)
        if (stored >= 0) update { it.copy(storage = it.storage.toMutableList().apply { removeAt(stored) }) }
        return placement
    }

    /** Moves a placed piece so its origin is at the tile, if it fits there. */
    fun moveFurniture(id: String, tileX: Int, tileY: Int): Boolean {
        val w = world ?: return false
        val lot = currentLot ?: return false
        val piece = lot.furniture.firstOrNull { it.id == id } ?: return false
        val rect = Placement.footprint(w.sprite(piece.spriteId), tileX, tileY, piece.flipped)
        if (!Placement.canPlace(lot, rect, w::sprite, ignoreId = id)) return false
        updateCurrentLot { l -> l.copy(furniture = l.furniture.map { if (it.id == id) it.copy(x = tileX, y = tileY) else it }) }
        return true
    }

    /** Mirrors a piece (which transposes its footprint). Returns false if the mirrored piece wouldn't fit. */
    fun flipFurniture(id: String): Boolean {
        val w = world ?: return false
        val lot = currentLot ?: return false
        val piece = lot.furniture.firstOrNull { it.id == id } ?: return false
        val rect = Placement.footprint(w.sprite(piece.spriteId), piece.x, piece.y, !piece.flipped)
        if (!Placement.canPlace(lot, rect, w::sprite, ignoreId = id)) return false
        updateCurrentLot { l -> l.copy(furniture = l.furniture.map { if (it.id == id) it.copy(flipped = !it.flipped) else it }) }
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

    fun setFurnitureType(spriteId: String, type: FurnitureType) = update { w ->
        w.copy(sprites = w.sprites.map { if (it.id == spriteId) it.copy(furnitureType = type) else it })
    }

    fun setSeatHeight(spriteId: String, height: Float) = update { w ->
        w.copy(sprites = w.sprites.map { if (it.id == spriteId) it.copy(seatHeight = height) else it })
    }

    /** Puts a product on a spot of one placed piece, or empties the spot (null). */
    fun setStock(placementId: String, spotId: String, offeringId: String?) = updateCurrentLot { l ->
        l.copy(furniture = l.furniture.map { p ->
            if (p.id != placementId) p
            else p.copy(stock = if (offeringId == null) p.stock - spotId else p.stock + (spotId to offeringId))
        })
    }

    /** Fills a placed display piece's empty spots with the shop's products. */
    fun autoFillStock(placementId: String) {
        val w = world ?: return
        val lot = currentLot ?: return
        val piece = lot.furniture.firstOrNull { it.id == placementId } ?: return
        val spots = w.sprite(piece.spriteId)?.displaySpots.orEmpty()
        val products = lot.business?.offerings.orEmpty()
            .filter { it.kind == com.blockparty.core.model.OfferingKind.PRODUCT }.map { it.id }
        val filled = DisplayStock.autoFill(spots, piece.stock, products)
        updateCurrentLot { l -> l.copy(furniture = l.furniture.map { if (it.id == placementId) it.copy(stock = filled) else it }) }
    }

    /** Picks a piece up into storage. */
    fun removeFurniture(id: String) {
        val piece = currentLot?.furniture?.firstOrNull { it.id == id } ?: return
        updateCurrentLot { l -> l.copy(furniture = l.furniture.filterNot { it.id == id }) }
        update { it.copy(storage = it.storage + piece.spriteId) }
    }

    private fun newId() = UUID.randomUUID().toString()
}

fun List<String>.toggle(id: String): List<String> = if (id in this) this - id else this + id
