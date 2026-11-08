package com.blockparty.core.model

enum class TagCategory(val label: String) {
    MOOD("Mood"),
    PRICE("Price"),
    AUDIENCE("Audience"),
    TYPE("Type"),
    TIME("Time"),
}

/**
 * A tag links a free-form business to customer behavior. [unlockLevel] is the player
 * level at which the tag becomes available (0 = available from the start).
 */
data class Tag(val id: String, val label: String, val category: TagCategory, val unlockLevel: Int = 0)

/** The hand-authored tag library. Tag ids are stable and saved in world data. */
object TagLibrary {
    private fun tags(category: TagCategory, vararg entries: Pair<String, Int>) =
        entries.map { (label, level) -> Tag(label.lowercase().replace(' ', '_'), label, category, level) }

    val all: List<Tag> = buildList {
        addAll(
            tags(
                TagCategory.MOOD,
                "Cozy" to 0, "Spooky" to 0, "Chaotic" to 0, "Fancy" to 0, "Weird" to 0, "Loud" to 0,
                "Calm" to 0, "Cute" to 0, "Retro" to 2, "Mysterious" to 3, "Romantic" to 4, "Edgy" to 5,
            )
        )
        addAll(
            tags(
                TagCategory.PRICE,
                "Cheap" to 0, "Budget" to 0, "Mid-range" to 0, "Premium" to 0, "Luxury" to 3, "Bargain bin" to 2,
            )
        )
        addAll(
            tags(
                TagCategory.AUDIENCE,
                "Kids" to 0, "Families" to 0, "Teens" to 0, "Students" to 0, "Nightlife" to 0, "Collectors" to 0,
                "Seniors" to 1, "Tourists" to 2, "Gamers" to 2, "Pet owners" to 3, "Artists" to 3, "Athletes" to 4,
            )
        )
        addAll(
            tags(
                TagCategory.TYPE,
                "Food" to 0, "Drinks" to 0, "Music" to 0, "Crafts" to 0, "Oddities" to 0, "Clothing" to 0,
                "Books" to 0, "Toys" to 0, "Plants" to 1, "Tech" to 1, "Beauty" to 2, "Fitness" to 2,
                "Art" to 2, "Games" to 3, "Magic" to 4, "Antiques" to 4,
            )
        )
        addAll(
            tags(
                TagCategory.TIME,
                "Morning" to 0, "Afternoon" to 0, "Evening" to 0, "Late night" to 0, "Weekend" to 1, "Seasonal" to 3,
            )
        )
    }

    private val byId = all.associateBy { it.id }

    fun get(id: String): Tag? = byId[id]

    fun byCategory(maxLevel: Int = Int.MAX_VALUE): Map<TagCategory, List<Tag>> =
        all.filter { it.unlockLevel <= maxLevel }.groupBy { it.category }
}
