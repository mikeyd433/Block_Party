package com.blockparty.core

import com.blockparty.core.art.ImageOps
import com.blockparty.core.geometry.DepthProjection
import com.blockparty.core.geometry.Facing
import com.blockparty.core.geometry.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoxBaseTest {
    private val w = 400
    private val h = 400

    /** Fills the convex hull of [pts] (given in order) into a w x h pixel array. */
    private fun fillConvex(pts: List<Vec2>): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = Vec2(x + 0.5f, y + 0.5f)
            var sign = 0
            var inside = true
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s != 0) {
                    if (sign == 0) sign = s else if (s != sign) { inside = false; break }
                }
            }
            if (inside) px[y * w + x] = 0xFF884422.toInt()
        }
        return px
    }

    private fun box(facing: Facing): Pair<IntArray, Vec2> {
        val l = DepthProjection.layout(160f, 120f, 0.6f, facing, w.toFloat(), h.toFloat())
        val front = listOf(Vec2(0f, 0f), Vec2(160f, 0f), Vec2(160f, 120f), Vec2(0f, 120f)).map(l.front::apply)
        val all = front + front.map { it + l.depth }
        // Convex hull of the 8 points (gift wrapping).
        val hull = ArrayList<Vec2>()
        var current = all.minBy { it.x }
        do {
            hull.add(current)
            var next = all.first { it != current }
            for (c in all) {
                val cross = (next.x - current.x) * (c.y - current.y) - (next.y - current.y) * (c.x - current.x)
                if (cross < 0) next = c
            }
            current = next
        } while (current != hull.first() && hull.size < 9)
        return fillConvex(hull) to l.groundCenter(160f, 120f)
    }

    @Test
    fun findsTheBaseOfTurnedBoxes() {
        for (facing in Facing.entries) {
            val (px, expected) = box(facing)
            val found = assertNotNull(ImageOps.estimateBoxBase(px, w, h), "$facing")
            assertTrue(abs(found.first - expected.x) < 4f && abs(found.second - expected.y) < 4f, "$facing: $found vs $expected")
        }
    }

    @Test
    fun leavesFlatDrawingsAlone() {
        val rect = fillConvex(listOf(Vec2(100f, 100f), Vec2(300f, 100f), Vec2(300f, 300f), Vec2(100f, 300f)))
        assertNull(ImageOps.estimateBoxBase(rect, w, h))
        val triangle = fillConvex(listOf(Vec2(200f, 50f), Vec2(350f, 350f), Vec2(50f, 350f)))
        assertNull(ImageOps.estimateBoxBase(triangle, w, h))
        assertNull(ImageOps.estimateBoxBase(IntArray(w * h), w, h))
    }
}
