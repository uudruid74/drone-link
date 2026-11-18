package com.dronelink.app.drone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException

/**
 * Explains DroneNetworkTransport.bind() failures.
 *
 * 2026-11-18 probe: `network.bindSocket` threw
 * "Binding socket to network 492 failed: EPERM (Operation not permitted)" while
 * the WifiNetworkSpecifier join itself succeeded. Root cause (verified against
 * AOSP netd NetworkController::checkUserNetworkAccessLocked and on-device
 * dumpsys): when a secure (non-bypassable) VPN applies to the app's UID, netd
 * refuses binds to any non-VPN network — and the drone WiFi is one. The phone
 * runs Tailscale whose UID ranges cover com.dronelink.app (uid 10453), so every
 * bind to the drone network fails until Tailscale lets this app bypass the VPN.
 */
object DroneBindCause {

    /**
     * Pure classifier — unit-tested on the JVM. [vpnActive] is true when the
     * calling UID's default network is a VPN (our traffic is tunneled).
     */
    fun describe(vpnActive: Boolean, raw: String): String =
        if (vpnActive) {
            "a secure VPN (likely Tailscale) applies to this app, so Android blocks binding the " +
                "UDP socket to the drone network. Fix: Tailscale -> \"Bypass VPN for apps\" -> add " +
                "DroneLink (or stop the VPN). Raw: $raw"
        } else {
            "socket bind failed: $raw"
        }

    fun explain(context: Context?, error: IOException): String =
        describe(isVpnActive(context), error.message ?: error.toString())

    /** True when the default network for THIS uid is a VPN (i.e. our traffic is tunneled). */
    fun isVpnActive(context: Context?): Boolean {
        if (context == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
    }
}
