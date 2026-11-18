package com.dronelink.app.drone

import android.content.Context
import android.net.Network
import com.dronelink.app.debug.DebugLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Android UDP transport for the engine, bound onto the app-scoped drone
 * network via [ScopedSockets] — the ONLY way to reach 192.168.1.1:7099
 * while the WifiNetworkSpecifier join is held by this app (proven: plain
 * shell sockets see 100% loss on the drone LAN).
 *
 * The [network] slot is set/cleared by DroneLinkService as the join comes
 * and goes. bind() fails with IOException until a joined network exists —
 * tools then surface a JSON-RPC error instead of silently sending nothing.
 *
 * v0.3.0 diagnostics (rx:0 evidence): every bind records which strategy was
 * used ([bindMode]), the resulting local address ([localAddress]), and the
 * source of the last received datagram ([lastReceiveSource]); every received
 * datagram is logged to [DebugLog]. That turns the next device run into
 * packet-level evidence of where telemetry dies.
 */
class DroneNetworkTransport(
    private val context: Context? = null,
) : DroneTransport {

    @Volatile
    var network: Network? = null

    private var socket: DatagramSocket? = null
    private val addr = InetSocketAddress(DroneProtocol.DRONE_IP, DroneProtocol.CTRL_PORT)

    /** Which bind strategy won: "bindSocket" or "inherited-eperm-bypass". */
    @Volatile
    var bindMode: String = "unbound"

    /** Local socket address after bind (IP:port), or null. */
    @Volatile
    var localAddress: String? = null

    /** Source (IP:port) of the last datagram received, or null. */
    @Volatile
    var lastReceiveSource: String? = null

    override fun bind() {
        if (socket != null) return
        val n = network ?: throw IOException("drone network not joined")
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val scoped = ScopedSockets.newDatagramSocket(cm, n)
        scoped.socket.soTimeout = 500
        socket = scoped.socket
        bindMode = scoped.mode
        localAddress = scoped.socket.localAddress?.toString()
        DebugLog.log("transport", "bound mode=$bindMode local=$localAddress -> ${addr.address.hostAddress}:${addr.port}")
    }

    override fun send(data: ByteArray) {
        val s = socket ?: return
        s.send(DatagramPacket(data, data.size, addr))
    }

    override fun receive(buf: ByteArray): Int {
        val s = socket ?: return -1
        val pkt = DatagramPacket(buf, buf.size)
        s.receive(pkt)
        lastReceiveSource = "${pkt.address?.hostAddress}:${pkt.port}"
        return pkt.length
    }

    override fun close() {
        socket?.close()
        socket = null
        bindMode = "unbound"
        localAddress = null
    }
}
