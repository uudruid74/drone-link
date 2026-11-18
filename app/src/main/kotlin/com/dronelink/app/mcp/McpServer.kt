package com.dronelink.app.mcp

import com.dronelink.app.drone.DroneEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal MCP server (JSON-RPC 2.0 over the streamable-HTTP subset) serving
 * the drone engine. Port of `drone-ctl/drone_mcp.py`'s FastMCP tool surface:
 * the 12 @mcp.tool() tools from lines 286-400 VERBATIM (names, schemas,
 * behavior, return shapes) plus `status` and `video_url` (same file, needed
 * for smoke verification — the full MCP surface of the oracle).
 *
 * Transport: POST JSON-RPC to /mcp (streamable-HTTP subset — SSE is not
 * required by the plan). Binds 0.0.0.0:8000 so the phone's tailnet IP can
 * reach it.
 *
 * Pure JVM + org.json (built into Android; mirrored for JVM unit tests).
 */
class McpServer(
    private val engine: DroneEngine,
    private val port: Int = 8000,
) {
    data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val SERVER_NAME = "drone-ctl"
        const val SERVER_VERSION = "0.2.0"

        private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
            for ((k, v) in pairs) putOpt(k, v)
        }
        private fun num() = JSONObject().put("type", "number")
        private fun int() = JSONObject().put("type", "integer")
        private fun bool() = JSONObject().put("type", "boolean")
        private fun required(vararg names: String) = JSONArray(names.toList())
    }

    private val tools: List<ToolSpec> = listOf(
        ToolSpec(
            "connect",
            "Connect to the drone's UDP control socket and start the heartbeat.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "disconnect",
            "Disconnect and close the control socket.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "takeoff",
            "Arm and auto-takeoff. Propellers spin. TEST WITH PROPS REMOVED.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "land",
            "Auto-land (descent).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "emergency_land",
            "Emergency stop + hold sticks neutral for 4s (ignores other input).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "fly",
            "Set the stick axes directly. Each in -1..1 (0=center; throttle 0..1).\n\n" +
                "roll:  -1 left, +1 right | pitch: -1 forward, +1 back\n" +
                "yaw:   -1 left, +1 right | throttle: 0 idle .. 1 max.",
            obj(
                "type" to "object",
                "properties" to JSONObject()
                    .put("roll", num())
                    .put("pitch", num())
                    .put("throttle", num())
                    .put("yaw", num()),
                "required" to required("roll", "pitch", "throttle", "yaw"),
            ),
        ),
        ToolSpec(
            "trajectory",
            "Upload a timed flight script executed autonomously at 25 Hz.\n\n" +
                "Each waypoint: {'roll': f, 'pitch': f, 'throttle': f, 'yaw': f, 'duration': s}\n" +
                "sticks in -1..1 (throttle 0..1). The drone flies the path without a latency\n" +
                "loop — the right AI control surface for a WiFi toy drone.",
            obj(
                "type" to "object",
                "properties" to JSONObject().put(
                    "waypoints",
                    JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")),
                ),
                "required" to required("waypoints"),
            ),
        ),
        ToolSpec(
            "stop_trajectory",
            "Clear any active trajectory and return sticks to neutral.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "camera",
            "Switch camera: 1 = front, 2 = rear.",
            obj(
                "type" to "object",
                "properties" to JSONObject().put("cam", int()),
                "required" to required("cam"),
            ),
        ),
        ToolSpec(
            "headless",
            "Toggle headless (no-head) mode.",
            obj(
                "type" to "object",
                "properties" to JSONObject().put("enabled", bool()),
                "required" to required("enabled"),
            ),
        ),
        ToolSpec(
            "calibrate",
            "Run gyro calibration (drone should be on a flat, still surface).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "set_gear",
            "Set speed gear: 1=slow, 2=normal, 3=fast.",
            obj(
                "type" to "object",
                "properties" to JSONObject().put("gear", int()),
                "required" to required("gear"),
            ),
        ),
        ToolSpec(
            "status",
            "Return the drone's current state (variant, axes, telemetry, counts).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "video_url",
            "Return the RTSP video URL for live FPV (MJPEG over UDP).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
    )

    private val toolsByName = tools.associateBy { it.name }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var running = false

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        val ss = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        running = true
        acceptThread = Thread {
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                Thread { handleClient(client) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        acceptThread?.join(1000)
    }

    // ------------------------------------------------------------------- HTTP

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = 5000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                ByteArray(contentLength).also { readFully(input, it) }
            } else {
                ByteArray(0)
            }

            val (status, contentType, respBody) = when {
                method == "POST" && path == "/mcp" ->
                    Triple(200, "application/json", handlePost(String(body, StandardCharsets.UTF_8)))
                method == "GET" ->
                    Triple(200, "text/plain", "DroneLink MCP v0.2 — POST JSON-RPC to /mcp\n")
                else -> Triple(404, "text/plain", "not found\n")
            }
            writeResponse(output, status, contentType, respBody)
        }
    }

    private fun handlePost(body: String): String =
        if (body.isBlank()) {
            errorResponse(null, -32700, "Parse error: empty body").toString()
        } else {
            handleJsonRpc(body)
        }

    private fun writeResponse(out: BufferedOutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) return
            off += n
        }
    }

    // ------------------------------------------------------------ JSON-RPC core

    internal fun handleJsonRpc(body: String): String {
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return errorResponse(null, -32700, "Parse error: ${e.message}").toString()
        }
        val id = if (req.has("id")) req.get("id") else null
        val method = req.optString("method")

        return when (method) {
            "initialize" -> success(id, JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject()
                    .put("name", SERVER_NAME)
                    .put("version", SERVER_VERSION)))
            "ping" -> success(id, JSONObject())
            "tools/list" -> {
                val arr = JSONArray()
                for (t in tools) {
                    arr.put(JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("inputSchema", t.schema))
                }
                success(id, JSONObject().put("tools", arr))
            }
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                callTool(id, name, args)
            }
            "" -> success(id, JSONObject())  // notification without method
            else -> errorResponse(id, -32601, "Method not found: $method").toString()
        }
    }

    private fun callTool(id: Any?, name: String, args: JSONObject): String {
        if (name !in toolsByName) {
            return errorResponse(id, -32602, "Unknown tool: $name").toString()
        }
        return try {
            val result = when (name) {
                "connect" -> engine.cmdConnect()
                "disconnect" -> engine.cmdDisconnect()
                "takeoff" -> engine.cmdTakeoff()
                "land" -> engine.cmdLand()
                "emergency_land" -> engine.cmdEmergencyLand()
                "fly" -> engine.cmdFly(
                    args.optDouble("roll", 0.0),
                    args.optDouble("pitch", 0.0),
                    args.optDouble("throttle", 0.0),
                    args.optDouble("yaw", 0.0),
                )
                "trajectory" -> engine.cmdTrajectory(parseWaypoints(args.optJSONArray("waypoints")))
                "stop_trajectory" -> engine.cmdStopTrajectory()
                "camera" -> engine.cmdCamera(args.optInt("cam", 1))
                "headless" -> engine.cmdHeadless(args.optBoolean("enabled", false))
                "calibrate" -> engine.cmdCalibrate()
                "set_gear" -> engine.cmdSetGear(args.optInt("gear", 1))
                "status" -> engine.status()
                "video_url" -> engine.cmdVideoUrl()
                else -> return errorResponse(id, -32602, "Unknown tool: $name").toString()
            }
            success(id, JSONObject()
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", toJson(result).toString())))
                .put("isError", false))
        } catch (e: IOException) {
            errorResponse(id, -32000, "Engine unavailable: ${e.message}").toString()
        } catch (e: Exception) {
            errorResponse(id, -32000, "Tool failed: ${e.message}").toString()
        }
    }

    private fun parseWaypoints(arr: JSONArray?): List<Map<String, Any?>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val m = mutableMapOf<String, Any?>()
            for (k in w.keys()) {
                val v = w.opt(k)
                if (v != JSONObject.NULL) m[k] = v
            }
            out += m
        }
        return out
    }

    /**
     * Convert engine result values to JSON-safe types explicitly. Android's
     * bundled org.json does NOT auto-convert Kotlin List/Map (it falls back
     * to toString -> a quoted string); the Maven org.json on the JVM does,
     * which is exactly the divergence this prevents.
     */
    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            for ((k, value) in v) put(k.toString(), toJson(value))
        }
        is Iterable<*> -> JSONArray().apply {
            for (item in v) put(toJson(item))
        }
        is Array<*> -> JSONArray().apply {
            for (item in v) put(toJson(item))
        }
        else -> v
    }

    private fun success(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

    private fun errorResponse(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject()
                .put("code", code)
                .put("message", message))
}
