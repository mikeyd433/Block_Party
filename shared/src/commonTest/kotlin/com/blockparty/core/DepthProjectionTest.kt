package com.blockparty.core

import com.blockparty.core.geometry.DepthProjection
import com.blockparty.core.geometry.Facing
import com.blockparty.core.geometry.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DepthProjectionTest {
    private fun inside(p: Vec2, w: Float, h: Float) = p.x >= -0.01f && p.y >= -0.01f && p.x <= w + 0.01f && p.y <= h + 0.01f

    @Test
    fun turnedDrawingFitsTheCanvas() {
        for (facing in Facing.entries) for (depth in listOf(0.1f, 0.5f, 1f)) {
            val l = DepthProjection.layout(800f, 600f, depth, facing, 1024f, 1024f)
            val corners = listOf(Vec2(0f, 0f), Vec2(800f, 0f), Vec2(0f, 600f), Vec2(800f, 600f)).map(l.front::apply)
            for (c in corners + corners.map { it + l.depth }) assertTrue(inside(c, 1024f, 1024f), "$facing $depth $c")
        }
    }

    @Test
    fun frontFollowsTheIsometricSlope() {
        val left = DepthProjection.layout(100f, 100f, 0.5f, Facing.LEFT, 1024f, 1024f)
        val a = left.front.apply(Vec2(0f, 0f))
        val b = left.front.apply(Vec2(100f, 0f))
        assertTrue(abs((b.y - a.y) / (b.x - a.x) - 0.5f) < 1e-4f, "LEFT front runs down-right at 2:1")
        assertTrue(left.depth.x > 0f && left.depth.y < 0f, "LEFT depth goes up-right")
        // Vertical lines stay vertical.
        val c = left.front.apply(Vec2(0f, 100f))
        assertTrue(abs(c.x - a.x) < 1e-4f)

        val right = DepthProjection.layout(100f, 100f, 0.5f, Facing.RIGHT, 1024f, 1024f)
        val ra = right.front.apply(Vec2(0f, 0f))
        val rb = right.front.apply(Vec2(100f, 0f))
        assertTrue(abs((rb.y - ra.y) / (rb.x - ra.x) + 0.5f) < 1e-4f, "RIGHT front runs up-right at 2:1")
        assertTrue(right.depth.x < 0f && right.depth.y < 0f, "RIGHT depth goes up-left")
    }

    @Test
    fun neverEnlargesSmallDrawings() {
        val l = DepthProjection.layout(50f, 50f, 0.5f, Facing.LEFT, 1024f, 1024f)
        assertTrue(abs(l.front.d - 1f) < 1e-6f)
    }
}
