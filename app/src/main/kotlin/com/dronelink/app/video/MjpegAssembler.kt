package com.dronelink.app.video

/**
 * Reassembles JPEG frames from an MJPEG byte stream by scanning for the
 * frame boundaries `0xFF 0xD8` (SOI) … `0xFF 0xD9` (EOI). Used by the RTSP
 * proxy on RTP payload bytes; stdlib only (Evan: no native libs).
 *
 * Marker bytes may be split across chunk boundaries — the assembler keeps the
 * trailing bytes of the previous chunk so a split SOI/EOI is still found.
 * Junk before the first SOI is discarded.
 */
class MjpegAssembler(
    private val onFrame: (frame: ByteArray) -> Unit,
) {
    @Volatile
    var frames = 0
        private set

    @Volatile
    var bytesScanned = 0L
        private set

    private var buf = ByteArray(65536)
    private var len = 0
    private var scanning = true

    fun feed(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        bytesScanned += chunk.size
        if (scanning) {
            val held = len > 0 && buf[len - 1] == 0xFF.toByte()
            val start = indexOf(SOI, chunk, 0, held)
            if (start < 0) {
                holdTail(chunk)
                return
            }
            if (held && start == 0 && chunk[0] == 0xD8.toByte()) {
                // Split SOI: buf already holds the leading 0xFF — carry it
                // into the frame (len stays 1, buf[0]=0xFF).
                scanning = false
                feedFrom(0, chunk)
                return
            }
            scanning = false
            len = 0
            feedFrom(start, chunk)
        } else {
            feedFrom(0, chunk)
        }
    }

    private fun feedFrom(start: Int, chunk: ByteArray) {
        var pos = start
        while (pos < chunk.size) {
            val heldFF = len > 0 && buf[len - 1] == 0xFF.toByte()
            val eoi = indexOf(EOI, chunk, pos, heldFF)
            if (eoi < 0) {
                append(chunk, pos, chunk.size - pos)
                holdTail(chunk)
                return
            }
            // Include BOTH marker bytes. In the split case buf already holds the
            // 0xFF, so only the 0xD9 is appended.
            val include = if (heldFF && eoi == pos && chunk[eoi] == 0xD9.toByte()) 1 else eoi - pos + 2
            append(chunk, pos, include)
            emit()
            scanning = true
            len = 0
            val next = indexOf(SOI, chunk, eoi + 1)
            if (next < 0) {
                holdTail(chunk)
                return
            }
            scanning = false
            pos = next
        }
    }

    private fun emit() {
        val frame = buf.copyOf(len)
        frames++
        onFrame(frame)
    }

    /** Keep the trailing 0xFF (if any) so a split marker is still found. */
    private fun holdTail(chunk: ByteArray) {
        val b = chunk.last()
        if (b == 0xFF.toByte() && (len == 0 || buf[len - 1] != 0xFF.toByte())) {
            append(chunk, chunk.size - 1, 1)
        }
    }

    private fun append(src: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) {
            var cap = buf.size
            while (cap < len + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        src.copyInto(buf, len, off, off + n)
        len += n
    }

    /**
     * Find the pattern in [hay] from [from]. When [heldFF] is true the caller
     * already holds 0xFF at the end of its buffer, so a pattern starting at
     * [from] with 0xD8/0xD9 is a split marker — we report [from] (the second
     * byte) as the match. The caller handles the hold byte separately.
     */
    private fun indexOf(pat: ByteArray, hay: ByteArray, from: Int, heldFF: Boolean = false): Int {
        var i = from
        if (heldFF && i < hay.size && hay[i] == pat[1]) return i
        while (i + pat.size <= hay.size) {
            if (hay[i] == pat[0] && hay[i + 1] == pat[1]) return i
            i++
        }
        return -1
    }

    companion object {
        val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
