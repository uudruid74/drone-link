package com.dronelink.app.drone

import android.content.Context
import android.net.Network
import com.dronelink.app.debug.DebugLog
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * rx:0 evidence probe (v0.3.0). The control socket binds an ephemeral local
 * port; if the drone streams telemetry to a FIXED port (or broadcasts) instead
 * of replying to the sender's source port, the control socket never sees it —
 * that is the #1 hypothesis for the maiden flight's rx:0 despite the drone
 * accepting control. This probe binds wildcard listeners on the candidate
 * fixed ports so the next flight CAPTURES where the telemetry actually lands:
 * every datagram is logged with its source (IP:port) and first bytes.
 *
 * The probe is purely passive — it never sends, so it cannot disturb the
 * drone or the control flow.
 */
class TelemetryProbe(
    context: Context,
    private val network: Network,
    private val ports: IntArray = intArrayOf(7070, 7098, 7099, 8080),
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    private val sockets = mutableListOf<DatagramSocket>()
    private val threads = mutableListOf<Thread>()
    @Volatile
    private var running = false

    val listeningPorts: List<Int> get() = sockets.mapNotNull { it.localPort }

    fun start() {
        if (running) return
        running = true
        for (port in ports) {
            val scoped = try {
                ScopedSockets.newDatagramSocket(cm, network)
            } catch (e: Exception) {
                DebugLog.log("probe", "port $port skipped: ${e.message}")
                continue
            }
            val s = scoped.socket
            try {
                s.bind(java.net.InetSocketAddress(port))
            } catch (e: Exception) {
                // Port busy (another app or SO_REUSEADDR rules) — skip quietly.
                s.close()
                DebugLog.log("probe", "port $port busy: ${e.message}")
                continue
            }
            s.soTimeout = 1000
            sockets += s
            DebugLog.log("probe", "listening on udp :$port (${scoped.mode})")
            threads += Thread {
                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val len = try {
                        s.receive(pkt)
                        if (!running) break
                        pkt.length
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (running) DebugLog.log("probe", "port $port error: ${e.message}")
                        break
                    }
                    val hex = buf.copyOf(len).joinToString(" ") { "%02x".format(it) }.take(96)
                    DebugLog.log(
                        "probe",
                        "udp :$port <- ${pkt.address?.hostAddress}:${pkt.port} len=$len $hex",
                    )
                }
            }.apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running = false
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        threads.clear()
    }
}
