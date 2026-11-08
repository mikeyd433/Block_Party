package com.blockparty.core.npc

import com.blockparty.core.geometry.Vec2
import com.blockparty.core.model.FurnitureType
import com.blockparty.core.model.GridRect
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.Street
import com.blockparty.core.sim.Wanderer
import kotlin.math.sqrt
import kotlin.random.Random

typealias TileXY = Pair<Int, Int>

/**
 * A piece of furniture customers use.
 * @property access free tile next to it where a customer stands to use it (null if boxed in).
 */
class Fixture(val id: String, val type: FurnitureType, val rect: GridRect, val access: TileXY?)

/** A shop customers can walk into. */
class ShopDoor(
    val lotId: String,
    val lot: GridRect,
    /** Free floor tiles inside the shop. */
    val freeTiles: Set<TileXY>,
    /** Chance (0..1) that a given customer walking past steps inside. */
    val visitChance: (NpcProfile) -> Float,
    val fixtures: List<Fixture> = emptyList(),
    /** Whether the shop sells services (customers then use service stations). */
    val offersServices: Boolean = false,
    /** Whether it only sells services (everyone who comes in wants one). */
    val servicesOnly: Boolean = false,
    /** Tiles next to display pieces; browsers head for these first. */
    val browseSpots: List<TileXY> = emptyList(),
    /** Staff working here (the shopkeeper for now; employees later). They provide services. */
    val staffIds: List<String> = listOf(StreetCrowd.SHOPKEEPER_STAFF_ID),
    /** Services on offer (offering ids); used when there's no [catalog]. */
    val serviceIds: List<String> = emptyList(),
    /** What the shop sells, prices and rating. Without it, visits are just for show. */
    val catalog: ShopCatalog? = null,
    /** Products shown on display pieces, by the tile customers stand on to look at them. */
    val displayItems: Map<TileXY, List<String>> = emptyMap(),
) {
    /** Tile just inside the front wall, in the middle of the shop front. */
    val entryTile: TileXY get() = (lot.x + lot.w / 2) to (lot.bottom - 1)

    fun usable(type: FurnitureType) = fixtures.filter { it.type == type && it.access != null }

    /** Free tiles customers can actually reach from the door. */
    internal val reachable: List<TileXY> by lazy {
        if (entryTile !in freeTiles) emptyList() else listOf(entryTile) + Fixtures.nearestTiles(entryTile, freeTiles, emptySet())
    }
    internal val reachableBrowseSpots: List<TileXY> by lazy { browseSpots.filter { it in reachable.toSet() } }

    /** Where people wait for [fixture]: free tiles nearest its access tile, in queue order. */
    internal val queueTiles: Map<String, List<TileXY>> by lazy {
        val reserved = fixtures.mapNotNull { it.access }.toSet()
        fixtures.filter { it.access != null }.associate { f ->
            f.id to Fixtures.nearestTiles(f.access!!, freeTiles, reserved)
        }
    }
}

object Fixtures {
    /**
     * Free tile next to [rect] for using it, preferring the front (street-facing) side, then the
     * right, left and back, and only tiles reachable from [entry].
     */
    fun accessTile(rect: GridRect, free: Set<TileXY>, entry: TileXY): TileXY? {
        val candidates = buildList {
            for (x in rect.x until rect.right) add(x to rect.bottom)
            for (y in rect.bottom - 1 downTo rect.y) add(rect.right to y)
            for (y in rect.bottom - 1 downTo rect.y) add(rect.x - 1 to y)
            for (x in rect.x until rect.right) add(x to rect.y - 1)
        }
        return candidates.firstOrNull { it in free && (it == entry || Wanderer.findPath(entry, it, free).isNotEmpty()) }
    }

    /** Free tiles in breadth-first order from [start] (excluding it and [exclude]). */
    fun nearestTiles(start: TileXY, free: Set<TileXY>, exclude: Set<TileXY>): List<TileXY> {
        val seen = hashSetOf(start)
        val queue = ArrayDeque<TileXY>().apply { add(start) }
        val out = ArrayList<TileXY>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((dx, dy) in listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1)) {
                val n = cur.first + dx to cur.second + dy
                if (n in free && seen.add(n)) {
                    queue.add(n)
                    if (n !in exclude) out.add(n)
                }
            }
        }
        return out
    }
}

internal enum class Task { WALK, ENTERING, BROWSING, RESTING, QUEUED, PAYING, SERVED, LEAVING }

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
    /** Furniture (placement id) the pedestrian is sitting on, if any. */
    var seatedOn: String? = null
        internal set
    /** Offering whose drawing they're holding (a product they picked, or a service item). */
    var heldItem: String? = null
        internal set

    // What happened during the current shop visit (for paying and reviewing).
    internal val cart = ArrayList<CatalogItem>()
    internal val tooPricey = ArrayList<CatalogItem>()
    internal var paid = false
    internal var chosenService: CatalogItem? = null
    internal var gotService = false
    internal var waited = 0f
    internal var queued = false
    internal var reviewed = false
    internal val spent: Long get() = cart.sumOf { it.price } + (if (gotService) chosenService?.price ?: 0L else 0L)
    internal val remainingBudget: Long get() = profile.budgetCoins - cart.sumOf { it.price } - (chosenService?.price ?: 0L)
    val isWalking: Boolean get() = path.isNotEmpty() && pause <= 0f

    internal val path = ArrayDeque<Vec2>()
    internal var pause = 0f
    internal val considered = HashSet<String>()
    internal var visiting: ShopDoor? = null
    internal var task = Task.WALK
    internal var browseStops = 0
    internal var queueFixture: Fixture? = null
    internal var queueIndex = -1
    internal var seat: Fixture? = null
    internal var done = false

    internal val tile: TileXY get() = position.x.toInt() to position.y.toInt()
}

/**
 * Customers walking along the street's sidewalks. Those on the near sidewalk may step into a
 * shop: they browse (display pieces first), queue and pay at a checkout, queue to be served at
 * service stations, and sit on free seats while they wait.
 */
class StreetCrowd(private val random: Random = Random.Default) {
    private val _people = ArrayList<Pedestrian>()
    val people: List<Pedestrian> get() = _people

    private var nextId = 1
    private var spawnTimer = 0.5f

    /** Queues per fixture (keyed by lot + fixture id), front first. */
    private val queues = LinkedHashMap<String, MutableList<Pedestrian>>()
    /** Seats in use (keyed by lot + fixture id). */
    private val seatsTaken = HashMap<String, Pedestrian>()

    private fun key(door: ShopDoor, f: Fixture) = door.lotId + "/" + f.id

    private val _staff = ArrayList<StaffMember>()
    /** Staff in every shop (shopkeepers now, employees later). */
    val staff: List<StaffMember> get() = _staff

    private val events = ArrayList<CrowdEvent>()

    /** Sales and reviews since the last call. */
    fun drainEvents(): List<CrowdEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    private val sessions = ArrayList<ServiceSession>()
    /** Services in progress, for drawing the item handover. */
    val services: List<ServiceView> get() = sessions.map { it.view() }

    /**
     * Advances the crowd by [dt] seconds.
     * @param pick chooses the next customer to appear (null when the pool is empty).
     */
    fun update(dt: Float, street: Street, doors: List<ShopDoor>, pick: (Random) -> NpcProfile?) {
        // Furniture may have moved since the last frame: swap in the current shop layouts.
        for (p in _people) {
            val v = p.visiting ?: continue
            val fresh = doors.firstOrNull { it.lotId == v.lotId }
            if (fresh == null) { p.visiting = null; continue }
            if (fresh !== v) refreshLayout(p, fresh)
        }

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = SPAWN_MIN_SEC + random.nextFloat() * (SPAWN_MAX_SEC - SPAWN_MIN_SEC)
            if (_people.size < MAX_PEOPLE) spawn(street, pick)
        }
        syncStaff(doors)
        for (p in _people) step(p, dt, street, doors)
        for (s in _staff) stepStaff(s, dt)
        updateQueues()
        updateSessions(dt)
        val gone = _people.filter { it.done }
        if (gone.isNotEmpty()) {
            for (p in gone) release(p)
            _people.removeAll { it.done }
        }
    }

    /** Simulates [seconds] ahead so the street is already lively when it's first shown. */
    fun warmUp(seconds: Float, street: Street, doors: List<ShopDoor>, pick: (Random) -> NpcProfile?) {
        var t = 0f
        while (t < seconds) {
            update(WARM_STEP, street, doors, pick)
            t += WARM_STEP
        }
        events.clear() // warm-up is only for looks: no free sales or reviews
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

    // --- Movement ------------------------------------------------------------------------------

    private fun step(p: Pedestrian, dt: Float, street: Street, doors: List<ShopDoor>) {
        if (p.task == Task.QUEUED || (p.task == Task.SERVED && p.heldItem == null && !p.gotService)) p.waited += dt
        if (p.pause > 0f) {
            p.pause -= dt
            if (p.pause <= 0f) afterPause(p)
        } else {
            if (p.task == Task.WALK && p.laneY < street.roadStartRow.toFloat()) maybeVisit(p, doors)
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
        }
        p.insideLotId = doors.firstOrNull { it.lot.containsPoint(p.position) }?.lotId
    }

    /** Replaces the path with a walk to [goal] over free tiles (straight line if no route). */
    private fun walkTo(p: Pedestrian, goal: TileXY) {
        val door = p.visiting ?: return
        p.path.clear()
        if (p.tile == goal) {
            p.path.add(Wanderer.center(goal))
            return
        }
        val route = Wanderer.findPath(p.tile, goal, door.freeTiles)
        when {
            route.isNotEmpty() -> route.forEach { p.path.add(Wanderer.center(it)) }
            // Unreachable from a normal tile: stay put (arrive where we are) rather than walk through walls.
            p.tile in door.freeTiles -> p.path.add(Wanderer.center(p.tile))
            // Stuck somewhere odd (e.g. furniture placed on us): step straight out.
            else -> p.path.add(Wanderer.center(goal))
        }
    }

    // --- Shop visits ---------------------------------------------------------------------------

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
            p.task = Task.ENTERING
            resetVisit(p)
            p.path.clear()
            p.path.add(Vec2(doorX, p.laneY))
            p.path.add(Wanderer.center(door.entryTile))
            return
        }
    }

    private fun onArrive(p: Pedestrian) {
        val door = p.visiting
        when (p.task) {
            Task.WALK -> p.done = true // reached the end of the street
            Task.ENTERING -> if (door != null) startShopping(p, door) else leave(p)
            Task.BROWSING -> p.pause = BROWSE_MIN_SEC + random.nextFloat() * (BROWSE_MAX_SEC - BROWSE_MIN_SEC)
            Task.RESTING -> {
                sitDown(p)
                p.pause = REST_MIN_SEC + random.nextFloat() * (REST_MAX_SEC - REST_MIN_SEC)
            }
            Task.QUEUED -> when {
                p.queueIndex == 0 && p.tile == p.queueFixture?.access -> beginService(p)
                p.seat != null && p.seatedOn == null && p.tile == p.seat?.access -> sitDown(p)
            }
            Task.LEAVING -> {
                // Back on the sidewalk: carry on along the street.
                p.visiting = null
                p.task = Task.WALK
                p.path.add(Vec2(p.exitX, p.laneY))
            }
            Task.PAYING, Task.SERVED -> Unit
        }
    }

    private fun afterPause(p: Pedestrian) {
        val door = p.visiting ?: return leave(p)
        when (p.task) {
            Task.BROWSING -> {
                lookAtItems(p, door)
                nextBrowse(p, door)
            }
            Task.RESTING -> {
                standUp(p)
                nextBrowse(p, door)
            }
            Task.PAYING -> {
                pay(p, door)
                dequeue(p)
                leave(p)
            }
            Task.SERVED -> {
                standUp(p)
                dequeue(p)
                leave(p)
            }
            else -> Unit
        }
    }

    private fun startShopping(p: Pedestrian, door: ShopDoor) {
        val stations = door.usable(FurnitureType.SERVICE_STATION)
        val catalog = door.catalog
        var wantsService = door.offersServices && stations.isNotEmpty() &&
            (door.servicesOnly || random.nextFloat() < SERVICE_SHARE)
        if (wantsService && catalog != null) {
            val service = CustomerBrain.chooseService(p.profile, catalog, p.remainingBudget, random)
            if (service == null) {
                // Every service is out of their budget.
                p.tooPricey += catalog.services.minBy { it.price }
                wantsService = false
            } else {
                p.chosenService = service
            }
        }
        if (wantsService) {
            joinQueue(p, door, stations)
        } else {
            p.task = Task.BROWSING
            p.browseStops = 1 + random.nextInt(3)
            nextBrowse(p, door)
        }
    }

    private fun nextBrowse(p: Pedestrian, door: ShopDoor) {
        p.task = Task.BROWSING
        if (p.browseStops <= 0) {
            val checkouts = door.usable(FurnitureType.CHECKOUT)
            if (checkouts.isNotEmpty()) joinQueue(p, door, checkouts) else leave(p)
            return
        }
        p.browseStops--
        // Sometimes take a break on a free seat.
        val seat = if (random.nextFloat() < REST_CHANCE) freeSeat(door) else null
        if (seat != null) {
            reserveSeat(p, door, seat)
            p.task = Task.RESTING
            walkTo(p, seat.access!!)
            return
        }
        val spots = door.reachableBrowseSpots
        val goal = if (spots.isNotEmpty() && random.nextFloat() < DISPLAY_PREFERENCE) {
            spots[random.nextInt(spots.size)]
        } else {
            door.reachable.randomOrNull(random) ?: door.entryTile
        }
        walkTo(p, goal)
    }

    private fun leave(p: Pedestrian) {
        val door = p.visiting
        if (door != null && p.task != Task.LEAVING && p.task != Task.WALK) {
            // Shops without a checkout take payment on the way out.
            if (!p.paid && p.cart.isNotEmpty()) pay(p, door)
            maybeReview(p, door)
        }
        standUp(p)
        dequeue(p)
        p.task = Task.LEAVING
        if (door == null) {
            p.path.clear()
            p.path.add(Vec2(p.position.x, p.laneY))
            return
        }
        walkTo(p, door.entryTile)
        p.path.add(Vec2(door.entryTile.first + 0.5f, p.laneY))
    }

    // --- Queues, service and seats -------------------------------------------------------------

    private fun joinQueue(p: Pedestrian, door: ShopDoor, options: List<Fixture>) {
        val fixture = options.minBy { queues[key(door, it)]?.size ?: 0 }
        queues.getOrPut(key(door, fixture)) { ArrayList() }.add(p)
        p.task = Task.QUEUED
        p.queueFixture = fixture
        p.queueIndex = -1 // updateQueues sends them to their place
        p.queued = true
    }

    private fun dequeue(p: Pedestrian) {
        val door = p.visiting
        val f = p.queueFixture
        if (door != null && f != null) queues[key(door, f)]?.remove(p)
        p.queueFixture = null
        p.queueIndex = -1
    }

    /** Moves everyone to their current place in line: front to the fixture, others to a seat or a queue tile. */
    private fun updateQueues() {
        for ((_, line) in queues) {
            line.removeAll { it.done || it.task != Task.QUEUED && it.task != Task.PAYING && it.task != Task.SERVED }
            for ((i, p) in line.withIndex()) {
                if (p.task != Task.QUEUED || p.queueIndex == i) continue
                p.queueIndex = i
                val door = p.visiting ?: continue
                val fixture = p.queueFixture ?: continue
                if (i == 0) {
                    standUp(p)
                    if (p.tile == fixture.access) beginService(p) else walkTo(p, fixture.access!!)
                } else if (p.seatedOn == null && p.seat == null) {
                    val seat = freeSeat(door)
                    if (seat != null) {
                        reserveSeat(p, door, seat)
                        walkTo(p, seat.access!!)
                    } else {
                        val tiles = door.queueTiles[fixture.id].orEmpty()
                        tiles.getOrNull(i - 1)?.let { walkTo(p, it) }
                    }
                } else if (p.seatedOn == null) {
                    p.seat?.access?.let { walkTo(p, it) }
                }
            }
        }
    }

    private fun beginService(p: Pedestrian) {
        val f = p.queueFixture ?: return
        when (f.type) {
            FurnitureType.SERVICE_STATION -> {
                // Sit on the station and wait for a member of staff to come and serve.
                val door = p.visiting ?: return
                p.task = Task.SERVED
                p.position = rectCenter(f.rect)
                p.seatedOn = f.id
                p.pause = 0f
                sessions += ServiceSession(
                    door = door, station = f, customer = p,
                    offeringId = p.chosenService?.id ?: door.serviceIds.randomOrNull(random),
                    duration = SERVICE_MIN_SEC + random.nextFloat() * (SERVICE_MAX_SEC - SERVICE_MIN_SEC),
                )
            }
            else -> {
                p.task = Task.PAYING
                p.pause = PAY_MIN_SEC + random.nextFloat() * (PAY_MAX_SEC - PAY_MIN_SEC)
            }
        }
    }

    private fun freeSeat(door: ShopDoor): Fixture? =
        door.usable(FurnitureType.SEATING).firstOrNull { key(door, it) !in seatsTaken }

    private fun reserveSeat(p: Pedestrian, door: ShopDoor, seat: Fixture) {
        seatsTaken[key(door, seat)] = p
        p.seat = seat
    }

    private fun sitDown(p: Pedestrian) {
        val seat = p.seat ?: return
        p.position = rectCenter(seat.rect)
        p.seatedOn = seat.id
    }

    /** Gets up from a seat or service station onto its access tile, freeing a reserved seat. */
    private fun standUp(p: Pedestrian) {
        val door = p.visiting
        val on = p.seatedOn
        if (on != null) {
            val fixture = door?.fixtures?.firstOrNull { it.id == on }
            fixture?.access?.let { p.position = Wanderer.center(it) }
            p.seatedOn = null
        }
        val seat = p.seat
        if (seat != null && door != null) {
            if (seatsTaken[key(door, seat)] === p) seatsTaken.remove(key(door, seat))
            p.seat = null
        }
    }

    private fun release(p: Pedestrian) {
        standUp(p)
        dequeue(p)
    }

    /**
     * The shop's furniture changed while someone was inside: re-point their fixtures to the new
     * layout, or send them home if what they were using is gone.
     */
    private fun refreshLayout(p: Pedestrian, fresh: ShopDoor) {
        val newQueue = p.queueFixture?.let { q -> fresh.fixtures.firstOrNull { it.id == q.id && it.access != null } }
        val newSeat = p.seat?.let { s -> fresh.fixtures.firstOrNull { it.id == s.id && it.access != null } }
        val lost = (p.queueFixture != null && newQueue == null) || (p.seat != null && newSeat == null) ||
            (p.seatedOn != null && fresh.fixtures.none { it.id == p.seatedOn })
        if (lost && p.task != Task.LEAVING && p.task != Task.WALK) {
            release(p) // with the old layout, so queue and seat entries are cleared
            p.visiting = fresh
            p.pause = 0f
            leave(p)
            return
        }
        // Keys are lot + fixture id, so queue and seat entries carry over unchanged.
        p.visiting = fresh
        p.queueFixture = newQueue
        p.seat = newSeat
        if (p.task == Task.QUEUED) p.queueIndex = -1 // re-place in the new layout
    }

    // --- Buying, paying and reviews -------------------------------------------------------------

    private fun resetVisit(p: Pedestrian) {
        p.cart.clear()
        p.tooPricey.clear()
        p.paid = false
        p.chosenService = null
        p.gotService = false
        p.waited = 0f
        p.queued = false
        p.reviewed = false
        p.heldItem = null
    }

    /**
     * Looks at what's on display where they're standing (or, if the shop has no stocked
     * displays, a few of its products) and maybe picks one up.
     */
    private fun lookAtItems(p: Pedestrian, door: ShopDoor) {
        val catalog = door.catalog ?: return
        if (p.cart.size >= MAX_CART) return
        val here = door.displayItems[p.tile]
        val candidates = when {
            here != null -> here.mapNotNull { catalog.item(it) }
            door.displayItems.isEmpty() -> catalog.products.shuffled(random).take(3)
            else -> emptyList()
        }.distinctBy { it.id }.shuffled(random)
        for (item in candidates) {
            if (item.price > p.remainingBudget) {
                if (CustomerBrain.tagAffinity(p.profile, item.tags + catalog.shopTags) > 0f) p.tooPricey += item
                continue
            }
            if (random.nextFloat() < CustomerBrain.buyChance(p.profile, item, catalog, p.remainingBudget)) {
                p.cart += item
                p.heldItem = item.id // carry it around the shop
                return
            }
        }
    }

    private fun pay(p: Pedestrian, door: ShopDoor) {
        if (p.paid || p.cart.isEmpty()) return
        p.paid = true
        events += CrowdEvent.Sale(door.lotId, p.profile, p.cart.toList(), p.cart.sumOf { it.price }, p.position)
    }

    private fun maybeReview(p: Pedestrian, door: ShopDoor) {
        val catalog = door.catalog ?: return
        if (p.reviewed) return
        p.reviewed = true
        val report = VisitReport(
            profile = p.profile, catalog = catalog, bought = p.cart.toList(), tooPricey = p.tooPricey.distinctBy { it.id },
            waitedSec = p.waited, queued = p.queued, gotService = p.gotService,
        )
        // Unhappy customers are a little more likely to speak up.
        val stars = ReviewWriter.stars(report)
        val chance = if (stars <= 2) REVIEW_CHANCE + 0.15f else REVIEW_CHANCE
        events += CrowdEvent.Visit(door.lotId, p.profile, report.gotSomething)
        if (random.nextFloat() >= chance) return
        events += CrowdEvent.Review(door.lotId, p.profile, stars, ReviewWriter.write(report, stars, random), p.position)
    }

    // --- Staff and services --------------------------------------------------------------------

    /** Makes sure every shop has its staff, and removes staff of shops that are gone. */
    private fun syncStaff(doors: List<ShopDoor>) {
        _staff.removeAll { s -> doors.none { it.lotId == s.lotId && s.id in it.staffIds } }
        for (door in doors) {
            for (id in door.staffIds) {
                val existing = _staff.firstOrNull { it.lotId == door.lotId && it.id == id }
                if (existing != null) {
                    existing.door = door
                    continue
                }
                val start = door.reachable.randomOrNull(random) ?: door.entryTile
                _staff += StaffMember(id, door.lotId, Wanderer.center(start)).also {
                    it.door = door
                    it.pause = random.nextFloat() * 2f
                }
            }
        }
    }

    private fun stepStaff(s: StaffMember, dt: Float) {
        val door = s.door ?: return
        if (s.path.isEmpty()) {
            if (s.session != null) return // standing at the station, serving
            s.pause -= dt
            if (s.pause > 0f) return
            // Idle: stroll to another spot in the shop.
            s.pause = STAFF_IDLE_MIN_SEC + random.nextFloat() * (STAFF_IDLE_MAX_SEC - STAFF_IDLE_MIN_SEC)
            val goal = door.reachable.randomOrNull(random) ?: return
            s.walkTo(goal, door)
            return
        }
        var remaining = STAFF_SPEED * dt
        while (remaining > 0f && s.path.isNotEmpty()) {
            val target = s.path.first()
            val delta = target - s.position
            val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
            val screenDx = delta.x - delta.y
            if (screenDx > 0.01f) s.facingRight = true else if (screenDx < -0.01f) s.facingRight = false
            if (dist <= remaining) {
                s.position = target
                s.distanceWalked += dist
                remaining -= dist
                s.path.removeFirst()
            } else {
                s.position = s.position + delta * (remaining / dist)
                s.distanceWalked += remaining
                remaining = 0f
            }
        }
    }

    /**
     * Runs services: a free staff member walks to the station, hands the service's drawing to the
     * customer, waits while they're served, takes it back, and the customer leaves.
     */
    private fun updateSessions(dt: Float) {
        val finished = ArrayList<ServiceSession>()
        for (s in sessions) {
            val c = s.customer
            if (c.done || c.task != Task.SERVED || c.seatedOn != s.station.id) {
                finished += s // customer left (e.g. the station was moved)
                continue
            }
            when (s.phase) {
                ServicePhase.WAITING_FOR_STAFF -> {
                    val worker = _staff.firstOrNull { it.lotId == s.door.lotId && it.session == null } ?: continue
                    worker.session = s
                    s.staff = worker
                    s.phase = ServicePhase.STAFF_WALKING
                    worker.walkTo(s.station.access ?: worker.tile, s.door)
                }
                ServicePhase.STAFF_WALKING -> {
                    val worker = s.staff ?: continue
                    if (worker.path.isEmpty()) {
                        worker.faceTowards(rectCenter(s.station.rect))
                        worker.heldItem = s.offeringId
                        s.advance(ServicePhase.PREPARING)
                    }
                }
                else -> {
                    s.elapsed += dt
                    if (s.elapsed < s.phaseLength()) continue
                    val worker = s.staff
                    when (s.phase) {
                        ServicePhase.PREPARING -> { worker?.heldItem = null; s.advance(ServicePhase.HANDING_OVER) }
                        ServicePhase.HANDING_OVER -> { c.heldItem = s.offeringId; s.advance(ServicePhase.SERVING) }
                        ServicePhase.SERVING -> { c.heldItem = null; s.advance(ServicePhase.HANDING_BACK) }
                        ServicePhase.HANDING_BACK -> { worker?.heldItem = s.offeringId; s.advance(ServicePhase.FINISHING) }
                        ServicePhase.FINISHING -> {
                            worker?.heldItem = null
                            c.gotService = true
                            c.chosenService?.let { service ->
                                events += CrowdEvent.Sale(s.door.lotId, c.profile, listOf(service), service.price, c.position)
                            }
                            finished += s
                            standUp(c)
                            dequeue(c)
                            leave(c)
                        }
                        else -> Unit
                    }
                }
            }
        }
        for (s in finished) {
            s.staff?.let { if (it.session === s) { it.session = null; it.heldItem = null; it.pause = 0.5f } }
            if (s.customer.heldItem == s.offeringId) s.customer.heldItem = null
        }
        sessions.removeAll(finished)
    }

    companion object {
        const val MAX_CART = 3
        const val REVIEW_CHANCE = 0.4f
        const val SHOPKEEPER_STAFF_ID = "shopkeeper"
        const val STAFF_SPEED = 1.4f
        const val STAFF_IDLE_MIN_SEC = 1f
        const val STAFF_IDLE_MAX_SEC = 3.5f
        const val MAX_PEOPLE = 9
        const val SPAWN_MIN_SEC = 1.6f
        const val SPAWN_MAX_SEC = 4.2f
        const val BROWSE_MIN_SEC = 1.5f
        const val BROWSE_MAX_SEC = 4f
        const val REST_MIN_SEC = 3f
        const val REST_MAX_SEC = 6f
        const val PAY_MIN_SEC = 1.5f
        const val PAY_MAX_SEC = 3f
        const val SERVICE_MIN_SEC = 4f
        const val SERVICE_MAX_SEC = 8f
        const val REST_CHANCE = 0.15f
        const val SERVICE_SHARE = 0.6f
        const val DISPLAY_PREFERENCE = 0.7f
        const val NEAR_LANE_SHARE = 0.65f
        const val EDGE_MARGIN = 1.5f
        private const val WARM_STEP = 0.1f

        fun nearLaneY(street: Street) = street.lotDepth + street.sidewalkRows / 2f
        fun farLaneY(street: Street) = street.roadStartRow + street.roadRows + street.sidewalkRows / 2f
    }
}

private fun GridRect.containsPoint(p: Vec2) = p.x >= x && p.y >= y && p.x < right && p.y < bottom

private fun rectCenter(r: GridRect) = Vec2(r.x + r.w / 2f, r.y + r.h / 2f)
