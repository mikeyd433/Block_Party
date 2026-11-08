package com.blockparty.core.model

import kotlinx.serialization.Serializable

/** What a player drawing is used for. Determines how it is sized and rendered. */
@Serializable
enum class SpriteKind(val label: String) {
    FURNITURE("Furniture"),
    PRODUCT("Product / service"),
    CHARACTER("Character"),
    FACADE("Storefront"),
}

/**
 * A player-drawn (or uploaded) image. The pixels live in platform storage as a PNG
 * with a transparent background, cropped to its content; this is its metadata.
 */
@Serializable
data class SpriteAsset(
    val id: String,
    val kind: SpriteKind,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    /** Which way the drawing faces, so characters can be flipped to face their walking direction. */
    val facesRight: Boolean = true,
    /** Furniture only: footprint on the grid in tiles. */
    val footprintW: Int = 1,
    val footprintH: Int = 1,
    /**
     * Furniture only: lean the straight-on drawing into the isometric grid as a flat panel
     * along one floor axis (true), or stand it upright facing the camera (false).
     */
    val alignToGrid: Boolean = true,
    /**
     * Point of the image (fractions of width/height) that rests on the centre of the footprint.
     * Bottom-centre for a straight-on drawing; the middle of the base for one turned into 3D.
     */
    val groundX: Float = 0.5f,
    val groundY: Float = 1f,
    /** Furniture only: where products can sit. Non-empty makes this a display piece. */
    val displaySpots: List<DisplaySpot> = emptyList(),
    /** Furniture only: what customers use it for. */
    val furnitureType: FurnitureType = FurnitureType.DECOR,
    /**
     * Seating and service stations: where a customer sits, as a fraction of the drawing's
     * height above the point that rests on the floor.
     */
    val seatHeight: Float = DEFAULT_SEAT_HEIGHT,
    /** Characters only: what the character is for. */
    val role: CharacterRole = CharacterRole.NONE,
    /** Part of the default starter set that ships with the game, not drawn by a player. */
    val isStarter: Boolean = false,
    val artistName: String = "",
    /** Artist's choice: show their name when the drawing is tapped, or stay anonymous. */
    val showCredit: Boolean = true,
    val createdAtMillis: Long = 0L,
)

/** What customers use a piece of furniture for. Display spots can be added to any type. */
@Serializable
enum class FurnitureType(val label: String, val description: String) {
    DECOR("Decor", "Just looks nice."),
    CHECKOUT("Checkout", "Customers queue and pay here before leaving."),
    SERVICE_STATION("Service station", "Customers sit here to be served. More stations serve more people at once."),
    SEATING("Seating", "Customers sit here while they wait in a queue or take a break."),
    ;

    val hasSeat: Boolean get() = this == SERVICE_STATION || this == SEATING
}

const val DEFAULT_SEAT_HEIGHT = 0.45f

@Serializable
enum class CharacterRole { NONE, SHOPKEEPER, CUSTOMER, EMPLOYEE }

/**
 * A customer in the street's NPC pool. The look is a player drawing (or a starter sprite);
 * the name, tastes and budget are assigned by the game, never by the drawer.
 */
@Serializable
data class NpcProfile(
    val id: String,
    val spriteId: String,
    val name: String,
    val tagIds: List<String>,
    val budgetCoins: Long,
    val walkSpeed: Float,
)

/** Rectangle on the street grid, in tiles. */
@Serializable
data class GridRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h

    fun contains(tx: Int, ty: Int) = tx >= x && ty >= y && tx < right && ty < bottom

    fun contains(other: GridRect) =
        other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    fun intersects(other: GridRect) =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

/**
 * A spot on a display piece where one product sits.
 *
 * @property x where the item's bottom-centre rests, as a fraction of the furniture drawing's width.
 * @property y same, as a fraction of its height.
 * @property size item height as a fraction of the furniture's height.
 */
@Serializable
data class DisplaySpot(val id: String, val x: Float, val y: Float, val size: Float = DEFAULT_SIZE) {
    companion object {
        const val DEFAULT_SIZE = 0.3f
        const val MIN_SIZE = 0.08f
        const val MAX_SIZE = 0.8f
    }
}

/** A drawn furniture cutout placed on a lot. Coordinates are absolute street tiles. */
@Serializable
data class FurniturePlacement(
    val id: String,
    val spriteId: String,
    val x: Int,
    val y: Int,
    val flipped: Boolean = false,
    /** Display pieces: which offering sits on each display spot (spot id -> offering id). */
    val stock: Map<String, String> = emptyMap(),
)

@Serializable
enum class OfferingKind { PRODUCT, SERVICE }

/** Whether a business sells products, offers services, or both. */
@Serializable
enum class BusinessMode(val label: String) {
    PRODUCTS("Products"),
    SERVICES("Services"),
    BOTH("Both"),
}

/** Something a business sells (product) or provides (service). */
@Serializable
data class Offering(
    val id: String,
    val kind: OfferingKind,
    val name: String,
    val priceCoins: Long,
    val spriteId: String? = null,
    val tagIds: List<String> = emptyList(),
    /** Products only: units in the shop right now. */
    val stock: Int = 5,
    /** Quality of the supplies used: better costs more and pleases customers. */
    val supplyTier: SupplyTier = SupplyTier.STANDARD,
)

/** Supplier quality for a product or service. Costs are per unit (or per service performed). */
@Serializable
enum class SupplyTier(val label: String, val unitCost: Long, val serviceCost: Long, val quality: Float) {
    BASIC("Basic", unitCost = 2, serviceCost = 1, quality = -0.35f),
    STANDARD("Standard", unitCost = 5, serviceCost = 3, quality = 0f),
    FANCY("Fancy", unitCost = 12, serviceCost = 8, quality = 0.4f),
}

/** One day's money in and out, for the money screen. */
@Serializable
data class DayBook(
    /** Days since 1970-01-01 in the street's timezone. */
    val day: Long,
    val sales: Long = 0,
    val rent: Long = 0,
    val wages: Long = 0,
    val supplies: Long = 0,
    /** Buying lots (and, later, shops). */
    val property: Long = 0,
) {
    val net: Long get() = sales - rent - wages - supplies - property
}

@Serializable
data class EconomyState(
    /** Last day rent and wages were charged (0 = never; the first settle just starts the clock). */
    val lastBilledDay: Long = 0,
    /** First day the player's coins were below zero, or null when not in debt. */
    val debtSinceDay: Long? = null,
    /** Most recent day first. */
    val books: List<DayBook> = emptyList(),
)

@Serializable
data class Business(
    val name: String,
    val description: String = "",
    val mode: BusinessMode = BusinessMode.PRODUCTS,
    val tagIds: List<String> = emptyList(),
    val offerings: List<Offering> = emptyList(),
    val facadeSpriteId: String? = null,
    val shopkeeperSpriteId: String? = null,
    /** Most recent reviews first, capped at [MAX_REVIEWS]. */
    val reviews: List<Review> = emptyList(),
    val revenueTotal: Long = 0,
    val customersServed: Int = 0,
    val itemsSold: Int = 0,
    val employees: List<Employee> = emptyList(),
) {
    /** Average stars of recent reviews (this business's reputation), or null before any. */
    val rating: Float?
        get() = reviews.take(RATING_WINDOW).takeIf { it.isNotEmpty() }?.map { it.stars }?.average()?.toFloat()

    companion object {
        const val MAX_REVIEWS = 60
        const val RATING_WINDOW = 30
    }
}

/** What an employee does in the shop. */
@Serializable
enum class EmployeeRole(val label: String, val description: String) {
    CASHIER("Cashier", "Works the checkout: customers pay faster."),
    STOCKER("Stocker", "Restocks shelves from the supplier, even while you're away."),
    SERVER("Server", "Provides services at service stations."),
    GREETER("Greeter", "Stands by the door and pulls in more passers-by."),
}

/** Personality quirks with trade-offs, assigned by the game. */
@Serializable
enum class EmployeeTrait(val label: String, val description: String) {
    FAST("Fast", "Works quicker."),
    FRIENDLY("Friendly", "Customers like them: better reviews."),
    CAREFUL("Careful", "Slower, but never wastes stock."),
    CHATTY("Chatty", "Customers enjoy them, but they work slower."),
    NIGHT_OWL("Night owl", "Better in the evening and at night."),
    EARLY_BIRD("Early bird", "Better in the morning."),
    CLUMSY("Clumsy", "Sometimes wastes a unit when restocking."),
    STEADY("Steady", "Morale drops more slowly."),
}

/** An NPC employee working at a business. Traits and wage ask come from the game. */
@Serializable
data class Employee(
    val id: String,
    val spriteId: String,
    val name: String,
    val role: EmployeeRole,
    val traits: List<EmployeeTrait>,
    val wagePerDay: Long,
    val hiredDay: Long,
    /** Experience from working shifts; levels make them better. */
    val xp: Int = 0,
    /** 0..100. Low morale leads to quitting. */
    val morale: Int = 75,
) {
    val level: Int get() = 1 + xp / 100
}

/** A customer's review: star rating plus text built from hand-written phrases. */
@Serializable
data class Review(
    val id: String,
    val npcId: String,
    val npcName: String,
    val stars: Int,
    val text: String,
    val atMillis: Long,
)

@Serializable
enum class LotState { OWNED, VACANT, LOCKED }

@Serializable
data class Lot(
    val id: String,
    val rect: GridRect,
    val state: LotState,
    val business: Business? = null,
    val furniture: List<FurniturePlacement> = emptyList(),
)

/**
 * Street layout: lots sit in a row with y in [0, lotDepth); in front of them
 * (increasing y) is a sidewalk row and then the road.
 */
@Serializable
data class Street(
    val id: String,
    val name: String,
    val widthTiles: Int,
    val lotDepth: Int,
    val sidewalkRows: Int = 1,
    val roadRows: Int = 3,
    val lots: List<Lot>,
    /** IANA timezone the street's game time follows (empty = the device's). */
    val timeZoneId: String = "",
) {
    val totalDepth get() = lotDepth + sidewalkRows * 2 + roadRows
    val roadStartRow get() = lotDepth + sidewalkRows
}

@Serializable
data class WorldState(
    val version: Int = CURRENT_VERSION,
    val playerName: String = "",
    val playerLevel: Int = 0,
    /** The player's money on this street. */
    val coins: Long = STARTING_COINS,
    val street: Street,
    val sprites: List<SpriteAsset> = emptyList(),
    /** The street's customer pool (player-drawn customers plus the starter set). */
    val npcs: List<NpcProfile> = emptyList(),
    /** Version of the default starter set already added to this world. */
    val starterSetVersion: Int = 0,
    val economy: EconomyState = EconomyState(),
    /** Furniture (sprite ids) taken out of closed shops, ready to place again. */
    val storage: List<String> = emptyList(),
) {
    fun sprite(id: String?): SpriteAsset? = id?.let { sid -> sprites.firstOrNull { it.id == sid } }

    fun lot(id: String): Lot? = street.lots.firstOrNull { it.id == id }

    fun updateLot(id: String, transform: (Lot) -> Lot): WorldState =
        copy(street = street.copy(lots = street.lots.map { if (it.id == id) transform(it) else it }))

    companion object {
        const val CURRENT_VERSION = 2
        const val STARTING_COINS = 100L
    }
}
