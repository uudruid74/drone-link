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

    /** UDP socket bound onto [n] (bindSocket, with EPERM inherited fallback).
     *  [peer] (when given) is CONNECTED INSIDE the bound window — Android
     *  stamps the routing at connect/use time, and the old create-in-window/
     *  use-outside pattern leaked sockets onto the cellular default (rx:0
     *  root-cause candidate: heartbeats sent to nowhere). */
    fun newDatagramSocket(
        cm: ConnectivityManager?,
        n: Network,
        peer: InetSocketAddress? = null,
    ): ScopedSocket<DatagramSocket> {
        val s = DatagramSocket()
        try {
            n.bindSocket(s)
            peer?.let { s.connect(it) }
            return ScopedSocket(s, "bindSocket")
        } catch (e: IOException) {
            s.close()
            val made = withinBoundNetwork(cm, n) {
                DatagramSocket().also { d -> peer?.let { d.connect(it) } }
            }
            return ScopedSocket(made, "inherited-eperm-bypass")
        }
    }

    /**
     * UNBOUND UDP socket scoped to [n] — for FIXED-port listeners.
     * Java's `DatagramSocket()` constructor auto-binds to an ephemeral port, so
     * a later `bind(port)` always throws "already bound" (the probe's
     * `listening on []` bug — Evan 2026-11-18). `DatagramSocket(null)` stays
     * unbound until the caller binds its fixed port.
     */
    fun newDatagramSocketUnbound(cm: ConnectivityManager?, n: Network): ScopedSocket<DatagramSocket> {
        val s = java.net.DatagramSocket(null)
        try {
            n.bindSocket(s)
            return ScopedSocket(s, "bindSocket")
        } catch (e: IOException) {
            s.close()
            return ScopedSocket(withinBoundNetwork(cm, n) { java.net.DatagramSocket(null) }, "inherited-eperm-bypass")
        }
    }

    /** TCP socket bound onto [n], then connected to [addr] — connect runs
     *  INSIDE the bound window on the bypass path (route stamped at connect). */
    fun newTcpSocket(cm: ConnectivityManager?, n: Network, addr: InetSocketAddress, timeoutMs: Int): ScopedSocket<Socket> {
        val s = Socket()
        try {
            n.bindSocket(s)
        } catch (e: IOException) {
            s.close()
            val created = withinBoundNetwork(cm, n) {
                Socket().also { it.connect(addr, timeoutMs) }
            }
            return ScopedSocket(created, "inherited-eperm-bypass")
        }
        s.connect(addr, timeoutMs)
        return ScopedSocket(s, "bindSocket")
    }
}
