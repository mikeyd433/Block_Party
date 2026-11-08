package com.blockparty.core

import com.blockparty.core.geometry.GridPanel
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.GridRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GridPanelTest {
    private val iso = IsoProjection(128f, 64f)

    private fun near(a: Vec2, b: Vec2, eps: Float = 1e-2f) =
        assertTrue(abs(a.x - b.x) < eps && abs(a.y - b.y) < eps, "expected $b but was $a")

    @Test
    fun xPanelFollowsTheGridXAxis() {
        val rect = GridRect(2, 3, 2, 1)
        val p = GridPanel.standing(iso, rect, alongY = false, imageW = 200f, imageH = 100f, maxHeight = 1000f, fill = 1f)
        // Bottom edge runs along y = 3.5 from x = 2 to x = 4.
        near(iso.gridToScreen(2f, 3.5f), p.corners[3])
        near(iso.gridToScreen(4f, 3.5f), p.corners[2])
        // Image corners land on the panel corners.
        near(p.corners[0], p.transform.apply(Vec2(0f, 0f)))
        near(p.corners[1], p.transform.apply(Vec2(200f, 0f)))
        near(p.corners[2], p.transform.apply(Vec2(200f, 100f)))
        // Vertical edges stay vertical, and the drawing keeps its proportions along the slant.
        assertTrue(abs(p.corners[0].x - p.corners[3].x) < 1e-3f)
        val length = kotlin.math.sqrt(128f * 128f + 64f * 64f)
        assertTrue(abs((p.corners[3].y - p.corners[0].y) - length / 2f) < 0.1f)
        // Slopes down to the right on screen.
        assertTrue(p.corners[2].x > p.corners[3].x && p.corners[2].y > p.corners[3].y)
    }

    @Test
    fun yPanelIsTheMirrorImage() {
        val rect = GridRect(0, 0, 1, 2)
        val p = GridPanel.standing(iso, rect, alongY = true, imageW = 100f, imageH = 100f, maxHeight = 1000f, fill = 1f)
        // Image left starts at the upper-right end and runs down-left.
        assertTrue(p.corners[2].x < p.corners[3].x && p.corners[2].y > p.corners[3].y)
        near(iso.gridToScreen(0.5f, 0f), p.corners[3])
        near(iso.gridToScreen(0.5f, 2f), p.corners[2])
    }

    @Test
    fun tallDrawingsAreCappedAndNarrowed() {
        val rect = GridRect(0, 0, 2, 2)
        val p = GridPanel.standing(iso, rect, alongY = false, imageW = 10f, imageH = 1000f, maxHeight = 200f)
        assertTrue(abs((p.corners[3].y - p.corners[0].y) - 200f) < 0.01f)
        near((p.corners[2] + p.corners[3]) * 0.5f, iso.gridToScreen(1f, 1f))
        near(p.foot, iso.gridToScreen(1f, 1f))
    }
}
