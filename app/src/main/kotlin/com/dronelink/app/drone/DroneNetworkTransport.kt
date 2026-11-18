package com.dronelink.app.drone

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
 */
class DroneNetworkTransport : DroneTransport {

    @Volatile
    var network: Network? = null

    private var socket: DatagramSocket? = null
    private val addr = InetSocketAddress(DroneProtocol.DRONE_IP, DroneProtocol.CTRL_PORT)

    override fun bind() {
        if (socket != null) return
        val n = network ?: throw IOException("drone network not joined")
        val s = DatagramSocket()
        n.bindSocket(s)
        s.soTimeout = 500
        socket = s
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
