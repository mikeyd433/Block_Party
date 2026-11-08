package com.blockparty.core.sim

import com.blockparty.core.model.Business
import com.blockparty.core.model.EmployeeRole
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.Lot
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.OfferingKind
import com.blockparty.core.model.Review
import com.blockparty.core.model.WorldState
import com.blockparty.core.npc.CatalogItem
import com.blockparty.core.npc.CustomerBrain
import com.blockparty.core.npc.ReviewWriter
import com.blockparty.core.npc.ShopCatalog
import com.blockparty.core.npc.Staffing
import com.blockparty.core.npc.VisitReport
import kotlin.random.Random

/** How busy the street is at a given time: quiet at night, busier in the evening and at weekends. */
object Traffic {
    /** [hour] 0..23, [weekend] Saturday or Sunday. 1 = a normal daytime hour. */
    fun multiplier(hour: Int, weekend: Boolean): Float {
        val byHour = when (hour) {
            in 0..5 -> 0.2f
            in 6..8 -> 0.6f
            in 9..11 -> 0.9f
            in 12..14 -> 1.1f
            in 15..17 -> 1.0f
            in 18..21 -> 1.2f
            else -> 0.5f
        }
        return byHour * if (weekend) 1.25f else 1f
    }
}

/**
 * What a shop did while its owner was away. Shops keep trading until they run out of stock;
 * stockers restock from the supplier while there's money.
 */
object OfflineCatchUp {
    /** Passers-by per hour at traffic 1 who might consider a shop. */
    const val PASSERS_PER_HOUR = 40
    /** Longest absence simulated (a week); beyond that shops would long be sold out anyway. */
    const val MAX_HOURS = 24 * 7
    /** Most reviews kept from one catch-up. */
    const val MAX_REVIEWS = 6
    /** Services one station can perform per hour (with someone to serve). */
    const val SERVICES_PER_STATION_HOUR = 12

    class Summary(
        val hours: Int,
        val customers: Int,
        val itemsSold: Int,
        val earned: Long,
        val supplies: Long,
        val newReviews: Int,
        val soldOut: List<String>,
    ) {
        fun describe(shopName: String): String? {
            if (customers == 0 && supplies == 0L) return null
            val parts = mutableListOf("$customers customers", "$itemsSold sold", "+$earned coins")
            if (supplies > 0) parts += "$supplies on restocking"
            if (newReviews > 0) parts += "$newReviews new review${if (newReviews == 1) "" else "s"}"
            val out = if (soldOut.isNotEmpty()) " Sold out of ${soldOut.joinToString(", ")}." else ""
            return "While you were away ($hours h), ${shopName.ifBlank { "your shop" }}: ${parts.joinToString(", ")}.$out"
        }
    }

    /**
     * Simulates [lotId] for [hours] hours ending now. [hourAt] gives (hour of day, weekend) for an
     * hour offset into the absence; [today] is used for books; [pool] are the street's customers.
     */
    fun run(
        world: WorldState,
        lotId: String,
        hours: Int,
        pool: List<Pair<NpcProfile, Float>>,
        hourAt: (Int) -> Pair<Int, Boolean>,
        today: Long,
        nowMillis: Long,
        random: Random,
    ): Pair<WorldState, Summary?> {
        val lot = world.lot(lotId) ?: return world to null
        val business = lot.business ?: return world to null
        val span = hours.coerceIn(0, MAX_HOURS)
        if (span == 0 || pool.isEmpty() || business.offerings.isEmpty()) return world to null

        var w = world
        var b: Business = business
        val stock = b.offerings.associate { it.id to it.stock }.toMutableMap()
        val stockers = b.employees.filter { it.role == EmployeeRole.STOCKER }
        val servers = 1 + b.employees.count { it.role == EmployeeRole.SERVER } // the shopkeeper serves too
        val stations = lot.furniture.count { w.sprite(it.spriteId)?.furnitureType == FurnitureType.SERVICE_STATION }
        val displayed = displayedProducts(lot, w)
        val greeter = Staffing.greeterBoost(b.employees)
        var customers = 0
        var itemsSold = 0
        var earned = 0L
        var supplies = 0L
        val reviews = ArrayList<Review>()
        val soldOut = LinkedHashSet<String>()

        for (h in 0 until span) {
            val (hour, weekend) = hourAt(h)
            val catalog = catalogOf(b, stock)
            val passers = (PASSERS_PER_HOUR * Traffic.multiplier(hour, weekend)).toInt()
            var servicesLeft = stations.coerceAtMost(servers) * SERVICES_PER_STATION_HOUR
            for (i in 0 until passers) {
                val profile = com.blockparty.core.npc.NpcPool.choose(pool, random) ?: continue
                if (random.nextFloat() >= CustomerBrain.visitChance(profile, catalog) * greeter) continue
                val bought = ArrayList<CatalogItem>()
                var service: CatalogItem? = null
                var foundEmpty = false
                val tooPricey = ArrayList<CatalogItem>()
                var budget = profile.budgetCoins
                if (servicesLeft > 0 && catalog.services.isNotEmpty() && random.nextFloat() < 0.6f) {
                    service = CustomerBrain.chooseService(profile, catalog, budget, random)
                    if (service != null) { servicesLeft--; budget -= service.price }
                }
                if (service == null) {
                    // Browse what's on display (or the counter if nothing is).
                    val candidates = (if (displayed.isEmpty()) catalog.products else catalog.products.filter { it.id in displayed })
                        .shuffled(random).take(3)
                    for (item in candidates) {
                        if (bought.size >= 3) break
                        if ((stock[item.id] ?: 0) <= 0) { foundEmpty = true; continue }
                        if (item.price > budget) { tooPricey += item; continue }
                        if (random.nextFloat() < CustomerBrain.buyChance(profile, item, catalog, budget)) {
                            bought += item
                            budget -= item.price
                            stock[item.id] = (stock[item.id] ?: 0) - 1
                            if (stock[item.id] == 0) soldOut += item.name.ifBlank { "something" }
                        }
                    }
                }
                val gotSomething = bought.isNotEmpty() || service != null
                if (gotSomething) {
                    customers++
                    itemsSold += bought.size + if (service != null) 1 else 0
                    val total = bought.sumOf { it.price } + (service?.price ?: 0)
                    earned += total
                    service?.let { s -> supplies += b.offerings.first { it.id == s.id }.supplyTier.serviceCost }
                }
                if (reviews.size < MAX_REVIEWS && random.nextFloat() < 0.08f) {
                    val report = VisitReport(
                        profile, catalog, bought, tooPricey, waitedSec = 6f, queued = false, gotService = service != null,
                        quality = (bought + listOfNotNull(service)).map { it.quality }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
                        foundEmpty = foundEmpty,
                    )
                    val stars = ReviewWriter.stars(report)
                    reviews += Review("offline-$nowMillis-${reviews.size}", profile.id, profile.name, stars, ReviewWriter.write(report, stars, random), nowMillis)
                }
            }
            // Stockers top up anything below half, while there's money for it.
            if (stockers.isNotEmpty()) {
                val waste = stockers.maxOf { Staffing.wasteChance(it) }
                for (o in b.offerings.filter { it.kind == OfferingKind.PRODUCT }) {
                    val cap = Economy.capacity(o, lot, w::sprite)
                    val have = stock[o.id] ?: 0
                    if (have * 2 >= cap) continue
                    val units = cap - have
                    val cost = units * o.supplyTier.unitCost
                    if (w.coins + earned - supplies < cost) continue
                    supplies += cost
                    stock[o.id] = have + units - if (random.nextFloat() < waste) 1 else 0
                }
            }
        }

        b = b.copy(
            offerings = b.offerings.map { o -> if (o.kind == OfferingKind.PRODUCT) o.copy(stock = (stock[o.id] ?: o.stock).coerceAtLeast(0)) else o },
            revenueTotal = b.revenueTotal + earned,
            customersServed = b.customersServed + customers,
            itemsSold = b.itemsSold + itemsSold,
            reviews = (reviews.reversed() + b.reviews).take(Business.MAX_REVIEWS),
        )
        w = w.copy(coins = w.coins + earned).updateLot(lotId) { it.copy(business = b) }
        w = Economy.book(w, today) { it.copy(sales = it.sales + earned) }
        if (supplies > 0) w = Economy.spendOnSupplies(w, today, supplies)
        return w to Summary(span, customers, itemsSold, earned, supplies, reviews.size, soldOut.toList())
    }

    private fun catalogOf(b: Business, stock: Map<String, Int>) = ShopCatalog(
        shopTags = b.tagIds,
        items = b.offerings.map {
            CatalogItem(
                it.id, it.name, it.kind, it.priceCoins, it.tagIds,
                stock = if (it.kind == OfferingKind.PRODUCT) stock[it.id] ?: 0 else Int.MAX_VALUE,
                quality = it.supplyTier.quality,
            )
        },
        rating = b.rating,
    )

    private fun displayedProducts(lot: Lot, w: WorldState): Set<String> =
        lot.furniture.flatMap { piece ->
            val spots = w.sprite(piece.spriteId)?.displaySpots.orEmpty()
            spots.mapNotNull { piece.stock[it.id] }
        }.toSet()
}
