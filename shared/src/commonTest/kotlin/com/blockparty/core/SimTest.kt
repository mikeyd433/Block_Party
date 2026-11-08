package com.blockparty.core

import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.FurniturePlacement
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.sim.Placement
import com.blockparty.core.sim.Wanderer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimTest {
    private val table = SpriteAsset("table", SpriteKind.FURNITURE, "Table", 100, 50, footprintW = 2, footprintH = 1)
    private val sprites = mapOf(table.id to table)::get
    private val lot = Lot(
        "lot", GridRect(4, 0, 4, 4), LotState.OWNED,
        furniture = listOf(FurniturePlacement("f1", "table", 4, 0)),
    )

    @Test
    fun flippingTransposesFootprint() {
        assertEquals(GridRect(1, 2, 2, 1), Placement.footprint(table, 1, 2, flipped = false))
        assertEquals(GridRect(1, 2, 1, 2), Placement.footprint(table, 1, 2, flipped = true))
    }

    @Test
    fun placementRespectsLotBoundsAndOverlap() {
        assertTrue(Placement.canPlace(lot, GridRect(6, 2, 2, 1), sprites))
        assertFalse(Placement.canPlace(lot, GridRect(7, 2, 2, 1), sprites), "sticks out of the lot")
        assertFalse(Placement.canPlace(lot, GridRect(5, 0, 2, 1), sprites), "overlaps the table")
        assertTrue(Placement.canPlace(lot, GridRect(5, 0, 2, 1), sprites, ignoreId = "f1"), "moving itself")
    }

    @Test
    fun furnitureLookupAndFreeTiles() {
        assertNotNull(Placement.furnitureAt(lot, 5, 0, sprites))
        assertNull(Placement.furnitureAt(lot, 6, 0, sprites))
        val free = Placement.freeTiles(lot, sprites)
        assertEquals(16 - 2, free.size)
        assertFalse((4 to 0) in free)
    }

    @Test
    fun pathAvoidsBlockedTiles() {
        // 3x3 with the middle column blocked except the bottom row.
        val free = setOf(0 to 0, 0 to 1, 0 to 2, 1 to 2, 2 to 2, 2 to 1, 2 to 0)
        val path = Wanderer.findPath(0 to 0, 2 to 0, free)
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 2, 2 to 2, 2 to 1, 2 to 0), path)
        assertTrue(Wanderer.findPath(0 to 0, 1 to 1, free).isEmpty(), "unreachable goal")
    }

    @Test
    fun wandererStaysOnFreeTilesAndWalks() {
        val free = Placement.freeTiles(lot, sprites)
        val w = Wanderer(Vec2(6.5f, 3.5f), random = Random(42))
        repeat(2000) {
            w.update(1f / 30f, free)
            val tile = w.position.x.toInt() to w.position.y.toInt()
            assertTrue(tile in free, "wandered onto $tile")
        }
        assertTrue(w.distanceWalked > 1f)
    }

    @Test
    fun wandererEscapesWhenFurnitureLandsOnIt() {
        val w = Wanderer(Vec2(4.5f, 0.5f), random = Random(1))
        val free = Placement.freeTiles(lot, sprites)
        w.update(0.01f, free)
        assertTrue((w.position.x.toInt() to w.position.y.toInt()) in free)
    }
}
