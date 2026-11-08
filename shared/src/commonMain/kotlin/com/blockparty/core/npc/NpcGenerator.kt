package com.blockparty.core.npc

import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.TagCategory
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.model.WorldState
import kotlin.random.Random

/**
 * Gives drawn customers their personality. Everything comes from hand-written lists and
 * simple dice rolls seeded by the drawing's id, so the same drawing always becomes the same
 * customer on every device.
 */
object NpcGenerator {
    private val firstNames = listOf(
        "Ada", "Bea", "Cal", "Dot", "Eli", "Fern", "Gus", "Hal", "Ivy", "Jo", "Kit", "Lou",
        "Mae", "Ned", "Ola", "Pip", "Quin", "Rosa", "Sid", "Tess", "Uma", "Vic", "Wren", "Xan",
        "Yuri", "Zed", "Abe", "Bo", "Cleo", "Dex", "Esme", "Finn", "Gigi", "Hugo", "Iris", "Jules",
        "Kai", "Lena", "Milo", "Nell", "Otto", "Pearl", "Rex", "Suki", "Theo", "Vera", "Walt", "Zoe",
    )

    enum class BudgetTier(val range: LongRange) {
        THRIFTY(5L..20L),
        REGULAR(15L..60L),
        BIG_SPENDER(50L..200L),
    }

    private val cheapTags = setOf("cheap", "budget", "bargain_bin")
    private val pricyTags = setOf("premium", "luxury")

    /** Creates the profile for a customer drawing. Only tags unlocked at [playerLevel] are used. */
    fun profile(id: String, spriteId: String, playerLevel: Int = 0): NpcProfile {
        val random = Random(seedOf(spriteId))
        val tags = TagLibrary.byCategory(playerLevel)
        fun pick(category: TagCategory) = tags[category].orEmpty().randomOrNull(random)?.id

        val chosen = buildList {
            pick(TagCategory.TYPE)?.let(::add) // everyone wants some kind of thing
            pick(if (random.nextBoolean()) TagCategory.MOOD else TagCategory.AUDIENCE)?.let(::add)
            if (random.nextFloat() < 0.5f) pick(TagCategory.PRICE)?.let(::add)
            if (random.nextFloat() < 0.35f) pick(TagCategory.TIME)?.let(::add)
            if (random.nextFloat() < 0.3f) pick(TagCategory.MOOD)?.let(::add)
        }.distinct()

        val tier = when {
            chosen.any { it in cheapTags } -> BudgetTier.THRIFTY
            chosen.any { it in pricyTags } -> BudgetTier.BIG_SPENDER
            else -> when (random.nextFloat()) {
                in 0f..0.3f -> BudgetTier.THRIFTY
                in 0.3f..0.85f -> BudgetTier.REGULAR
                else -> BudgetTier.BIG_SPENDER
            }
        }
        return NpcProfile(
            id = id,
            spriteId = spriteId,
            name = firstNames[random.nextInt(firstNames.size)],
            tagIds = chosen,
            budgetCoins = tier.range.first + random.nextLong(tier.range.last - tier.range.first + 1),
            walkSpeed = 0.9f + random.nextFloat() * 0.7f,
        )
    }

    /** Stable string hash (independent of platform hashCode quirks). */
    fun seedOf(text: String): Long {
        var h = 1125899906842597L
        for (c in text) h = 31 * h + c.code
        return h
    }
}

/** Chooses which customers walk onto the street. */
object NpcPool {
    /** Once a street has this many player-drawn customers, starter customers stop appearing. */
    const val STARTER_PHASE_OUT = 6

    /**
     * Candidates and their weights: player drawings are favoured and gradually take over from
     * the starter set.
     */
    fun weighted(world: WorldState): List<Pair<NpcProfile, Float>> {
        val starterIds = world.sprites.filter { it.isStarter }.map { it.id }.toSet()
        val (starter, player) = world.npcs.partition { it.spriteId in starterIds }
        val starterWeight = 1f - player.size.coerceAtMost(STARTER_PHASE_OUT) / STARTER_PHASE_OUT.toFloat()
        return player.map { it to 2f } + starter.map { it to starterWeight }.filter { it.second > 0f }
    }

    fun choose(world: WorldState, random: Random): NpcProfile? = choose(weighted(world), random)

    fun choose(candidates: List<Pair<NpcProfile, Float>>, random: Random): NpcProfile? {
        val total = candidates.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return null
        var roll = random.nextFloat() * total
        for ((profile, weight) in candidates) {
            roll -= weight
            if (roll <= 0f) return profile
        }
        return candidates.last().first
    }
}

