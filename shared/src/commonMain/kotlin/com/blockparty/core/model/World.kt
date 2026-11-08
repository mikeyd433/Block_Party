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
    /** Characters only: what the character is for. */
    val role: CharacterRole = CharacterRole.NONE,
    /** Part of the default starter set that ships with the game, not drawn by a player. */
    val isStarter: Boolean = false,
    val artistName: String = "",
    /** Artist's choice: show their name when the drawing is tapped, or stay anonymous. */
    val showCredit: Boolean = true,
    val createdAtMillis: Long = 0L,
)

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

/** A drawn furniture cutout placed on a lot. Coordinates are absolute street tiles. */
@Serializable
data class FurniturePlacement(
    val id: String,
    val spriteId: String,
    val x: Int,
    val y: Int,
    val flipped: Boolean = false,
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
) {
    val totalDepth get() = lotDepth + sidewalkRows * 2 + roadRows
    val roadStartRow get() = lotDepth + sidewalkRows
}

@Serializable
data class WorldState(
    val version: Int = CURRENT_VERSION,
    val playerName: String = "",
    val playerLevel: Int = 0,
    val street: Street,
    val sprites: List<SpriteAsset> = emptyList(),
    /** The street's customer pool (player-drawn customers plus the starter set). */
    val npcs: List<NpcProfile> = emptyList(),
    /** Version of the default starter set already added to this world. */
    val starterSetVersion: Int = 0,
) {
    fun sprite(id: String?): SpriteAsset? = id?.let { sid -> sprites.firstOrNull { it.id == sid } }

    fun lot(id: String): Lot? = street.lots.firstOrNull { it.id == id }

    fun updateLot(id: String, transform: (Lot) -> Lot): WorldState =
        copy(street = street.copy(lots = street.lots.map { if (it.id == id) transform(it) else it }))

    companion object {
        const val CURRENT_VERSION = 1
    }
}
