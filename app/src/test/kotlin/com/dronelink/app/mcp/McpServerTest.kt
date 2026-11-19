package com.dronelink.app.mcp

import com.dronelink.app.BuildConfig
import com.dronelink.app.drone.DroneEngine
import com.dronelink.app.drone.DroneTransport
import com.dronelink.app.net.JavaServerSocketFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * MCP server tests: JSON-RPC core + real-socket HTTP roundtrip (JVM).
 * The tool surface must mirror drone_mcp.py VERBATIM (names, schemas,
 * behavior, return shapes).
 */
class McpServerTest {

    private class RecordingTransport : DroneTransport {
        override fun bind() {}
        override fun send(data: ByteArray) {}
        override fun receive(buf: ByteArray): Int = -1
        override fun close() {}
    }

    private lateinit var engine: DroneEngine
    private lateinit var server: McpServer

    @Before
    fun setUp() {
        engine = DroneEngine(RecordingTransport(), 10)
        server = McpServer(engine, 0, socketFactory = JavaServerSocketFactory)  // ephemeral port for tests
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

    // ---- initialize ----

    @Test fun initializeHandshake() {
        val res = rpc("initialize")
        assertEquals("2.0", res.getString("jsonrpc"))
        assertEquals(1, res.getInt("id"))
        val result = res.getJSONObject("result")
        assertEquals("2025-03-26", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("drone-ctl", result.getJSONObject("serverInfo").getString("name"))
        assertEquals(BuildConfig.VERSION_NAME, result.getJSONObject("serverInfo").getString("version"))
    }

    // ---- tools/list ----

    @Test fun toolsListSurface() {
        val res = rpc("tools/list")
        val tools = res.getJSONObject("result").getJSONArray("tools")
        val names = mutableListOf<String>()
        for (i in 0 until tools.length()) names += tools.getJSONObject(i).getString("name")
        // the 12 verbatim tools from drone_mcp.py:286-400 + status/video_url
        // + v0.3.0 fly_home/snapshot/debug_log
        assertEquals(
            listOf(
                "connect", "disconnect", "takeoff", "land", "emergency_land",
                "fly_home", "fly", "trajectory", "stop_trajectory", "camera", "headless",
                "calibrate", "set_gear", "status", "video_url", "snapshot",
                "debug_log",
            ),
            names,
        )
        assertEquals(17, tools.length())
    }

    @Test fun flySchemaVerbatim() {
        val res = rpc("tools/list")
        val tools = res.getJSONObject("result").getJSONArray("tools")
        val fly = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "fly" }
        val schema = fly.getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
        val props = schema.getJSONObject("properties")
        assertEquals("number", props.getJSONObject("roll").getString("type"))
        assertEquals("number", props.getJSONObject("pitch").getString("type"))
        assertEquals("number", props.getJSONObject("throttle").getString("type"))
        assertEquals("number", props.getJSONObject("yaw").getString("type"))
        assertEquals(listOf("roll", "pitch", "throttle", "yaw"), schema.getJSONArray("required").toList())
    }

    @Test fun cameraHeadlessTrajectorySchemas() {
        val res = rpc("tools/list")
        val tools = res.getJSONObject("result").getJSONArray("tools")
        fun tool(name: String): JSONObject =
            (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == name }

        val cam = tool("camera").getJSONObject("inputSchema")
        assertEquals("integer", cam.getJSONObject("properties").getJSONObject("cam").getString("type"))
        assertEquals(listOf("cam"), cam.getJSONArray("required").toList())

        val hl = tool("headless").getJSONObject("inputSchema")
        assertEquals("boolean", hl.getJSONObject("properties").getJSONObject("enabled").getString("type"))
        assertEquals(listOf("enabled"), hl.getJSONArray("required").toList())

        val traj = tool("trajectory").getJSONObject("inputSchema")
        val wp = traj.getJSONObject("properties").getJSONObject("waypoints")
        assertEquals("array", wp.getString("type"))
        assertEquals("object", wp.getJSONObject("items").getString("type"))
        assertEquals(listOf("waypoints"), traj.getJSONArray("required").toList())
    }

    // ---- tools/call ----

    @Test fun callStatusShape() {
        val res = rpc("tools/call", JSONObject().put("name", "status"))
        val call = res.getJSONObject("result")
        assertFalse(call.getBoolean("isError"))
        val text = call.getJSONArray("content").getJSONObject(0).getString("text")
        // wire format: axes must be a real JSON array, not a quoted list string
        assertTrue(text, text.contains("\"axes\":[128,128,0,128]"))
        assertFalse(text, text.contains("\"axes\":\""))
        val st = JSONObject(text)
        assertEquals(false, st.getBoolean("connected"))
        assertEquals("TC(9B)", st.getString("variant"))
        assertEquals(1, st.getInt("gear"))
        assertEquals(false, st.getBoolean("headless"))
        assertEquals(1, st.getInt("cam"))
        assertEquals(listOf(128, 128, 0, 128), st.getJSONArray("axes").toList())
        assertEquals(0, st.getInt("sent"))
        assertEquals(0, st.getInt("rx"))
        assertTrue(st.isNull("last_telemetry"))
        assertEquals(false, st.getBoolean("trajectory_active"))
        assertEquals(0, st.getInt("trajectory_index"))
        assertEquals("rtsp://192.168.1.1:7070/webcam", st.getString("rtsp"))
    }

    @Test fun callSetGearThenStatus() {
        rpc("tools/call", JSONObject()
            .put("name", "set_gear")
            .put("arguments", JSONObject().put("gear", 3)))
        val st = JSONObject(
            rpc("tools/call", JSONObject().put("name", "status"))
                .getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"),
        )
        assertEquals(3, st.getInt("gear"))
    }

    @Test fun callVideoUrl() {
        server.videoHost = "100.64.0.1"
        val res = rpc("tools/call", JSONObject().put("name", "video_url"))
        val text = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        // v0.3.0: video_url serves the proxied MJPEG stream, not the raw rtsp://
        assertEquals("http://100.64.0.1:8080/video", JSONObject(text).getString("url"))
        assertEquals("http://100.64.0.1:8080/snapshot", JSONObject(text).getString("snapshot"))
    }

    @Test fun callFlyReturnsAxes() {
        val res = rpc("tools/call", JSONObject()
            .put("name", "fly")
            .put("arguments", JSONObject()
                .put("roll", 0.5)
                .put("pitch", -0.5)
                .put("throttle", 0.4)
                .put("yaw", 0.2)))
        val text = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        val call = JSONObject(text)
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(listOf(148, 108, 50, 153), call.getJSONArray("axes").toList())
    }

    @Test fun callTakeoffShape() {
        val res = rpc("tools/call", JSONObject().put("name", "takeoff"))
        val text = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        val call = JSONObject(text)
        assertEquals(true, call.getBoolean("ok"))
        assertEquals("takeoff", call.getString("cmd"))
    }

    @Test fun callTrajectoryShape() {
        val res = rpc("tools/call", JSONObject()
            .put("name", "trajectory")
            .put("arguments", JSONObject().put("waypoints", org.json.JSONArray().put(
                JSONObject()
                    .put("roll", 0.5).put("pitch", 0.0)
                    .put("throttle", 0.4).put("yaw", 0.0)
                    .put("duration", 1.0),
            ))))
        val text = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        val call = JSONObject(text)
        assertEquals(true, call.getBoolean("ok"))
        assertEquals(1, call.getInt("waypoints"))
        assertEquals(true, call.getJSONObject("status").getBoolean("trajectory_active"))
    }

    // ---- errors ----

    @Test fun unknownToolError() {
        val res = rpc("tools/call", JSONObject().put("name", "nope"))
        val err = res.getJSONObject("error")
        assertEquals(-32602, err.getInt("code"))
        assertTrue(err.getString("message").contains("nope"))
    }

    @Test fun unknownMethodError() {
        val res = rpc("bogus")
        assertEquals(-32601, res.getJSONObject("error").getInt("code"))
    }

    @Test fun parseError() {
        val res = JSONObject(server.handleJsonRpc("{not json"))
        assertEquals(-32700, res.getJSONObject("error").getInt("code"))
    }

    @Test fun pingReturnsEmptyResult() {
        val res = rpc("ping")
        assertTrue(res.getJSONObject("result").isEmpty)
    }

    @Test fun notificationWithoutIdHasNoIdField() {
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "tools/list")
        val res = JSONObject(server.handleJsonRpc(req.toString()))
        assertFalse(res.has("id"))
    }

    // ---- HTTP roundtrip (real socket) ----

    @Test fun httpPostRoundtrip() {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", "status"))
            .toString()
        val response = httpPost("/mcp", body)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Content-Type: application/json"))
        val payload = response.substringAfter("\r\n\r\n")
        val json = JSONObject(payload)
        assertEquals(7, json.getInt("id"))
        assertTrue(json.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text").contains("\"connected\":false"))
    }

    @Test fun httpGetProbe() {
        val response = httpRequest("GET", "/", "")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("DroneLink MCP"))
    }

    @Test fun http404() {
        val response = httpPost("/nope", "{}")
        assertTrue(response.startsWith("HTTP/1.1 404"))
    }

    private fun httpPost(path: String, body: String): String = httpRequest("POST", path, body)

    private fun httpRequest(method: String, path: String, body: String): String {
        Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
            s.soTimeout = 5000
            val out = BufferedOutputStream(s.getOutputStream())
            val head = "$method $path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body.toByteArray())
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                sb.append(String(buf, 0, n))
            }
            return sb.toString()
        }
    }
}
