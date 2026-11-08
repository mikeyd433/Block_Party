package com.blockparty.core

import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.npc.Fixture
import com.blockparty.core.npc.Fixtures
import com.blockparty.core.npc.NpcGenerator
import com.blockparty.core.npc.ShopDoor
import com.blockparty.core.npc.StreetCrowd
import com.blockparty.core.world.WorldFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureCrowdTest {
    private val world = WorldFactory.newWorld()
    private val street = world.street
    private val lot = world.lot(WorldFactory.HOME_LOT_ID)!!.rect
    private val profiles = (0 until 12).map { NpcGenerator.profile("n$it", "s$it") }
    private val pick: (Random) -> NpcProfile? = { r -> profiles[r.nextInt(profiles.size)] }

    private val checkoutRect = GridRect(lot.x + 1, lot.y + 1, 2, 1)
    private val stationRect = GridRect(lot.x + 4, lot.y + 1, 1, 1)
    private val seatRects = listOf(GridRect(lot.x, lot.y + 4, 1, 1), GridRect(lot.x + 5, lot.y + 4, 1, 1))

    private fun door(servicesOnly: Boolean = false): ShopDoor {
        val blocked = (listOf(checkoutRect, stationRect) + seatRects)
        val free = buildSet {
            for (x in lot.x until lot.right) for (y in lot.y until lot.bottom) {
                if (blocked.none { it.contains(x, y) }) add(x to y)
            }
        }
        val entry = (lot.x + lot.w / 2) to (lot.bottom - 1)
        val fixtures = listOf(
            Fixture("checkout", FurnitureType.CHECKOUT, checkoutRect, Fixtures.accessTile(checkoutRect, free, entry)),
            Fixture("station", FurnitureType.SERVICE_STATION, stationRect, Fixtures.accessTile(stationRect, free, entry)),
        ) + seatRects.mapIndexed { i, r -> Fixture("seat$i", FurnitureType.SEATING, r, Fixtures.accessTile(r, free, entry)) }
        return ShopDoor(
            WorldFactory.HOME_LOT_ID, lot, free, { 0.9f }, fixtures,
            offersServices = true, servicesOnly = servicesOnly,
            serviceIds = listOf("haircut"),
        )
    }

    @Test
    fun accessTilePrefersTheFront() {
        val d = door()
        assertEquals(checkoutRect.x to checkoutRect.bottom, d.fixtures.first { it.id == "checkout" }.access)
    }

    @Test
    fun customersPayQueueGetServedAndSit() {
        val d = door()
        val crowd = StreetCrowd(Random(3))
        val checkoutAccess = d.fixtures.first { it.id == "checkout" }.access!!
        var paidAtCheckout = false
        var servedOnStation = false
        var satOnSeat = false
        repeat(12000) {
            crowd.update(0.05f, street, listOf(d), pick)
            val seated = crowd.people.filter { it.seatedOn != null }
            // A seat or station holds one person at a time.
            assertEquals(seated.size, seated.map { it.seatedOn }.toSet().size, "two people on one seat")
            for (p in crowd.people) {
                when (p.seatedOn) {
                    "station" -> {
                        servedOnStation = true
                        assertTrue(stationRect.contains(p.position.x.toInt(), p.position.y.toInt()))
                    }
                    "seat0", "seat1" -> satOnSeat = true
                    null -> if (p.insideLotId != null) {
                        val t = p.position.x.toInt() to p.position.y.toInt()
                        assertTrue(t in d.freeTiles, "standing on furniture at $t")
                        if (t == checkoutAccess && !p.isWalking) paidAtCheckout = true
                    }
                }
            }
        }
        assertTrue(paidAtCheckout, "someone paid at the checkout")
        assertTrue(servedOnStation, "someone was served at the station")
        assertTrue(satOnSeat, "someone sat down")
    }

    @Test
    fun staffHandTheServiceItemOverAndCustomerKeepsIt() {
        val d = door(servicesOnly = true)
        val crowd = StreetCrowd(Random(5))
        val access = d.fixtures.first { it.id == "station" }.access!!
        var staffHeld = false
        var customerHeld = false
        var sawHandOver = false
        var leftWithItem = false
        repeat(8000) {
            crowd.update(0.05f, street, listOf(d), pick)
            assertEquals(1, crowd.staff.size, "one shopkeeper")
            val keeper = crowd.staff.single()
            val t = keeper.position.x.toInt() to keeper.position.y.toInt()
            assertTrue(t in d.freeTiles, "shopkeeper on furniture at $t")
            if (keeper.heldItem == "haircut") staffHeld = true
            if (crowd.people.any { it.heldItem == "haircut" && it.seatedOn == "station" }) customerHeld = true
            for (v in crowd.services) {
                if (v.phase == com.blockparty.core.npc.ServicePhase.HANDING_OVER) {
                    sawHandOver = true
                    assertEquals(access, t, "hands over from beside the station")
                }
            }
            if (crowd.people.any { it.heldItem == "haircut" && it.seatedOn == null }) leftWithItem = true
            // The shopkeeper hands over one item at a time.
            assertTrue(crowd.people.count { it.heldItem != null && it.seatedOn != null } <= 1)
        }
        assertTrue(staffHeld && customerHeld && sawHandOver && leftWithItem)
    }

    @Test
    fun servicesOnlyShopsStillEmptyOut() {
        val d = door(servicesOnly = true)
        val crowd = StreetCrowd(Random(9))
        repeat(4000) { crowd.update(0.05f, street, listOf(d), pick) }
        // Stop spawning visitors and let everyone finish.
        val closed = ShopDoor(d.lotId, d.lot, d.freeTiles, { 0f }, d.fixtures, true, true)
        repeat(6000) { crowd.update(0.05f, street, listOf(closed)) { null } }
        assertTrue(crowd.people.isEmpty(), "${crowd.people.size} people stuck")
    }
}
