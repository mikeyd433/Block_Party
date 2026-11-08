package com.blockparty.core.art

import com.blockparty.core.geometry.Vec2

object StrokeOps {
    /**
     * Drops input points closer than [minDistance] to the previously kept point, which keeps
     * freehand strokes light without visibly changing them. The last point is always kept.
     */
    fun thin(points: List<Vec2>, minDistance: Float): List<Vec2> {
        if (points.size <= 2) return points
        val minSq = minDistance * minDistance
        val out = ArrayList<Vec2>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            val p = points[i]
            val last = out.last()
            val dx = p.x - last.x
            val dy = p.y - last.y
            if (dx * dx + dy * dy >= minSq) out.add(p)
        }
        out.add(points.last())
        return out
    }
}
