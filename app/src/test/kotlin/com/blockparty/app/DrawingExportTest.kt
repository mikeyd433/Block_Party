package com.blockparty.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.blockparty.app.ui.draw.DrawingExport
import com.blockparty.app.ui.draw.DrawingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DrawingExportTest {
    private fun DrawingState.line(from: Offset, to: Offset) {
        begin(from)
        for (i in 1..20) extend(from + (to - from) * (i / 20f))
        end()
    }

    @Test
    fun exportCropsToTheDrawingWithTransparentBackground() {
        val state = DrawingState(1024, 1024)
        state.color = Color.Red
        state.brushWidth = 20f
        state.line(Offset(300f, 400f), Offset(500f, 400f))
        val out = DrawingExport.cropAndScale(state.rasterize())
        assertNotNull(out)
        out!!
        // Stroke is 200 long + round caps (20) + padding (8).
        assertTrue("width ${out.width}", out.width in 220..232)
        assertTrue("height ${out.height}", out.height in 18..32)
        assertEquals(0, out.getPixel(0, 0) ushr 24) // transparent corner
        assertEquals(Color.Red.value.toLong() ushr 32, out.getPixel(out.width / 2, out.height / 2).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun eraserClearsAndUndoRestores() {
        val state = DrawingState(512, 512)
        state.brushWidth = 30f
        state.line(Offset(100f, 256f), Offset(400f, 256f))
        state.eraser = true
        state.brushWidth = 80f
        state.line(Offset(50f, 256f), Offset(460f, 256f))
        assertNull("fully erased", DrawingExport.cropAndScale(state.rasterize()))
        state.undo()
        assertNotNull(DrawingExport.cropAndScale(state.rasterize()))
        state.redo()
        assertNull(DrawingExport.cropAndScale(state.rasterize()))
    }

    @Test
    fun largeDrawingsAreScaledDown() {
        val state = DrawingState(1024, 1024)
        state.brushWidth = 40f
        state.line(Offset(20f, 20f), Offset(1000f, 1000f))
        val out = DrawingExport.cropAndScale(state.rasterize(), maxDim = 512)!!
        assertTrue(out.width <= 512 && out.height <= 512)
        assertFalse(state.isEmpty)
    }

    @Test
    fun backgroundRemovalMakesBorderColourTransparent() {
        val photo = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            for (x in 20..30) for (y in 20..30) setPixel(x, y, android.graphics.Color.BLUE)
        }
        val cut = DrawingExport.removeBackground(photo)
        assertEquals(0, cut.getPixel(0, 0) ushr 24)
        assertEquals(android.graphics.Color.BLUE, cut.getPixel(25, 25))
    }
}
