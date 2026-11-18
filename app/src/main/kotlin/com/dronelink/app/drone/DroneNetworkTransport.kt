package com.dronelink.app.drone

import android.content.Context
import android.net.Network
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Android UDP transport for the engine, bound onto the app-scoped drone
 * network via network.bindSocket — the ONLY way to reach 192.168.1.1:7099
 * while the WifiNetworkSpecifier join is held by this app (proven: plain
 * shell sockets see 100% loss on the drone LAN).
 *
 * The [network] slot is set/cleared by DroneLinkService as the join comes
 * and goes. bind() fails with IOException until a joined network exists —
 * tools then surface a JSON-RPC error instead of silently sending nothing.
 *
 * Known failure mode (2026-11-18): if a secure VPN (Tailscale) applies to this
 * app's UID, Android/netd rejects the bind with EPERM even though the join
 * succeeded. [context] is used (when available) to detect that and produce an
 * actionable error instead of a bare errno.
 */
class DroneNetworkTransport(
    private val context: Context? = null,
) : DroneTransport {

    @Volatile
    var network: Network? = null

    private var socket: DatagramSocket? = null
    private val addr = InetSocketAddress(DroneProtocol.DRONE_IP, DroneProtocol.CTRL_PORT)

    override fun bind() {
        if (socket != null) return
        val n = network ?: throw IOException("drone network not joined")
        val s = DatagramSocket()
        try {
            n.bindSocket(s)
        } catch (e: IOException) {
            // EPERM under a secure VPN (Tailscale, 2026-11-18): netd refuses the
            // EXPLICIT cross-network bind call. Bypass (Evan: "bypass bindSocket
            // or whatever we need"): sockets inherit the process default network
            // at creation — so create this socket inside a millisecond-scale
            // bindProcessToNetwork(n) window instead of calling bindSocket at all.
            // The socket keeps the drone network's netId for its whole life;
            // sends/receives need no per-call scoping and the MCP server's
            // tailnet sockets are only exposed during the creation window.
            s.close()
            val scoped = createInherited(n)
            scoped.soTimeout = 500
            socket = scoped
            return
        }
        s.soTimeout = 500
        socket = s
    }

    private fun createInherited(n: Network): DatagramSocket {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: throw IOException(
                "bindSocket failed and no ConnectivityManager for the scoped-create bypass"
            )
        val prev = try {
            cm.boundNetworkForProcess
        } catch (_: Throwable) {
            null
        }
        return try {
            cm.bindProcessToNetwork(n)
            DatagramSocket()
        } finally {
            cm.bindProcessToNetwork(prev) // restore immediately — null unbinds
        }
    }

    override fun send(data: ByteArray) {
        val s = socket ?: return
        s.send(DatagramPacket(data, data.size, addr))
    }

    override fun receive(buf: ByteArray): Int {
        val s = socket ?: return -1
        val pkt = DatagramPacket(buf, buf.size)
        s.receive(pkt)
        return pkt.length
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}
