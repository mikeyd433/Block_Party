package com.blockparty.core

import com.blockparty.core.model.Business
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.LotState
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.sim.Economy
import com.blockparty.core.sim.Lots
import com.blockparty.core.world.WorldFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LotsTest {
    private val day = 20_000L
    private val home = WorldFactory.HOME_LOT_ID

    @Test
    fun buyingAVacantLotOpensANewShop() {
        val w = WorldFactory.newWorld().copy(coins = 1000)
        val west = w.lot("lot-west")!!
        assertEquals(LotState.VACANT, west.state, "the west lot is for sale from the start")
        val price = Lots.price(west, w)
        val bought = assertIs<Lots.Result.Ok>(Lots.buy(w, "lot-west", day)).world
        assertEquals(1000 - price, bought.coins)
        assertEquals(LotState.OWNED, bought.lot("lot-west")!!.state)
        assertTrue(bought.lot("lot-west")!!.business != null)
        assertIs<Lots.Result.Failed>(Lots.buy(w, "lot-east", day), "locked")
        assertIs<Lots.Result.Failed>(Lots.buy(w.copy(coins = 1), "lot-west", day), "too poor")
        // Prime middle lots cost more than edge lots.
        assertTrue(Lots.price(w.lot(home)!!, w) > price)
    }

    @Test
    fun oldSavesGetTheWestLotForSale() {
        val old = WorldFactory.newWorld().updateLot("lot-west") { it.copy(state = LotState.LOCKED) }.copy(version = 1)
        val upgraded = com.blockparty.core.world.WorldCodec.decode(com.blockparty.core.world.WorldCodec.encode(old))
        assertEquals(LotState.VACANT, upgraded.lot("lot-west")!!.state)
        assertEquals(com.blockparty.core.model.WorldState.CURRENT_VERSION, upgraded.version)
    }

    @Test
    fun mergingJoinsLotsAndTheGap() {
        var w = WorldFactory.newWorld().copy(coins = 2000)
        w = w.updateLot(home) {
            it.copy(
                business = Business("Cakes", offerings = listOf(Offering("cake", OfferingKind.PRODUCT, "Cake", 10))),
                furniture = listOf(FurniturePlacement("f1", "chair", it.rect.x, it.rect.y)),
            )
        }
        val home0 = w.lot(home)!!
        val west0 = w.lot("lot-west")!!
        assertEquals("lot-west", Lots.neighbour(w, home0, -1)!!.id)
        val merged = assertIs<Lots.Result.Ok>(Lots.merge(w, home, "lot-west", day)).world
        val lot = merged.lot(home)!!
        assertNull(merged.lot("lot-west"))
        assertEquals(west0.rect.x, lot.rect.x)
        assertEquals(home0.rect.right, lot.rect.right)
        assertEquals(home0.rect.w + west0.rect.w + 1, lot.rect.w, "includes the gap tile")
        assertEquals(2000 - Lots.price(west0, w), merged.coins)
        assertEquals(1, lot.furniture.size)
        assertTrue(Economy.rentPerDay(lot, merged.street) > Economy.rentPerDay(home0, w.street), "bigger shop, more rent")
    }

    @Test
    fun mergingTwoOwnedShopsFoldsOneIn() {
        var w = WorldFactory.newWorld().copy(coins = 2000)
        w = assertIs<Lots.Result.Ok>(Lots.buy(w, "lot-west", day)).world
        w = w.updateLot("lot-west") {
            it.copy(
                business = it.business!!.copy(offerings = listOf(Offering("pie", OfferingKind.PRODUCT, "Pie", 5)), customersServed = 3),
                furniture = listOf(FurniturePlacement("f2", "shelf", it.rect.x, it.rect.y)),
            )
        }
        val coins = w.coins
        val merged = assertIs<Lots.Result.Ok>(Lots.merge(w, home, "lot-west", day)).world
        val b = merged.lot(home)!!.business!!
        assertEquals(coins, merged.coins, "already owned: free")
        assertTrue(b.offerings.any { it.id == "pie" })
        assertEquals(3, b.customersServed)
        assertEquals(listOf("f2"), merged.lot(home)!!.furniture.map { it.id })
    }
}
