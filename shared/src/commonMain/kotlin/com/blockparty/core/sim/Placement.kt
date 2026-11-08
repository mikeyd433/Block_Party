package com.blockparty.core.sim

import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.Lot
import com.blockparty.core.model.SpriteAsset

object Placement {
    /**
     * Grid footprint of a piece of furniture. Mirroring a cutout horizontally in an
     * isometric view swaps the grid axes, so a flipped piece has its footprint transposed.
     */
    fun footprint(sprite: SpriteAsset?, x: Int, y: Int, flipped: Boolean): GridRect {
        val w = sprite?.footprintW ?: 1
        val h = sprite?.footprintH ?: 1
        return if (flipped) GridRect(x, y, h, w) else GridRect(x, y, w, h)
    }

    fun footprint(placement: FurniturePlacement, sprites: (String) -> SpriteAsset?): GridRect =
        footprint(sprites(placement.spriteId), placement.x, placement.y, placement.flipped)

    /** True if [rect] lies inside the lot and doesn't overlap furniture (other than [ignoreId]). */
    fun canPlace(
        lot: Lot,
        rect: GridRect,
        sprites: (String) -> SpriteAsset?,
        ignoreId: String? = null,
    ): Boolean {
        if (!lot.rect.contains(rect)) return false
        return lot.furniture.none { it.id != ignoreId && footprint(it, sprites).intersects(rect) }
    }

    /** Placed furniture covering a tile, if any. */
    fun furnitureAt(lot: Lot, tx: Int, ty: Int, sprites: (String) -> SpriteAsset?): FurniturePlacement? =
        lot.furniture.lastOrNull { footprint(it, sprites).contains(tx, ty) }

    /** Lot tiles not covered by furniture. */
    fun freeTiles(lot: Lot, sprites: (String) -> SpriteAsset?): Set<Pair<Int, Int>> {
        val blocked = lot.furniture.map { footprint(it, sprites) }
        val r = lot.rect
        val result = HashSet<Pair<Int, Int>>()
        for (ty in r.y until r.bottom) for (tx in r.x until r.right) {
            if (blocked.none { it.contains(tx, ty) }) result.add(tx to ty)
        }
        return result
    }
}
