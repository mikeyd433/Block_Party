package com.blockparty.core.anim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transform applied to a flat paper cutout, pivoting around its bottom-center (its "feet").
 *
 * @property lift vertical lift as a fraction of the cutout's height (positive = up).
 * @property scaleX horizontal scale (squash and stretch). Facing flips are applied separately.
 * @property scaleY vertical scale.
 * @property rotationDeg sway angle in degrees, clockwise.
 */
data class CutoutPose(
    val lift: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
) {
    /** Layers another pose on top of this one (e.g. a hop during a walk). */
    operator fun plus(other: CutoutPose) = CutoutPose(
        lift = lift + other.lift,
        scaleX = scaleX * other.scaleX,
        scaleY = scaleY * other.scaleY,
        rotationDeg = rotationDeg + other.rotationDeg,
    )

    companion object {
        val Rest = CutoutPose()
    }
}

/**
 * Procedural motion that turns one flat drawing into an animated character or prop:
 * walking bob, squash and stretch, sway and a small hop. Everything here is a pure
 * function of time/distance so it can be tested and later replayed identically on
 * every device.
 */
object ProceduralAnimator {
    private const val TWO_PI = (2 * PI).toFloat()

    /** Distance in tiles covered by one step. */
    const val STRIDE_TILES = 0.45f

    /** Slow breathing and a gentle sway while standing still. [seed] desynchronises cutouts. */
    fun idle(timeSec: Float, seed: Float = 0f, intensity: Float = 1f): CutoutPose {
        val breath = sin(TWO_PI * 0.45f * timeSec + seed)
        val sway = sin(TWO_PI * 0.23f * timeSec + seed * 1.7f)
        val scaleY = 1f + 0.018f * breath * intensity
        return CutoutPose(
            lift = 0f,
            scaleX = conserveArea(scaleY),
            scaleY = scaleY,
            rotationDeg = 1.4f * sway * intensity,
        )
    }

    /**
     * Walking: each step lifts the cutout in an arc, squashes it on foot contact and
     * stretches it slightly at the top of the arc, while rocking side to side.
     *
     * @param distanceTiles total distance walked so far; drives the step cycle so steps
     * match ground speed.
     */
    fun walk(distanceTiles: Float, seed: Float = 0f): CutoutPose {
        val phase = (distanceTiles / STRIDE_TILES) * PI.toFloat() + seed
        val arc = abs(sin(phase)) // 0 at foot contact, 1 at top of the step
        val contact = 1f - arc
        val scaleY = 1f - 0.07f * contact * contact + 0.035f * arc
        return CutoutPose(
            lift = 0.06f * arc,
            scaleX = conserveArea(scaleY),
            scaleY = scaleY,
            rotationDeg = 5f * sin(phase),
        )
    }

    /**
     * A small hop, e.g. when tapped or when a sale happens.
     * @param progress 0..1 through the hop; values outside that range return [CutoutPose.Rest].
     */
    fun hop(progress: Float, height: Float = 0.22f): CutoutPose {
        if (progress <= 0f || progress >= 1f) return CutoutPose.Rest
        val windUp = 0.18f
        val land = 0.85f
        val scaleY = when {
            progress < windUp -> 1f - 0.16f * sin(PI.toFloat() * progress / windUp) // crouch
            progress < land -> {
                val t = (progress - windUp) / (land - windUp)
                1f + 0.12f * sin(PI.toFloat() * t) // stretch in the air
            }
            else -> 1f - 0.12f * sin(PI.toFloat() * (progress - land) / (1f - land)) // landing squash
        }
        val lift = if (progress in windUp..land) {
            val t = (progress - windUp) / (land - windUp)
            4f * height * t * (1f - t)
        } else 0f
        return CutoutPose(lift = lift, scaleX = conserveArea(scaleY), scaleY = scaleY)
    }

    /** A quick wobble when an object is first placed. [progress] 0..1. */
    fun placeWobble(progress: Float): CutoutPose {
        if (progress <= 0f || progress >= 1f) return CutoutPose.Rest
        val decay = 1f - progress
        val osc = sin(TWO_PI * 2.5f * progress)
        val scaleY = 1f + 0.1f * osc * decay
        return CutoutPose(scaleX = conserveArea(scaleY), scaleY = scaleY, rotationDeg = 3f * osc * decay)
    }

    /** Keeps the cutout's area roughly constant while squashing/stretching. */
    private fun conserveArea(scaleY: Float): Float = 1f / sqrt(scaleY)
}
