package com.blockparty.core

import com.blockparty.core.model.DisplaySpot
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.LotState
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.sim.Economy
import com.blockparty.core.world.WorldFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EconomyTest {
    private val day0 = 20_000L
    private val home = WorldFactory.HOME_LOT_ID

    @Test
    fun primeLotsCostMore() {
        val w = WorldFactory.newWorld()
        val middle = Economy.rentPerDay(w.lot(home)!!, w.street)
        val edge = Economy.rentPerDay(w.lot("lot-west")!!, w.street)
        assertTrue(middle > edge, "$middle vs $edge")
        assertTrue(Economy.rentPerDay(w.lot(home)!!, w.street, streetLevel = 4) > middle * 1.9)
    }

    @Test
    fun firstSettleStartsTheClockThenRentIsChargedDaily() {
        var w = WorldFactory.newWorld()
        w = Economy.settle(w, day0).world
        assertEquals(100L, w.coins)
        val rent = Economy.rentPerDay(w.lot(home)!!, w.street)
        val s = Economy.settle(w, day0 + 3)
        assertEquals(100L - 3 * rent, s.world.coins)
        assertEquals(3, s.world.economy.books.size)
        assertTrue(s.notices.single().contains("3 days"))
        assertEquals(0, Economy.settle(s.world, day0 + 3).notices.size, "same day: nothing more to pay")
    }

    @Test
    fun debtClosesTheShopAfterTheGracePeriod() {
        var w = Economy.settle(WorldFactory.newWorld().copy(coins = 10), day0).world
        w = w.updateLot(home) { it.copy(furniture = listOf(FurniturePlacement("f", "chair", it.rect.x, it.rect.y))) }
        val rent = Economy.rentPerDay(w.lot(home)!!, w.street)
        w = Economy.settle(w, day0 + 1).world
        assertEquals(day0 + 1, w.economy.debtSinceDay)
        assertEquals(Economy.GRACE_DAYS, Economy.graceDaysLeft(w, day0 + 1))
        // Paying it back in time clears the debt.
        val rescued = Economy.settle(w.copy(coins = 1000), day0 + 2).world
        assertNull(rescued.economy.debtSinceDay)
        // Staying in debt closes the shop on the last grace day.
        val s = Economy.settle(w, day0 + 1 + Economy.GRACE_DAYS)
        val lot = s.world.lot(home)!!
        assertEquals(LotState.VACANT, lot.state)
        assertNull(lot.business)
        assertEquals(listOf("chair"), s.world.storage)
        assertEquals(0L, s.world.coins, "last shop closed: debt forgiven")
        assertTrue(s.notices.any { "closed" in it })
        assertTrue(rent > 0)
    }

    @Test
    fun shelfSpaceComesFromDisplaySpots() {
        val shelf = SpriteAsset("shelf", SpriteKind.FURNITURE, "Shelf", 10, 10,
            displaySpots = listOf(DisplaySpot("a", 0.3f, 0.5f), DisplaySpot("b", 0.7f, 0.5f)))
        val pie = Offering("pie", OfferingKind.PRODUCT, "Pie", 10, stock = 3)
        val w = WorldFactory.newWorld()
        val bare = w.lot(home)!!
        assertEquals(Economy.COUNTER_CAPACITY, Economy.capacity(pie, bare, mapOf<String, SpriteAsset>()::get))
        val withShelf = bare.copy(furniture = listOf(
            FurniturePlacement("p1", "shelf", bare.rect.x, bare.rect.y, stock = mapOf("a" to "pie", "b" to "pie")),
        ))
        val sprites = mapOf("shelf" to shelf)::get
        assertEquals(2 * Economy.UNITS_PER_SPOT, Economy.capacity(pie, withShelf, sprites))
        val (units, cost) = Economy.restockNeed(pie, withShelf, sprites)
        assertEquals(7, units)
        assertEquals(7 * pie.supplyTier.unitCost, cost)
        assertNotNull(cost)
    }
}
