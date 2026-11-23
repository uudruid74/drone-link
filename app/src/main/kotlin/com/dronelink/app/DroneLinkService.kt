package com.dronelink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.IBinder
import com.dronelink.app.debug.DebugLog
import com.dronelink.app.drone.DroneEngine
import com.dronelink.app.drone.DroneNetworkTransport
import com.dronelink.app.drone.ScopedSockets
import com.dronelink.app.drone.TelemetryProbe
import com.dronelink.app.mcp.McpServer
import com.dronelink.app.video.MjpegHttpServer
import com.dronelink.app.video.RtspClient
import com.dronelink.app.video.RtspRewrite
import com.dronelink.app.video.TcpSocketProvider
import com.dronelink.app.video.UdpSocketProvider
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Phase 1: joins the drone WiFi as a LOCAL-ONLY secondary network via
 * WifiNetworkSpecifier. Cellular stays the default network; the drone subnet
 * becomes reachable on the joined network (app-scoped — see README).
 *
 * v0.2: the drone control engine (DroneEngine) + MCP server (McpServer, port
 * 8000) live in this service, bound onto the joined network — retires Termux,
 * the UDP relay, and laptop Python.
 *
 * The service holds the NetworkRequest while running. The system shows one
 * consent panel per join request; the request stays pending until the user
 * approves or the service stops.
 */
class DroneLinkService : Service() {

    companion object {
        const val EXTRA_SSID = "ssid"
        private const val CHANNEL_ID = "drone_join"
        private const val NOTIF_ID = 1
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var request: NetworkRequest? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var ssid: String = ""

    // v0.2: in-app drone engine + MCP server (retires Termux + relay + laptop Python).
    private val transport = DroneNetworkTransport(this)
    private var engine: DroneEngine? = null
    private var mcp: McpServer? = null
    // v0.3.0: rx:0 telemetry probe (passive fixed-port listeners).
    private var probe: TelemetryProbe? = null
    // v0.3.0: RTSP→MJPEG proxy (laptop FPV over tailnet + snapshots).
    private var proxyClient: RtspClient? = null
    private var videoHttp: MjpegHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private val cellKeepAliveCb = object : ConnectivityManager.NetworkCallback() {}

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ssid = intent?.getStringExtra(EXTRA_SSID)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_ssid)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_searching)))
        // Ensure a clean slate before every join: release any leftover session
        // binds (Evan 2026-11-23) — but only if something is actually live.
        DebugLog.log("service", "onStartCommand startId=$startId flags=$flags ssid=$ssid mcp=${mcp != null} engine=${engine != null}")
        releaseAllNetworking()
        JoinStatus.state = JoinState.REQUESTING
        JoinStatus.detail = "requesting $ssid"
        startEngineMcp()
        DebugLog.log("service", "calling requestJoin")
        requestJoin(ssid)
        DebugLog.log("service", "requestJoin returned")
        return START_STICKY
    }

    /**
     * v0.2: start the drone engine + MCP server with the service (lifecycle).
     * The engine connects lazily on the first tool that needs the drone; the
     * MCP server listens on 0.0.0.0:8000 (tailnet-reachable).
     */
    private fun startEngineMcp() {
        if (mcp != null) return
        val e = DroneEngine(transport)
        engine = e
        val s = McpServer(e, onEmergencyLand = ::onEmergencyLand, onDisconnect = ::onDisconnect)
        s.ffmpegProbe = ::runFfmpegProbe
        s.batteryProvider = { com.dronelink.app.drone.BatteryStatus.summary() }
        try {
            s.start()
            mcp = s
        } catch (ex: Exception) {
            // Port in use / bind failure — surface it, don't crash the service.
            JoinStatus.detail = "mcp failed to start: ${ex.message}"
        }
    }

    private fun requestJoin(ssid: String) {
        // Cellular keep-alive — 2026-11-18 live test: OxygenOS tears down the mobile
        // data PDN when any WiFi joins ("it was concurrent, then it dropped the data
        // connection and internet went dead"). A HELD TRANSPORT_CELLULAR+INTERNET
        // request obliges the system to keep the data connection up — the app-strength
        // form of "mobile data always active".
        val cellReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.requestNetwork(cellReq, cellKeepAliveCb)
        DebugLog.log("service", "cellular keep-alive request registered")

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                JoinStatus.state = JoinState.CONNECTED
                JoinStatus.detail = "connected ${network}, mcp :8000"
                JoinStatus.joinedNetwork = network
                transport.network = network
                DebugLog.log("service", "drone network available: $network")
                // v0.3.0: autonomous heartbeat — starts with the service, NOT
                // gated behind an MCP connect. Suspect #1 for rx:0 (no
                // telemetry ever came back): without this, nothing was sent to
                // the drone until an agent called a tool.
                try {
                    engine?.bindAndStartAutonomous()
                    DebugLog.log("heartbeat", "autonomous heartbeat started (1Hz 01 01)")
                } catch (e: Exception) {
                    DebugLog.log("service", "autonomous heartbeat failed: ${e.message}")
                }
                // Passive telemetry probe on candidate fixed ports.
                try {
                    val p = TelemetryProbe(this@DroneLinkService, network)
                    p.start()
                    probe = p
                    DebugLog.log("probe", "listening on ${p.listeningPorts}")
                } catch (e: Exception) {
                    DebugLog.log("service", "telemetry probe failed: ${e.message}")
                }
                // RTSP → MJPEG proxy: FPV for the laptop over tailnet + snapshots.
                // Custom RTSP handshake + RTP receiver bound to the drone's
                // secondary network; vendor JPEG header templates reassemble
                // the MJPEG stream (ijk removed 2026-11-22 — it never
                // completed prepare on the secondary network).
                try {
                    val cm = connectivityManager
                    val proxy = RtspClient(
                        tcp = TcpSocketProvider { addr, timeoutMs ->
                            ScopedSockets.newTcpSocket(cm, network, addr, timeoutMs).socket
                        },
                        udp = UdpSocketProvider {
                            ScopedSockets.newDatagramSocket(cm, network).socket
                        },
                    )
                    proxy.start()
                    proxyClient = proxy
                    val http = MjpegHttpServer(proxy)
                    http.start()
                    videoHttp = http
                    mcp?.videoFeed = proxy
                    mcp?.videoPort = http.boundPort
                    mcp?.videoHostResolver = { guessExternalHost() }
                    mcp?.videoFirstFrame = proxy.firstFrameDump
                    DebugLog.log("video", "proxy up: :${http.boundPort} (rtsp err=${proxy.error})")
                } catch (e: Exception) {
                    DebugLog.log("service", "video proxy failed: ${e.message}")
                }
                updateNotification(getString(R.string.notif_connected, ssid, network.toString()))
            }

            override fun onLost(network: Network) {
                transport.network = null
                probe?.stop()
                probe = null
                videoHttp?.stop()
                videoHttp = null
                proxyClient?.stop()
                proxyClient = null
                JoinStatus.state = JoinState.LOST
                JoinStatus.detail = "lost ${network}"
                JoinStatus.joinedNetwork = null
                DebugLog.log("service", "drone network lost: $network")
                updateNotification(getString(R.string.notif_lost, ssid))
            }

            override fun onUnavailable() {
                JoinStatus.state = JoinState.UNAVAILABLE
                JoinStatus.detail = "unavailable (timed out?)"
                updateNotification(getString(R.string.notif_unavailable, ssid))
            }
        }
        connectivityManager.requestNetwork(req, cb)
        DebugLog.log("service", "wifi specifier request registered (ssid=$ssid)")
        request = req
        callback = cb
    }

    /**
     * Run the on-device stock ffmpeg against the drone's RTSP. The spawned
     * child process cannot bind to the drone's secondary network (Android
     * process binding doesn't propagate across exec), so ffmpeg talks to a
     * loopback TCP forwarder running inside this (network-bound via scoped
     * sockets) process, which relays to the drone. The drone's RTSP is
     * UDP-only (TCP interleave is refused with 461), so media rides UDP: the
     * forwarder rewrites SETUP's client_port to fresh relay ports and bridges
     * the drone's UDP media to ffmpeg's loopback sockets. Captures N JPEG
     * frames to filesDir/ffprobe_%02d.jpg and returns them as base64, plus
     * ffmpeg's stderr tail.
     */
    private fun runFfmpegProbe(): Map<String, Any?> {
        val network = JoinStatus.joinedNetwork
            ?: return linkedMapOf("ok" to false, "error" to "not joined to drone network")
        // The staging sandbox cannot read /data/local/tmp; the binary must be
        // staged into filesDir (adb run-as cp ... files/ffmpeg).
        val bin = File(filesDir, "ffmpeg")
        if (!bin.canExecute()) {
            return linkedMapOf("ok" to false, "error" to "ffmpeg binary missing in app files dir (stage via run-as)")
        }
        val forwarder = TcpForwarder(connectivityManager, network)
        val localPort = try {
            forwarder.start()
        } catch (e: Exception) {
            return linkedMapOf("ok" to false, "error" to "forwarder start failed: ${e.message}")
        }
        try {
            val outPrefix = File(filesDir, "ffprobe_").absolutePath
            filesDir.listFiles()?.forEach { if (it.name.startsWith("ffprobe_")) it.delete() }
            val args = listOf(
                "-hide_banner", "-loglevel", "verbose",
                "-y",
                "-rtsp_transport", "udp",
                "-t", "6",
                "-i", "rtsp://127.0.0.1:$localPort/webcam",
                "-c:v", "copy",
                "-frames:v", "4",
                "-f", "image2",
                "$outPrefix%02d.jpg",
            )
            // SELinux blocks execve of app_data_file binaries from the app
            // domain; the standard workaround is invoking via the system
            // linker (which itself is system-executable).
            val cmd = mutableListOf("/system/bin/linker64", bin.absolutePath)
            cmd += args
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val err = p.errorStream.bufferedReader().readText()
            val ok = p.waitFor(30, TimeUnit.SECONDS)
            if (!ok) p.destroyForcibly()
            val files = filesDir.listFiles()
                ?.filter { it.name.startsWith("ffprobe_") && it.isFile }
                ?.sortedBy { it.name }
                ?: emptyList()
            val frames = files.associate { f ->
                f.name to Base64.getEncoder().encodeToString(f.readBytes())
            }
            return linkedMapOf(
                "ok" to true,
                "exit" to (if (ok) p.exitValue() else -99),
                "stderr_tail" to err.takeLast(2000),
                "stderr_full" to err,
                "traffic" to forwarder.upstreamCapture + "\n=== DOWN ===\n" + forwarder.downstreamCapture,
                "frames" to frames,
            )
        } catch (e: Exception) {
            return linkedMapOf("ok" to false, "error" to "ffmpeg run failed: ${e.message}")
        } finally {
            forwarder.stop()
        }
    }

    /**
     * Loopback TCP relay: accepts local connections (ffmpeg) and bridges them
     * to the drone's RTSP port over a scoped socket bound to the drone
     * network. Nothing here depends on process-level network binding. Also
     * sniffs SETUP's client_port and bridges the drone's UDP media ports to
     * loopback (the drone is UDP-only — TCP interleave is refused with 461).
     */
    private class TcpForwarder(
        private val cm: ConnectivityManager,
        private val network: Network,
    ) {
        private val server = ServerSocket()
        @Volatile private var running = false
        private val threads = mutableListOf<Thread>()
        private val udpRelays = mutableListOf<UdpRelayPair>()

        /** Raw RTSP control traffic (both directions) for offline diagnosis. */
        @Volatile
        var upstreamCapture: String = ""
        @Volatile
        var downstreamCapture: String = ""

        fun start(): Int {
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", 0))
            running = true
            val acceptor = Thread { acceptLoop() }.apply { isDaemon = true; start() }
            threads += acceptor
            return server.localPort
        }

        private fun acceptLoop() {
            while (running) {
                val client = try { server.accept() } catch (e: Exception) { break }
                var drone: Socket? = null
                try {
                    drone = ScopedSockets.newTcpSocket(
                        cm, network, InetSocketAddress("192.168.1.1", 7070), 8000
                    ).socket
                } catch (e: Exception) {
                    runCatching { client.close() }
                    continue
                }
                val d = drone
                // Upstream: ffmpeg -> drone. Buffer and forward every complete
                // RTSP request (OPTIONS -> DESCRIBE -> SETUP -> PLAY). When a
                // SETUP negotiates client_port=N-M, allocate a fresh relay port
                // pair (R, R+1) on the drone-net address, rewrite the request to
                // use R-(R+1), and relay R->N / R+1->N+1. This avoids colliding
                // with ffmpeg's own sockets (it binds N before SETUP), which
                // previously caused a swallowed EADDRINUSE + early close = the
                // drone's silent non-response to SETUP. NOTE: streaming is never
                // switched on — every request is framed to \r\n\r\n and routed
                // through RtspRewrite (after PLAY there are no further upstream
                // RTSP messages, so framing costs nothing).
                val up = Thread {
                    try {
                        val buf = ByteArray(8192)
                        val cap = StringBuilder()
                        val pending = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = client.inputStream.read(buf)
                            if (n < 0) break
                            pending.write(buf, 0, n)
                            val chunk = String(buf, 0, n, Charsets.ISO_8859_1)
                            if (cap.length < 16000) {
                                cap.append(chunk)
                                if (cap.length > 16000) cap.setLength(16000)
                            }
                            upstreamCapture = cap.toString()
                            // Forward every complete request; keep any partial tail.
                            var raw = pending.toString(Charsets.ISO_8859_1)
                            var headEnd = raw.indexOf("\r\n\r\n")
                            while (headEnd >= 0) {
                                val req = raw.substring(0, headEnd + 4)
                                val ports = RtspRewrite.clientPorts(req)
                                if (ports != null) {
                                    val relay = UdpRelayPair(cm, network)
                                    if (relay.start(ports.first)) {
                                        val rewritten = RtspRewrite.rewriteClientPort(req, relay.portA)
                                        d.outputStream.write(rewritten.toByteArray(Charsets.ISO_8859_1))
                                        d.outputStream.flush()
                                        DebugLog.log(
                                            "forwarder",
                                            "SETUP client_port=${ports.first}-${ports.second} -> " +
                                                "${relay.portA}-${relay.portB}",
                                        )
                                        synchronized(udpRelays) { udpRelays += relay }
                                    } else {
                                        d.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
                                        d.outputStream.flush()
                                    }
                                } else {
                                    d.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
                                    d.outputStream.flush()
                                }
                                raw = raw.substring(headEnd + 4)
                                headEnd = raw.indexOf("\r\n\r\n")
                            }
                            pending.reset()
                            if (raw.isNotEmpty()) {
                                pending.write(raw.toByteArray(Charsets.ISO_8859_1))
                            }
                        }
                        if (pending.size() > 0) {
                            d.outputStream.write(pending.toByteArray())
                            d.outputStream.flush()
                        }
                    } catch (e: Exception) {
                        DebugLog.log("forwarder", "up pump failed: ${e.message}")
                    } finally {
                        runCatching { d.close() }
                        runCatching { client.close() }
                    }
                }.apply { isDaemon = true; start() }
                val down = Thread {
                    try {
                        val buf = ByteArray(8192)
                        val cap = StringBuilder()
                        while (true) {
                            val n = d.inputStream.read(buf)
                            if (n < 0) break
                            client.outputStream.write(buf, 0, n)
                            client.outputStream.flush()
                            val chunk = String(buf, 0, n, Charsets.ISO_8859_1)
                            if (cap.length < 16000) {
                                cap.append(chunk)
                                if (cap.length > 16000) cap.setLength(16000)
                            }
                            downstreamCapture = cap.toString()
                        }
                        runCatching { d.close() }
                        runCatching { client.close() }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { d.close() }
                        runCatching { client.close() }
                    }
                }.apply { isDaemon = true; start() }
                threads += up
                threads += down
            }
        }

        fun stop() {
            running = false
            runCatching { server.close() }
            threads.forEach { runCatching { it.interrupt() } }
            synchronized(udpRelays) {
                udpRelays.forEach { runCatching { it.stop() } }
                udpRelays.clear()
            }
        }

        /**
         * Relays the drone's UDP media to ffmpeg's loopback sockets. Allocates
         * TWO consecutive free ports (R, R+1) on the drone-net address — the
         * SETUP request is rewritten to client_port=R-(R+1) so the drone sends
         * RTP to R and RTCP to R+1, both of which are forwarded to ffmpeg's
         * real sockets (N, N+1 on 127.0.0.1). ffmpeg already owns N/N+1, so
         * binding here is a hard collision — never bind N.
         */
        private inner class UdpRelayPair(
            private val cm: ConnectivityManager,
            private val network: Network,
        ) {
            var portA: Int = 0
                private set
            var portB: Int = 0
                private set
            private var sockA: DatagramSocket? = null
            private var sockB: DatagramSocket? = null
            @Volatile private var stopped = false
            private val threads = mutableListOf<Thread>()

            /** @param forwardBase ffmpeg's real client_port N (loopback target). */
            fun start(forwardBase: Int): Boolean {
                return try {
                    val local4 = cm?.getLinkProperties(network)?.linkAddresses
                        ?.mapNotNull { it.address }
                        ?.filterIsInstance<java.net.Inet4Address>()
                        ?.firstOrNull()
                        ?: run {
                            DebugLog.log("forwarder", "relay: no IPv4 on drone net")
                            return false
                        }
                    // Bind on the drone-net address (never wildcard — a 0.0.0.0
                    // bind rides the process-default network). Port 0 picks a
                    // free ephemeral port.
                    val sa = ScopedSockets.newDatagramSocketUnbound(cm, network).socket
                    sa.reuseAddress = true
                    sa.bind(InetSocketAddress(local4, 0))
                    portA = sa.localPort
                    sockA = sa
                    // Try to get the consecutive port for the second socket;
                    // re-roll portA if the neighbour is taken.
                    var sb: DatagramSocket? = null
                    var attempt = 0
                    while (sb == null && attempt < 5) {
                        attempt++
                        val s = ScopedSockets.newDatagramSocketUnbound(cm, network).socket
                        s.reuseAddress = true
                        try {
                            s.bind(InetSocketAddress(local4, portA + 1))
                            sb = s
                        } catch (_: Exception) {
                            runCatching { s.close() }
                            runCatching { sa.close() }
                            val sA = ScopedSockets.newDatagramSocketUnbound(cm, network).socket
                            sA.reuseAddress = true
                            sA.bind(InetSocketAddress(local4, 0))
                            portA = sA.localPort
                            sockA = sA
                        }
                    }
                    val sB = sb ?: run { runCatching { sa.close() }; sockA = null; return false }
                    portB = sB.localPort
                    sockB = sB
                    threads += relayThread(sockA!!, forwardBase)
                    threads += relayThread(sockB!!, forwardBase + 1)
                    DebugLog.log("forwarder", "relay ${portA}-${portB} -> 127.0.0.1:$forwardBase-${forwardBase + 1}")
                    true
                } catch (e: Exception) {
                    DebugLog.log("forwarder", "relay start failed: ${e.message}")
                    stop()
                    false
                }
            }

            private fun relayThread(s: DatagramSocket, targetPort: Int): Thread =
                Thread {
                    val pkt = DatagramPacket(ByteArray(65536), 65536)
                    while (!stopped) {
                        try {
                            s.receive(pkt)
                            s.send(DatagramPacket(pkt.data, pkt.length, InetSocketAddress("127.0.0.1", targetPort)))
                        } catch (_: Exception) {
                            break
                        }
                    }
                }.apply { isDaemon = true; start() }

            fun stop() {
                stopped = true
                runCatching { sockA?.close() }
                runCatching { sockB?.close() }
                threads.forEach { runCatching { it.interrupt() } }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title, ssid))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /**
     * v0.3.0: emergency_land kill switch — the MCP server stops until the app
     * restarts. Called by the MCP server after it executes emergency_land.
     */
    fun onEmergencyLand() {
        DebugLog.log("service", "emergency_land kill switch — stopping MCP server")
        mcp?.stop()
        mcp = null
        JoinStatus.state = JoinState.KILLED
        JoinStatus.detail = "kill switch engaged — MCP stopped until app restart"
        updateNotification(getString(R.string.notif_killed))
    }

    /**
     * MCP `disconnect`: the engine stops its control/rx loops; this tears
     * down the RTSP session + HTTP MJPEG proxy (unbind the video sockets)
     * so the drone's camera link and the laptop-facing HTTP listener close.
     * The autonomous heartbeat and the MCP server itself stay up.
     */
    fun onDisconnect() {
        DebugLog.log("service", "MCP disconnect — stopping video proxy")
        videoHttp?.stop()
        videoHttp = null
        proxyClient?.stop()
        proxyClient = null
    }

    /**
     * The address on THIS phone that the laptop can reach over tailnet (for
     * the proxied video URL). Evan 2026-11-18: never advertise tailnet IPv6
     * (fd7a) — "IPv6 routing isn't stable here." The ONLY correct candidate is
     * the tailnet IPv4 (100.x, CGNAT 100.64/10). Anything else (RFC1918
     * wlan/172.x bridges, etc.) is NOT reachable by the laptop over tailnet
     * and must never be advertised — a wrong URL is worse than none. Returns
     * null when no tailnet IPv4 is present (tailscale not up yet).
     */
    private fun guessExternalHost(): String? {
        return try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            ifaces.toList()
                .flatMap { it.inetAddresses.toList() }
                .map { it.hostAddress!! }
                .firstOrNull { it.startsWith("100.") }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Release ALL networking: video proxy, telemetry probe, engine control
     * socket, and every network callback. Called before a fresh join and at
     * service destroy so no stale connection/bind blocks the next session
     * (Evan 2026-11-23: force-stop + full bind-release discipline).
     */
    private fun releaseAllNetworking(clearStatus: Boolean = false) {
        val hasLive = videoHttp != null || proxyClient != null || probe != null ||
            engine != null || callback != null
        DebugLog.log(
            "service",
            "releasing all networking" + if (clearStatus) " (stop)" else " (pre-join)" +
                " live=$hasLive"
        )
        if (!hasLive && !clearStatus) return
        videoHttp?.stop()
        videoHttp = null
        proxyClient?.stop()
        proxyClient = null
        probe?.stop()
        probe = null
        engine?.shutdown()
        val cb = callback
        if (cb != null) {
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        callback = null
        request = null
        runCatching { connectivityManager.unregisterNetworkCallback(cellKeepAliveCb) }
        if (clearStatus) {
            JoinStatus.state = JoinState.IDLE
            JoinStatus.detail = "service stopped"
            JoinStatus.joinedNetwork = null
        }
    }

    override fun onDestroy() {
        releaseAllNetworking(clearStatus = true)
        mcp?.stop()
        mcp = null
        engine = null
        super.onDestroy()
    }
}
