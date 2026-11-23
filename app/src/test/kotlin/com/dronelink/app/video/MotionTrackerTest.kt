package com.dronelink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {

    private fun luma(fill: Int): ByteArray = ByteArray(16 * 16) { fill.toByte() }

    private fun lumaWithNoise(base: Int, amount: Int): ByteArray =
        ByteArray(16 * 16) { (base + ((it * 5) % (amount + 1))).toByte() }

    @Test fun firstObservationHasNoDiff() {
        var clock = 100.0
        val t = MotionTracker { clock }
        t.observeLuma(luma(128))
        assertEquals(0.0, t.summary["diff"]!! as Double, 0.0)
        assertEquals("stable", t.summary["state"])
        assertEquals(1, t.summary["observed"])
    }

    @Test fun identicalFramesAreStable() {
        val t = MotionTracker()
        t.observeLuma(luma(128))
        t.observeLuma(luma(128))
        assertEquals("stable", t.summary["state"])
        assertEquals(0.0, t.summary["diff"]!! as Double, 0.0)
    }

    @Test fun tinyNoiseIsStable() {
        val t = MotionTracker()
        t.observeLuma(luma(50))
        t.observeLuma(lumaWithNoise(50, 1))   // ±1 of 255 → ~0.00065
        assertEquals("stable", t.summary["state"])
    }

    @Test fun moderateChangeIsDrifting() {
        val t = MotionTracker()
        t.observeLuma(luma(40))
        t.observeLuma(lumaWithNoise(40, 6))   // ~0.0049
        assertEquals("drifting", t.summary["state"])
    }

    @Test fun strongChangeIsMotion() {
        val t = MotionTracker()
        t.observeLuma(luma(40))
        t.observeLuma(luma(200))              // 160/255 ≈ 0.63
        assertEquals("motion", t.summary["state"])
        assertTrue(t.summary["diff"]!! as Double > 0.3)
    }

    @Test fun mismatchSizeThrows() {
        val t = MotionTracker()
        t.observeLuma(ByteArray(16 * 16))
        var threw = false
        try {
            t.observeLuma(ByteArray(8 * 8))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
