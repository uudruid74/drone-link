package com.dronelink.app

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Phase 2: localhost UDP relay. Termux daemon talks to 127.0.0.1:17099 and
 * datagrams are forwarded to the drone control port over the specifier-bound
 * network (app-scoped — plain sockets outside this app cannot reach it).
 *
 * Both directions:
 *   local 127.0.0.1:17099  -> drone 192.168.1.1:7099 (via network.bindSocket)
 *   drone replies            -> last local sender
 */
class UdpRelay(
    private val network: Network,
    private val localPort: Int = 17099,
    private val droneHost: String = "192.168.1.1",
    private val dronePort: Int = 7099
) {
    private val running = AtomicBoolean(true)
    private val localSocket = DatagramSocket(null)
    private val droneSocket = DatagramSocket()
    @Volatile
    private var lastClient: InetSocketAddress? = null
    private val droneAddress = InetSocketAddress(droneHost, dronePort)

    private val localThread: Thread
    private val droneThread: Thread

    init {
        localSocket.reuseAddress = true
        localSocket.bind(InetSocketAddress("127.0.0.1", localPort))
        // Force the drone socket onto the specifier network. This is the only
        // way to reach the app-scoped drone subnet.
        network.bindSocket(droneSocket)

        localThread = thread(name = "relay-local") {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    localSocket.receive(pkt)
                    lastClient = pkt.socketAddress as InetSocketAddress
                    val out = DatagramPacket(
                        pkt.data, pkt.offset, pkt.length,
                        droneAddress.address, droneAddress.port
                    )
                    droneSocket.send(out)
                } catch (e: Exception) {
                    if (running.get()) {
                        // Only surface fatal socket errors; transient receive
                        // interruptions on close are expected.
                        if (localSocket.isClosed || droneSocket.isClosed) break
                        e.printStackTrace()
                    } else {
                        break
                    }
                }
            }
        }

        droneThread = thread(name = "relay-drone") {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    droneSocket.receive(pkt)
                    val client = lastClient ?: continue
                    val reply = DatagramPacket(pkt.data, pkt.offset, pkt.length, client)
                    localSocket.send(reply)
                } catch (e: Exception) {
                    if (running.get()) {
                        if (localSocket.isClosed || droneSocket.isClosed) break
                        e.printStackTrace()
                    } else {
                        break
                    }
                }
            }
        }
    }

    val listeningPort: Int
        get() = localSocket.localPort

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { localSocket.close() }
        runCatching { droneSocket.close() }
        localThread.join(1000)
        droneThread.join(1000)
    }
}
