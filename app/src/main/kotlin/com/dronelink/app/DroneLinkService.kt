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
        val s = McpServer(e, onEmergencyLand = ::onEmergencyLand)
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
                    mcp?.videoHost = guessExternalHost() ?: "127.0.0.1"
                    DebugLog.log("video", "proxy up: ${mcp?.videoHost}:${http.boundPort} (rtsp err=${proxy.error})")
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
     * Best guess of an address on THIS phone that the laptop can reach over
     * tailnet (for the proxied video URL). Evan 2026-11-18: never advertise
     * tailnet IPv6 (fd7a) — "IPv6 routing isn't stable here." Prefer tailnet
     * IPv4 (100.x), then any other non-link-local, non-drone-LAN IPv4. No IPv6.
     */
    private fun guessExternalHost(): String? {
        return try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            val v4 = ifaces.toList()
                .filter { i ->
                    val name = i.name.lowercase()
                    !(name.contains("wlan") || name.contains("rmnet"))
                }
                .flatMap { it.inetAddresses.toList() }
                .filter {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.hostAddress!!.contains(":")
                }
                .map { it.hostAddress!! }
                .filter { !it.startsWith("192.168.1.") }
            v4.firstOrNull { it.startsWith("100.") } ?: v4.firstOrNull()
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
