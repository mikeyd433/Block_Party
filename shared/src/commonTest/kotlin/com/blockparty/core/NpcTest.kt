package com.blockparty.core

import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.npc.NpcGenerator
import com.blockparty.core.npc.NpcPool
import com.blockparty.core.npc.ShopDoor
import com.blockparty.core.npc.StreetCrowd
import com.blockparty.core.world.WorldFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NpcTest {
    @Test
    fun profilesAreDeterministicAndSensible() {
        val a = NpcGenerator.profile("p1", "sprite-abc")
        val b = NpcGenerator.profile("p1", "sprite-abc")
        assertEquals(a, b)
        val many = (0 until 200).map { NpcGenerator.profile("p$it", "sprite-$it") }
        assertTrue(many.all { it.tagIds.isNotEmpty() && it.budgetCoins in 5L..200L && it.walkSpeed in 0.9f..1.6f })
        assertTrue(many.all { p -> p.tagIds.all { TagLibrary.get(it)!!.unlockLevel == 0 } }, "only unlocked tags")
        assertTrue(many.map { it.name }.toSet().size > 20, "varied names")
        // Cheap tastes mean small budgets.
        assertTrue(many.filter { "cheap" in it.tagIds }.all { it.budgetCoins <= 20 })
    }

    private fun world(playerCustomers: Int): com.blockparty.core.model.WorldState {
        val starter = (0 until 4).map { SpriteAsset("s$it", SpriteKind.CHARACTER, "S", 10, 10, isStarter = true) }
        val drawn = (0 until playerCustomers).map { SpriteAsset("d$it", SpriteKind.CHARACTER, "D", 10, 10) }
        val npcs = (starter + drawn).map { NpcGenerator.profile("n-${it.id}", it.id) }
        return WorldFactory.newWorld().copy(sprites = starter + drawn, npcs = npcs)
    }

    @Test
    fun playerDrawingsTakeOverFromTheStarterSet() {
        val fresh = NpcPool.weighted(world(0))
        assertEquals(4, fresh.size)
        val some = NpcPool.weighted(world(3)).toMap()
        assertTrue(some.filterKeys { it.spriteId.startsWith("d") }.values.all { it == 2f })
        assertTrue(some.filterKeys { it.spriteId.startsWith("s") }.values.all { it == 0.5f })
        val lots = NpcPool.weighted(world(NpcPool.STARTER_PHASE_OUT))
        assertTrue(lots.all { it.first.spriteId.startsWith("d") })
    }

    @Test
    fun crowdWalksSidewalksAndVisitsShops() {
        val w = world(2)
        val street = w.street
        val lot = w.lot(WorldFactory.HOME_LOT_ID)!!.rect
        val free = buildSet { for (x in lot.x until lot.right) for (y in lot.y until lot.bottom) if (y != 2) add(x to y) }
        val door = ShopDoor(WorldFactory.HOME_LOT_ID, lot, free) { 0.6f }
        val crowd = StreetCrowd(Random(7))
        val pick: (Random) -> NpcProfile? = { r -> NpcPool.choose(w, r) }
        val near = StreetCrowd.nearLaneY(street)
        val far = StreetCrowd.farLaneY(street)
        var sawInside = false
        var maxPeople = 0
        repeat(6000) {
            crowd.update(0.05f, street, listOf(door), pick)
            maxPeople = maxOf(maxPeople, crowd.people.size)
            for (p in crowd.people) {
                if (p.insideLotId != null) {
                    sawInside = true
                    val tile = p.position.x.toInt() to p.position.y.toInt()
                    assertTrue(tile in free, "inside but on blocked tile $tile")
                } else {
                    val y = p.position.y
                    val onLane = kotlin.math.abs(y - near) < 0.2f || kotlin.math.abs(y - far) < 0.2f
                    val atDoor = kotlin.math.abs(p.position.x - (door.entryTile.first + 0.5f)) < 0.01f
                    assertTrue(onLane || atDoor, "off the sidewalk at ${p.position}")
                }
            }
        }
        assertTrue(sawInside, "someone visited the shop")
        assertTrue(maxPeople in 2..StreetCrowd.MAX_PEOPLE)
    }

    @Test
    fun emptyPoolMeansEmptyStreet() {
        val crowd = StreetCrowd(Random(1))
        val street = WorldFactory.newWorld().street
        crowd.warmUp(30f, street, emptyList()) { null }
        assertTrue(crowd.people.isEmpty())
    }
}
