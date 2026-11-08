package com.blockparty.core.sim

import com.blockparty.core.model.DisplaySpot

object DisplayStock {
    /**
     * Fills empty spots with products, preferring ones not already on this piece, then cycling.
     * Spots that already hold a valid product keep it. Stock for removed spots or products is dropped.
     */
    fun autoFill(spots: List<DisplaySpot>, stock: Map<String, String>, productIds: List<String>): Map<String, String> {
        val valid = stock.filter { (spot, product) -> spots.any { it.id == spot } && product in productIds }
        if (productIds.isEmpty()) return valid
        val result = LinkedHashMap(valid)
        val shown = valid.values.toMutableSet()
        val queue = ArrayDeque(productIds.filter { it !in shown } + productIds.filter { it in shown })
        for (spot in spots) {
            if (spot.id in result) continue
            val next = queue.removeFirst()
            result[spot.id] = next
            queue.addLast(next)
        }
        return result
    }
}
