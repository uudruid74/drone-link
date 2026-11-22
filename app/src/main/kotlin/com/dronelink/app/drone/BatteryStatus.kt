package com.dronelink.app.drone

/**
 * Process-wide battery telemetry, decoded from the drone's UDP status packets
 * (vendor decode: data[2] marker `M`/0x4D -> hex digit byte at data[3];
 * `X`/0x58 -> hex digit byte at data[4]; bytes are stored as-is, so levelRaw
 * IS the byte value). Written by DroneEngine.handleReceive, read by
 * MainActivity and the MCP `battery` tool.
 *
 * Scaling to percent is NOT yet calibrated (needs a live read against a known
 * charge state) — consumers should treat [levelRaw] as the raw vendor value.
 */
object BatteryStatus {

    /** Raw vendor battery byte (e.g. 0x46 = 70), or null if never seen. */
    @Volatile
    var levelRaw: Int? = null

    /** Marker byte that carried the value: 'M' or 'X'. */
    @Volatile
    var marker: Char? = null

    /** Full packet hex (for diagnostics / scaling calibration). */
    @Volatile
    var rawHex: String? = null

    @Volatile
    var updatedAtMillis: Long = 0L

    val ageSeconds: Long
        get() = if (updatedAtMillis == 0L) -1L else (System.currentTimeMillis() - updatedAtMillis) / 1000L

    fun update(marker: Char, level: Int, packetHex: String) {
        this.marker = marker
        this.levelRaw = level
        this.rawHex = packetHex
        this.updatedAtMillis = System.currentTimeMillis()
    }

    fun reset() {
        marker = null
        levelRaw = null
        rawHex = null
        updatedAtMillis = 0L
    }

    fun summary(): Map<String, Any?> = linkedMapOf(
        "ok" to (levelRaw != null),
        "level_raw" to levelRaw,
        "marker" to marker?.toString(),
        "age_seconds" to ageSeconds,
        "packet_hex" to rawHex,
    )
}
