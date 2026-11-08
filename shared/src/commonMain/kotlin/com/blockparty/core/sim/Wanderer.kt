package com.blockparty.core.sim

import com.blockparty.core.geometry.Vec2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A character that strolls between random free tiles, pausing in between.
 * Positions are continuous grid coordinates; tile (x, y) has its center at (x + 0.5, y + 0.5).
 */
class Wanderer(
    start: Vec2,
    private val speedTilesPerSec: Float = 1.3f,
    private val random: Random = Random.Default,
) {
    var position: Vec2 = start
        private set

    /** Total distance walked, drives the walking animation cycle. */
    var distanceWalked: Float = 0f
        private set

    var facingRight: Boolean = true
        private set

    val isWalking: Boolean get() = path.isNotEmpty()

    private var path: ArrayDeque<Vec2> = ArrayDeque()
    private var pauseLeft: Float = random.nextFloat() * 2f

    /** Advances the simulation by [dt] seconds. [freeTiles] are the tiles it may stand on. */
    fun update(dt: Float, freeTiles: Set<Pair<Int, Int>>) {
        if (freeTiles.isEmpty()) {
            path.clear()
            return
        }
        val here = currentTile()
        if (here !in freeTiles) {
            // Something was placed on top of us: hop to the nearest free tile.
            val nearest = freeTiles.minBy { (tx, ty) -> sq(tx - here.first) + sq(ty - here.second) }
            position = center(nearest)
            path.clear()
        }

        if (path.isEmpty()) {
            pauseLeft -= dt
            if (pauseLeft <= 0f) {
                val goal = freeTiles.elementAt(random.nextInt(freeTiles.size))
                path = ArrayDeque(findPath(currentTile(), goal, freeTiles).map(::center))
                pauseLeft = 0.8f + random.nextFloat() * 2.5f
            }
            return
        }

        var remaining = speedTilesPerSec * dt
        while (remaining > 0f && path.isNotEmpty()) {
            val target = path.first()
            val delta = target - position
            val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
            // Screen-space horizontal direction is (dx - dy) in an isometric view.
            val screenDx = delta.x - delta.y
            if (screenDx > 0.01f) facingRight = true else if (screenDx < -0.01f) facingRight = false
            if (dist <= remaining) {
                position = target
                distanceWalked += dist
                remaining -= dist
                path.removeFirst()
            } else {
                position = position + delta * (remaining / dist)
                distanceWalked += remaining
                remaining = 0f
            }
        }
    }

    private fun currentTile(): Pair<Int, Int> = position.x.toInt() to position.y.toInt()

    companion object {
        fun center(tile: Pair<Int, Int>) = Vec2(tile.first + 0.5f, tile.second + 0.5f)

        private fun sq(v: Int) = v * v

        /** Breadth-first path over 4-connected free tiles, excluding the start tile. */
        fun findPath(start: Pair<Int, Int>, goal: Pair<Int, Int>, free: Set<Pair<Int, Int>>): List<Pair<Int, Int>> {
            if (start == goal || goal !in free) return emptyList()
            val cameFrom = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(start)
            cameFrom[start] = start
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur == goal) break
                for ((dx, dy) in NEIGHBOURS) {
                    val next = cur.first + dx to cur.second + dy
                    if (next in free && next !in cameFrom) {
                        cameFrom[next] = cur
                        queue.add(next)
                    }
                }
            }
            if (goal !in cameFrom) return emptyList()
            val result = ArrayList<Pair<Int, Int>>()
            var node = goal
            while (node != start) {
                result.add(node)
                node = cameFrom.getValue(node)
            }
            result.reverse()
            return result
        }

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
