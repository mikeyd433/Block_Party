package com.blockparty.core.sim

import com.blockparty.core.model.Business
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.WorldState

/** Buying, opening, closing and merging lots. */
object Lots {
    /** Days of rent a lot costs to buy. */
    const val PRICE_IN_RENT_DAYS = 12

    /** Price to buy a vacant lot: prime spots cost more and prices rise with the street's level. */
    fun price(lot: Lot, world: WorldState, streetLevel: Int = 0): Long =
        Economy.rentPerDay(lot, world.street, streetLevel) * PRICE_IN_RENT_DAYS

    sealed class Result {
        class Ok(val world: WorldState) : Result()
        class Failed(val reason: String) : Result()
    }

    /** Buys a vacant lot and opens a new business on it. */
    fun buy(world: WorldState, lotId: String, today: Long, streetLevel: Int = 0): Result {
        val lot = world.lot(lotId) ?: return Result.Failed("That lot doesn't exist.")
        if (lot.state != LotState.VACANT) return Result.Failed(
            if (lot.state == LotState.LOCKED) "This lot unlocks as the street grows." else "You already own this lot."
        )
        val cost = price(lot, world, streetLevel)
        if (world.coins < cost) return Result.Failed("You need $cost coins to buy this lot.")
        val bought = world.copy(coins = world.coins - cost).updateLot(lotId) {
            it.copy(
                state = LotState.OWNED, business = Business(name = "New Shop"), furniture = emptyList(),
                ownerId = world.myUserId, ownerName = world.playerName,
            )
        }
        return Result.Ok(Economy.book(bought, today) { it.copy(property = it.property + cost) })
    }

    /**
     * The lot directly next to [lot] on the given side (-1 = west, +1 = east), if the two can be
     * merged: neighbours in the same row with nothing but a gap between them.
     */
    fun neighbour(world: WorldState, lot: Lot, side: Int): Lot? {
        val candidates = world.street.lots.filter { it.id != lot.id && it.rect.y == lot.rect.y && it.rect.h == lot.rect.h }
        return if (side < 0) candidates.filter { it.rect.right <= lot.rect.x }.maxByOrNull { it.rect.right }
        else candidates.filter { it.rect.x >= lot.rect.right }.minByOrNull { it.rect.x }
    }

    /** What expanding into a neighbour would cost (lot price if vacant, 0 if you own it), or null if not possible. */
    fun mergeCost(world: WorldState, lot: Lot, other: Lot, streetLevel: Int = 0): Long? = when {
        other.state == LotState.VACANT -> price(other, world, streetLevel)
        other.isMine(world) -> 0L
        else -> null
    }

    /**
     * Expands [lotId] into the neighbouring [otherId]: the two lots and the gap between them become
     * one bigger lot. A vacant neighbour is bought; an owned one's business is folded in (its
     * furniture stays where it is, its products and services join this shop).
     */
    fun merge(world: WorldState, lotId: String, otherId: String, today: Long, streetLevel: Int = 0): Result {
        val lot = world.lot(lotId) ?: return Result.Failed("That lot doesn't exist.")
        val other = world.lot(otherId) ?: return Result.Failed("That lot doesn't exist.")
        if (!lot.isMine(world) || lot.business == null) return Result.Failed("You can only expand a shop you own.")
        if (other.state == LotState.OWNED && !other.isMine(world)) return Result.Failed("That shop belongs to someone else.")
        val adjacent = neighbour(world, lot, -1)?.id == otherId || neighbour(world, lot, 1)?.id == otherId
        if (!adjacent) return Result.Failed("Lots must be next to each other to merge.")
        val cost = mergeCost(world, lot, other, streetLevel) ?: return Result.Failed("That lot is still locked.")
        if (world.coins < cost) return Result.Failed("You need $cost coins to expand.")

        val rect = GridRect(
            x = minOf(lot.rect.x, other.rect.x),
            y = lot.rect.y,
            w = maxOf(lot.rect.right, other.rect.right) - minOf(lot.rect.x, other.rect.x),
            h = lot.rect.h,
        )
        val business = lot.business
        val otherBusiness = other.business
        val mergedBusiness = if (otherBusiness == null) business else business.copy(
            offerings = business.offerings + otherBusiness.offerings.filter { o -> business.offerings.none { it.id == o.id } },
            tagIds = (business.tagIds + otherBusiness.tagIds).distinct(),
            employees = business.employees + otherBusiness.employees,
            revenueTotal = business.revenueTotal + otherBusiness.revenueTotal,
            customersServed = business.customersServed + otherBusiness.customersServed,
            itemsSold = business.itemsSold + otherBusiness.itemsSold,
        )
        val merged = lot.copy(rect = rect, business = mergedBusiness, furniture = lot.furniture + other.furniture)
        var w = world.copy(
            coins = world.coins - cost,
            street = world.street.copy(lots = world.street.lots.filter { it.id != otherId }.map { if (it.id == lotId) merged else it }),
        )
        if (cost > 0) w = Economy.book(w, today) { it.copy(property = it.property + cost) }
        return Result.Ok(w)
    }
}
