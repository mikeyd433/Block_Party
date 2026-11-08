package com.blockparty.core.npc

import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.NpcProfile

/** Things that happen in shops that the game needs to record. */
sealed class CrowdEvent {
    abstract val lotId: String

    /** A customer paid for products (at the checkout or on the way out) or a service. */
    class Sale(
        override val lotId: String,
        val customer: NpcProfile,
        val items: List<CatalogItem>,
        val total: Long,
        val at: Vec2,
    ) : CrowdEvent()

    /** A customer finished a visit (whether or not they bought anything). */
    class Visit(override val lotId: String, val customer: NpcProfile, val boughtSomething: Boolean) : CrowdEvent()

    /** A customer left a review on the way out. */
    class Review(
        override val lotId: String,
        val customer: NpcProfile,
        val stars: Int,
        val text: String,
        val at: Vec2,
    ) : CrowdEvent()
}
