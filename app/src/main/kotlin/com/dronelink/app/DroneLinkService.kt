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
import com.dronelink.app.video.TcpSocketProvider
import com.dronelink.app.video.UdpSocketProvider
import java.io.File
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
        JoinStatus.state = JoinState.REQUESTING
        JoinStatus.detail = "requesting $ssid"
        startEngineMcp()
        requestJoin(ssid)
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
        request = req
        callback = cb
    }

    /**
     * Run the on-device stock ffmpeg against the drone's RTSP while the
     * process is bound to the drone network — the same trick the ijk path
     * used, but with a REAL ffmpeg binary. Captures N JPEG frames to
     * /data/local/tmp/ffprobe_%02d.jpg and returns them as base64, plus the
     * ffmpeg stderr tail (negotiation/depacketization evidence).
     */
    private fun runFfmpegProbe(): Map<String, Any?> {
        val network = JoinStatus.joinedNetwork
            ?: return linkedMapOf("ok" to false, "error" to "not joined to drone network")
        val bin = File(filesDir, "ffmpeg")
        if (!bin.canExecute()) {
            // First run after install: copy from /data/local/tmp (shell-pushed).
            val src = File("/data/local/tmp/ffmpeg")
            if (src.canRead()) {
                runCatching { src.copyTo(bin, overwrite = true); bin.setExecutable(true, false) }
            }
            if (!bin.canExecute()) {
                return linkedMapOf("ok" to false, "error" to "ffmpeg binary missing in app files dir")
            }
        }
        var bound = false
        try {
            connectivityManager.bindProcessToNetwork(network)
            bound = true
        } catch (e: Exception) {
            return linkedMapOf("ok" to false, "error" to "bind: ${e.message}")
        }
        try {
            val outPrefix = File(filesDir, "ffprobe_").absolutePath
            filesDir.listFiles()?.forEach { if (it.name.startsWith("ffprobe_")) it.delete() }
            val args = listOf(
                "-hide_banner", "-loglevel", "info",
                "-y",
                "-rtsp_transport", "udp",
                "-stimeout", "8000000",
                "-t", "6",
                "-i", "rtsp://192.168.1.1:7070/webcam",
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
                "frames" to frames,
            )
        } catch (e: Exception) {
            return linkedMapOf("ok" to false, "error" to "ffmpeg run failed: ${e.message}")
        } finally {
            if (bound) runCatching { connectivityManager.bindProcessToNetwork(null) }
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

    override fun onDestroy() {
        mcp?.stop()
        engine?.shutdown()
        probe?.stop()
        probe = null
        videoHttp?.stop()
        videoHttp = null
        proxyClient?.stop()
        proxyClient = null
        mcp = null
        engine = null
        val cb = callback
        if (cb != null) {
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        callback = null
        request = null
        JoinStatus.state = JoinState.IDLE
        JoinStatus.detail = "service stopped"
        JoinStatus.joinedNetwork = null
        super.onDestroy()
    }
}
