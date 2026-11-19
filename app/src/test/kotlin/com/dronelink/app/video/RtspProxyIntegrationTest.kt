package com.dronelink.app.video

import com.dronelink.app.net.JavaServerSocketFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * End-to-end JVM integration: a fake RTSP drone (loopback) serves the
 * handshake and then sends two RTP packets containing a JPEG split across
 * them. Proves the whole v0.3.0 pipeline without hardware:
 * RTSP handshake → RTP payload strip → MJPEG boundary reassembly → HTTP
 * /snapshot and /video.
 */
class RtspProxyIntegrationTest {

    private class FakeDrone {
        val server = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort
        val requests = mutableListOf<Pair<String, String>>()  // (method, uri)
        val jpeg = makeJpeg(42)
        var lastClientPort = -1

        /** Send another JPEG frame to the last SETUP client port. */
        fun sendFrame() {
            if (lastClientPort > 0) sendRtp(lastClientPort)
        }

        fun start() {
            Thread {
                try {
                    val s = server.accept()
                    s.use { c ->
                        val inp = BufferedInputStream(c.getInputStream())
                        val out = BufferedOutputStream(c.getOutputStream())
                        var clientPort = -1
                        var cseq = 0
                        var method = ""
                        var transportHeader = ""
                        while (true) {
                            val line = readLine(inp) ?: break
                            if (line.isEmpty()) {
                                // request complete — respond
                                when (method) {
                                    "OPTIONS" -> respond(out, cseq, "200 OK", "Public: DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
                                    "DESCRIBE" -> {
                                        val sdp = "v=0\r\n" +
                                            "o=- 0 0 IN IP4 127.0.0.1\r\n" +
                                            "s=webcam\r\n" +
                                            "m=video 0 RTP/AVP 26\r\n" +
                                            "a=control:rtsp://127.0.0.1:$port/webcam/track1\r\n" +
                                            "a=rtpmap:26 JPEG/90000\r\n"
                                        respond(out, cseq, "200 OK", body = sdp)
                                    }
                                    "SETUP" -> {
                                        clientPort = Regex("client_port=(\\d+)").find(transportHeader)
                                            ?.groupValues?.get(1)?.toInt() ?: -1
                                        lastClientPort = clientPort
                                        respond(out, cseq, "200 OK",
                                            "Session: test123\r\n" +
                                                "Transport: RTP/AVP/UDP;unicast;client_port=$clientPort-${clientPort + 1};server_port=5000-5001\r\n")
                                    }
                                    "PLAY" -> {
                                        respond(out, cseq, "200 OK", "Session: test123\r\n")
                                        if (clientPort > 0) sendRtp(clientPort)
                                    }
                                    else -> respond(out, cseq, "404 Not Found")
                                }
                                if (method == "PLAY") break
                                method = ""
                            } else {
                                val parts = line.split(" ")
                                if (parts.size >= 2 && parts[0] in setOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY")) {
                                    method = parts[0]
                                    requests += parts[0] to parts[1]
                                } else if (line.startsWith("CSeq:")) {
                                    cseq = line.substringAfter(':').trim().toIntOrNull() ?: cseq
                                } else if (line.startsWith("Transport:")) {
                                    transportHeader = line
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // server side closed — fine for the test
                }
            }.apply { isDaemon = true; start() }
        }

        private fun respond(
            out: BufferedOutputStream,
            cseq: Int,
            status: String,
            extraHeaders: String = "",
            body: String = "",
        ) {
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val head = "RTSP/1.0 $status\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                extraHeaders + "\r\n"
            out.write(head.toByteArray(StandardCharsets.UTF_8))
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()
        }

        /**
         * Send the JPEG as two RFC 2435 RTP packets: each payload carries
         * an 8-byte JPEG header (fragment offset in bytes 1..3 big-endian)
         * followed by the fragment; the marker bit marks the final packet.
         */
        private fun sendRtp(clientPort: Int) {
            val addr = InetSocketAddress("127.0.0.1", clientPort)
            val first = jpeg.copyOfRange(0, 20)
            val second = jpeg.copyOfRange(20, jpeg.size)
            val s = DatagramSocket()
            s.send(DatagramPacket(rtpPacket(rfc2435Payload(0, first), seq = 0, marker = false), first.size + 8 + 12, addr))
            Thread.sleep(50)
            s.send(DatagramPacket(rtpPacket(rfc2435Payload(20, second), seq = 1, marker = true), second.size + 8 + 12, addr))
            s.close()
        }

        private fun rfc2435Payload(offset: Int, fragment: ByteArray): ByteArray {
            val hdr = ByteArray(8)
            hdr[1] = ((offset ushr 16) and 0xFF).toByte()
            hdr[2] = ((offset ushr 8) and 0xFF).toByte()
            hdr[3] = (offset and 0xFF).toByte()
            hdr[4] = 0x01       // type
            hdr[5] = 0x50       // Q
            hdr[6] = 0x01       // width
            hdr[7] = 0x0E       // height
            return hdr + fragment
        }

        private fun rtpPacket(payload: ByteArray, seq: Int, marker: Boolean): ByteArray {
            val hdr = ByteArray(12)
            hdr[0] = 0x80.toByte()          // V=2
            hdr[1] = (0x1A or (if (marker) 0x80 else 0)).toByte()  // PT=26 + M
            hdr[2] = ((seq ushr 8) and 0xFF).toByte()
            hdr[3] = (seq and 0xFF).toByte()
            hdr[4] = 0; hdr[5] = 0; hdr[6] = 0x01; hdr[7] = 0x00  // timestamp
            hdr[8] = 0x11; hdr[9] = 0x22; hdr[10] = 0x33; hdr[11] = 0x44  // ssrc
            return hdr + payload
        }

        private fun readLine(inp: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
    }

    private fun waitUntil(timeoutMs: Long = 8000, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timeout waiting for $what")
    }

    @Test fun fullPipelineHandshakeRtpSnapshot() {
        val drone = FakeDrone()
        drone.start()

        val client = RtspClient(
            host = "127.0.0.1",
            rtspPort = drone.port,
            path = "/webcam",
            tcp = TcpSocketProvider { addr, timeoutMs -> Socket().apply { connect(addr, timeoutMs) } },
            udp = UdpSocketProvider { DatagramSocket() },
        )
        client.start()

        waitUntil(what = "RTSP handshake") { client.sessionId != null }
        assertEquals("test123", client.sessionId)
        assertEquals(listOf("OPTIONS", "DESCRIBE", "SETUP", "PLAY"), drone.requests.map { it.first })

        waitUntil(what = "first frame") { client.frameVersion > 0 }
        assertArrayEquals(drone.jpeg, client.latestFrame)

        // HTTP proxy on top of the client feed.
        val http = MjpegHttpServer(client, 0, socketFactory = JavaServerSocketFactory)
        http.start()
        try {
            val snap = httpGet(http.boundPort, "/snapshot", readMs = 3000)
            assertTrue("snapshot status line, got: ${snap.take(160)}", snap.startsWith("HTTP/1.1 200"))
            assertTrue("snapshot content-type, got: ${snap.take(160)}", snap.contains("Content-Type: image/jpeg"))
            val body = snap.substringAfter("\r\n\r\n")
            assertArrayEquals(drone.jpeg, body.toByteArray(StandardCharsets.ISO_8859_1))

            val video = if (drone.lastClientPort > 0) {
                drone.sendFrame()          // a NEW frame so the live stream emits a part
                waitUntil(what = "second frame") { client.frameVersion > 1 }
                httpGet(http.boundPort, "/video", readMs = 2500)
            } else {
                httpGet(http.boundPort, "/video", readMs = 2500)
            }
            assertTrue("video status line, got: ${video.take(160)}", video.startsWith("HTTP/1.1 200"))
            assertTrue("video multipart, got: ${video.take(160)}", video.contains("multipart/x-mixed-replace"))
            assertTrue("video boundary, got: ${video.take(160)}", video.contains("--droneframe"))
            assertTrue("video jpeg part, got: ${video.take(160)}", video.contains("Content-Type: image/jpeg"))
        } finally {
            http.stop()
            client.stop()
            drone.server.close()
        }
    }

    /** Reads until [readMs] elapses (multipart streams don't EOF). */
    private fun httpGet(port: Int, path: String, readMs: Long): String {
        val s: java.net.Socket = java.net.Socket("127.0.0.1", port)
        try {
            s.soTimeout = readMs.toInt()
            val out = BufferedOutputStream(s.getOutputStream())
            out.write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val inp = BufferedInputStream(s.getInputStream())
            val buf = ByteArray(65536)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + readMs
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    inp.read(buf)
                } catch (e: SocketTimeoutException) {
                    break
                }
                if (n == -1) break
                sb.append(String(buf, 0, n, StandardCharsets.ISO_8859_1))
            }
            return sb.toString()
        } finally {
            runCatching { s.close() }
        }
    }
}

private fun makeJpeg(seed: Int): ByteArray {
    val b = ByteArray(64)
    b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
    for (i in 2 until 62) b[i] = (seed + i).toByte()
    b[62] = 0xFF.toByte(); b[63] = 0xD9.toByte()
    return b
}
