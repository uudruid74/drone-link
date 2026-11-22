package com.dronelink.app.mcp

import com.dronelink.app.BuildConfig
import com.dronelink.app.drone.DroneEngine
import com.dronelink.app.net.AcceptedSocket
import com.dronelink.app.net.ListeningSocket
import com.dronelink.app.net.OsServerSocketFactory
import com.dronelink.app.net.ServerSocketFactory
import com.dronelink.app.video.VideoFeed
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Minimal MCP server (JSON-RPC 2.0 over the streamable-HTTP subset) serving
 * the drone engine. Port of `drone-ctl/drone_mcp.py`'s FastMCP tool surface:
 * the 12 @mcp.tool() tools from lines 286-400 VERBATIM (names, schemas,
 * behavior, return shapes) plus `status` and `video_url` (same file, needed
 * for smoke verification — the full MCP surface of the oracle).
 *
 * v0.3.0 additions (Evan/Gopher spec):
 *  - `fly_home` tool (Return-to-Home, protocol +32 byte 6)
 *  - `snapshot` tool (latest MJPEG frame, base64 — stdlib only)
 *  - emergency_land = kill switch: the engine refuses all tools and the
 *    server stops itself via [onEmergencyLand] until app restart
 *  - video_url now returns the proxied MJPEG stream (RTSP proxy), not the
 *    useless drone-side rtsp:// URL
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
    private val onEmergencyLand: (() -> Unit)? = null,
    private val onDisconnect: (() -> Unit)? = null,
    private val socketFactory: ServerSocketFactory = OsServerSocketFactory,
) {
    /** Live video feed (RTSP proxy) for the snapshot tool. Set by the service. */
    @Volatile
    var videoFeed: VideoFeed? = null

    /**
     * Resolves the host the laptop uses to reach the app (tailnet IPv4).
     * Called freshly on every `video_url` — never cache a stale guess at
     * startup (tailscale may not be up yet, and a wrong host is worse than
     * an explicit error). Set by the service.
     */
    @Volatile
    var videoHostResolver: (() -> String?)? = null

    /** Fallback host (legacy field; prefer [videoHostResolver]). */
    @Volatile
    var videoHost: String = "127.0.0.1"

    /** Proxy HTTP port for /video and /snapshot. */
    @Volatile
    var videoPort: Int = 8080

    /** First complete JPEG frame assembled (raw bytes) — for offline
     * verification against stock ffmpeg. Set by the service from
     * RtspClient.firstFrameDump. */
    @Volatile
    var videoFirstFrame: ByteArray? = null

    /** Runs the on-device stock ffmpeg against the drone RTSP while bound to
     * the drone network. Set by the service. */
    @Volatile
    var ffmpegProbe: (() -> Map<String, Any?>)? = null

    data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val SERVER_NAME = "drone-ctl"

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
            "Stop the MCP-scoped control/rx loops. The autonomous heartbeat keeps running.",
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
            "EMERGENCY KILL SWITCH: lands, holds neutral, stops the MCP server. " +
                "Restart the app to re-arm.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "fly_home",
            "Return-to-Home (protocol flag +32, byte 6) for 5s.",
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
            "raw_udp_send",
            "Send raw hex bytes over the connected control socket (debug/protocol probe only).",
            obj(
                "type" to "object",
                "properties" to JSONObject().put("dataHex", obj("type" to "string")),
                "required" to required("dataHex"),
            ),
        ),
        ToolSpec(
            "udp_probe_send",
            "Bind a UDP socket on srcPort, send hex to the drone at destPort, capture replies (protocol probe).",
            obj(
                "type" to "object",
                "properties" to JSONObject()
                    .put("destPort", int())
                    .put("srcPort", int())
                    .put("dataHex", obj("type" to "string")),
                "required" to required("destPort", "srcPort", "dataHex"),
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
            "Return the proxied live video URL (MJPEG over HTTP, tailnet-reachable).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "snapshot",
            "Return the latest complete frame as base64 JPEG (or an error if none yet).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "debug_log",
            "Return the app's DebugLog ring (transport/probe/rtsp lines) as stamped text.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "debug_frame",
            "Return the first complete JPEG frame assembled this session as base64 (wire ground truth for offline ffmpeg verification).",
            obj("type" to "object", "properties" to JSONObject()),
        ),
        ToolSpec(
            "ffmpeg_probe",
            "Run the on-device stock ffmpeg against the drone RTSP (bound to the drone network) and return the captured JPEG frames as base64.",
            obj("type" to "object", "properties" to JSONObject()),
        ),
    )

    private val toolsByName = tools.associateBy { it.name }

    private var listener: ListeningSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var running = false

    val boundPort: Int
        get() = listener?.boundPort ?: port

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        val ls = socketFactory.listenV4(port, 50)
        listener = ls
        running = true
        acceptThread = Thread {
            while (running) {
                val client = try {
                    ls.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { handleClient(client) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { listener?.close() }
        acceptThread?.join(1000)
    }

    // ------------------------------------------------------------------- HTTP

    private fun handleClient(client: AcceptedSocket) {
        try {
            client.setSoTimeout(5000)
            val input = BufferedInputStream(client.inputStream())
            val output = BufferedOutputStream(client.outputStream())

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
                    Triple(200, "text/plain", "DroneLink MCP v0.3 — POST JSON-RPC to /mcp\n")
                else -> Triple(404, "text/plain", "not found\n")
            }
            writeResponse(output, status, contentType, respBody)
        } finally {
            client.close()
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
                    .put("version", BuildConfig.VERSION_NAME)))
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
        if (engine.killed) {
            return errorResponse(
                id, -32000,
                "emergency_land kill switch engaged — restart the app to re-arm",
            ).toString()
        }
        return try {
            val result = when (name) {
                "connect" -> engine.cmdConnect()
                "disconnect" -> {
                    val r = engine.cmdDisconnect()
                    // Stop the control/rx loops AND tear down the proxies
                    // (RTSP session + HTTP listener unbind). The MCP server
                    // itself stays up so `connect` can resume.
                    onDisconnect?.invoke()
                    r
                }
                "takeoff" -> engine.cmdTakeoff()
                "land" -> engine.cmdLand()
                "emergency_land" -> {
                    val r = engine.cmdEmergencyLand()
                    // Kill switch: stop the MCP server (and everything else) now.
                    onEmergencyLand?.invoke()
                    r
                }
                "fly_home" -> engine.cmdFlyHome()
                "fly" -> engine.cmdFly(
                    args.optDouble("roll", 0.0),
                    args.optDouble("pitch", 0.0),
                    args.optDouble("throttle", 0.0),
                    args.optDouble("yaw", 0.0),
                )
                "trajectory" -> engine.cmdTrajectory(parseWaypoints(args.optJSONArray("waypoints")))
                "stop_trajectory" -> engine.cmdStopTrajectory()
                "camera" -> engine.cmdCamera(args.optInt("cam", 1))
                "raw_udp_send" -> engine.rawUdpSend(args.optString("dataHex", ""))
                "udp_probe_send" -> engine.udpProbeSend(
                    args.optInt("destPort", 7099),
                    args.optInt("srcPort", 8890),
                    args.optString("dataHex", ""),
                )
                "headless" -> engine.cmdHeadless(args.optBoolean("enabled", false))
                "calibrate" -> engine.cmdCalibrate()
                "set_gear" -> engine.cmdSetGear(args.optInt("gear", 1))
                "status" -> engine.status()
                "video_url" -> {
                    val host = videoHostResolver?.invoke() ?: videoHost
                    if (host.isEmpty() || host == "127.0.0.1" || host.startsWith("172.")) {
                        linkedMapOf(
                            "ok" to false,
                            "error" to "tailnet IPv4 not up yet — video URL unavailable " +
                                "(start Tailscale; 100.x address required)",
                        )
                    } else {
                        val base = "http://$host:$videoPort"
                        linkedMapOf("ok" to true, "url" to "$base/video", "snapshot" to "$base/snapshot")
                    }
                }
                "snapshot" -> snapshotTool()
                "debug_log" -> linkedMapOf(
                    "ok" to true,
                    "log" to com.dronelink.app.debug.DebugLog.exportText(),
                )
                "debug_frame" -> {
                    val f = videoFirstFrame
                        ?: return errorResponse(id, -32000, "no frame captured yet").toString()
                    linkedMapOf(
                        "ok" to true,
                        "size" to f.size,
                        "jpeg_base64" to Base64.getEncoder().encodeToString(f),
                    )
                }
                "ffmpeg_probe" -> {
                    val runner = ffmpegProbe
                        ?: return errorResponse(id, -32000, "ffmpeg probe unavailable (service not set it)").toString()
                    runner()
                }
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

    private fun snapshotTool(): Map<String, Any?> {
        val frame = videoFeed?.latestFrame
            ?: return linkedMapOf("ok" to false, "error" to "no video frame yet")
        return linkedMapOf(
            "ok" to true,
            "size" to frame.size,
            "jpeg_base64" to Base64.getEncoder().encodeToString(frame),
        )
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
