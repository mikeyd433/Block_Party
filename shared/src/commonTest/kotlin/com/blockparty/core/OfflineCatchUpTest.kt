package com.blockparty.core

import com.blockparty.core.model.Business
import com.blockparty.core.model.Employee
import com.blockparty.core.model.EmployeeRole
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.sim.OfflineCatchUp
import com.blockparty.core.sim.Traffic
import com.blockparty.core.world.WorldFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineCatchUpTest {
    private val pool = (0 until 8).map { NpcProfile("n$it", "s$it", "P$it", listOf("cozy", "food"), 40, 1f) to 1f }
    private val home = WorldFactory.HOME_LOT_ID

    private fun shop(stock: Int, employees: List<Employee> = emptyList()) = WorldFactory.newWorld().updateLot(home) {
        it.copy(business = Business("Pies", tagIds = listOf("cozy"),
            offerings = listOf(Offering("pie", OfferingKind.PRODUCT, "Pie", 10, stock = stock)), employees = employees))
    }

    @Test
    fun trafficIsQuietAtNightAndBusyAtWeekends() {
        assertTrue(Traffic.multiplier(3, false) < Traffic.multiplier(13, false))
        assertTrue(Traffic.multiplier(13, true) > Traffic.multiplier(13, false))
    }

    @Test
    fun shopsSellUntilStockRunsOut() {
        val (w, summary) = OfflineCatchUp.run(shop(stock = 5), home, hours = 48, pool, { (12 + it) % 24 to false }, 20_000, 0L, Random(1))
        val s = assertNotNull(summary)
        assertEquals(5, s.itemsSold, "can't sell more than was in stock")
        assertEquals(0, w.lot(home)!!.business!!.offerings.single().stock)
        assertEquals(100L + 50, w.coins)
        assertEquals(listOf("Pie"), s.soldOut)
        assertTrue(s.describe("Pies")!!.contains("Sold out of Pie"))
    }

    @Test
    fun stockersKeepItGoing() {
        val stocker = Employee("e", "s", "Kip", EmployeeRole.STOCKER, emptyList(), 5, 0)
        val (w, summary) = OfflineCatchUp.run(shop(stock = 5, listOf(stocker)), home, hours = 48, pool, { (12 + it) % 24 to false }, 20_000, 0L, Random(1))
        val s = assertNotNull(summary)
        assertTrue(s.itemsSold > 5, "restocked and kept selling: ${s.itemsSold}")
        assertTrue(s.supplies > 0)
        assertEquals(100L + s.earned - s.supplies, w.coins)
    }

    @Test
    fun nothingHappensWithoutTimeOrProducts() {
        assertEquals(null, OfflineCatchUp.run(shop(5), home, 0, pool, { 12 to false }, 20_000, 0L, Random(1)).second)
        val empty = WorldFactory.newWorld()
        assertEquals(null, OfflineCatchUp.run(empty, home, 10, pool, { 12 to false }, 20_000, 0L, Random(1)).second)
    }
}
