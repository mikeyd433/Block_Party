package com.blockparty.core.geometry

import com.blockparty.core.art.CutoutSizing
import com.blockparty.core.model.GridRect
import kotlin.math.sqrt

/**
 * Geometry for a shop building standing on a lot. The storefront (facade) is the wall
 * on the lot's street side (max y), which faces the bottom-left of the screen.
 */
class Storefront(private val iso: IsoProjection) {
    val wallHeight: Float get() = iso.tileHeight * CutoutSizing.WALL_HEIGHT_TILES

    /** Width / height ratio of the front wall as it appears, used for the facade drawing canvas. */
    fun facadeAspect(lotWidthTiles: Int): Float {
        val halfW = iso.tileWidth / 2f
        val halfH = iso.tileHeight / 2f
        val faceLength = lotWidthTiles * sqrt(halfW * halfW + halfH * halfH)
        return faceLength / wallHeight
    }

    /** Front wall polygon: bottom-left, bottom-right, top-right, top-left. */
    fun frontFace(lot: GridRect): List<Vec2> = listOf(
        iso.gridToScreen(lot.x.toFloat(), lot.bottom.toFloat()),
        iso.gridToScreen(lot.right.toFloat(), lot.bottom.toFloat()),
        iso.gridToScreen(lot.right.toFloat(), lot.bottom.toFloat(), wallHeight),
        iso.gridToScreen(lot.x.toFloat(), lot.bottom.toFloat(), wallHeight),
    )

    /** Side wall on the lot's max-x edge (faces bottom-right): front, back, back-top, front-top. */
    fun sideFace(lot: GridRect): List<Vec2> = listOf(
        iso.gridToScreen(lot.right.toFloat(), lot.bottom.toFloat()),
        iso.gridToScreen(lot.right.toFloat(), lot.y.toFloat()),
        iso.gridToScreen(lot.right.toFloat(), lot.y.toFloat(), wallHeight),
        iso.gridToScreen(lot.right.toFloat(), lot.bottom.toFloat(), wallHeight),
    )

    /** Back walls seen from inside when the front is cut away: along min-y and along min-x. */
    fun backWalls(lot: GridRect): Pair<List<Vec2>, List<Vec2>> {
        val x0 = lot.x.toFloat()
        val y0 = lot.y.toFloat()
        val along = listOf(
            iso.gridToScreen(x0, y0), iso.gridToScreen(lot.right.toFloat(), y0),
            iso.gridToScreen(lot.right.toFloat(), y0, wallHeight), iso.gridToScreen(x0, y0, wallHeight),
        )
        val side = listOf(
            iso.gridToScreen(x0, lot.bottom.toFloat()), iso.gridToScreen(x0, y0),
            iso.gridToScreen(x0, y0, wallHeight), iso.gridToScreen(x0, lot.bottom.toFloat(), wallHeight),
        )
        return along to side
    }

    /** Roof (top face) of the closed building. */
    fun roof(lot: GridRect): List<Vec2> =
        iso.rectCorners(lot.x.toFloat(), lot.y.toFloat(), lot.w.toFloat(), lot.h.toFloat(), wallHeight)

    /** Transform that maps a flat facade image onto the front wall. */
    fun facadeTransform(lot: GridRect, imageW: Float, imageH: Float): Affine2D {
        val face = frontFace(lot)
        return Affine2D.mapRectTo(imageW, imageH, topLeft = face[3], topRight = face[2], bottomLeft = face[0])
    }
}
