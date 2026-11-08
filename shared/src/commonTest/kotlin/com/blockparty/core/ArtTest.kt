package com.blockparty.core

import com.blockparty.core.art.CutoutSizing
import com.blockparty.core.art.ImageOps
import com.blockparty.core.art.PixelRect
import com.blockparty.core.art.StrokeOps
import com.blockparty.core.geometry.IsoProjection
import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.world.WorldCodec
import com.blockparty.core.world.WorldFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtTest {
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun contentBoundsFindsOpaquePixels() {
        val px = IntArray(10 * 8)
        px[2 * 10 + 3] = red
        px[5 * 10 + 7] = red
        assertEquals(PixelRect(3, 2, 8, 6), ImageOps.contentBounds(px, 10, 8))
        assertNull(ImageOps.contentBounds(IntArray(16), 4, 4))
    }

    @Test
    fun fitWithinNeverUpscales() {
        assertEquals(100 to 50, ImageOps.fitWithin(100, 50, 512))
        assertEquals(512 to 256, ImageOps.fitWithin(1024, 512, 512))
    }

    @Test
    fun removeBackgroundKeepsEnclosedRegions() {
        // 7x7 white image with a red ring; the white inside the ring must survive.
        val w = 7
        val px = IntArray(w * w) { white }
        for (i in 1..5) {
            px[1 * w + i] = red; px[5 * w + i] = red; px[i * w + 1] = red; px[i * w + 5] = red
        }
        val out = ImageOps.removeBackground(px, w, w)
        assertEquals(0, ImageOps.alpha(out[0]), "outer background removed")
        assertEquals(red, out[1 * w + 1], "drawing untouched")
        assertEquals(white, out[3 * w + 3], "enclosed white kept")
    }

    @Test
    fun strokeThinningKeepsEndpoints() {
        val pts = (0..100).map { Vec2(it * 0.1f, 0f) }
        val thin = StrokeOps.thin(pts, 1f)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
        assertTrue(thin.size in 10..13)
    }

    @Test
    fun cutoutsFitTheirSlot() {
        val iso = IsoProjection()
        val tall = SpriteAsset("a", SpriteKind.CHARACTER, "Tall", 100, 1000)
        val (w, h) = CutoutSizing.displaySize(tall, iso)
        assertTrue(h <= iso.tileHeight * 2f + 0.01f)
        assertEquals(0.1f, w / h, 1e-4f)
        val wide = SpriteAsset("b", SpriteKind.FURNITURE, "Wide", 1000, 100, footprintW = 1, footprintH = 1)
        val (fw, _) = CutoutSizing.displaySize(wide, iso)
        assertTrue(fw <= iso.footprintScreenWidth(1, 1))
    }

    @Test
    fun worldRoundTripsThroughJson() {
        val world = WorldFactory.newWorld("Sam")
        assertEquals(world, WorldCodec.decode(WorldCodec.encode(world)))
        assertTrue(world.lot(WorldFactory.HOME_LOT_ID) != null)
    }

    @Test
    fun tagIdsAreUnique() {
        val ids = TagLibrary.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(TagLibrary.byCategory(0).values.all { it.isNotEmpty() })
    }
}
