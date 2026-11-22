package com.dronelink.app.mcp

import com.dronelink.app.drone.DroneEngine
import com.dronelink.app.drone.DroneTransport
import com.dronelink.app.net.JavaServerSocketFactory
import com.dronelink.app.video.VideoFeed
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * v0.3.0 MCP surface: fly_home, snapshot, emergency_land kill switch
 * (server stops + engine refuses), video_url → proxied stream.
 */
class McpServerV03Test {

    private class RecordingTransport : DroneTransport {
        override fun bind() {}
        override fun send(data: ByteArray) {}
        override fun receive(buf: ByteArray): Int = -1

        override fun probeSend(destPort: Int, srcPort: Int, data: ByteArray): List<String> = emptyList()
        override fun close() {}
    }

    private class StubFeed(val frame: ByteArray?) : VideoFeed {
        override var frameVersion: Long = if (frame != null) 1 else 0
            private set
        override val latestFrame: ByteArray? get() = frame
        override fun awaitFrame(seenVersion: Long, timeoutMs: Long): Long = frameVersion
    }

    private lateinit var engine: DroneEngine
    private lateinit var server: McpServer
    private var emergencyLandCalled = false
    private var disconnectCalled = false

    @Before
    fun setUp() {
        engine = DroneEngine(RecordingTransport(), 10)
        server = McpServer(
            engine, 0,
            onEmergencyLand = { emergencyLandCalled = true },
            onDisconnect = { disconnectCalled = true },
            socketFactory = JavaServerSocketFactory,
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        engine.disconnect()
    }

    private fun rpc(method: String, params: JSONObject? = null, id: Int = 1): JSONObject {
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) req.put("params", params)
        return JSONObject(server.handleJsonRpc(req.toString()))
    }

    private fun callTool(name: String, vararg pairs: Pair<String, Any?>): JSONObject =
        rpc("tools/call", JSONObject()
            .put("name", name)
            .put("arguments", JSONObject().apply { for ((k, v) in pairs) put(k, v) }))

    private fun resultText(res: JSONObject): String =
        res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")

    // ---- fly_home ----

    @Test fun toolsListIncludesFlyHomeAndSnapshot() {
        val res = rpc("tools/list")
        val tools = res.getJSONObject("result").getJSONArray("tools")
        val names = mutableListOf<String>()
        for (i in 0 until tools.length()) names += tools.getJSONObject(i).getString("name")
        assertTrue(names.contains("fly_home"))
        assertTrue(names.contains("snapshot"))
        assertEquals(21, tools.length())
    }

    @Test fun flyHomeToolRuns() {
        val res = callTool("fly_home")
        val call = JSONObject(resultText(res))
        assertEquals(true, call.getBoolean("ok"))
        assertEquals("fly_home", call.getString("cmd"))
    }

    // ---- snapshot ----

    @Test fun snapshotNoFeedReturnsError() {
        val res = callTool("snapshot")
        val call = JSONObject(resultText(res))
        assertEquals(false, call.getBoolean("ok"))
    }

    @Test fun snapshotWithFrameReturnsBase64() {
        val frame = ByteArray(16) { it.toByte() }
        server.videoFeed = StubFeed(frame)
        val res = callTool("snapshot")
        val call = JSONObject(resultText(res))
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(16, call.getInt("size"))
        assertTrue(Base64.getDecoder().decode(call.getString("jpeg_base64")).contentEquals(frame))
    }

    // ---- emergency_land kill switch ----

    @Test fun emergencyLandInvokesServerStop() {
        callTool("emergency_land")
        assertTrue("onEmergencyLand must be invoked", emergencyLandCalled)
    }

    @Test fun emergencyLandRefusesAllFurtherTools() {
        callTool("emergency_land")
        val res = callTool("status")
        val err = res.getJSONObject("error")
        assertEquals(-32000, err.getInt("code"))
        assertTrue(err.getString("message").contains("kill switch"))
    }

    @Test fun emergencyLandStatusReflectsKilled() {
        val res = callTool("emergency_land")
        val call = JSONObject(resultText(res))
        assertEquals(true, call.getJSONObject("status").getBoolean("killed"))
    }

    // ---- video_url proxied ----

    @Test fun videoUrlPointsAtProxy() {
        server.videoHost = "100.64.0.1"
        server.videoPort = 8080
        val res = callTool("video_url")
        val call = JSONObject(resultText(res))
        assertEquals("http://100.64.0.1:8080/video", call.getString("url"))
        assertEquals("http://100.64.0.1:8080/snapshot", call.getString("snapshot"))
        assertFalse(call.getString("url").contains("192.168.1.1"))
    }

    @Test fun videoUrlUsesLiveResolverOverStaleFallback() {
        server.videoHost = "172.30.208.69"   // stale/bogus fallback
        server.videoHostResolver = { "100.122.172.27" }
        val res = callTool("video_url")
        val call = JSONObject(resultText(res))
        assertEquals("http://100.122.172.27:8080/video", call.getString("url"))
    }

    @Test fun videoUrlRefusesBogusFallbackWhenTailnetDown() {
        server.videoHost = "172.30.208.69"   // the exact bad-advertise bug
        server.videoHostResolver = { null }   // tailnet IPv4 not up
        val res = callTool("video_url")
        val call = JSONObject(resultText(res))
        assertEquals(false, call.getBoolean("ok"))
        assertTrue(call.getString("error").contains("tailnet"))
    }

    @Test fun disconnectStopsProxies() {
        val res = callTool("disconnect")
        val call = JSONObject(resultText(res))
        assertEquals(true, call.getBoolean("ok"))
        assertTrue("onDisconnect hook must fire", disconnectCalled)
    }

    // ---- liveness readback (t_b43707a1) ----

    private fun assertLivenessReadback(call: JSONObject, expectCmd: String) {
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(expectCmd, call.getString("cmd"))
        val st = call.getJSONObject("status")
        for (k in listOf(
            "connected", "variant", "gear", "axes", "sent", "rx",
            "last_telemetry", "heartbeat_active",
        )) {
            assertTrue("status missing $k", st.has(k))
        }
        assertEquals(false, call.getBoolean("verified"))
        assertTrue("no reason for unverified command", call.has("reason"))
    }

    @Test fun takeoffReturnsLivenessReadback() {
        assertLivenessReadback(JSONObject(resultText(callTool("takeoff"))), "takeoff")
    }

    @Test fun landReturnsLivenessReadback() {
        assertLivenessReadback(JSONObject(resultText(callTool("land"))), "land")
    }

    @Test fun flyHomeReturnsLivenessReadback() {
        assertLivenessReadback(JSONObject(resultText(callTool("fly_home"))), "fly_home")
    }

    @Test fun flyReturnsLivenessReadback() {
        val call = JSONObject(resultText(callTool(
            "fly", "roll" to 0.5, "pitch" to 0.0, "throttle" to 0.3, "yaw" to 0.0,
        )))
        assertEquals(true, call.getBoolean("ok"))
        // backward compat: commanded-sticks echo preserved
        val axes = call.getJSONArray("axes")
        assertEquals(4, axes.length())
        // readback mirrors the engine's actual state (same source as status tool)
        assertEquals(axes.toString(), call.getJSONObject("status").getJSONArray("axes").toString())
        assertEquals(false, call.getBoolean("verified"))
        assertTrue("no reason for unverified command", call.has("reason"))
    }

    @Test fun setGearReturnsLivenessReadback() {
        val call = JSONObject(resultText(callTool("set_gear", "gear" to 2)))
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(2, call.getInt("gear"))
        assertEquals(2, call.getJSONObject("status").getInt("gear"))
        assertEquals(false, call.getBoolean("verified"))
        assertTrue("no reason for unverified command", call.has("reason"))
    }

    @Test fun stopTrajectoryReturnsStatusNotBareOk() {
        val call = JSONObject(resultText(callTool("stop_trajectory")))
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(false, call.getJSONObject("status").getBoolean("trajectory_active"))
    }
}
