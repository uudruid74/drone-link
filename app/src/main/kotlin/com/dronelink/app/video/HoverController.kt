package com.dronelink.app.video

/**
 * Camera-feedback altitude hold (Evan 2026-11-23, "camera feedback is likely
 * the best approach"). The drone has no altitude/IMU telemetry, so the camera
 * is the only closed-loop sensor.
 *
 * Principle: as the drone rises, ground features shrink (scene contracts); as
 * it descends, they grow (scene expands). Between two consecutive luma
 * thumbnails we estimate the scale factor s that best matches the previous
 * frame to the current one (nearest-neighbour resize, MAD). s < 1 ⇒ scene
 * shrank ⇒ rising; s > 1 ⇒ descending. Vertical-velocity estimate vel = 1 - s
 * (clamped), smoothed with an EMA, then fed to a PI controller that adjusts the
 * hover throttle around the base hold value.
 *
 * Sign conventions: delta > 0 ⇒ more throttle (counter a descent); delta < 0
 * ⇒ less throttle (counter a climb). Output is a small integer delta the caller
 * adds to the base hold throttle.
 *
 * Pure-Kotlin core (JVM-testable); the frame plumbing lives in RtspClient.
 */
class HoverController(
    private val emaAlpha: Double = 0.3,
    private val kp: Double = 140.0,
    private val ki: Double = 25.0,
    private val dt: Double = 0.8,
    private val deltaClamp: Int = 24,
    private val integralClamp: Double = 8.0,
    private val deadband: Double = 0.004,
) {
    private val scales = doubleArrayOf(0.95, 0.975, 1.0, 1.025, 1.05)

    private var emaVel = 0.0
    private var integral = 0.0
    private var lastDelta = 0
    private var lastScale = 1.0
    private var lastVel = 0.0
    private var lastErr = 0.0
    private var observed = 0

    val summary: Map<String, Any?> get() = linkedMapOf(
        "state" to (if (kotlin.math.abs(lastDelta) <= 0) "hold" else "adjusting"),
        "scale" to lastScale,
        "vel" to lastVel,
        "ema" to emaVel,
        "err" to lastErr,
        "delta" to lastDelta,
        "integral" to integral,
        "observed" to observed,
    )

    /** Reset the filter (new flight / telemetry gap). */
    fun reset() {
        emaVel = 0.0
        integral = 0.0
        lastDelta = 0
        lastScale = 1.0
        lastVel = 0.0
        lastErr = 0.0
    }

    /**
     * Feed two consecutive luma thumbnails (same size).
     * @return suggested throttle delta (Int) to add to the base hold.
     */
    fun observe(prev: ByteArray, curr: ByteArray): Int {
        require(prev.size == curr.size) { "thumbnail size mismatch" }
        val n = prev.size
        val side = kotlin.math.sqrt(n.toDouble()).toInt()

        // 1) scale estimate (tie-break prefers 1.0 — identical frames ⇒ static)
        var bestS = 1.0
        var bestMad = Double.MAX_VALUE
        for (s in scales) {
            val mad = madAfterResize(prev, curr, side, s)
            val better = mad < bestMad - 1e-9 ||
                (kotlin.math.abs(mad - bestMad) <= 1e-9 && kotlin.math.abs(s - 1.0) < kotlin.math.abs(bestS - 1.0))
            if (better) {
                bestMad = mad
                bestS = s
            }
        }
        lastScale = bestS
        lastVel = (1.0 - bestS).coerceIn(-0.05, 0.05)
        emaVel = emaAlpha * lastVel + (1.0 - emaAlpha) * emaVel

        // 2) PI
        val err = -emaVel
        lastErr = err
        if (kotlin.math.abs(err) < deadband) {
            integral = 0.0
            lastDelta = 0
        } else {
            integral = (integral + ki * err * dt).coerceIn(-integralClamp, integralClamp)
            var d = (kp * err + integral).toInt()
            d = d.coerceIn(-deltaClamp, deltaClamp)
            lastDelta = d
        }
        observed++
        return lastDelta
    }

    private fun madAfterResize(prev: ByteArray, curr: ByteArray, side: Int, s: Double): Double {
        var sum = 0L
        for (y in 0 until side) {
            for (x in 0 until side) {
                val sx = ((x + 0.5) / s - 0.5).coerceIn(0.0, (side - 1).toDouble())
                val sy = ((y + 0.5) / s - 0.5).coerceIn(0.0, (side - 1).toDouble())
                // bilinear sample — distinct MADs per scale (nearest-neighbour
                // truncation ties scales together at 16×16 and kills the signal)
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = minOf(x0 + 1, side - 1)
                val y1 = minOf(y0 + 1, side - 1)
                val fx = sx - x0
                val fy = sy - y0
                val p00 = prev[y0 * side + x0].toInt() and 0xFF
                val p10 = prev[y0 * side + x1].toInt() and 0xFF
                val p01 = prev[y1 * side + x0].toInt() and 0xFF
                val p11 = prev[y1 * side + x1].toInt() and 0xFF
                val pv = (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy
                val cv = curr[y * side + x].toInt() and 0xFF
                sum += kotlin.math.abs(pv - cv).toLong()
            }
        }
        return sum.toDouble() / (side * side * 255.0)
    }
}
