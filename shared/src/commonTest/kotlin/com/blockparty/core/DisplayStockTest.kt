package com.blockparty.core

import com.blockparty.core.model.DisplaySpot
import com.blockparty.core.sim.DisplayStock
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayStockTest {
    private val spots = (1..4).map { DisplaySpot("s$it", 0.2f * it, 0.5f) }

    @Test
    fun fillsEmptySpotsWithDifferentProductsFirst() {
        val filled = DisplayStock.autoFill(spots, mapOf("s1" to "cake"), listOf("cake", "pie", "bun"))
        assertEquals("cake", filled["s1"])
        assertEquals(listOf("pie", "bun", "cake"), listOf(filled["s2"], filled["s3"], filled["s4"]))
    }

    @Test
    fun dropsStaleEntriesAndHandlesNoProducts() {
        val stale = mapOf("s1" to "gone", "old-spot" to "cake")
        assertEquals(mapOf("s1" to "cake", "s2" to "cake", "s3" to "cake", "s4" to "cake"), DisplayStock.autoFill(spots, stale, listOf("cake")))
        assertEquals(emptyMap(), DisplayStock.autoFill(spots, stale, emptyList()))
    }
}
