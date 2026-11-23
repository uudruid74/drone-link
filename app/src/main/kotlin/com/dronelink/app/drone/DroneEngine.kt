package com.dronelink.app.drone

import java.io.IOException

/**
 * The drone control engine — a faithful Kotlin port of the Python oracle
 * `drone-ctl/drone_daemon.py` (Drone class, 380 lines) and the matching
 * Drone class in `drone-ctl/drone_mcp.py`.
 *
 * OBSERVABLE PROTOCOL BEHAVIOR MUST NOT DRIFT. The Python files are the
 * contract: identical UDP framing (25Hz control loop, 1Hz heartbeat, C=128,
 * flags 0x01/0x02/0x04/0x08/0x10/0x20/0x80), identical packet bytes, identical
 * return shapes. Port, don't redesign.
 *
 * Targets the ORIGINAL drone endpoint 192.168.1.1:7099 (the
 * 127.0.0.1:17099 relay retarget in the repo copy of the daemon is
 * superseded — revert-note: v0.2 owns the bound socket in-app).
 *
 * v0.3.0 lifecycle (Evan/Gopher spec): the heartbeat runs LOCALLY and starts
 * with the service — it is NOT gated behind an MCP `connect`. [bindAndStartAutonomous]
 * is called by the service as soon as the drone network joins; MCP `connect`
 * only additionally starts the 25Hz control + rx loops. MCP `disconnect`
 * stops control/rx but leaves the autonomous heartbeat running.
 */

object DroneProtocol {
    const val DRONE_IP = "192.168.1.1"
    const val CTRL_PORT = 7099
    const val CTRL_PERIOD = 0.04          // 25 Hz control loop
    const val HB_PERIOD = 1.0             // heartbeat period
    const val C = 128                     // stick center
    val GEARS = intArrayOf(40, 60, 127)   // stick deflection scaling per speed gear (1/2/3)
    const val STALE = 0.4                 // no command for this long -> dead-man neutral

    const val FLAG_TAKEOFF = 0x01
    const val FLAG_LAND = 0x02
    const val FLAG_STOP = 0x04
    const val FLAG_FLIP = 0x08
    const val FLAG_HEADLESS = 0x10
    const val FLAG_RETURN = 0x20          // +32 isFastReturn (Return-to-Home), byte 6 flags
    const val FLAG_CALIBRATE = 0x80

    /** Throttle deflection (0..127) sent together with FLAG_TAKEOFF on the
     * TC(9B) variant. Flag alone is not enough — the drone ACKs the arm
     * flag but lifts only if the throttle axis is non-zero. Mid-range
     * (gear-2 scale) lift by default. */
    const val TAKEOFF_THROTTLE = 60

    // Telemetry auto-detect: first byte -> GL (21-byte) vs TC (9-byte)
    val GL_IDS = (90..101).toSet() + setOf(103, 82, 85, 88)

    val RTSP_URL = "rtsp://$DRONE_IP:7070/webcam"

    val NEUTRAL_AXES = intArrayOf(C, C, 0, C)
}

/** One normalized trajectory waypoint — mirrors the Python 5-tuple. */
data class Waypoint(
    val roll: Int,
    val pitch: Int,
    val throttle: Int,
    val yaw: Int,
    val duration: Double,
)

/**
 * UDP transport abstraction. The Android implementation binds the socket onto
 * the app-scoped drone network (network.bindSocket); tests use a recorder.
 */
interface DroneTransport {
    fun bind()
    fun send(data: ByteArray)
    /** Receive into buf; returns bytes received, or -1 on timeout/closed. */
    fun receive(buf: ByteArray): Int
    fun close()
    /**
     * Protocol probe: bind a fresh scoped UDP socket on [srcPort], send [data]
     * to the drone at [destPort], capture replies (up to ~2.5s). Returns hex
     * reply strings. Used to trial the vendor's video-engine envelopes.
     */
    fun probeSend(destPort: Int, srcPort: Int, data: ByteArray): List<String>
}

/**
 * Port of the Python Drone class. Threads mirror the Python daemon threads
 * (_hb, _ctl, _rx) and are daemon, started by [connect].
 *
 * @param forceVariant force 10=TC(9B) or a GL id; null = auto-detect.
 * @param now injectable clock in seconds (defaults to wall clock), for tests.
 * @param extraStatus optional transport-level facts appended to [status]
 *        (local socket address, bind mode, last RX source — packet evidence).
 * @param debugLog optional diagnostic sink (wired to the app's scrolling log).
 */
class DroneEngine(
    internal val transport: DroneTransport,
    private val forceVariant: Int? = null,
    private val extraStatus: () -> Map<String, Any?> = { emptyMap() },
    private val debugLog: (tag: String, msg: String) -> Unit = { _, _ -> },
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val auto: Boolean = forceVariant == null
    var variant: Int = forceVariant ?: 10

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var axes = DroneProtocol.NEUTRAL_AXES.copyOf()

    @Volatile
    var axesTs = 0.0

    @Volatile
    var gear = 1

    @Volatile
    var headless = false

    @Volatile
    var cam = 1

    private val pulses = java.util.concurrent.ConcurrentHashMap<String, Double>()

    @Volatile
    var lockUntil = 0.0

    /** Kill-switch state (emergency_land). Once set, the engine refuses all commands. */
    @Volatile
    var killed = false
        private set

    @Volatile
    var waypoints = mutableListOf<Waypoint>()

    @Volatile
    var wpStart = 0.0

    @Volatile
    var wpIndex = 0

    @Volatile
    var sent = 0

    @Volatile
    var rx = 0

    @Volatile
    var lastTelemetry: String? = null

    @Volatile
    private var running = false          // control + rx loops

    /** Test-observable control-loop state (running). */
    internal val controlRunning: Boolean get() = running

    @Volatile
    private var hbRunning = false        // heartbeat loop (autonomous)

    @Volatile
    private var hbAutonomous = false     // service-owned heartbeat survives MCP disconnect

    private val threads = mutableListOf<Thread>()

    // -- lifecycle ----------------------------------------------------------

    /**
     * Bind the UDP socket and start heartbeat + control + rx threads.
     * Heartbeat also runs standalone (see [bindAndStartAutonomous]).
     */
    @Synchronized
    fun connect() {
        // Evan 2026-11-18 "It's not working" — this early-returned when the
        // autonomous bind had already set `connected`, so the 25Hz stream NEVER
        // started and buttons were silently dead. Always (re)start the loops.
        if (!connected) {
            transport.bind()                   // throws IOException if no joined network
            connected = true
        }
        running = true
        hbRunning = true
        ensureThreads()
    }

    /**
     * v0.3.0: autonomous heartbeat. Called by the service the moment the drone
     * network joins, BEFORE any MCP connect. Sends `01 01` at 1Hz locally.
     * Survives MCP disconnects; only [shutdown] stops it.
     */
    @Synchronized
    fun bindAndStartAutonomous() {
        if (connected) {
            hbAutonomous = true
            hbRunning = true
            return
        }
        transport.bind()                       // throws IOException if no joined network
        hbAutonomous = true
        hbRunning = true
        connected = true
        ensureThreads()
    }

    /** Stop MCP-scoped control/rx loops. Autonomous heartbeat keeps running. */
    @Synchronized
    fun disconnect() {
        running = false
        if (!hbAutonomous) {
            hbRunning = false
        }
        if (!hbRunning) {
            connected = false
        }
    }

    /** Full teardown (service destroy): stop everything, close the socket. */
    @Synchronized
    fun shutdown() {
        running = false
        hbRunning = false
        hbAutonomous = false
        try {
            transport.close()
        } catch (e: IOException) {
            // Python swallows OSError here — mirror it.
        }
        connected = false
    }

    private fun ensureThreads() {
        if (threads.none { it.name == "engine-hb" && it.isAlive }) {
            threads += thread("engine-hb") { hbLoop() }
        }
        if (threads.none { it.name == "engine-ctl" && it.isAlive }) {
            threads += thread("engine-ctl") { ctlLoop() }
        }
        if (threads.none { it.name == "engine-rx" && it.isAlive }) {
            threads += thread("engine-rx") { rxLoop() }
        }
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true; start() }

    // -- low-level send ------------------------------------------------------

    fun raw(data: ByteArray) {
        try {
            transport.send(data)
            sent++
        } catch (e: IOException) {
            // Python swallows OSError here — mirror it.
        }
    }

    fun pulse(name: String, sec: Double) {
        pulses[name] = now() + sec
    }

    fun on(name: String): Boolean = (pulses[name] ?: 0.0) > now()

    // -- kill switch -----------------------------------------------------------

    /** Engage the emergency kill switch. All further commands refuse. */
    fun engageKillSwitch() {
        killed = true
        lockUntil = Double.POSITIVE_INFINITY
        clearTrajectory()
        pulses.clear()
        debugLog("engine", "KILL SWITCH engaged — MCP server stopping, neutral held until restart")
    }

    /** Clear the kill switch (button toggle "Enable MCP"). MCP tools re-enable. */
    fun disengageKillSwitch() {
        killed = false
        lockUntil = 0.0
        debugLog("engine", "kill switch cleared — MCP re-armed")
    }

    /** Every command path must pass through this. */
    private fun guardKill() {
        if (killed) {
            throw IllegalStateException("emergency_land kill switch engaged — restart the app to re-arm")
        }
    }

    // -- packet building ------------------------------------------------------

    /**
     * Build the next control packet. TC (variant 10) = 9 bytes,
     * GL (anything else) = 21 bytes. Byte-for-byte identical to the oracle.
     */
    fun packet(): ByteArray {
        guardKill()
        var r = axes[0]
        var p = axes[1]
        var t = axes[2]
        var y = axes[3]
        // clamp sticks to >= 1 so "center-ish" isn't read as 0 (which is coerced)
        if (r < 1) r = 1
        if (p < 1) p = 1
        if (y < 1) y = 1
        val fly = on("takeoff")
        val drop = on("land")
        val estop = on("estop")
        val gyro = on("gyro")
        val home = on("returnhome")

        if (variant != 10) {  // GL, 21 bytes
            val f1 = (if (fly || drop) 1 else 0) or (if (estop) 2 else 0) or (if (gyro) 4 else 0) or
                (if (home) DroneProtocol.FLAG_RETURN else 0)
            val f2 = (if (headless) 1 else 0) or 2   // bit1 = fixed-height (alt hold)
            val cs = (f1 xor (((p xor r) xor t) xor y)) xor f2
            val inner = ByteArray(20)
            inner[0] = 0x66.toByte()
            inner[1] = 0x14.toByte()
            inner[2] = r.toByte()
            inner[3] = p.toByte()
            inner[4] = t.toByte()
            inner[5] = y.toByte()
            inner[6] = (f1 and 255).toByte()
            inner[7] = (f2 and 255).toByte()
            inner[18] = (cs and 255).toByte()
            inner[19] = 0x99.toByte()
            return byteArrayOf(0x03) + inner
        }

        // TC, 9 bytes
        val fl = (if (fly) 1 else 0) +
            (if (drop) 2 else 0) +
            (if (estop) 4 else 0) +
            (if (headless) 16 else 0) +
            (if (home) DroneProtocol.FLAG_RETURN else 0) +
            (if (gyro) 128 else 0)
        // Takeoff lift: flag alone is not enough on TC(9B) — the drone ACKs
        // the arm flag but only lifts with a non-zero throttle axis. When the
        // takeoff pulse is active and no user throttle is commanded, send the
        // takeoff throttle together with the flag. (GL merges the land flag
        // into bit 0, so this must stay TC-only.)
        if (fly && variant == 10 && t == 0) {
            t = DroneProtocol.TAKEOFF_THROTTLE
        }
        val cs = (((r xor p) xor t) xor y) xor fl
        return byteArrayOf(3, 0x66, r.toByte(), p.toByte(), t.toByte(), y.toByte(),
            (fl and 255).toByte(), (cs and 255).toByte(), 0x99.toByte())
    }

    // -- background threads ------------------------------------------------------

    private fun hbLoop() {
        while (hbRunning) {
            raw(byteArrayOf(0x01, 0x01))
            Thread.sleep((DroneProtocol.HB_PERIOD * 1000).toLong())
        }
    }

    private fun ctlLoop() {
        var n = now()
        // Stream runs while BOUND (hbRunning), not just while a UI session is
        // active: the Python daemon streams 25Hz neutral frames from startup and
        // the drone is built for that (dead-man neutral). `running` gates USER
        // control only — without it the stream stays neutral but alive.
        while (hbRunning) {
            if (running) advanceTrajectory()
            if (!running || deadman()) {
                axes = DroneProtocol.NEUTRAL_AXES.copyOf()
            }
            raw(packet())
            n += DroneProtocol.CTRL_PERIOD
            val sleepMs = (n - now()) * 1000
            if (sleepMs > 0) Thread.sleep(sleepMs.toLong()) else Thread.yield()
        }
    }

    private fun rxLoop() {
        val buf = ByteArray(1024)
        while (hbRunning) {
            val len = try {
                transport.receive(buf)
            } catch (e: IOException) {
                continue
            }
            if (len <= 0) continue
            handleReceive(buf.copyOf(len))
        }
    }

    /** Mirrors the oracle's _rx body for one datagram; isolated for tests. */
    internal fun handleReceive(data: ByteArray) {
        rx++
        lastTelemetry = data.joinToString(" ") { "%02x".format(it) }
        debugLog("rx", "len=${data.size} bytes=$lastTelemetry")
        if (auto && rx == 1) {
            val b0 = data[0].toInt() and 0xFF
            variant = if (b0 !in DroneProtocol.GL_IDS) 10 else b0
        }
        // photo/video notifications -> ack so the app-equivalent stays in sync.
        // (Battery decode was removed 2026-11-23: the vendor firmware has no
        // battery telemetry — see vendor-battery-telemetry reference.)
    }

    internal fun deadman(): Boolean {
        if (killed) return true
        if (waypoints.isNotEmpty()) return false
        if (now() < lockUntil) return true
        return (now() - axesTs) > DroneProtocol.STALE
    }

    // -- trajectory --------------------------------------------------------------

    internal fun advanceTrajectory() {
        if (waypoints.isEmpty()) return
        if (wpStart == 0.0) wpStart = now()
        val elapsed = now() - wpStart
        var t = 0.0
        for (i in waypoints.indices) {
            t += waypoints[i].duration
            if (elapsed < t) {
                wpIndex = i
                val wp = waypoints[i]
                axes = intArrayOf(wp.roll, wp.pitch, wp.throttle, wp.yaw)
                axesTs = now()  // keep dead-man off during trajectory
                return
            }
        }
        // finished
        axes = DroneProtocol.NEUTRAL_AXES.copyOf()
        waypoints = mutableListOf()
        wpStart = 0.0
        wpIndex = 0
    }

    fun setTrajectory(rawWaypoints: List<Map<String, Any?>>) {
        val norm = mutableListOf<Waypoint>()
        for (w in rawWaypoints) {
            val r = num(w["roll"])
            val p = num(w["pitch"])
            val th = num(w["throttle"])
            val y = num(w["yaw"])
            val dur = w["duration"]?.let { num(it) } ?: 1.0
            norm += Waypoint(
                roll = (DroneProtocol.C + r.coerceIn(-1.0, 1.0) * DroneProtocol.GEARS[gear - 1]).toInt(),
                pitch = (DroneProtocol.C + p.coerceIn(-1.0, 1.0) * DroneProtocol.GEARS[gear - 1]).toInt(),
                throttle = (th.coerceIn(0.0, 1.0) * 127).toInt(),
                yaw = (DroneProtocol.C + y.coerceIn(-1.0, 1.0) * 127).toInt(),
                duration = dur,
            )
        }
        waypoints = norm
        wpStart = 0.0
        wpIndex = 0
    }

    fun clearTrajectory() {
        waypoints = mutableListOf()
        wpStart = 0.0
        wpIndex = 0
    }

    private fun num(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    // -- stick set -----------------------------------------------------------------

    /** Set raw stick axes, all in -1..1 (Python int() truncation mirrored). */
    fun setSticks(roll: Double, pitch: Double, throttle: Double, yaw: Double) {
        guardKill()
        axes = intArrayOf(
            (DroneProtocol.C + roll.coerceIn(-1.0, 1.0) * DroneProtocol.GEARS[gear - 1]).toInt(),
            (DroneProtocol.C + pitch.coerceIn(-1.0, 1.0) * DroneProtocol.GEARS[gear - 1]).toInt(),
            (throttle.coerceIn(0.0, 1.0) * 127).toInt(),
            (DroneProtocol.C + yaw.coerceIn(-1.0, 1.0) * 127).toInt(),
        )
        axesTs = now()
    }

    fun setGearRaw(value: Int) {
        gear = value.coerceIn(1, 3)
    }

    // -- status ----------------------------------------------------------------------

    fun status(): Map<String, Any?> = linkedMapOf(
        "connected" to connected,
        "variant" to (if (variant == 10) "TC(9B)" else "GL(21B)"),
        "gear" to gear,
        "headless" to headless,
        "cam" to cam,
        "axes" to axes.toList(),
        "sent" to sent,
        "rx" to rx,
        "last_telemetry" to lastTelemetry,
        "trajectory_active" to waypoints.isNotEmpty(),
        "trajectory_index" to wpIndex,
        "killed" to killed,
        "heartbeat_active" to hbRunning,
        "rtsp" to DroneProtocol.RTSP_URL,
    ) + extraStatus()

    // -- command handlers (mirror drone_daemon.py handle()) ------------------------------

    /**
     * Mirrors `_req`: ensure the control/rx loops are running. connect() is
     * idempotent and always sets running=true — call it UNCONDITIONALLY.
     * Gating on !connected was a bug: bindAndStartAutonomous() sets
     * connected=true without running=true, so every command after the
     * autonomous bind skipped connect() and the 25Hz loop stayed neutral
     * (no trajectory advance, no sticks — the no-lift/crash class).
     */
    fun requireConnected() {
        guardKill()
        connect()
        Thread.sleep(200)
    }

    fun cmdConnect(): Map<String, Any?> {
        guardKill()
        requireConnected()
        return linkedMapOf("ok" to true, "status" to status())
    }
    fun cmdDisconnect(): Map<String, Any?> {
        guardKill()
        disconnect()   // stop control/rx loops (autonomous heartbeat survives)
        return linkedMapOf("ok" to true, "status" to status())
    }

    fun cmdTakeoff(): Map<String, Any?> {
        requireConnected()
        pulse("takeoff", 1.0)
        return linkedMapOf(
            "ok" to true,
            "cmd" to "takeoff",
            "status" to status(),
            "verified" to false,
            "reason" to "pulse scheduled; execution unconfirmed until rx telemetry advances",
        )
    }

    fun cmdLand(): Map<String, Any?> {
        requireConnected()
        pulse("land", 1.0)
        return linkedMapOf(
            "ok" to true,
            "cmd" to "land",
            "status" to status(),
            "verified" to false,
            "reason" to "pulse scheduled; execution unconfirmed until rx telemetry advances",
        )
    }

    fun cmdEmergencyLand(): Map<String, Any?> {
        requireConnected()
        pulse("land", 4.0)
        engageKillSwitch()
        return linkedMapOf("ok" to true, "cmd" to "emergency_land", "status" to status())
    }

    fun cmdRearm(): Map<String, Any?> {
        disengageKillSwitch()
        return linkedMapOf("ok" to true, "cmd" to "rearm_mcp", "status" to status())
    }

    fun cmdFlyHome(): Map<String, Any?> {
        requireConnected()
        pulse("returnhome", 5.0)
        return linkedMapOf(
            "ok" to true,
            "cmd" to "fly_home",
            "status" to status(),
            "verified" to false,
            "reason" to "pulse scheduled; execution unconfirmed until rx telemetry advances",
        )
    }

    fun cmdFly(roll: Double, pitch: Double, throttle: Double, yaw: Double): Map<String, Any?> {
        requireConnected()
        setSticks(roll, pitch, throttle, yaw)
        return linkedMapOf(
            "ok" to true,
            "axes" to axes.toList(),
            "status" to status(),
            "verified" to false,
            "reason" to "sticks applied locally; aircraft confirmation requires rx telemetry",
        )
    }

    fun cmdTrajectory(waypoints: List<Map<String, Any?>>): Map<String, Any?> {
        requireConnected()
        setTrajectory(waypoints)
        return linkedMapOf("ok" to true, "waypoints" to waypoints.size, "status" to status())
    }

    fun cmdStopTrajectory(): Map<String, Any?> {
        clearTrajectory()
        axes = DroneProtocol.NEUTRAL_AXES.copyOf()
        return linkedMapOf("ok" to true, "status" to status())
    }

    fun cmdCamera(value: Int): Map<String, Any?> {
        requireConnected()
        cam = value
        raw(byteArrayOf(6, value.toByte()))
        return linkedMapOf(
            "ok" to true,
            "cam" to cam,
            "status" to status(),
            "verified" to false,
            "reason" to "UDP datagram sent; aircraft confirmation requires rx telemetry",
        )
    }

    /**
     * Send raw bytes over the connected control socket (debug/protocol-probe
     * only). Used to exercise vendor-side commands (e.g. the bl60xmjpeg
     * query/settings envelopes) without touching the engine's state machine.
     */
    fun rawUdpSend(hex: String): Map<String, Any?> {
        requireConnected()
        val clean = hex.replace(" ", "").replace("0x", "")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            return linkedMapOf(
                "ok" to false,
                "error" to "dataHex must be even-length hex (e.g. \"ef10080001000000\")",
            )
        }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        raw(bytes)
        return linkedMapOf(
            "ok" to true,
            "bytes" to bytes.size,
            "hex" to hex,
            "status" to status(),
        )
    }

    /**
     * Protocol-probe: bind a UDP socket on [srcPort] (drone-net address), send
     * [hex] to the drone at [destPort], and capture any replies for up to
     * ~2.5s. Used to trial the vendor's video-engine envelopes (0x20EF
     * command format) from the right source port.
     */
    fun udpProbeSend(destPort: Int, srcPort: Int, hex: String): Map<String, Any?> {
        val clean = hex.replace(" ", "").replace("0x", "")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            return linkedMapOf("ok" to false, "error" to "dataHex must be even-length hex")
        }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val replies = transport.probeSend(destPort, srcPort, bytes)
        return linkedMapOf(
            "ok" to true,
            "src_port" to srcPort,
            "dest_port" to destPort,
            "sent_bytes" to bytes.size,
            "replies" to replies,
        )
    }

    fun cmdHeadless(enabled: Boolean): Map<String, Any?> {
        requireConnected()
        headless = enabled
        return linkedMapOf(
            "ok" to true,
            "headless" to headless,
            "status" to status(),
            "verified" to false,
            "reason" to "local mode flag set; effective from next transmitted packet",
        )
    }

    fun cmdCalibrate(): Map<String, Any?> {
        requireConnected()
        pulse("gyro", 2.0)
        return linkedMapOf(
            "ok" to true,
            "cmd" to "calibrate",
            "status" to status(),
            "verified" to false,
            "reason" to "pulse scheduled; execution unconfirmed until rx telemetry advances",
        )
    }

    fun cmdSetGear(value: Int): Map<String, Any?> {
        setGearRaw(value)
        return linkedMapOf(
            "ok" to true,
            "gear" to gear,
            "status" to status(),
            "verified" to false,
            "reason" to "gear is local stick scaling; effective from next transmitted packet",
        )
    }

    fun cmdVideoUrl(): Map<String, Any?> =
        linkedMapOf("ok" to true, "url" to DroneProtocol.RTSP_URL)
}
