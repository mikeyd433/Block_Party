package com.blockparty.core

import com.blockparty.core.geometry.Affine2D
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Storefront
import com.blockparty.core.geometry.Tile
import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.GridRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsoTest {
    private val iso = IsoProjection(128f, 64f)

    private fun near(a: Vec2, b: Vec2, eps: Float = 1e-3f) =
        assertTrue(abs(a.x - b.x) < eps && abs(a.y - b.y) < eps, "expected $b but was $a")

    @Test
    fun gridToScreenFollowsTwoToOneProjection() {
        near(Vec2(0f, 0f), iso.gridToScreen(0f, 0f))
        near(Vec2(64f, 32f), iso.gridToScreen(1f, 0f))
        near(Vec2(-64f, 32f), iso.gridToScreen(0f, 1f))
        near(Vec2(0f, 64f - 10f), iso.gridToScreen(1f, 1f, z = 10f))
    }

    @Test
    fun screenToGridInvertsProjection() {
        for (gx in listOf(-3.5f, 0f, 2.25f, 9f)) for (gy in listOf(-1f, 0.5f, 4f)) {
            val s = iso.gridToScreen(gx, gy)
            near(Vec2(gx, gy), iso.screenToGrid(s.x, s.y))
        }
    }

    @Test
    fun screenToTileFloorsIncludingNegatives() {
        val center = iso.gridToScreen(3.5f, 2.5f)
        assertEquals(Tile(3, 2), iso.screenToTile(center.x, center.y))
        val neg = iso.gridToScreen(-0.5f, 0.5f)
        assertEquals(Tile(-1, 0), iso.screenToTile(neg.x, neg.y))
    }

    @Test
    fun affineMapsImageCornersOntoParallelogram() {
        val tl = Vec2(10f, 20f)
        val tr = Vec2(110f, 70f)
        val bl = Vec2(10f, 220f)
        val m = Affine2D.mapRectTo(200f, 100f, tl, tr, bl)
        near(tl, m.apply(Vec2(0f, 0f)))
        near(tr, m.apply(Vec2(200f, 0f)))
        near(bl, m.apply(Vec2(0f, 100f)))
        near(Vec2(110f, 270f), m.apply(Vec2(200f, 100f)))
    }

    @Test
    fun facadeTransformCoversTheFrontWall() {
        val store = Storefront(iso)
        val lot = GridRect(2, 0, 6, 6)
        val face = store.frontFace(lot)
        val m = store.facadeTransform(lot, 400f, 180f)
        near(face[3], m.apply(Vec2(0f, 0f)))
        near(face[2], m.apply(Vec2(400f, 0f)))
        near(face[1], m.apply(Vec2(400f, 180f)))
        near(face[0], m.apply(Vec2(0f, 180f)))
        assertTrue(store.facadeAspect(6) > 1f)
    }
}
