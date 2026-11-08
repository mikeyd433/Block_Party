package com.blockparty.core.geometry

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which way a turned drawing's front faces in the isometric view. */
enum class Facing { LEFT, RIGHT }

/**
 * Where a straight-on drawing goes once it's turned to the isometric angle and given depth.
 *
 * @property front maps the flat drawing's pixels onto its front face in the canvas.
 * @property depth offset (canvas pixels) from the front face to the back face.
 */
data class DepthLayout(val front: Affine2D, val depth: Vec2) {
    /** Centre of the box's base (the part touching the floor) for a front image of the given size. */
    fun groundCenter(imageW: Float, imageH: Float): Vec2 {
        val frontBottomMid = front.apply(Vec2(imageW / 2f, imageH))
        return frontBottomMid + depth * 0.5f
    }
}

/**
 * Turns a flat front view into an isometric box: the front stays readable left-to-right, and the
 * depth runs back into the scene. Facing LEFT puts the front on a wall facing bottom-left (it
 * runs down-right, depth goes up-right); RIGHT puts it facing bottom-right (runs up-right,
 * depth goes up-left).
 */
object DepthProjection {
    private val SQRT5 = sqrt(5f)

    fun frontDirection(facing: Facing) = when (facing) {
        Facing.LEFT -> Vec2(2f / SQRT5, 1f / SQRT5)
        Facing.RIGHT -> Vec2(2f / SQRT5, -1f / SQRT5)
    }

    fun depthDirection(facing: Facing) = when (facing) {
        Facing.LEFT -> Vec2(2f / SQRT5, -1f / SQRT5)
        Facing.RIGHT -> Vec2(-2f / SQRT5, -1f / SQRT5)
    }

    /**
     * Fits the turned drawing plus its depth inside a canvas, centered with a margin, never
     * enlarging the drawing.
     *
     * @param depthFraction depth as a fraction of the drawing's width.
     * @param fitDepthFraction depth used to fit the canvas. Fitting to the largest depth the user
     * can pick keeps the front face fixed while the depth is adjusted.
     */
    fun layout(
        imageW: Float,
        imageH: Float,
        depthFraction: Float,
        facing: Facing,
        canvasW: Float,
        canvasH: Float,
        margin: Float = 0.06f,
        fitDepthFraction: Float = depthFraction,
    ): DepthLayout {
        val u = frontDirection(facing)
        val fitDepth = depthDirection(facing) * (max(fitDepthFraction, depthFraction) * imageW)
        val depth = depthDirection(facing) * (depthFraction * imageW)
        val down = Vec2(0f, imageH)
        val front = listOf(Vec2.Zero, u * imageW, down, u * imageW + down)
        val points = front + front.map { it + fitDepth }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val bw = max(maxX - minX, 1f)
        val bh = max(maxY - minY, 1f)
        val scale = min(1f, min(canvasW * (1 - 2 * margin) / bw, canvasH * (1 - 2 * margin) / bh))
        val tx = canvasW / 2f - (minX + maxX) / 2f * scale
        val ty = canvasH / 2f - (minY + maxY) / 2f * scale
        return DepthLayout(
            front = Affine2D(a = u.x * scale, b = u.y * scale, c = 0f, d = scale, tx = tx, ty = ty),
            depth = depth * scale,
        )
    }
}
