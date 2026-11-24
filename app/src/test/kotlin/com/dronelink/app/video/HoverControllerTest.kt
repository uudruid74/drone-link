package com.dronelink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HoverControllerTest {

    /** Low-frequency structured pattern — blob + ring + gentle texture. */
    private fun patternAt(i: Int, j: Int): Int {
        val dx = i - 8
        val dy = j - 8
        val r2 = dx * dx + dy * dy
        val blob = if (r2 < 25) 210 else 35
        val ring = ((dx * 3 + dy * 5) % 7) * 8
        return (blob + ring + ((i * j) % 13) * 4) and 0xFF
    }

    private fun base(): ByteArray {
        val a = ByteArray(16 * 16)
        for (y in 0 until 16) for (x in 0 until 16) a[y * 16 + x] = patternAt(x, y).toByte()
        return a
    }

    /**
     * Simulate an apparent-size change: k > 1 zooms in (features larger —
     * descending), k < 1 zooms out (features smaller — climbing).
     */
    private fun zoom(src: ByteArray, k: Double): ByteArray {
        val out = ByteArray(16 * 16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val sx = (x / k).roundToInt().coerceIn(0, 15)
                val sy = (y / k).roundToInt().coerceIn(0, 15)
                out[y * 16 + x] = src[sy * 16 + sx]
            }
        }
        return out
    }

    @Test fun identicalFramesDeltaNearZero() {
        val c = HoverController()
        val a = base()
        val d = c.observe(a, a.copyOf())
        assertEquals(0, d)
        assertTrue(c.summary["delta"] as Int == 0)
    }

    @Test fun expandedSceneDescentAddsThrottle() {
        // curr content is LARGER than prev ⇒ descending ⇒ throttle up (delta > 0)
        val c = HoverController()
        val a = base()
        val curr = zoom(a, 1.1)
        val d = c.observe(a, curr)
        assertTrue("expected positive delta for descent, got $d", d > 0)
        assertTrue((c.summary["scale"] as Double) > 1.0)
    }

    @Test fun contractedSceneClimbReducesThrottle() {
        // curr content is SMALLER than prev ⇒ rising ⇒ throttle down (delta < 0)
        val c = HoverController()
        val a = base()
        val curr = zoom(a, 0.9)
        val d = c.observe(a, curr)
        assertTrue("expected negative delta for climb, got $d", d < 0)
        assertTrue((c.summary["scale"] as Double) < 1.0)
    }

    @Test fun deltaClampedAndReset() {
        val c = HoverController(deltaClamp = 3, integralClamp = 2.0)
        val a = base()
        val d = c.observe(a, zoom(a, 1.1))
        assertTrue(kotlin.math.abs(d) <= 3)
        c.reset()
        assertEquals(0, c.summary["delta"] as Int)
        assertEquals(1.0, c.summary["scale"] as Double, 0.001)
    }

    @Test fun sizeMismatchThrows() {
        val c = HoverController()
        var threw = false
        try {
            c.observe(base(), ByteArray(8 * 8))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun fastCadenceSlowDriftClimbDetected() {
        // 10 Hz output cadence over a 0.3 s reference window. Synthetic blob
        // pattern is low-frequency — the live textured floor resolves far
        // smaller deltas (v0.6.12 flight saw ±2.5% at 1 s spacing); 6% is a
        // robust lower bound for this probe.
        val c = HoverController(dt = 0.3)
        val a = base()
        c.observe(a, a.copyOf())
        val d = c.observe(a, zoom(a, 0.94))
        assertTrue("expected negative delta for climb, got $d", d < 0)
        assertTrue((c.summary["vel"] as Double) > 0.0)
    }

    @Test fun velocityNormalizedByDt() {
        val slow = HoverController(dt = 0.8)
        val fast = HoverController(dt = 0.1)
        val a = base()
        val curr = zoom(a, 1.06)
        slow.observe(a, curr)
        fast.observe(a, curr)
        val vSlow = slow.summary["vel"] as Double
        val vFast = fast.summary["vel"] as Double
        // same scene pair ⇒ same scale ⇒ velocity proxy scales inversely with dt
        assertEquals(vSlow * 8.0, vFast, 1e-9)
        assertTrue(vSlow < 0.0 && vFast < 0.0)
    }

    @Test fun derivativeTermDampsFromSecondSample() {
        val c = HoverController()
        val a = base()
        c.observe(a, zoom(a, 1.05))   // descent starts — no derivative on first sample
        assertEquals(0.0, c.summary["dterm"] as Double, 1e-9)
        c.observe(a, zoom(a, 0.95))   // reversal — derivative opposes the error trend
        val dterm = c.summary["dterm"] as Double
        assertTrue("expected damping dterm < 0, got $dterm", dterm < 0.0)
    }

    @Test fun summaryExposesTuning() {
        val c = HoverController()
        assertEquals(50.0, c.summary["kp"] as Double, 1e-9)
        assertEquals(15.0, c.summary["kd"] as Double, 1e-9)
        assertEquals(0.3, c.summary["dt"] as Double, 1e-9)
    }
}
