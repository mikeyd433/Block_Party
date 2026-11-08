package com.blockparty.core.sim

import com.blockparty.core.model.Business
import com.blockparty.core.model.DayBook
import com.blockparty.core.model.Lot
import com.blockparty.core.model.LotState
import com.blockparty.core.model.Offering
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.SpriteAsset
import com.blockparty.core.model.Street
import com.blockparty.core.model.WorldState
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Running costs: daily rent (and wages, once there are employees), stock and restocking, and
 * debt with a grace period. Days are counted in the street's timezone by the caller.
 */
object Economy {
    /** Days a player can stay in debt before a business closes. */
    const val GRACE_DAYS = 7
    const val BASE_RENT = 15L
    /** Units of a product one display spot holds. */
    const val UNITS_PER_SPOT = 5
    /** Stock a product can have without being on display (kept behind the counter). */
    const val COUNTER_CAPACITY = 8
    private const val BOOK_DAYS = 14

    /**
     * Daily rent for a lot. Prime spots (near the middle of the street, where foot traffic is
     * highest) cost up to 30% more, and everything scales up as the street levels up.
     */
    fun rentPerDay(lot: Lot, street: Street, streetLevel: Int = 0): Long {
        val mid = street.widthTiles / 2f
        val center = lot.rect.x + lot.rect.w / 2f
        val prime = 1f + 0.3f * (1f - (abs(center - mid) / mid).coerceIn(0f, 1f))
        val size = lot.rect.w * lot.rect.h / 36f // merged lots pay for their area
        return (BASE_RENT * prime * size * (1f + 0.25f * streetLevel)).roundToLong()
    }

    /** Daily wages for a business (employees arrive in a later build stage). */
    fun wagesPerDay(business: Business): Long = business.employees.sumOf { it.wagePerDay }

    /** How many units of [offering] the lot can hold: shelf space on display pieces, or counter stock. */
    fun capacity(offering: Offering, lot: Lot, sprites: (String) -> SpriteAsset?): Int {
        if (offering.kind != OfferingKind.PRODUCT) return Int.MAX_VALUE
        val spots = lot.furniture.sumOf { piece ->
            val spriteSpots = sprites(piece.spriteId)?.displaySpots.orEmpty()
            spriteSpots.count { piece.stock[it.id] == offering.id }
        }
        return if (spots > 0) spots * UNITS_PER_SPOT else COUNTER_CAPACITY
    }

    /** Units and coins needed to fill [offering] back up to capacity. */
    fun restockNeed(offering: Offering, lot: Lot, sprites: (String) -> SpriteAsset?): Pair<Int, Long> {
        if (offering.kind != OfferingKind.PRODUCT) return 0 to 0L
        val units = (capacity(offering, lot, sprites) - offering.stock).coerceAtLeast(0)
        return units to units * offering.supplyTier.unitCost
    }

    /** Adds to today's book (creating it if needed) and keeps the last two weeks. */
    fun book(world: WorldState, day: Long, change: (DayBook) -> DayBook): WorldState {
        val books = world.economy.books
        val today = books.firstOrNull { it.day == day } ?: DayBook(day)
        val updated = (listOf(change(today)) + books.filter { it.day != day }).sortedByDescending { it.day }.take(BOOK_DAYS)
        return world.copy(economy = world.economy.copy(books = updated))
    }

    /** Spends coins on supplies, booked under [day]. Coins may go negative (debt). */
    fun spendOnSupplies(world: WorldState, day: Long, amount: Long): WorldState =
        book(world.copy(coins = world.coins - amount), day) { it.copy(supplies = it.supplies + amount) }

    /** Result of settling the days since the last bill. */
    class Settlement(val world: WorldState, val notices: List<String>)

    /**
     * Charges rent and wages for every day from the last bill up to [today], tracks how long the
     * player has been in debt, and closes a business when the grace period runs out.
     */
    fun settle(world: WorldState, today: Long, streetLevel: Int = 0): Settlement {
        val last = world.economy.lastBilledDay
        if (last == 0L) {
            return Settlement(world.copy(economy = world.economy.copy(lastBilledDay = today)), emptyList())
        }
        if (today <= last) return Settlement(world, emptyList())
        var w = world
        val notices = ArrayList<String>()
        var rentTotal = 0L
        var wagesTotal = 0L
        for (day in last + 1..today) {
            val owned = w.street.lots.filter { it.state == LotState.OWNED && it.business != null }
            val rent = owned.sumOf { rentPerDay(it, w.street, streetLevel) }
            val wages = owned.sumOf { wagesPerDay(it.business!!) }
            rentTotal += rent
            wagesTotal += wages
            w = book(w.copy(coins = w.coins - rent - wages), day) { it.copy(rent = it.rent + rent, wages = it.wages + wages) }
            val (staffed, staffNotices) = com.blockparty.core.npc.Staffing.dailyUpdate(w)
            w = staffed
            notices += staffNotices

            // Debt tracking: the grace period starts the first day coins are negative.
            val debtSince = w.economy.debtSinceDay
            if (w.coins >= 0) {
                if (debtSince != null) w = w.copy(economy = w.economy.copy(debtSinceDay = null))
            } else if (debtSince == null) {
                w = w.copy(economy = w.economy.copy(debtSinceDay = day))
                notices += "You're in debt. Get back above zero within $GRACE_DAYS days or a shop will close."
            } else if (day - debtSince >= GRACE_DAYS) {
                val closing = w.street.lots
                    .filter { it.state == LotState.OWNED && it.business != null }
                    .maxByOrNull { rentPerDay(it, w.street, streetLevel) + wagesPerDay(it.business!!) }
                if (closing != null) {
                    notices += "${closing.business!!.name.ifBlank { "Your shop" }} closed after $GRACE_DAYS days in debt. " +
                        "Its furniture is in storage."
                    w = closeBusiness(w, closing.id)
                }
                val stillOpen = w.street.lots.any { it.state == LotState.OWNED && it.business != null }
                w = if (stillOpen) {
                    w.copy(economy = w.economy.copy(debtSinceDay = day)) // grace starts again for the rest
                } else {
                    notices += "With no shops left, your debt has been cleared. Time for a fresh start."
                    w.copy(coins = 0, economy = w.economy.copy(debtSinceDay = null))
                }
            }
        }
        if (rentTotal + wagesTotal > 0 && today - last > 1) {
            notices.add(0, "While you were away: ${today - last} days of rent" +
                (if (wagesTotal > 0) " and wages" else "") + " (${rentTotal + wagesTotal} coins).")
        }
        return Settlement(w.copy(economy = w.economy.copy(lastBilledDay = today)), notices)
    }

    /**
     * Closes a business: its furniture goes to the player's storage and the lot becomes vacant
     * and claimable. Drawings stay in the player's library.
     */
    fun closeBusiness(world: WorldState, lotId: String): WorldState {
        val lot = world.lot(lotId) ?: return world
        val stored = world.storage + lot.furniture.map { it.spriteId }
        return world.copy(storage = stored).updateLot(lotId) {
            it.copy(state = LotState.VACANT, business = null, furniture = emptyList())
        }
    }

    /** Days the player has left before a shop closes, or null when not in debt. */
    fun graceDaysLeft(world: WorldState, today: Long): Int? {
        val since = world.economy.debtSinceDay ?: return null
        return (GRACE_DAYS - (today - since)).toInt().coerceAtLeast(0)
    }
}
