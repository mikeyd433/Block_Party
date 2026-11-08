package com.blockparty.core.npc

import com.blockparty.core.geometry.Vec2
import com.blockparty.core.sim.Wanderer

/** Someone working in a shop: the shopkeeper now, employees later. Provides services. */
class StaffMember internal constructor(
    val id: String,
    val lotId: String,
    start: Vec2,
) {
    var position: Vec2 = start
        internal set
    var distanceWalked: Float = 0f
        internal set
    var facingRight: Boolean = true
        internal set
    /** Offering whose drawing they're holding (handing a service item over or back). */
    var heldItem: String? = null
        internal set
    val isWalking: Boolean get() = path.isNotEmpty()
    val isServing: Boolean get() = session != null

    internal val path = ArrayDeque<Vec2>()
    internal var pause = 0f
    internal var door: ShopDoor? = null
    /** Role and work modifiers (kept up to date from the shop). */
    var spec: StaffSpec = StaffSpec(id, role = null)
        internal set
    internal var session: ServiceSession? = null

    internal val tile: TileXY get() = position.x.toInt() to position.y.toInt()

    internal fun walkTo(goal: TileXY, door: ShopDoor) {
        path.clear()
        if (tile == goal) return
        val route = Wanderer.findPath(tile, goal, door.freeTiles)
        if (route.isNotEmpty()) route.forEach { path.add(Wanderer.center(it)) }
        else if (tile !in door.freeTiles) path.add(Wanderer.center(goal)) // stuck on furniture: step out
    }

    internal fun faceTowards(p: Vec2) {
        val screenDx = (p.x - position.x) - (p.y - position.y)
        if (screenDx > 0.01f) facingRight = true else if (screenDx < -0.01f) facingRight = false
    }
}

enum class ServicePhase {
    WAITING_FOR_STAFF, STAFF_WALKING, PREPARING, HANDING_OVER, SERVING, FINISHING,
}

/** A service in progress, as seen by the renderer. [progress] is 0..1 through the current phase. */
class ServiceView(
    val offeringId: String?,
    val staff: StaffMember?,
    val customer: Pedestrian,
    val phase: ServicePhase,
    val progress: Float,
)

internal class ServiceSession(
    val door: ShopDoor,
    val station: Fixture,
    val customer: Pedestrian,
    val offeringId: String?,
    var duration: Float,
) {
    var staff: StaffMember? = null
    var phase = ServicePhase.WAITING_FOR_STAFF
    var elapsed = 0f

    fun advance(next: ServicePhase) {
        phase = next
        elapsed = 0f
    }

    fun phaseLength(): Float = when (phase) {
        ServicePhase.PREPARING, ServicePhase.FINISHING -> 0.45f
        ServicePhase.HANDING_OVER -> 0.6f
        ServicePhase.SERVING -> duration
        else -> 0f
    }

    fun view() = ServiceView(
        offeringId, staff, customer, phase,
        if (phaseLength() > 0f) (elapsed / phaseLength()).coerceIn(0f, 1f) else 0f,
    )
}
