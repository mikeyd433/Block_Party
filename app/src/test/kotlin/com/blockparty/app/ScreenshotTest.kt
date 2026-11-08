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
import com.blockparty.core.world.WorldFactory
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
        val lot = vm.currentLot!!.rect
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
        assertEquals(5, vm.currentLot!!.furniture.size)

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
    fun streetsSignInAndSharedStreet() {
        val vm = newViewModel()
        val online = OnlineViewModel(ApplicationProvider.getApplicationContext<Application>())
        online.debugShow(null, emptyList())
        compose.mainClock.autoAdvance = false // the street animates forever
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm, online) } }
        advance(500)

        compose.onNodeWithContentDescription("Streets").performClick()
        advance(500)
        compose.onNodeWithText("Sign in or create account").assertExists()
        snapshot("20_streets_signed_out")
        // Off the street nothing animates forever, so let the test clock run (dialogs need it).
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("Sign in or create account").performClick()
        advance(500)
        compose.onNodeWithText("New account").performClick()
        advance(500)
        compose.onNodeWithText("Your name").assertExists()
        snapshot("21_create_account")
        vm.back()

        val me = com.blockparty.core.net.RemoteMember("u1", "Ann")
        val elm = com.blockparty.core.net.RemoteStreet("s1", "Elm Street", "AB12CD", adminId = "u1")
        val oak = com.blockparty.core.net.RemoteStreet("s2", "Oak Lane", "QW34ER", adminId = "u2")
        online.debugShow(
            Account("u1", "ann@example.com", "Ann"), listOf(elm, oak),
            mapOf("s1" to listOf(me, com.blockparty.core.net.RemoteMember("u2", "Bo"))),
        )
        advance(500)
        compose.onNodeWithText("Oak Lane").assertExists()
        snapshot("22_streets_signed_in")

        compose.onNodeWithText("Elm Street").performClick()
        advance(500)
        compose.onNodeWithText("AB12CD").assertExists()
        compose.onNodeWithText("Bo").assertExists()
        snapshot("23_shared_street")
    }

    @Test
    fun sharedStreetWithAFriendsShop() {
        val vm = newViewModel()
        val base = vm.world!!
        fun lot(i: Int, owner: String?, name: String?) = com.blockparty.core.net.RemoteLot(
            "lot-$i", com.blockparty.core.model.GridRect(1 + i * 7, 0, 6, 6), if (owner == null) "VACANT" else "OWNED",
            owner, if (owner == "u1") "Ann" else "Bo",
            name?.let { com.blockparty.core.net.SyncCodec.business(com.blockparty.core.model.Business(it)) },
            com.blockparty.core.net.SyncCodec.furniture(emptyList()),
        )
        val start = WorldFactory.newSharedWorld("s1", "Elm Street", "UTC", "u1", "Ann")
            .copy(sprites = base.sprites, npcs = base.npcs, starterSetVersion = base.starterSetVersion)
        val shared = com.blockparty.core.net.SyncMerge.merge(
            start,
            com.blockparty.core.net.ServerStreet(listOf(lot(0, "u1", "Ann's Cakes"), lot(1, "u2", "Bo's Books"), lot(2, null, null)), emptyList()),
        ).world
        vm.debugReplaceWorld(shared)
        vm.selectLot("lot-1")

        // Bo's shop can't be changed from Ann's phone.
        vm.setBusinessName("Hacked")
        assertEquals("Bo's Books", vm.world!!.lot("lot-1")!!.business!!.name)

        compose.mainClock.autoAdvance = false // the street animates forever
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(500)
        compose.onNodeWithText("Elm Street · Bo's shop").assertExists()
        compose.onNodeWithText("Edit shop").assertDoesNotExist()
        snapshot("30_shared_street")

        compose.onNodeWithText("Visit").performClick()
        advance(1500)
        compose.onNodeWithText("Visiting Bo's shop").assertExists()
        compose.onNodeWithText("Furniture").assertDoesNotExist()
        snapshot("31_visiting")

        // Ann's own shop still edits as normal.
        vm.selectLot("lot-0")
        vm.setBusinessName("Ann's Bigger Cakes")
        assertEquals("Ann's Bigger Cakes", vm.world!!.lot("lot-0")!!.business!!.name)
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
        val lot = vm.currentLot!!.rect
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
        val table = vm.currentLot!!.furniture.first { it.spriteId == "starter-display" }
        assertEquals(3, vm.world!!.sprite("starter-display")!!.displaySpots.size)
        vm.autoFillStock(table.id)
        assertEquals(3, vm.currentLot!!.furniture.first { it.id == table.id }.stock.size)
        // New products start empty: buy stock from the supplier (2 spots of cake, 1 of jam).
        assertEquals(0, vm.currentLot!!.business!!.offerings.first { it.name == "Cake" }.stock)
        val (units, cost) = vm.restockAllNeed()
        assertEquals(15, units)
        vm.restockAll()
        assertEquals(10, vm.currentLot!!.business!!.offerings.first { it.name == "Cake" }.stock)
        assertEquals(100L - cost, vm.world!!.coins)

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

        // Keep the shop open until it has earned money and been reviewed.
        val startCoins = vm.world!!.coins
        for (i in 0 until 1200) {
            val w = vm.world!!
            if (w.coins > startCoins && w.lot(WorldFactory.HOME_LOT_ID)!!.business!!.reviews.isNotEmpty()) break
            advance(250)
        }
        val business = vm.world!!.lot(WorldFactory.HOME_LOT_ID)!!.business!!
        assertTrue("earned coins", vm.world!!.coins > startCoins)
        val supplies = vm.world!!.economy.books.sumOf { it.supplies } - cost // services use supplies too
        assertEquals(vm.world!!.coins - startCoins + supplies, business.revenueTotal)
        val stockLeft = business.offerings.filter { it.kind == OfferingKind.PRODUCT }.sumOf { it.stock }
        val productsSold = 15 - stockLeft
        assertTrue("stock goes down as products sell", productsSold >= 0)
        assertTrue(business.customersServed > 0 && business.itemsSold > 0)
        assertTrue("reviewed", business.reviews.isNotEmpty() && business.rating != null)
        snapshot("07b_after_sales")
        compose.runOnIdle { vm.navigate(Screen.DisplayEditor(table.id)) }
        advance(500)
        compose.onNodeWithText("On display").assertExists()
        snapshot("08_display_editor")
        compose.runOnIdle { vm.back() }
        // Hire staff from today's job board.
        val applicants = vm.applicants()
        assertTrue("starter employees apply", applicants.size >= 2)
        assertTrue(applicants.all { a -> vm.world!!.sprite(a.spriteId)?.role == com.blockparty.core.model.CharacterRole.EMPLOYEE })
        compose.runOnIdle { vm.navigate(Screen.JobBoard) }
        advance(500)
        compose.onNodeWithText("Job board").assertExists()
        snapshot("12_job_board")
        compose.runOnIdle {
            assertEquals(null, vm.hire(applicants[0]))
            assertEquals(null, vm.hire(applicants[1]))
            vm.back()
        }
        assertEquals(2, vm.currentLot!!.business!!.employees.size)
        assertTrue("hired people leave the board", vm.applicants().none { it.id == applicants[0].id })
        compose.onNodeWithText("Go inside").let { }
        for (i in 0 until 8) advance(250)
        assertEquals(3, vm.crowd.staff.count { it.lotId == vm.currentLot!!.id })
        snapshot("13_with_staff")
        val chair = vm.currentLot!!.furniture.first { it.spriteId == "starter-salon-chair" }
        compose.runOnIdle { vm.navigate(Screen.DisplayEditor(chair.id)) }
        advance(500)
        compose.onNodeWithText("Seat height").assertExists()
        snapshot("09_seat_setup")
    }

    @Test
    fun multipleShopsClosingAndMerging() {
        val vm = newViewModel()
        // Buy the west lot: a second shop.
        assertEquals("You need ${com.blockparty.core.sim.Lots.price(vm.world!!.lot("lot-west")!!, vm.world!!)} coins to buy this lot.", vm.buyLot("lot-west"))
        compose.runOnIdle { } // no-op: just making sure we're on the main thread
        vm.recordEvents(emptyList())
        val rich = vm.world!!.copy(coins = 5000)
        vm.debugReplaceWorld(rich)
        assertEquals(null, vm.buyLot("lot-west"))
        assertEquals("lot-west", vm.currentLot!!.id)
        vm.setBusinessName("West Wing")
        vm.selectLot(WorldFactory.HOME_LOT_ID)
        vm.setBusinessName("Home Shop")
        assertEquals("West Wing", vm.world!!.lot("lot-west")!!.business!!.name)
        // Furniture removed goes to storage, and is used up when placed again.
        vm.startPlacing("starter-plant")
        val placed = vm.placeFurniture(vm.currentLot!!.rect.x, vm.currentLot!!.rect.y)!!
        vm.removeFurniture(placed.id)
        assertEquals(listOf("starter-plant"), vm.world!!.storage)
        vm.startPlacing("starter-plant")
        vm.placeFurniture(vm.currentLot!!.rect.x, vm.currentLot!!.rect.y)
        assertTrue(vm.world!!.storage.isEmpty())
        vm.startPlacing(null)
        vm.setFacade("starter-facade-brick")
        compose.mainClock.autoAdvance = false
        compose.setContent { BlockPartyTheme { BlockPartyApp(vm) } }
        advance(500)
        snapshot("10_two_shops")
        // Merge the two shops.
        compose.runOnIdle { assertEquals(null, vm.expandInto("lot-west")) }
        assertEquals(null, vm.world!!.lot("lot-west"))
        assertEquals(13, vm.currentLot!!.rect.w)
        advance(500)
        snapshot("11_merged_shop")
        compose.runOnIdle { }
        // Close it.
        vm.navigate(Screen.ShopEditor)
        vm.closeCurrentBusiness()
        assertEquals(com.blockparty.core.model.LotState.VACANT, vm.world!!.lot(WorldFactory.HOME_LOT_ID)!!.state)
        assertTrue(vm.world!!.storage.contains("starter-plant"))
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
