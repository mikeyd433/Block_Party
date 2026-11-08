package com.blockparty.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.blockparty.app.ui.BlockPartyApp
import com.blockparty.app.ui.theme.BlockPartyTheme
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the real screens with Robolectric's native graphics and writes PNGs to
 * app/build/screenshots, as a smoke test that the street, cutouts and editors draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun snapshot(name: String) {
        // The street animates forever, so the UI is never "idle": draw the window directly
        // instead of using captureToImage, which waits for idleness.
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File(outDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun advance(ms: Long) {
        // Deliver pending snapshot-apply messages first so state changes schedule a frame.
        ShadowLooper.idleMainLooper()
        compose.mainClock.advanceTimeBy(ms)
        ShadowLooper.idleMainLooper()
    }

    private fun newViewModel(): GameViewModel {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        ShadowLooper.idleMainLooper()
        repeat(50) { if (vm.world == null) { Thread.sleep(20); ShadowLooper.idleMainLooper() } }
        assertNotNull("world loads", vm.world)
        return vm
    }

    @Test
    fun streetAndShopInterior() {
        val vm = newViewModel()
        vm.setPlayerName("Sam")
        vm.setBusinessName("Midnight Cakes")
        vm.saveDrawing(SampleArt.facade(), template(SpriteKind.FACADE, "Cake front"), DrawPurpose.FACADE)
        vm.saveDrawing(SampleArt.person(), template(SpriteKind.CHARACTER, "Sam"), DrawPurpose.SHOPKEEPER)
        vm.saveDrawing(SampleArt.table(), template(SpriteKind.FURNITURE, "Table", 2, 1), DrawPurpose.FURNITURE)
        val lot = vm.homeLot!!.rect
        assertNotNull(vm.placeFurniture(lot.x + 1, lot.y + 1))
        val bench = vm.placeFurniture(lot.x + 3, lot.y + 3)!!
        assert(vm.flipFurniture(bench.id)) // runs along the other floor axis
        vm.saveDrawing(SampleArt.plant(), template(SpriteKind.FURNITURE, "Plant"), DrawPurpose.FURNITURE)
        assertNotNull(vm.placeFurniture(lot.x + 4, lot.y + 1))
        assertNotNull(vm.placeFurniture(lot.x, lot.y + 4))
        vm.startPlacing(null)
        assertEquals(4, vm.homeLot!!.furniture.size)

        compose.mainClock.autoAdvance = false // the street animates forever
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(500)
        snapshot("01_street")

        compose.onNodeWithText("Go inside").performClick()
        advance(1500)
        snapshot("02_interior")

        compose.onNodeWithText("Furniture").performClick()
        advance(800)
        compose.onNodeWithText("Draw new furniture").assertExists() // sheet is its own window
    }

    @Test
    fun editors() {
        val vm = newViewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(300)
        compose.runOnIdle { vm.navigate(Screen.ShopEditor) }
        advance(300)
        compose.onNodeWithText("Your shop").assertExists()
        snapshot("04_shop_editor")
        compose.runOnIdle { vm.navigate(Screen.Draw(SpriteKind.FURNITURE, DrawPurpose.FURNITURE)) }
        advance(300)
        compose.onNodeWithText("Draw furniture").assertExists()
        snapshot("05_drawing")
    }

    private fun template(kind: SpriteKind, name: String, fw: Int = 1, fh: Int = 1) =
        SpriteAsset(id = "", kind = kind, name = name, widthPx = 0, heightPx = 0, footprintW = fw, footprintH = fh)
}

/** Stand-ins for player drawings, drawn with simple strokes like a finger would. */
private object SampleArt {
    private fun pen(color: Int, width: Float = 10f, fill: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun canvas(w: Int, h: Int, block: Canvas.() -> Unit): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).block() }

    fun person() = canvas(200, 360) {
        drawOval(RectF(40f, 150f, 160f, 290f), pen(0xFF3D6FC4.toInt(), fill = true))
        drawCircle(100f, 90f, 55f, pen(0xFFE2B48A.toInt(), fill = true))
        drawCircle(100f, 90f, 55f, pen(0xFF2E2B26.toInt(), 6f))
        drawCircle(118f, 80f, 6f, pen(0xFF2E2B26.toInt(), fill = true))
        drawArc(RectF(95f, 95f, 135f, 120f), 10f, 160f, false, pen(0xFF2E2B26.toInt(), 5f))
        drawLine(70f, 285f, 65f, 350f, pen(0xFF2E2B26.toInt(), 12f))
        drawLine(130f, 285f, 140f, 350f, pen(0xFF2E2B26.toInt(), 12f))
    }

    fun table() = canvas(420, 220) {
        drawRect(20f, 30f, 400f, 70f, pen(0xFF8C5A3C.toInt(), fill = true))
        drawLine(50f, 70f, 50f, 210f, pen(0xFF4A3426.toInt(), 16f))
        drawLine(370f, 70f, 370f, 210f, pen(0xFF4A3426.toInt(), 16f))
        drawCircle(140f, 18f, 16f, pen(0xFFE58BB8.toInt(), fill = true))
        drawCircle(260f, 15f, 14f, pen(0xFFF5C542.toInt(), fill = true))
    }

    fun plant() = canvas(200, 300) {
        val leaf = pen(0xFF2F8F5B.toInt(), 14f)
        drawLine(100f, 200f, 60f, 60f, leaf)
        drawLine(100f, 200f, 100f, 30f, leaf)
        drawLine(100f, 200f, 145f, 70f, leaf)
        val pot = Path().apply { moveTo(50f, 190f); lineTo(150f, 190f); lineTo(135f, 290f); lineTo(65f, 290f); close() }
        drawPath(pot, pen(0xFFF08A4B.toInt(), fill = true))
    }

    fun facade() = canvas(1024, 457) {
        drawRect(60f, 30f, 964f, 120f, pen(0xFFE58BB8.toInt(), fill = true))
        drawText("MIDNIGHT CAKES", 512f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF27387A.toInt(); textSize = 70f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        })
        drawRect(120f, 190f, 300f, 457f, pen(0xFF27387A.toInt(), fill = true))
        drawRect(420f, 190f, 900f, 360f, pen(0xFF5BC0D9.toInt(), fill = true))
        drawRect(420f, 190f, 900f, 360f, pen(0xFF2E2B26.toInt(), 10f))
        for (x in listOf(80f, 340f, 940f)) drawCircle(x, 420f, 22f, pen(0xFFF5C542.toInt(), fill = true))
    }
}
