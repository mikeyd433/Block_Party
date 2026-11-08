package com.blockparty.core

import com.blockparty.core.anim.CutoutPose
import com.blockparty.core.anim.ProceduralAnimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimatorTest {
    @Test
    fun hopIsRestOutsideItsDuration() {
        assertEquals(CutoutPose.Rest, ProceduralAnimator.hop(0f))
        assertEquals(CutoutPose.Rest, ProceduralAnimator.hop(1f))
        assertEquals(CutoutPose.Rest, ProceduralAnimator.hop(-2f))
    }

    @Test
    fun hopCrouchesThenLiftsThenLands() {
        val crouch = ProceduralAnimator.hop(0.09f)
        assertTrue(crouch.scaleY < 1f && crouch.lift == 0f, "wind-up squashes on the ground")
        val air = ProceduralAnimator.hop(0.5f)
        assertTrue(air.lift > 0.15f && air.scaleY > 1f, "mid-hop is lifted and stretched")
        val land = ProceduralAnimator.hop(0.92f)
        assertTrue(land.scaleY < 1f && land.lift == 0f, "landing squashes")
    }

    @Test
    fun walkBobsOncePerStride() {
        val stride = ProceduralAnimator.STRIDE_TILES
        val contact = ProceduralAnimator.walk(0f)
        val top = ProceduralAnimator.walk(stride / 2f)
        val nextContact = ProceduralAnimator.walk(stride)
        assertTrue(abs(contact.lift) < 1e-4f)
        assertTrue(top.lift > 0.05f)
        assertTrue(abs(nextContact.lift) < 1e-4f)
        assertTrue(contact.scaleY < 1f, "squash on foot contact")
        assertTrue(contact.scaleX > 1f, "squash widens the cutout")
    }

    @Test
    fun idleStaysSubtle() {
        for (i in 0..100) {
            val p = ProceduralAnimator.idle(i * 0.1f, seed = 1.3f)
            assertTrue(abs(p.scaleY - 1f) < 0.03f)
            assertTrue(abs(p.rotationDeg) < 2f)
            assertEquals(0f, p.lift)
        }
    }

    @Test
    fun posesCombine() {
        val a = CutoutPose(lift = 0.1f, scaleX = 2f, scaleY = 0.5f, rotationDeg = 3f)
        val b = CutoutPose(lift = 0.2f, scaleX = 0.5f, scaleY = 2f, rotationDeg = -1f)
        val c = a + b
        assertTrue(abs(c.lift - 0.3f) < 1e-6f)
        assertEquals(1f, c.scaleX)
        assertEquals(1f, c.scaleY)
        assertEquals(2f, c.rotationDeg)
    }
}
