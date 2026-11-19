package com.dronelink.app.video

import com.dronelink.app.debug.DebugLog
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Serves the drone's MJPEG stream over HTTP so the laptop can reach FPV over
 * tailnet (requirement 4) and grab snapshots (requirement 5).
 *
 *   GET /video     → multipart/x-mixed-replace stream (live frames)
 *   GET /snapshot  → the latest complete JPEG frame (image/jpeg) or 503
 *   GET /          → plain-text status
 *
 * stdlib only; one daemon thread per client.
 */
class MjpegHttpServer(
    private val feed: VideoFeed,
    private val port: Int = 8080,
    private val tag: String = "video",
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var running = false

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        serverSocket = ss
        running = true
        DebugLog.log(tag, "HTTP MJPEG proxy on :$boundPort (/video, /snapshot)")
        acceptThread = Thread {
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
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

    private fun handleClient(client: Socket) {
        client.use { s ->
            s.soTimeout = 20000
            val inp = s.getInputStream()
            val out = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            // Drain headers.
            while (true) {
                val line = readLine(inp)
                if (line == null || line.isEmpty()) break
            }
            when {
                method == "GET" && path == "/video" -> stream(out, s)
                method == "GET" && path == "/snapshot" -> snapshot(out)
                method == "GET" -> status(out)
                else -> response(out, 404, "text/plain", "not found\n")
            }
            out.flush()
        }
    }

    private fun stream(out: BufferedOutputStream, s: Socket) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=droneframe\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.flush()
        // Emit the current frame immediately (instant first paint), then live.
        var seen = feed.frameVersion
        feed.latestFrame?.let { frame ->
            writePart(out, frame)
            seen = feed.frameVersion
        }
        while (running && !s.isClosed) {
            val v = feed.awaitFrame(seen, 10000)
            if (v == seen) continue  // timeout — keep the stream alive
            seen = v
            val frame = feed.latestFrame ?: continue
            writePart(out, frame)
        }
    }

    private fun writePart(out: BufferedOutputStream, frame: ByteArray) {
        val prefix = "--droneframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
        out.write(prefix.toByteArray(StandardCharsets.UTF_8))
        out.write(frame)
        out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun snapshot(out: BufferedOutputStream) {
        val frame = feed.latestFrame
        if (frame == null) {
            response(out, 503, "text/plain", "no frame yet\n")
            return
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${frame.size}\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(frame)
    }

    private fun status(out: BufferedOutputStream) {
        val body = "DroneLink RTSP→MJPEG proxy\n" +
            "frames: ${feed.frameVersion}\n" +
            "latest: ${feed.latestFrame?.size ?: 0} bytes\n" +
            "endpoints: /video (MJPEG stream), /snapshot (JPEG)\n"
        response(out, 200, "text/plain", body)
    }

    private fun response(out: BufferedOutputStream, status: Int, contentType: String, bodyText: String) {
        val body = bodyText.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
