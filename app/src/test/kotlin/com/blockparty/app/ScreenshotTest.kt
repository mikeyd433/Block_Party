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
import androidx.compose.ui.test.onNodeWithContentDescription
import com.blockparty.app.data.StarterSet
import com.blockparty.core.model.CharacterRole
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.npc.ServicePhase
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.blockparty.app.ui.BlockPartyApp
import com.blockparty.app.ui.draw.DrawingExport
import com.blockparty.core.geometry.Facing
import com.blockparty.app.ui.theme.BlockPartyTheme
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        repeat(200) {
            if ((vm.world?.starterSetVersion ?: 0) < StarterSet.VERSION) { Thread.sleep(20); ShadowLooper.idleMainLooper() }
        }
        assertNotNull("world loads", vm.world)
        assertEquals("starter set installed", StarterSet.VERSION, vm.world!!.starterSetVersion)
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
        val crate = DrawingExport.addDepth(SampleArt.crateFront(), 0.6f, Facing.LEFT)!!
        val (crateBitmap, crop) = DrawingExport.cropAndScaleWithRect(crate.bitmap)!!
        vm.saveDrawing(
            crateBitmap,
            template(SpriteKind.FURNITURE, "Cake crate").copy(
                alignToGrid = false,
                groundX = (crate.ground.x - crop.left) / crop.width,
                groundY = (crate.ground.y - crop.top) / crop.height,
            ),
            DrawPurpose.FURNITURE,
        )
        assertNotNull(vm.placeFurniture(lot.x + 4, lot.y + 4))
        vm.startPlacing(null)
        assertEquals(5, vm.homeLot!!.furniture.size)

        vm.saveDrawing(SampleArt.person(), template(SpriteKind.CHARACTER, "Sam's customer"), DrawPurpose.CUSTOMER)

        compose.mainClock.autoAdvance = false // the street animates forever
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(500)
        assertTrue("crowd is out", vm.crowd.people.isNotEmpty())
        snapshot("01_street")


        compose.onNodeWithText("Go inside").performClick()
        advance(1500)
        snapshot("02_interior")

        compose.onNodeWithText("Furniture").performClick()
        advance(800)
        compose.onNodeWithText("Draw new furniture").assertExists() // sheet is its own window
    }

    @Test
    fun starterSetAndDrawnCustomers() {
        val vm = newViewModel()
        val world = vm.world!!
        val starterCustomers = world.sprites.filter { it.isStarter && it.role == CharacterRole.CUSTOMER }
        assertEquals(6, starterCustomers.size)
        assertEquals(6, world.npcs.size)
        assertTrue(world.sprites.count { it.isStarter && it.kind == SpriteKind.FURNITURE } >= 5)
        assertEquals(2, world.sprites.count { it.isStarter && it.kind == SpriteKind.FACADE })
        assertTrue(world.sprites.filter { it.isStarter }.all { vm.sprites.images[it.id] != null })

        vm.saveDrawing(SampleArt.person(), template(SpriteKind.CHARACTER, "Drawn customer"), DrawPurpose.CUSTOMER)
        val drawn = vm.world!!.sprites.first { it.name == "Drawn customer" }
        assertEquals(CharacterRole.CUSTOMER, drawn.role)
        val profile = vm.world!!.npcs.first { it.spriteId == drawn.id }
        assertTrue(profile.tagIds.isNotEmpty() && profile.budgetCoins > 0)
        vm.deleteSprite(drawn.id)
        assertTrue(vm.world!!.npcs.none { it.spriteId == drawn.id })

        // Starter plant and lamp are plain upright drawings and must not be "fixed" as boxes.
        assertEquals(1f, vm.world!!.sprite("starter-plant")!!.groundY)
        assertEquals(1f, vm.world!!.sprite("starter-lamp")!!.groundY)
    }

    @Test
    fun starterShop() {
        val vm = newViewModel()
        vm.setBusinessName("Starter Store")
        vm.setFacade("starter-facade-awning")
        val lot = vm.homeLot!!.rect
        for ((id, x, y) in listOf(
            Triple("starter-bookshelf", lot.x + 1, lot.y),
            Triple("starter-counter", lot.x + 3, lot.y + 2),
            Triple("starter-plant", lot.x, lot.y + 4),
            Triple("starter-lamp", lot.x + 5, lot.y),
            Triple("starter-stool", lot.x + 3, lot.y + 4),
            Triple("starter-display", lot.x + 1, lot.y + 3),
            Triple("starter-salon-chair", lot.x + 5, lot.y + 3),
        )) {
            vm.startPlacing(id)
            assertNotNull(id, vm.placeFurniture(x, y))
        }
        vm.startPlacing(null)
        // Products on the display table.
        vm.saveDrawing(SampleArt.cake(), template(SpriteKind.PRODUCT, "Cake"), DrawPurpose.FURNITURE)
        vm.saveDrawing(SampleArt.jar(), template(SpriteKind.PRODUCT, "Jam"), DrawPurpose.FURNITURE)
        vm.startPlacing(null)
        val cakeSprite = vm.world!!.sprites.first { it.name == "Cake" }.id
        val jamSprite = vm.world!!.sprites.first { it.name == "Jam" }.id
        for ((name, sprite) in listOf("Cake" to cakeSprite, "Jam" to jamSprite)) {
            vm.startOffering(OfferingKind.PRODUCT)
            vm.updateDraft { it.copy(name = name, spriteId = sprite) }
            vm.saveDraft()
        }
        vm.setBusinessMode(com.blockparty.core.model.BusinessMode.BOTH)
        // Every starter tag, so passers-by are likely to come in and the test doesn't rely on luck.
        TagLibrary.byCategory(0).values.flatten().forEach { vm.toggleBusinessTag(it.id) }
        vm.saveDrawing(SampleArt.scissors(), template(SpriteKind.PRODUCT, "Scissors"), DrawPurpose.FURNITURE)
        vm.saveDrawing(SampleArt.person(), template(SpriteKind.CHARACTER, "Sam"), DrawPurpose.SHOPKEEPER)
        vm.startPlacing(null)
        val scissors = vm.world!!.sprites.first { it.name == "Scissors" }.id
        vm.startOffering(OfferingKind.SERVICE)
        vm.updateDraft { it.copy(name = "Haircut", spriteId = scissors) }
        vm.saveDraft()
        assertEquals(FurnitureType.CHECKOUT, vm.world!!.sprite("starter-counter")!!.furnitureType)
        assertEquals(FurnitureType.SEATING, vm.world!!.sprite("starter-stool")!!.furnitureType)
        assertEquals(FurnitureType.SERVICE_STATION, vm.world!!.sprite("starter-salon-chair")!!.furnitureType)
        val table = vm.homeLot!!.furniture.first { it.spriteId == "starter-display" }
        assertEquals(3, vm.world!!.sprite("starter-display")!!.displaySpots.size)
        vm.autoFillStock(table.id)
        assertEquals(3, vm.homeLot!!.furniture.first { it.id == table.id }.stock.size)

        compose.mainClock.autoAdvance = false
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(500)
        snapshot("06_starter_street")
        compose.onNodeWithText("Go inside").performClick()
        // Run the shop for a while and catch someone sitting.
        var sawService = false
        for (i in 0 until 600) {
            advance(250)
            val serving = vm.crowd.services.any { it.phase == ServicePhase.SERVING && it.progress > 0.2f }
            if (serving) { sawService = true; break }
        }
        assertTrue("someone was served with the scissors", sawService)
        assertTrue(vm.crowd.people.any { it.heldItem != null && it.seatedOn != null })
        snapshot("07_starter_interior")
        compose.runOnIdle { vm.navigate(Screen.DisplayEditor(table.id)) }
        advance(500)
        compose.onNodeWithText("On display").assertExists()
        snapshot("08_display_editor")
        compose.runOnIdle { vm.back() }
        val chair = vm.homeLot!!.furniture.first { it.spriteId == "starter-salon-chair" }
        compose.runOnIdle { vm.navigate(Screen.DisplayEditor(chair.id)) }
        advance(500)
        compose.onNodeWithText("Seat height").assertExists()
        snapshot("09_seat_setup")
    }

    @Test
    fun oldTurnedFurnitureIsGroundedOnLoad() {
        val vm = newViewModel()
        val crate = DrawingExport.addDepth(SampleArt.crateFront(), 0.6f, Facing.LEFT)!!
        val bitmap = DrawingExport.cropAndScale(crate.bitmap)!!
        // Saved the old way: no base point, so it would rest on the bottom of the picture.
        vm.saveDrawing(bitmap, template(SpriteKind.FURNITURE, "Old crate").copy(alignToGrid = false), DrawPurpose.FURNITURE)
        vm.saveDrawing(SampleArt.plant(), template(SpriteKind.FURNITURE, "Upright plant").copy(alignToGrid = false), DrawPurpose.FURNITURE)
        repeat(20) { Thread.sleep(25); ShadowLooper.idleMainLooper() } // let saves finish

        val reloaded = newViewModel()
        var crateSprite = reloaded.world!!.sprites.first { it.name == "Old crate" }
        repeat(50) {
            if (crateSprite.groundY == 1f) {
                Thread.sleep(20); ShadowLooper.idleMainLooper()
                crateSprite = reloaded.world!!.sprites.first { it.name == "Old crate" }
            }
        }
        assertTrue("crate base found: ${crateSprite.groundX}, ${crateSprite.groundY}", crateSprite.groundY < 0.9f)
        val plant = reloaded.world!!.sprites.first { it.name == "Upright plant" }
        assertEquals(1f, plant.groundY)
    }

    @Test
    fun editors() {
        val vm = newViewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(300)
        compose.onNodeWithContentDescription("Street people").performClick()
        advance(800)
        compose.onNodeWithText("Draw a customer").assertExists()
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

    fun crateFront() = canvas(1024, 1024) {
        drawRect(362f, 412f, 662f, 662f, pen(0xFFE2B48A.toInt(), fill = true))
        drawRect(362f, 412f, 662f, 662f, pen(0xFF8C5A3C.toInt(), 12f))
        drawLine(362f, 537f, 662f, 537f, pen(0xFF8C5A3C.toInt(), 8f))
        drawCircle(512f, 474f, 26f, pen(0xFFE58BB8.toInt(), fill = true))
    }

    fun scissors() = canvas(200, 200) {
        val blade = pen(0xFF8F897D.toInt(), 14f)
        drawLine(60f, 150f, 160f, 30f, blade)
        drawLine(140f, 150f, 40f, 30f, blade)
        drawCircle(55f, 165f, 24f, pen(0xFFD9443A.toInt(), 10f))
        drawCircle(145f, 165f, 24f, pen(0xFFD9443A.toInt(), 10f))
    }

    fun cake() = canvas(200, 180) {
        drawRect(20f, 70f, 180f, 170f, pen(0xFFF7E68B.toInt(), fill = true))
        drawRect(20f, 70f, 180f, 100f, pen(0xFFE58BB8.toInt(), fill = true))
        drawRect(20f, 70f, 180f, 170f, pen(0xFF2E2B26.toInt(), 6f))
        drawLine(100f, 70f, 100f, 25f, pen(0xFFD9443A.toInt(), 8f))
        drawCircle(100f, 18f, 9f, pen(0xFFF5C542.toInt(), fill = true))
    }

    fun jar() = canvas(120, 180) {
        drawRect(15f, 50f, 105f, 175f, pen(0xFFD9443A.toInt(), fill = true))
        drawRect(10f, 25f, 110f, 50f, pen(0xFF8C5A3C.toInt(), fill = true))
        drawRect(15f, 50f, 105f, 175f, pen(0xFF2E2B26.toInt(), 6f))
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
