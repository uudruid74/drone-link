package com.dronelink.app.drone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Creates sockets bound onto an app-scoped [Network] (WifiNetworkSpecifier
 * join). Two strategies, in order:
 *
 *  1. `network.bindSocket(socket)` — the normal explicit cross-network bind.
 *  2. EPERM bypass (Tailscale/secure-VPN, 2026-11-18): netd refuses the
 *     explicit bind for the app's UID. Fallback: create the socket inside a
 *     millisecond `bindProcessToNetwork(n)` window so it inherits the drone
 *     network's netId at creation; the process binding is restored immediately.
 *
 * Used by the control transport, the RTSP proxy, and the telemetry probe —
 * every socket that must talk to 192.168.1.1 on the joined network.
 */
object ScopedSockets {

    /** Run [block] while the process is bound to [n]; restore previous binding. */
    fun <T> withinBoundNetwork(cm: ConnectivityManager?, n: Network, block: () -> T): T {
        if (cm == null) throw IOException("no ConnectivityManager for scoped socket creation")
        val prev = try {
            cm.boundNetworkForProcess
        } catch (_: Throwable) {
            null
        }
        return try {
            cm.bindProcessToNetwork(n)
            block()
        } finally {
            cm.bindProcessToNetwork(prev) // restore immediately — null unbinds
        }
    }

    /** Result of a scoped socket creation: the socket + which strategy won. */
    class ScopedSocket<T>(val socket: T, val mode: String)

    /** UDP socket bound onto [n] (bindSocket, with EPERM inherited fallback). */
    fun newDatagramSocket(cm: ConnectivityManager?, n: Network): ScopedSocket<DatagramSocket> {
        val s = DatagramSocket()
        try {
            n.bindSocket(s)
            return ScopedSocket(s, "bindSocket")
        } catch (e: IOException) {
            s.close()
            return ScopedSocket(withinBoundNetwork(cm, n) { DatagramSocket() }, "inherited-eperm-bypass")
        }
    }

    /** TCP socket bound onto [n], then connected to [addr]. */
    fun newTcpSocket(cm: ConnectivityManager?, n: Network, addr: InetSocketAddress, timeoutMs: Int): ScopedSocket<Socket> {
        val s = Socket()
        try {
            n.bindSocket(s)
        } catch (e: IOException) {
            s.close()
            val created = withinBoundNetwork(cm, n) { Socket() }
            try {
                created.connect(addr, timeoutMs)
                return ScopedSocket(created, "inherited-eperm-bypass")
            } catch (e2: Exception) {
                created.close()
                throw e2
            }
        }
        s.connect(addr, timeoutMs)
        return ScopedSocket(s, "bindSocket")
    }
}
