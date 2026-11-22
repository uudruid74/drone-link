package com.dronelink.app.video

import com.dronelink.app.BuildConfig
import com.dronelink.app.debug.DebugLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/** Opens UDP sockets (plain in tests; network-scoped in the app). */
fun interface UdpSocketProvider {
    fun open(): DatagramSocket
}

/** Opens TCP sockets (plain in tests; network-scoped in the app). */
fun interface TcpSocketProvider {
    fun open(addr: InetSocketAddress, timeoutMs: Int): Socket
}

/**
 * Live video feed: latest complete JPEG frame + a version counter to wait on.
 */
interface VideoFeed {
    val frameVersion: Long
    val latestFrame: ByteArray?
    /** Block until the frame version changes or [timeoutMs] elapses. */
    fun awaitFrame(seenVersion: Long, timeoutMs: Long): Long
}

/**
 * Minimal RTSP client for the drone's MJPEG stream (Evan/Gopher spec: the
 * RTSP proxy must live in the app because 192.168.1.1:7070/webcam is only
 * reachable via the phone's bound socket). stdlib only.
 *
 * Handshake: OPTIONS → DESCRIBE (parse SDP for payload type + track control)
 * → SETUP (UDP unicast, RTP/AVP) → PLAY. RTP payloads are fed to an
 * [MjpegAssembler] which re-frames JPEGs by 0xFFD8…0xFFD9 boundaries; the
 * latest complete frame is exposed through [VideoFeed] for the HTTP proxy.
 */
class RtspClient(
    private val host: String = DroneVideo.DRONE_IP,
    private val rtspPort: Int = 7070,
    private val path: String = "/webcam",
    private val tcp: TcpSocketProvider,
    private val udp: UdpSocketProvider,
    private val tag: String = "rtsp",
) : VideoFeed {

    private val url get() = "rtsp://$host:$rtspPort$path"

    @Volatile
    var running = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var sessionId: String? = null
        private set

    /** RTP datagrams received (after SETUP/PLAY). 0 while the camera is
     * negotiated but not actually streaming — the black-frame diagnostic. */
    @Volatile
    var rtpPackets: Long = 0
        private set

    @Volatile
    var rtpBytes: Long = 0
        private set

    @Volatile
    private var _frameVersion = 0L

    @Volatile
    private var _latestFrame: ByteArray? = null

    private val lock = Object()

    private var sock: Socket? = null
    private var rtp: DatagramSocket? = null
    private val receiverThreads = mutableListOf<Thread>()

    override val frameVersion: Long get() = _frameVersion
    override val latestFrame: ByteArray? get() = _latestFrame

    override fun awaitFrame(seenVersion: Long, timeoutMs: Long): Long {
        if (_frameVersion != seenVersion) return _frameVersion
        synchronized(lock) {
            if (_frameVersion != seenVersion) return _frameVersion
            try {
                lock.wait(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return _frameVersion
    }

    /** Connect and run the RTSP handshake; spawns the RTP receiver. */
    fun start() {
        if (running) return
        error = null
        try {
            val s = tcp.open(InetSocketAddress(host, rtspPort), 8000)
            s.soTimeout = 5000
            sock = s
            val out = BufferedOutputStream(s.getOutputStream())
            val inp = BufferedInputStream(s.getInputStream())

            // 1. OPTIONS
            val opts = request(out, inp, 1, "OPTIONS", listOf("CSeq: 1"))
            checkOk("OPTIONS", opts)

            // 2. DESCRIBE — parse SDP
            val desc = request(out, inp, 2, "DESCRIBE", listOf("CSeq: 2", "Accept: application/sdp"))
            checkOk("DESCRIBE", desc)
            val sdp = parseSdp(desc.body, url)
            DebugLog.log(tag, "SDP: pt=${sdp.payloadType} control=${sdp.trackControl}")

            // 3. SETUP — UDP unicast transport
            val rtpSock = udp.open()
            rtpSock.soTimeout = 1000
            val rtcpSock = udp.open()
            rtcpSock.soTimeout = 1000
            val clientPort = rtpSock.localPort
            val setup = request(
                out, inp, 3, "SETUP",
                listOf(
                    "CSeq: 3",
                    "Transport: RTP/AVP/UDP;unicast;client_port=$clientPort-${clientPort + 1}",
                ),
                sdp.trackControl,
            )
            checkOk("SETUP", setup)
            sessionId = setup.headers["session"]?.substringBefore(';')?.trim()
            DebugLog.log(tag, "SETUP ok session=$sessionId port=$clientPort")

            // 4. PLAY
            val playHeaders = mutableListOf("CSeq: 4")
            sessionId?.let { playHeaders += "Session: $it" }
            val play = request(out, inp, 4, "PLAY", playHeaders)
            checkOk("PLAY", play)

            running = true
            rtp = rtpSock
            receiverThreads += Thread { rtpLoop(rtpSock) }.apply { isDaemon = true; start() }
            DebugLog.log(tag, "PLAY ok — RTP receiver on udp :$clientPort")
        } catch (e: Exception) {
            error = "RTSP handshake failed: ${e.message}"
            DebugLog.log(tag, error!!)
            close()
        }
    }

    fun stop() {
        running = false
        close()
    }

    private fun close() {
        runCatching { sock?.close() }
        runCatching { rtp?.close() }
        sock = null
        rtp = null
        synchronized(lock) { lock.notifyAll() }
    }

    // ------------------------------------------------------------ RTP receive

    private fun rtpLoop(s: DatagramSocket) {
        val buf = ByteArray(65536)
        // RFC 2435 depacketization: fragments placed by offset, frame done
        // on the RTP marker bit — NOT SOI/EOI scanning (the 8-byte JPEG
        // headers interleave with data and corrupt naively-concatenated
        // frames).
        val reassembler = Rfc2435Reassembler { frame ->
            synchronized(lock) {
                _latestFrame = frame
                _frameVersion++
                lock.notifyAll()
            }
        }
        // Black-frame diagnostic: after PLAY ok the camera should push RTP
        // datagrams. Log the first packet and then every ~500 so a session
        // that negotiated but never streams is visible in the debug log.
        var logEvery = 1L
        // Wire ground-truth capture: per-packet metadata for the first 2
        // frames (offset/type/Q/W/H/marker/seq + first 16 payload bytes).
        // Exposes offset accounting, fragment interleaving and header
        // semantics without needing a packet capture on the device.
        var diagFrames = 0
        val diagLines = mutableListOf<String>()
        var diagSeqPrev = -1
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            val len = try {
                s.receive(pkt)
                pkt.length
            } catch (e: SocketTimeoutException) {
                if (rtpPackets == 0L && logEvery == 1L) {
                    DebugLog.log(tag, "rtp: waiting for first datagram after PLAY ok...")
                    logEvery = 0L
                }
                continue
            } catch (e: Exception) {
                if (running) DebugLog.log(tag, "rtp loop stopped: ${e.message}")
                break
            }
            rtpPackets++
            rtpBytes += len
            // RTP header byte 1 bit 7 = marker (final fragment of a frame).
            val marker = (buf[1].toInt() and 0x80) != 0
            if (rtpPackets >= logEvery) {
                DebugLog.log(
                    tag,
                    "rtp: pkts=$rtpPackets bytes=$rtpBytes frames=${reassembler.frames}",
                )
                logEvery = rtpPackets + 500
            }
            val payload = rtpPayload(buf, len)
            if (payload != null) {
                reassembler.feed(payload, marker)
                if (diagFrames < 2) {
                    val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                    val off = ((payload[1].toInt() and 0xFF) shl 16) or
                        ((payload[2].toInt() and 0xFF) shl 8) or
                        (payload[3].toInt() and 0xFF)
                    val typ = payload[4].toInt() and 0xFF
                    val q = payload[5].toInt() and 0xFF
                    val w = payload[6].toInt() and 0xFF
                    val h = payload[7].toInt() and 0xFF
                    val sb = StringBuilder()
                    sb.append("rtpDiag f=$diagFrames seq=$seq")
                    if (diagSeqPrev >= 0) sb.append(" dseq=${seq - diagSeqPrev}")
                    diagSeqPrev = seq
                    sb.append(" off=$off type=${typ.toString(16)} q=$q w=$w h=$h marker=$marker len=${payload.size} rtpLen=$len")
                    sb.append(" hex=")
                    val n = minOf(16, payload.size)
                    for (i in 0 until n) sb.append("%02x".format(payload[i]))
                    diagLines += sb.toString()
                    if (marker) {
                        DebugLog.log(tag, diagLines.joinToString("\n"))
                        diagLines.clear()
                        diagFrames++
                        diagSeqPrev = -1
                    }
                }
            }
        }
    }

    /**
     * Strip the RTP header (12 bytes + CSRC + optional extension). Returns the
     * payload bytes, or null for a non-valid RTP header.
     */
    private fun rtpPayload(buf: ByteArray, len: Int): ByteArray? {
        if (len < 12) return null
        val cc = buf[0].toInt() and 0x0F
        var off = 12 + cc * 4
        if (off > len) return null
        if ((buf[0].toInt() and 0x10) != 0) {  // extension
            if (off + 4 > len) return null
            val extLen = ((buf[off + 2].toInt() and 0xFF) shl 8 or (buf[off + 3].toInt() and 0xFF)) * 4
            off += 4 + extLen
            if (off > len) return null
        }
        return buf.copyOfRange(off, len)
    }

    // ------------------------------------------------------------ RTSP protocol

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(
        out: BufferedOutputStream,
        inp: BufferedInputStream,
        cseq: Int,
        method: String,
        headers: List<String>,
        uri: String = url,
    ): Response {
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        for (h in headers) sb.append(h).append("\r\n")
        sb.append("User-Agent: DroneLink/${BuildConfig.VERSION_NAME}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        out.flush()
        return readResponse(inp)
    }

    private fun readResponse(inp: BufferedInputStream): Response {
        val statusLine = readLine(inp) ?: throw java.io.IOException("connection closed by server")
        val parts = statusLine.split(" ")
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("bad status line: $statusLine")
        val headers = mutableMapOf<String, String>()
        var contentLength = 0
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                headers[k] = v
                if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = inp.read(bytes, off, contentLength - off)
                if (n == -1) break
                off += n
            }
            String(bytes, 0, off, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return Response(status, headers, body)
    }

    private fun checkOk(what: String, r: Response) {
        if (r.status !in 200..299) {
            throw java.io.IOException("$what failed: HTTP $r.status ${r.body.take(120)}")
        }
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

    // ------------------------------------------------------------------ SDP

    private class SdpInfo(val payloadType: Int, val trackControl: String)

    private fun parseSdp(sdp: String, baseUrl: String): SdpInfo {
        var payloadType = 26
        var trackControl = baseUrl
        var inMedia = false
        for (line in sdp.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("m=") -> {
                    inMedia = true
                    val f = l.split(" ")
                    // m=video <port> RTP/AVP <pt>...
                    val pt = f.getOrNull(3)?.toIntOrNull() ?: f.getOrNull(3)?.toIntOrNull()
                    if (pt != null) payloadType = pt
                }
                l.startsWith("a=control:") -> {
                    val c = l.removePrefix("a=control:").trim()
                    if (c.startsWith("rtsp://")) {
                        trackControl = c
                    } else if (c.isNotEmpty()) {
                        trackControl = baseUrl.trimEnd('/') + "/" + c.trimStart('/')
                    }
                    if (!inMedia) {
                        // session-level control — leave as base unless media section overrides
                    }
                }
                l.startsWith("a=rtpmap:") -> {
                    val m = Regex("""(\d+)\s+JPEG/90000""").find(l)
                    if (m != null) payloadType = m.groupValues[1].toInt()
                }
                l.startsWith("m=") -> { /* no-op */ }
            }
        }
        return SdpInfo(payloadType, trackControl)
    }

    object DroneVideo {
        const val DRONE_IP = "192.168.1.1"
        const val RTSP_PORT = 7070
        const val PATH = "/webcam"
        const val HTTP_PORT = 8080
    }
}
