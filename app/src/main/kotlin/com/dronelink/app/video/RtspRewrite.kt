package com.dronelink.app.video

/**
 * Pure RTSP request rewriting for the loopback forwarder (v0.6.1).
 *
 * Why: with `-rtsp_transport udp`, ffmpeg binds its RTP/RTCP sockets to the
 * negotiated `client_port=N-M` BEFORE sending SETUP. Our old relay also bound
 * that same port (0.0.0.0:N) to forward the drone's media to loopback — an
 * EADDRINUSE collision that was swallowed, after which the `finally` closed
 * the drone socket before the SETUP response arrived (silence, not a 4xx).
 *
 * Fix: allocate a fresh relay port R on the drone-net address, rewrite
 * `client_port=N-M` -> `client_port=R-(R+1)` in the SETUP request before it
 * is forwarded, and relay `0.0.0.0:R` (drone-net) -> `127.0.0.1:N`
 * (ffmpeg's real sockets). No collision: ffmpeg owns N, we own R.
 */
object RtspRewrite {

    private val CLIENT_PORT_RE = Regex("client_port=(\\d+)-(\\d+)")

    /** Parse the negotiated client port range from an RTSP SETUP request. */
    fun clientPorts(request: String): Pair<Int, Int>? {
        val m = CLIENT_PORT_RE.find(request) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return a to b
    }

    /** Rewrite `client_port=N-M` to `client_port=newBase-(newBase+1)`. */
    fun rewriteClientPort(request: String, newBase: Int): String =
        request.replace(CLIENT_PORT_RE, "client_port=$newBase-${newBase + 1}")
}
