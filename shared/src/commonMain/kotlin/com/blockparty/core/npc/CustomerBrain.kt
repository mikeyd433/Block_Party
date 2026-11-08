package com.blockparty.core.npc

import com.blockparty.core.model.Business
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.OfferingKind
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/** Something a shop sells, as customers see it. */
data class CatalogItem(
    val id: String,
    val name: String,
    val kind: OfferingKind,
    val price: Long,
    val tags: List<String>,
)

/** What a shop offers and how it's rated, as customers see it. */
class ShopCatalog(
    val shopTags: List<String>,
    val items: List<CatalogItem>,
    /** Average star rating, or null before any reviews. */
    val rating: Float?,
) {
    val products: List<CatalogItem> get() = items.filter { it.kind == OfferingKind.PRODUCT }
    val services: List<CatalogItem> get() = items.filter { it.kind == OfferingKind.SERVICE }
    val allTags: Set<String> by lazy { (shopTags + items.flatMap { it.tags }).toSet() }

    /** Median price, used as the shop's price level. */
    val typicalPrice: Float? by lazy {
        val prices = items.map { it.price }.sorted()
        if (prices.isEmpty()) null else prices[prices.size / 2].toFloat()
    }

    fun item(id: String?) = items.firstOrNull { it.id == id }

    companion object {
        fun of(business: Business?): ShopCatalog? {
            if (business == null) return null
            return ShopCatalog(
                shopTags = business.tagIds,
                items = business.offerings.map { CatalogItem(it.id, it.name, it.kind, it.priceCoins, it.tagIds) },
                rating = business.rating,
            )
        }
    }
}

/**
 * How customers decide. Hand-tuned formulas only: tags pull customers in, prices are weighed
 * against their budget, and ratings nudge visits up or down.
 */
object CustomerBrain {
    /** Share of their budget a customer expects a typical item to cost. */
    const val EXPECTED_SHARE = 0.4f

    fun expectedPrice(budget: Long): Float = max(1f, budget * EXPECTED_SHARE)

    /** Fraction of the customer's liked tags found in [tags] (0..1). */
    fun tagAffinity(profile: NpcProfile, tags: Collection<String>): Float {
        if (profile.tagIds.isEmpty()) return 0.5f
        val set = tags.toSet()
        return profile.tagIds.count { it in set }.toFloat() / profile.tagIds.size
    }

    /**
     * How well a price level suits a budget (0..1). Much pricier than expected falls off quickly;
     * much cheaper falls off gently (big spenders mildly avoid bargain shops).
     */
    fun priceFit(price: Float, budget: Long): Float {
        if (price <= 0f) return 0.7f
        val ratio = price / expectedPrice(budget)
        val l = ln(ratio)
        val sigma = if (ratio > 1f) 0.55f else 1.3f
        return exp(-(l * l) / (2 * sigma * sigma))
    }

    /** Chance (0..1) that this customer steps inside when walking past. */
    fun visitChance(profile: NpcProfile, catalog: ShopCatalog?): Float {
        if (catalog == null || catalog.items.isEmpty()) return 0.06f
        val affinity = tagAffinity(profile, catalog.allTags)
        val fit = catalog.typicalPrice?.let { priceFit(it, profile.budgetCoins) } ?: 0.6f
        val ratingFactor = catalog.rating?.let { 0.75f + 0.1f * (it - 1f) } ?: 1f
        return ((0.08f + 0.55f * affinity) * (0.2f + 0.8f * fit) * ratingFactor).coerceIn(0.03f, 0.85f)
    }

    /** Price compared with what the customer expects to pay (1 = exactly as expected). */
    fun priceRatio(item: CatalogItem, profile: NpcProfile): Float = item.price / expectedPrice(profile.budgetCoins)

    /** Chance (0..1) of buying [item] when looking at it, given the money they have left. */
    fun buyChance(profile: NpcProfile, item: CatalogItem, catalog: ShopCatalog, remaining: Long): Float {
        if (item.price > remaining) return 0f
        val match = tagAffinity(profile, item.tags + catalog.shopTags)
        val priceFactor = (1.35f - 0.55f * priceRatio(item, profile)).coerceIn(0f, 1f)
        return ((0.12f + 0.65f * match) * priceFactor).coerceIn(0f, 0.92f)
    }

    /** Picks an affordable service, favouring ones matching the customer's tastes. */
    fun chooseService(profile: NpcProfile, catalog: ShopCatalog, remaining: Long, random: Random): CatalogItem? {
        val options = catalog.services.filter { it.price <= remaining }
        if (options.isEmpty()) return null
        val weights = options.map { 0.2f + tagAffinity(profile, it.tags + catalog.shopTags) }
        var roll = random.nextFloat() * weights.sum()
        for ((i, o) in options.withIndex()) {
            roll -= weights[i]
            if (roll <= 0f) return o
        }
        return options.last()
    }
}
