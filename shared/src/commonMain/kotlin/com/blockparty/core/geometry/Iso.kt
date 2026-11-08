package com.blockparty.core.geometry

import kotlin.math.floor

/** Simple 2D vector used for screen- and grid-space math. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

/** Integer tile coordinate on the street grid. */
data class Tile(val x: Int, val y: Int)

/**
 * 2:1 isometric projection.
 *
 * Grid axes: +x runs toward the bottom-right of the screen, +y toward the bottom-left.
 * A grid point (gx, gy) at height z (screen pixels, up is positive) maps to
 *   sx = (gx - gy) * tileWidth / 2
 *   sy = (gx + gy) * tileHeight / 2 - z
 * relative to the screen-space origin of grid point (0, 0).
 */
class IsoProjection(val tileWidth: Float = 128f, val tileHeight: Float = 64f) {
    private val halfW = tileWidth / 2f
    private val halfH = tileHeight / 2f

    fun gridToScreen(gx: Float, gy: Float, z: Float = 0f): Vec2 =
        Vec2((gx - gy) * halfW, (gx + gy) * halfH - z)

    /** Inverse of [gridToScreen] for points on the ground plane (z = 0). */
    fun screenToGrid(sx: Float, sy: Float): Vec2 {
        val a = sx / halfW // gx - gy
        val b = sy / halfH // gx + gy
        return Vec2((a + b) / 2f, (b - a) / 2f)
    }

    fun screenToTile(sx: Float, sy: Float): Tile {
        val g = screenToGrid(sx, sy)
        return Tile(floor(g.x).toInt(), floor(g.y).toInt())
    }

    /** Ground-plane corners of a tile rectangle in screen space: top, right, bottom, left. */
    fun rectCorners(x: Float, y: Float, w: Float, h: Float, z: Float = 0f): List<Vec2> = listOf(
        gridToScreen(x, y, z),
        gridToScreen(x + w, y, z),
        gridToScreen(x + w, y + h, z),
        gridToScreen(x, y + h, z),
    )

    /** Center of a tile rectangle on the ground plane. */
    fun rectCenter(x: Float, y: Float, w: Float, h: Float): Vec2 =
        gridToScreen(x + w / 2f, y + h / 2f)

    /** On-screen width of a footprint's diamond. */
    fun footprintScreenWidth(w: Int, h: Int): Float = (w + h) * halfW
}
