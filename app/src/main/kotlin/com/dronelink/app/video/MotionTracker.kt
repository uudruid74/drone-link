package com.dronelink.app.video

/**
 * Camera motion confirmation (Evan 2026-11-23): the drone has no
 * position/altitude/IMU telemetry, so the camera is the only sensor that can
 * confirm a maneuver happened. This computes a coarse frame-difference signal
 * between consecutive downscaled luma frames.
 *
 * Pure-Kotlin core (testable on the JVM). The Android side builds the luma
 * thumbnails via [JpegThumbnail]; feed them to [observeLuma].
 *
 * Classification thresholds (coarse, tunable):
 *  - stable    diff < 0.008  (sensor noise / truly still)
 *  - drifting  diff < 0.03   (slow drift — e.g. a coasting drone)
 *  - motion    diff >= 0.03  (strong change — lift/land/turn)
 */
class MotionTracker(
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private var prev: ByteArray? = null
    private var lastDiff = 0.0
    private var lastState = "stable"
    private var lastAt = 0.0
    private var observed = 0

    val summary: Map<String, Any?> get() = linkedMapOf(
        "state" to lastState,
        "diff" to lastDiff,
        "at" to lastAt,
        "observed" to observed,
    )

    /** Feed one luma thumbnail (same size each time); diffs vs the previous. */
    fun observeLuma(luma: ByteArray) {
        val p = prev
        if (p != null) {
            require(p.size == luma.size) { "thumbnail size mismatch" }
            var sum = 0L
            for (i in luma.indices) {
                sum += kotlin.math.abs((luma[i].toInt() and 0xFF) - (p[i].toInt() and 0xFF))
            }
            lastDiff = sum.toDouble() / (luma.size * 255.0)
            lastState = when {
                lastDiff >= 0.03 -> "motion"
                lastDiff >= 0.008 -> "drifting"
                else -> "stable"
            }
            lastAt = now()
        }
        prev = luma.copyOf()
        observed++
    }
}

/** Android-side JPEG -> luma thumbnail (16×16). Uses BitmapFactory. */
object JpegThumbnail {
    /**
     * Decode a JPEG and return a 16×16 luma (Y) thumbnail. Uses
     * inSampleSize=4 so the decode reads ~1/16 of the source pixels — the
     * camera-feedback loop now runs at up to 10 Hz and full decodes
     * (20–40 ms) would starve the RTP receiver thread.
     */
    fun decode(jpeg: ByteArray, size: Int = 16): ByteArray {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: return ByteArray(size * size)
        val scaled = if (bmp.width == size && bmp.height == size) {
            bmp
        } else {
            android.graphics.Bitmap.createScaledBitmap(bmp, size, size, true)
        }
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        val luma = ByteArray(size * size)
        for (i in px.indices) {
            val c = px[i]
            // Rec.601 luma — cheap, good enough for diffing.
            val y = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8)
            luma[i] = y.toByte()
        }
        return luma
    }
}
