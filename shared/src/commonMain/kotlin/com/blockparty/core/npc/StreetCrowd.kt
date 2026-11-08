package com.blockparty.core.npc

import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.Street
import com.blockparty.core.sim.Wanderer
import kotlin.math.sqrt
import kotlin.random.Random

/** A shop customers can walk into. */
class ShopDoor(
    val lotId: String,
    val lot: GridRect,
    /** Free floor tiles inside the shop. */
    val freeTiles: Set<Pair<Int, Int>>,
    /** Chance (0..1) that a given customer walking past steps inside. */
    val visitChance: (NpcProfile) -> Float,
) {
    /** Tile just inside the front wall, in the middle of the shop front. */
    val entryTile: Pair<Int, Int> get() = (lot.x + lot.w / 2) to (lot.bottom - 1)
}

/** Someone on the street right now. Not saved: the crowd is re-created each session. */
class Pedestrian internal constructor(
    val id: Int,
    val profile: NpcProfile,
    start: Vec2,
    internal val direction: Int,
    internal val laneY: Float,
    internal val exitX: Float,
) {
    var position: Vec2 = start
        internal set
    var distanceWalked: Float = 0f
        internal set
    var facingRight: Boolean = direction > 0
        internal set
    /** Lot the pedestrian is currently inside, or null when out on the street. */
    var insideLotId: String? = null
        internal set
    val isWalking: Boolean get() = path.isNotEmpty() && pause <= 0f

    internal val path = ArrayDeque<Vec2>()
    internal var pause = 0f
    internal val considered = HashSet<String>()
    internal var visiting: ShopDoor? = null
    internal var browseStops = 0
    internal var leaving = false
    internal var done = false
}

/**
 * Customers walking along the street's sidewalks. Those on the near sidewalk may step into a
 * shop as they pass its door, browse between free tiles for a while, then carry on.
 */
class StreetCrowd(private val random: Random = Random.Default) {
    private val _people = ArrayList<Pedestrian>()
    val people: List<Pedestrian> get() = _people

    private var nextId = 1
    private var spawnTimer = 0.5f

    /**
     * Advances the crowd by [dt] seconds.
     * @param pick chooses the next customer to appear (null when the pool is empty).
     */
    fun update(dt: Float, street: Street, doors: List<ShopDoor>, pick: (Random) -> NpcProfile?) {
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = SPAWN_MIN_SEC + random.nextFloat() * (SPAWN_MAX_SEC - SPAWN_MIN_SEC)
            if (_people.size < MAX_PEOPLE) spawn(street, pick)
        }
        for (p in _people) step(p, dt, street, doors)
        _people.removeAll { it.done }
    }

    /** Simulates [seconds] ahead so the street is already lively when it's first shown. */
    fun warmUp(seconds: Float, street: Street, doors: List<ShopDoor>, pick: (Random) -> NpcProfile?) {
        var t = 0f
        while (t < seconds) {
            update(WARM_STEP, street, doors, pick)
            t += WARM_STEP
        }
    }

    private fun spawn(street: Street, pick: (Random) -> NpcProfile?) {
        // Prefer someone who isn't already out, so the same drawing rarely appears twice.
        var chosen = pick(random) ?: return
        repeat(3) {
            if (_people.any { it.profile.id == chosen.id }) chosen = pick(random) ?: return
        }
        val direction = if (random.nextBoolean()) 1 else -1
        val near = random.nextFloat() < NEAR_LANE_SHARE
        val laneY = if (near) nearLaneY(street) else farLaneY(street)
        val jitter = (random.nextFloat() - 0.5f) * 0.3f
        val startX = if (direction > 0) -EDGE_MARGIN else street.widthTiles + EDGE_MARGIN
        val exitX = if (direction > 0) street.widthTiles + EDGE_MARGIN else -EDGE_MARGIN
        val p = Pedestrian(nextId++, chosen, Vec2(startX, laneY + jitter), direction, laneY + jitter, exitX)
        p.path.add(Vec2(exitX, laneY + jitter))
        _people.add(p)
    }

    private fun step(p: Pedestrian, dt: Float, street: Street, doors: List<ShopDoor>) {
        if (p.pause > 0f) {
            p.pause -= dt
            if (p.pause <= 0f) afterPause(p)
            return
        }
        val nearLane = p.laneY < street.roadStartRow.toFloat()
        if (p.visiting == null && !p.leaving && nearLane) maybeVisit(p, doors)

        var remaining = p.profile.walkSpeed * dt
        while (remaining > 0f && p.path.isNotEmpty()) {
            val target = p.path.first()
            val delta = target - p.position
            val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
            val screenDx = delta.x - delta.y
            if (screenDx > 0.01f) p.facingRight = true else if (screenDx < -0.01f) p.facingRight = false
            if (dist <= remaining) {
                p.position = target
                p.distanceWalked += dist
                remaining -= dist
                p.path.removeFirst()
                if (p.path.isEmpty()) onArrive(p)
                if (p.pause > 0f) break
            } else {
                p.position = p.position + delta * (remaining / dist)
                p.distanceWalked += remaining
                remaining = 0f
            }
        }
        p.insideLotId = doors.firstOrNull { it.lot.containsPoint(p.position) }?.lotId
    }

    private fun maybeVisit(p: Pedestrian, doors: List<ShopDoor>) {
        for (door in doors) {
            if (door.lotId in p.considered) continue
            val doorX = door.entryTile.first + 0.5f
            val passed = if (p.direction > 0) p.position.x >= doorX else p.position.x <= doorX
            if (!passed) continue
            p.considered += door.lotId
            if (door.entryTile !in door.freeTiles) continue // blocked by furniture
            if (random.nextFloat() >= door.visitChance(p.profile)) continue
            p.visiting = door
            p.browseStops = 1 + random.nextInt(3)
            p.path.clear()
            p.path.add(Vec2(doorX, p.laneY))
            p.path.add(Wanderer.center(door.entryTile))
            return
        }
    }

    private fun onArrive(p: Pedestrian) {
        val door = p.visiting
        when {
            p.leaving -> {
                // Back on the sidewalk: carry on along the street.
                p.leaving = false
                p.visiting = null
                p.path.add(Vec2(p.exitX, p.laneY))
            }
            door != null -> p.pause = BROWSE_MIN_SEC + random.nextFloat() * (BROWSE_MAX_SEC - BROWSE_MIN_SEC)
            else -> p.done = true // reached the end of the street
        }
    }

    private fun afterPause(p: Pedestrian) {
        val door = p.visiting ?: return
        val here = p.position.x.toInt() to p.position.y.toInt()
        if (p.browseStops > 0) {
            p.browseStops--
            val goal = door.freeTiles.elementAt(random.nextInt(door.freeTiles.size))
            val route = Wanderer.findPath(here, goal, door.freeTiles)
            if (route.isNotEmpty()) {
                route.forEach { p.path.add(Wanderer.center(it)) }
                return
            }
        }
        // Done browsing: head back out through the door.
        p.leaving = true
        Wanderer.findPath(here, door.entryTile, door.freeTiles).forEach { p.path.add(Wanderer.center(it)) }
        p.path.add(Vec2(door.entryTile.first + 0.5f, p.laneY))
    }

    companion object {
        const val MAX_PEOPLE = 9
        const val SPAWN_MIN_SEC = 1.6f
        const val SPAWN_MAX_SEC = 4.2f
        const val BROWSE_MIN_SEC = 1.5f
        const val BROWSE_MAX_SEC = 4f
        const val NEAR_LANE_SHARE = 0.65f
        const val EDGE_MARGIN = 1.5f
        private const val WARM_STEP = 0.1f

        fun nearLaneY(street: Street) = street.lotDepth + street.sidewalkRows / 2f
        fun farLaneY(street: Street) = street.roadStartRow + street.roadRows + street.sidewalkRows / 2f
    }
}

private fun GridRect.containsPoint(p: Vec2) = p.x >= x && p.y >= y && p.x < right && p.y < bottom
