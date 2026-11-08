package com.blockparty.core.npc

import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.TagCategory
import com.blockparty.core.model.TagLibrary
import kotlin.math.roundToInt
import kotlin.random.Random

/** What happened during one customer's visit, used to rate it. */
data class VisitReport(
    val profile: NpcProfile,
    val catalog: ShopCatalog,
    val bought: List<CatalogItem>,
    /** Things they liked but couldn't afford. */
    val tooPricey: List<CatalogItem>,
    val waitedSec: Float,
    val queued: Boolean,
    val gotService: Boolean,
    /** Product/service quality bonus (supplies now; upgrades and staff later). */
    val quality: Float = 0f,
    /** Wanted something that was sold out. */
    val foundEmpty: Boolean = false,
    /** Extra star adjustment from staff (friendly or chatty employees). */
    val staffBonus: Float = 0f,
) {
    val matchedTags: List<String> get() = profile.tagIds.filter { it in catalog.allTags }
    val missedTags: List<String> get() = profile.tagIds.filter { it !in catalog.allTags }
    val gotSomething: Boolean get() = bought.isNotEmpty() || gotService
}

/**
 * Star ratings and review text from hand-written phrases. Every sentence below was written by
 * hand; the game only picks and fills them in based on what happened.
 */
object ReviewWriter {
    fun stars(r: VisitReport): Int {
        var s = 3f
        s += 1.4f * CustomerBrain.tagAffinity(r.profile, r.catalog.allTags) - 0.3f
        s += if (r.gotSomething) 0.5f else -0.4f
        if (r.tooPricey.isNotEmpty() && !r.gotSomething) s -= 0.6f
        if (r.bought.isNotEmpty()) {
            val ratio = r.bought.map { CustomerBrain.priceRatio(it, r.profile) }.average().toFloat()
            if (ratio < 0.6f) s += 0.4f
            if (ratio > 1.3f) s -= 0.6f
        }
        if (r.queued) {
            s += when {
                r.waitedSec > 25f -> -1.2f
                r.waitedSec > 12f -> -0.6f
                r.waitedSec < 5f -> 0.2f
                else -> 0f
            }
        }
        if (r.foundEmpty && !r.gotSomething) s -= 0.6f
        s += r.quality + r.staffBonus
        return s.roundToInt().coerceIn(1, 5)
    }

    fun write(r: VisitReport, stars: Int, random: Random): String {
        val parts = ArrayList<String>()
        parts += openers.getValue(stars).random(random)

        val liked = r.matchedTags.mapNotNull { TagLibrary.get(it) }
        val missed = r.missedTags.mapNotNull { TagLibrary.get(it) }
        var saidMissing = false
        if (liked.isNotEmpty() && random.nextFloat() < 0.8f) {
            val tag = liked.random(random)
            parts += likedTag.getValue(tag.category).random(random).fill(tag.label.lowercase())
        } else if (missed.isNotEmpty() && stars <= 3 && random.nextFloat() < 0.7f) {
            val tag = missed.random(random)
            parts += missedTag.getValue(tag.category).random(random).fill(tag.label.lowercase())
            saidMissing = true
        }

        val item = r.bought.randomOrNull(random)
        val pricey = r.tooPricey.randomOrNull(random)
        when {
            item != null && random.nextFloat() < 0.75f -> {
                val ratio = CustomerBrain.priceRatio(item, r.profile)
                parts += when {
                    ratio < 0.6f -> bargain.random(random).fill(item.name)
                    ratio > 1.3f -> overpriced.random(random).fill(item.name)
                    else -> boughtIt.random(random).fill(item.name)
                }
            }
            pricey != null -> parts += couldNotAfford.random(random).fill(pricey.name)
            r.foundEmpty -> parts += soldOut.random(random)
            !r.gotSomething && !saidMissing -> parts += nothingForMe.random(random)
        }
        if (r.gotSomething && random.nextFloat() < 0.5f) {
            if (r.quality >= 0.3f) parts += goodQuality.random(random)
            else if (r.quality <= -0.3f) parts += poorQuality.random(random)
        }

        if (r.queued) {
            when {
                r.waitedSec > 25f -> parts += longWait.random(random)
                r.waitedSec > 12f && random.nextFloat() < 0.6f -> parts += someWait.random(random)
                r.waitedSec < 5f && random.nextFloat() < 0.5f -> parts += quick.random(random)
            }
        }
        if (random.nextFloat() < 0.6f) parts += closers.getValue(stars).random(random)
        return parts.joinToString(" ")
    }

    private fun String.fill(value: String) = replace("{x}", value.ifBlank { "thing" })

    private val openers = mapOf(
        5 to listOf("Absolutely loved this place!", "New favourite spot on the street.", "Five stars, no notes.", "What a gem."),
        4 to listOf("Really nice shop.", "Had a good time here.", "Pretty great visit.", "Glad I popped in."),
        3 to listOf("It was okay.", "Not bad, not amazing.", "Decent enough.", "A perfectly fine shop."),
        2 to listOf("Bit of a letdown.", "Expected more, honestly.", "Not really for me.", "Meh."),
        1 to listOf("Wouldn't recommend.", "Disappointing visit.", "Not my cup of tea at all.", "Oof."),
    )
    // Tag phrases per category, so they read naturally: moods are adjectives, types are things,
    // audiences are people, price and time tags have their own wording.
    private val likedTag = mapOf(
        TagCategory.MOOD to listOf("Love the {x} vibe.", "So {x}, just how I like it.", "Finally a {x} place on this street."),
        TagCategory.TYPE to listOf("Great {x} selection.", "Exactly the {x} I was after.", "Best {x} on the street."),
        TagCategory.AUDIENCE to listOf("Perfect for {x}.", "Great spot for {x}.", "Clearly made with {x} in mind."),
        TagCategory.PRICE to listOf("Exactly the {x} prices I wanted.", "Prices feel right for a {x} shop."),
        TagCategory.TIME to listOf("Perfect {x} stop.", "My new {x} spot."),
    )
    private val missedTag = mapOf(
        TagCategory.MOOD to listOf("Was hoping for something more {x}.", "Could be a bit more {x}.", "Not very {x}, which is what I wanted."),
        TagCategory.TYPE to listOf("Wish they had some {x}.", "No {x} here, sadly.", "Came for {x}, didn't find any."),
        TagCategory.AUDIENCE to listOf("Not really aimed at {x}.", "Didn't feel made for {x}."),
        TagCategory.PRICE to listOf("Hoped it'd be more {x}.", "Not the {x} prices I was after."),
        TagCategory.TIME to listOf("Not much of a {x} spot.", "Doesn't feel like a {x} place."),
    )
    private val boughtIt = listOf(
        "The {x} was worth it.", "Picked up the {x} and I'm happy.", "Got the {x}, no regrets.", "Ten out of ten for the {x}.",
    )
    private val bargain = listOf("The {x} was a steal.", "Can't believe how cheap the {x} was.", "Great price on the {x}.")
    private val overpriced = listOf(
        "The {x} was nice but pricey.", "Paid a lot for the {x}.", "The {x} cost more than I'd like.",
    )
    private val couldNotAfford = listOf(
        "The {x} looked great, but it's way out of my budget.", "Wanted the {x}, couldn't afford it.",
        "Maybe one day I can afford the {x}.",
    )
    private val soldOut = listOf("Everything I wanted was sold out.", "Shelves were looking bare.", "Came back to empty shelves.")
    private val goodQuality = listOf("Really good quality.", "You can tell they use the good stuff.", "Top quality.")
    private val poorQuality = listOf("Felt a bit cheap.", "Quality could be better.", "Fell apart a bit, honestly.")
    private val nothingForMe = listOf("Didn't find anything for me.", "Browsed, but nothing grabbed me.", "Left empty-handed.")
    private val longWait = listOf("Waited forever in line.", "The queue took ages.", "So much waiting.")
    private val someWait = listOf("Had to wait a bit.", "The line was slow.")
    private val quick = listOf("In and out, no waiting.", "Super quick.", "No queue at all.")
    private val closers = mapOf(
        5 to listOf("Will be back!", "Tell your friends.", "Can't wait to come back."),
        4 to listOf("Would come again.", "Worth a visit.", "I'll be back."),
        3 to listOf("Might come back.", "Maybe next time."),
        2 to listOf("Probably won't return.", "Not rushing back."),
        1 to listOf("Never again.", "Won't be back."),
    )
}
