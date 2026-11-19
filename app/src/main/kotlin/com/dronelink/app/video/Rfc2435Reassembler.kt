package com.dronelink.app.video

/**
 * RFC 2435 RTP JPEG depacketizer.
 *
 * Every RTP payload for MJPEG over RTP starts with an 8-byte JPEG header:
 *   Type-specific(1) | Fragment Offset(3, big-endian) | Type(1) |
 *   Q(1) | Width(1) | Height(1)
 * followed by the JPEG data fragment of this packet. Frames are split
 * across many RTP packets (a webcam can send hundreds per frame), and the
 * RTP MARKER bit is set on the final fragment of a frame.
 *
 * Depacketization: place each fragment at its byte offset into a frame
 * buffer — never concatenate payloads (the 8-byte headers would interleave
 * with JPEG data and corrupt the frame). Emit the complete JPEG only when
 * the frame is done (marker seen). This preserves the original SOI→APP0→
 * …→EOI structure, so no SOI/EOI rescanning is needed.
 */
class Rfc2435Reassembler(
    private val onFrame: (frame: ByteArray) -> Unit,
) {
    @Volatile
    var frames = 0
        private set

    private var frameBuf = ByteArray(0)
    private var written = 0

    /**
     * [payload] must be the RTP payload AFTER the 12-byte RTP header
     * (i.e. starting at the 8-byte JPEG header). [marker] is the RTP
     * marker bit (last fragment of the frame).
     */
    fun feed(payload: ByteArray, marker: Boolean) {
        if (payload.size < 8) return
        val offset = ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        val frag = payload.copyOfRange(8, payload.size)
        if (offset == 0 && written > 0) {
            // A new frame began before the previous one completed (e.g. a
            // dropped marker packet) — discard the partial frame.
            frameBuf = ByteArray(0)
            written = 0
        }
        val end = offset + frag.size
        if (end > frameBuf.size) {
            frameBuf = frameBuf.copyOf(end)
        }
        frag.copyInto(frameBuf, offset)
        if (end > written) written = end
        if (marker) {
            val frame = frameBuf.copyOf(written)
            frames++
            onFrame(frame)
            frameBuf = ByteArray(0)
            written = 0
        }
    }
}
