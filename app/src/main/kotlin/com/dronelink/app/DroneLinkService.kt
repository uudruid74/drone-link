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

/**
 * Phase 1: joins the drone WiFi as a LOCAL-ONLY secondary network via
 * WifiNetworkSpecifier. Cellular stays the default network; the drone subnet
 * becomes reachable on the joined network (app-scoped — see README).
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
    private var relay: UdpRelay? = null

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
        requestJoin(ssid)
        return START_STICKY
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
                JoinStatus.detail = "connected ${network}"
                JoinStatus.joinedNetwork = network
                startRelay(network)
                updateNotification(getString(R.string.notif_connected, ssid, network.toString()))
            }

            override fun onLost(network: Network) {
                stopRelay()
                JoinStatus.state = JoinState.LOST
                JoinStatus.detail = "lost ${network}"
                JoinStatus.joinedNetwork = null
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
     * Phase 2 UDP relay: 127.0.0.1:17099 -> 192.168.1.1:7099 on the joined
     * network. The specifier network is app-scoped, so the relay lives here —
     * the Termux daemon targets localhost.
     */
    private fun startRelay(network: Network) {
        if (relay != null) return
        val r = UdpRelay(network)
        relay = r
        JoinStatus.detail = "connected ${network}, relay :${r.listeningPort}"
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
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

    override fun onDestroy() {
        stopRelay()
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
