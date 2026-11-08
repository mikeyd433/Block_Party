package com.blockparty.core.geometry

import com.blockparty.core.model.GridRect
import kotlin.math.sqrt

/**
 * Turns a drawing made straight-on into a flat panel standing along one of the grid axes,
 * so it lines up with the isometric floor instead of facing the camera.
 */
object GridPanel {
    /**
     * @property transform maps image pixels onto the screen.
     * @property corners screen corners: top-left, top-right, bottom-right, bottom-left (image orientation).
     * @property foot middle of the panel's bottom edge, used as the pivot for animation.
     */
    data class Panel(val transform: Affine2D, val corners: List<Vec2>, val foot: Vec2)

    /**
     * Stands the image along the middle of [rect].
     *
     * @param alongY false: the panel runs along the grid x-axis (down-right on screen), image left at
     * the min-x end. true: it runs along the y-axis (down-left on screen) with image left at the min-y
     * end, which looks like the x-axis panel mirrored, matching how flipping works for furniture.
     * @param maxHeight caps the panel height in screen pixels; a tall drawing gets a narrower panel.
     * @param fill fraction of the footprint length the panel spans.
     */
    fun standing(
        iso: IsoProjection,
        rect: GridRect,
        alongY: Boolean,
        imageW: Float,
        imageH: Float,
        maxHeight: Float,
        fill: Float = 0.9f,
    ): Panel {
        val start = if (alongY) Vec2(rect.x + rect.w / 2f, rect.y.toFloat()) else Vec2(rect.x.toFloat(), rect.y + rect.h / 2f)
        val end = if (alongY) Vec2(rect.x + rect.w / 2f, rect.bottom.toFloat()) else Vec2(rect.right.toFloat(), rect.y + rect.h / 2f)
        val mid = (start + end) * 0.5f

        fun ends(k: Float): Pair<Vec2, Vec2> {
            val s = mid + (start - mid) * k
            val e = mid + (end - mid) * k
            return iso.gridToScreen(s.x, s.y) to iso.gridToScreen(e.x, e.y)
        }

        var (a, b) = ends(fill)
        val length = distance(a, b)
        var height = length * imageH / imageW.coerceAtLeast(1f)
        if (height > maxHeight) {
            val k = maxHeight / height
            val shrunk = ends(fill * k)
            a = shrunk.first
            b = shrunk.second
            height = maxHeight
        }
        val up = Vec2(0f, height)
        val topLeft = a - up
        val topRight = b - up
        return Panel(
            transform = Affine2D.mapRectTo(imageW, imageH, topLeft, topRight, a),
            corners = listOf(topLeft, topRight, b, a),
            foot = (a + b) * 0.5f,
        )
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
