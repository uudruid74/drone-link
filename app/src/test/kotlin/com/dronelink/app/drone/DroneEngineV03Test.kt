package com.dronelink.app.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.0 engine behavior: fly_home flag (+32 byte 6), emergency kill switch,
 * autonomous heartbeat lifecycle, diagnostics hooks.
 */
class DroneEngineV03Test {

    private class RecordingTransport : DroneTransport {
        val sent = mutableListOf<ByteArray>()
        var bindCalls = 0
        override fun bind() { bindCalls++ }
        override fun send(data: ByteArray) { sent += data }
        override fun receive(buf: ByteArray): Int = -1
        override fun close() {}
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    private fun engine(forceVariant: Int? = null, transport: RecordingTransport = RecordingTransport()): Pair<DroneEngine, RecordingTransport> =
        DroneEngine(transport, forceVariant) to transport

    // ---- fly_home flag (+32, byte 6) ----

    @Test fun tcReturnHomeFlagByte6() {
        val (d, _) = engine(10)
        d.axes = intArrayOf(DroneProtocol.C, DroneProtocol.C, 0, DroneProtocol.C)
        d.pulse("returnhome", 1.0)
        assertEquals("03 66 80 80 00 80 20 a0 99", hex(d.packet()))
    }

    @Test fun gl82ReturnHomeFlagByte6() {
        val (d, _) = engine(82)
        d.axes = intArrayOf(128, 128, 0, 128)
        d.pulse("returnhome", 1.0)
        assertEquals("03 66 14 80 80 00 80 20 02 00 00 00 00 00 00 00 00 00 00 a2 99", hex(d.packet()))
    }

    @Test fun cmdFlyHomePulsesAndExpires() {
        var clock = 100.0
        val d = DroneEngine(RecordingTransport(), 10) { clock }
        d.axes = intArrayOf(128, 128, 0, 128)
        val res = d.cmdFlyHome()
        assertEquals("fly_home", res["cmd"])
        assertTrue(d.on("returnhome"))
        assertTrue(d.packet()[6].toInt() and 0x20 != 0)
        clock = 106.0  // 5s pulse expired
        assertFalse(d.on("returnhome"))
        assertEquals(0, d.packet()[6].toInt() and 0x20)
    }

    // ---- takeoff lift: flag + throttle together (TC 9B) ----

    @Test fun tcTakeoffSendsThrottleWithFlag() {
        val (d, _) = engine(10)
        d.axes = intArrayOf(DroneProtocol.C, DroneProtocol.C, 0, DroneProtocol.C)
        d.pulse("takeoff", 1.0)
        val pkt = d.packet()
        assertEquals(1, pkt[6].toInt() and 0x01)   // FLAG_TAKEOFF
        assertEquals(DroneProtocol.TAKEOFF_THROTTLE.toByte(), pkt[4])  // throttle axis
    }

    @Test fun tcTakeoffRespectsUserThrottle() {
        val (d, _) = engine(10)
        d.axes = intArrayOf(DroneProtocol.C, DroneProtocol.C, 30, DroneProtocol.C)
        d.pulse("takeoff", 1.0)
        val pkt = d.packet()
        assertEquals(30.toByte(), pkt[4])            // user throttle preserved
    }

    @Test fun glTakeoffDoesNotOverrideThrottle() {
        // GL merges the land flag into bit 0, so takeoff-throttle synthesis
        // must never apply to the 21-byte variant. Throttle is byte 5 in GL.
        val (d, _) = engine(82)
        d.axes = intArrayOf(128, 128, 0, 128)
        d.pulse("takeoff", 1.0)
        assertEquals(0.toByte(), d.packet()[5])
    }

    @Test fun tcTakeoffThrottleExpiresWithPulse() {
        var clock = 100.0
        val d = DroneEngine(RecordingTransport(), 10) { clock }
        d.pulse("takeoff", 1.0)
        assertEquals(DroneProtocol.TAKEOFF_THROTTLE.toByte(), d.packet()[4])
        clock = 101.1  // pulse expired
        assertEquals(0.toByte(), d.packet()[4])
    }

    // ---- emergency_land kill switch ----

    @Test fun emergencyLandEngagesKillSwitch() {
        val (d, _) = engine(10)
        d.setSticks(0.5, 0.0, 0.3, 0.0)
        val res = d.cmdEmergencyLand()
        assertEquals("emergency_land", res["cmd"])
        assertEquals(true, res["status"]?.let { (it as Map<*, *>)["killed"] })
        assertTrue(d.killed)
        assertTrue(d.deadman())
        assertTrue(d.lockUntil == Double.POSITIVE_INFINITY)
    }

    @Test fun killedEngineRefusesCommands() {
        val (d, _) = engine(10)
        d.cmdEmergencyLand()
        try {
            d.cmdTakeoff()
            throw AssertionError("cmdTakeoff must refuse after kill switch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kill switch"))
        }
        try {
            d.packet()
            throw AssertionError("packet() must refuse after kill switch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kill switch"))
        }
        try {
            d.setSticks(0.1, 0.0, 0.1, 0.0)
            throw AssertionError("setSticks must refuse after kill switch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kill switch"))
        }
        // deadman stays true forever (neutral held)
        assertTrue(d.deadman())
    }

    @Test fun statusReportsKilledAndHeartbeat() {
        val (d, _) = engine(10)
        assertEquals(false, d.status()["killed"])
        d.cmdEmergencyLand()
        assertEquals(true, d.status()["killed"])
        assertTrue(d.status().containsKey("heartbeat_active"))
    }

    // ---- autonomous heartbeat lifecycle ----

    @Test fun heartbeatSurvivesMcpDisconnect() {
        val (d, t) = engine(10)
        d.bindAndStartAutonomous()   // service-side, before any MCP connect
        assertEquals(1, t.bindCalls)
        assertTrue(d.connected)
        d.connect()                  // MCP connect — idempotent
        assertTrue(d.connected)
        d.disconnect()               // MCP disconnect — heartbeat must survive
        assertTrue("autonomous heartbeat keeps engine alive", d.connected)
        d.shutdown()                 // service destroy
        assertFalse(d.connected)
    }

    @Test fun connectWithoutAutonomousStopsOnDisconnect() {
        val (d, _) = engine(10)
        d.connect()
        d.disconnect()
        assertFalse(d.connected)
    }

    // ---- autonomous-bind + requireConnected (the no-lift root cause) ----

    @Test fun autonomousBindLeavesControlStopped() {
        // The service starts via bindAndStartAutonomous(): connected=true but
        // running stays false — the pre-fix trap.
        val (d, _) = engine(10)
        d.bindAndStartAutonomous()
        assertTrue(d.connected)
        assertFalse(d.controlRunning)
    }

    @Test fun requireConnectedStartsControlEvenWhenAlreadyConnected() {
        // Regression: requireConnected() gated on !connected, so every command
        // after the autonomous bind skipped connect() and running stayed false
        // (trajectory never advanced; throttle forced to neutral — the crash).
        val (d, _) = engine(10)
        d.bindAndStartAutonomous()
        assertFalse(d.controlRunning)
        d.requireConnected()
        assertTrue("command after autonomous bind must start control loops", d.controlRunning)
    }

    @Test fun requireConnectedAfterDisconnectRestartsControl() {
        val (d, _) = engine(10)
        d.bindAndStartAutonomous()
        d.requireConnected()
        d.disconnect()
        assertFalse(d.controlRunning)
        d.requireConnected()   // reconnect path
        assertTrue(d.controlRunning)
    }

    // ---- diagnostics hook ----

    @Test fun debugLogHookReceivesRxEvents() {
        val logged = mutableListOf<Pair<String, String>>()
        val d = DroneEngine(RecordingTransport(), 10, debugLog = { tag, msg -> logged += tag to msg })
        d.handleReceive(byteArrayOf(0x01, 0x02, 0x4D, 0x00, 0x00, 0x00))
        assertTrue(logged.any { it.first == "rx" && it.second.contains("4d") })
    }
}
