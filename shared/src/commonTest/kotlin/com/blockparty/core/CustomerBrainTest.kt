package com.blockparty.core

import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.GridRect
import com.blockparty.core.npc.CatalogItem
import com.blockparty.core.npc.CrowdEvent
import com.blockparty.core.npc.CustomerBrain
import com.blockparty.core.npc.Fixture
import com.blockparty.core.npc.Fixtures
import com.blockparty.core.npc.ReviewWriter
import com.blockparty.core.npc.ShopCatalog
import com.blockparty.core.npc.ShopDoor
import com.blockparty.core.npc.StreetCrowd
import com.blockparty.core.npc.VisitReport
import com.blockparty.core.world.WorldFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomerBrainTest {
    private val cozyFoodie = NpcProfile("n1", "s1", "Ada", listOf("cozy", "food"), budgetCoins = 50, walkSpeed = 1f)
    private val thrifty = NpcProfile("n2", "s2", "Bo", listOf("cozy", "food"), budgetCoins = 10, walkSpeed = 1f)

    private fun item(id: String, price: Long, vararg tags: String, kind: OfferingKind = OfferingKind.PRODUCT) =
        CatalogItem(id, id, kind, price, tags.toList())

    @Test
    fun matchingTagsAndFairPricesPullCustomersIn() {
        val matching = ShopCatalog(listOf("cozy"), listOf(item("pie", 20, "food")), rating = null)
        val unrelated = ShopCatalog(listOf("spooky"), listOf(item("skull", 20, "oddities")), rating = null)
        assertTrue(CustomerBrain.visitChance(cozyFoodie, matching) > CustomerBrain.visitChance(cozyFoodie, unrelated) * 3)
        // Pricey shops lose thrifty customers.
        val affordable = ShopCatalog(listOf("cozy"), listOf(item("pie", 4, "food")), rating = null)
        val pricey = ShopCatalog(listOf("cozy"), listOf(item("pie", 120, "food")), rating = null)
        assertTrue(CustomerBrain.visitChance(thrifty, pricey) < CustomerBrain.visitChance(thrifty, affordable) * 0.5f)
        // ...while big spenders still drop into cheap shops, just a bit less keenly.
        assertTrue(CustomerBrain.visitChance(cozyFoodie, affordable) > CustomerBrain.visitChance(cozyFoodie, matching) * 0.4f)
        // Good ratings help, bad ones hurt.
        val loved = ShopCatalog(listOf("cozy"), listOf(item("pie", 20, "food")), rating = 5f)
        val hated = ShopCatalog(listOf("cozy"), listOf(item("pie", 20, "food")), rating = 1f)
        assertTrue(CustomerBrain.visitChance(cozyFoodie, loved) > CustomerBrain.visitChance(cozyFoodie, hated))
        // Empty shops get the odd curious visitor.
        assertTrue(CustomerBrain.visitChance(cozyFoodie, ShopCatalog(emptyList(), emptyList(), null)) in 0.01f..0.1f)
    }

    @Test
    fun higherPricesMeanFewerSales() {
        val catalog = ShopCatalog(listOf("cozy"), emptyList(), null)
        val cheap = CustomerBrain.buyChance(cozyFoodie, item("pie", 10, "food"), catalog, 50)
        val fair = CustomerBrain.buyChance(cozyFoodie, item("pie", 20, "food"), catalog, 50)
        val steep = CustomerBrain.buyChance(cozyFoodie, item("pie", 45, "food"), catalog, 50)
        assertTrue(cheap >= fair && fair > steep)
        assertEquals(0f, CustomerBrain.buyChance(cozyFoodie, item("pie", 60, "food"), catalog, 50), "can't afford")
    }

    @Test
    fun reviewsReflectTheVisit() {
        val catalog = ShopCatalog(listOf("cozy"), listOf(item("pie", 10, "food")), rating = null)
        val great = VisitReport(cozyFoodie, catalog, bought = listOf(catalog.items[0]), tooPricey = emptyList(),
            waitedSec = 2f, queued = true, gotService = false)
        assertEquals(5, ReviewWriter.stars(great))
        val awful = VisitReport(
            cozyFoodie, ShopCatalog(listOf("spooky"), listOf(item("skull", 90)), null),
            bought = emptyList(), tooPricey = listOf(item("skull", 90)), waitedSec = 40f, queued = true, gotService = false,
        )
        assertEquals(1, ReviewWriter.stars(awful))
        val text = ReviewWriter.write(great, 5, Random(1))
        assertTrue(text.isNotBlank() && text.length < 300, text)
        val awfulText = ReviewWriter.write(awful, 1, Random(2))
        assertTrue("{x}" !in awfulText && "{x}" !in text)
        // Deterministic for the same dice.
        assertEquals(text, ReviewWriter.write(great, 5, Random(1)))
    }

    @Test
    fun soldOutItemsDontSellAndAnnoyCustomers() {
        val world = WorldFactory.newWorld()
        val lot = world.lot(WorldFactory.HOME_LOT_ID)!!.rect
        val free = buildSet { for (x in lot.x until lot.right) for (y in lot.y until lot.bottom) add(x to y) }
        val catalog = ShopCatalog(listOf("cozy", "food"), listOf(item("pie", 8, "food").copy(stock = 0)), rating = null)
        val door = ShopDoor(WorldFactory.HOME_LOT_ID, lot, free, visitChance = { 0.9f }, catalog = catalog)
        val crowd = StreetCrowd(Random(4))
        val pool = (0 until 6).map { NpcProfile("n$it", "s$it", "P$it", listOf("cozy", "food"), 40, 1.2f) }
        val events = ArrayList<CrowdEvent>()
        repeat(8000) {
            crowd.update(0.05f, world.street, listOf(door)) { r -> pool[r.nextInt(pool.size)] }
            events += crowd.drainEvents()
        }
        assertTrue(events.none { it is CrowdEvent.Sale }, "nothing in stock, nothing sold")
        val reviews = events.filterIsInstance<CrowdEvent.Review>()
        assertTrue(reviews.isNotEmpty() && reviews.all { it.stars <= 4 })
    }

    @Test
    fun qualityAndEmptyShelvesShowInStars() {
        val catalog = ShopCatalog(listOf("cozy"), listOf(item("pie", 20, "food")), rating = null)
        fun report(quality: Float, empty: Boolean, bought: Boolean) = VisitReport(
            cozyFoodie, catalog, bought = if (bought) catalog.items else emptyList(), tooPricey = emptyList(),
            waitedSec = 8f, queued = false, gotService = false, quality = quality, foundEmpty = empty,
        )
        assertTrue(ReviewWriter.stars(report(0.4f, false, true)) >= ReviewWriter.stars(report(-0.35f, false, true)))
        assertTrue(ReviewWriter.stars(report(0f, true, false)) < ReviewWriter.stars(report(0f, false, true)))
    }

    @Test
    fun customersBuyFromDisplaysPayAtCheckoutAndReview() {
        val world = WorldFactory.newWorld()
        val lot = world.lot(WorldFactory.HOME_LOT_ID)!!.rect
        val checkoutRect = GridRect(lot.x + 1, lot.y + 1, 2, 1)
        val displayRect = GridRect(lot.x + 4, lot.y + 1, 1, 1)
        val free = buildSet {
            for (x in lot.x until lot.right) for (y in lot.y until lot.bottom)
                if (!checkoutRect.contains(x, y) && !displayRect.contains(x, y)) add(x to y)
        }
        val entry = (lot.x + lot.w / 2) to (lot.bottom - 1)
        val displayAccess = Fixtures.accessTile(displayRect, free, entry)!!
        val catalog = ShopCatalog(listOf("cozy", "food"), listOf(item("pie", 8, "food"), item("cake", 900, "food")), rating = null)
        val door = ShopDoor(
            WorldFactory.HOME_LOT_ID, lot, free, visitChance = { 0.9f },
            fixtures = listOf(Fixture("checkout", FurnitureType.CHECKOUT, checkoutRect, Fixtures.accessTile(checkoutRect, free, entry))),
            browseSpots = listOf(displayAccess),
            catalog = catalog,
            displayItems = mapOf(displayAccess to listOf("pie", "cake")),
        )
        val crowd = StreetCrowd(Random(11))
        val pool = (0 until 6).map { NpcProfile("n$it", "s$it", "P$it", listOf("cozy", "food"), 40, 1.2f) }
        val events = ArrayList<CrowdEvent>()
        repeat(10000) {
            crowd.update(0.05f, world.street, listOf(door)) { r -> pool[r.nextInt(pool.size)] }
            events += crowd.drainEvents()
        }
        val sales = events.filterIsInstance<CrowdEvent.Sale>()
        val reviews = events.filterIsInstance<CrowdEvent.Review>()
        assertTrue(sales.isNotEmpty(), "sales happened")
        assertTrue(sales.all { s -> s.items.all { it.id == "pie" } }, "never sold what nobody could afford")
        assertTrue(sales.all { it.total == it.items.sumOf { i -> i.price } && it.total <= 40 })
        assertTrue(reviews.isNotEmpty() && reviews.all { it.stars in 1..5 && it.text.isNotBlank() })
        val visits = events.filterIsInstance<CrowdEvent.Visit>()
        assertTrue(visits.size >= reviews.size)
    }
}
