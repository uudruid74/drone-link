package com.dronelink.app.video

/**
 * RFC 2435 RTP JPEG depacketizer.
 *
 * Every RTP payload for MJPEG over RTP starts with an 8-byte main JPEG header:
 *   Type-specific(1) | Fragment Offset(3, big-endian) | Type(1) |
 *   Q(1) | Width(1) | Height(1)
 * followed — on the first fragment of a frame only (fragment offset 0) — by:
 *   - a 4-byte Restart Marker header when the Type field has bit 0x40 set
 *     (Restart Interval(16, big-endian) | F(1) | L(1) | Restart Count(14)),
 *   - a 4-byte Quantization Table header when Q >= 128
 *     (MBZ | Precision | Length(16, big-endian)), plus `Length` bytes of
 *     quantization table data when Length > 0,
 * and then the JPEG payload fragment (entropy-coded scan data).
 *
 * RFC 2435 represents JPEG in abbreviated form: the frame and scan headers
 * (SOI/APP0/DQT/DHT/SOF0/SOS) are NOT transmitted — they are inferred from the
 * RTP/JPEG header fields. The depacketizer therefore rebuilds the missing JPEG
 * header segments and prepends them to the reassembled scan so the emitted
 * frame is a valid interchange-format JPEG.
 *
 * Reassembly: place each fragment at its byte offset into a frame buffer —
 * never concatenate payloads (the 8-byte headers would interleave with JPEG
 * data and corrupt the frame), never rescan for SOI/EOI. Emit the complete
 * JPEG only when the RTP MARKER bit arrives (final fragment of the frame).
 * Quantization/huffman tables are the standard JPEG Annex K tables (scaled per
 * Q when Q in 1..99, or taken verbatim from the payload when Q >= 128).
 */
class Rfc2435Reassembler(
    private val onFrame: (frame: ByteArray) -> Unit,
) {
    @Volatile
    var frames = 0
        private set

    private var frameBuf = ByteArray(0)
    private var written = 0
    private var header: ByteArray? = null
    private var sawStart = false

    /**
     * [payload] must be the RTP payload AFTER the 12-byte RTP header
     * (i.e. starting at the 8-byte main JPEG header). [marker] is the RTP
     * marker bit (last fragment of the frame).
     */
    fun feed(payload: ByteArray, marker: Boolean) {
        if (payload.size < 8) return
        val offset = ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        val type = payload[4].toInt() and 0xFF
        val q = payload[5].toInt() and 0xFF
        val width = payload[6].toInt() and 0xFF
        val height = payload[7].toInt() and 0xFF
        var pos = 8

        if (offset == 0) {
            // A new frame begins. Any partial previous frame is discarded.
            frameBuf = ByteArray(0)
            written = 0
            header = null
            sawStart = true

            // The restart-marker header (dri, F/L bits, restart count) is sent by
            // this camera on the first fragment only; consume it there.
            var dri = 0
            if ((type and 0x40) != 0) {
                if (payload.size - pos < 4) return
                dri = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                pos += 4
            }

            val tables: ByteArray = when {
                q >= 128 -> {
                    // Quantization Table header + table data.
                    if (payload.size - pos < 4) return
                    val qlen = ((payload[pos + 2].toInt() and 0xFF) shl 8) or
                        (payload[pos + 3].toInt() and 0xFF)
                    pos += 4
                    if (qlen == 0) {
                        // Q=255 + Length=0 is invalid; other Q>=128 values must
                        // reference out-of-band tables we don't track — drop.
                        return
                    }
                    if (qlen % 64 != 0 || qlen > 256 || payload.size - pos < qlen) return
                    val t = payload.copyOfRange(pos, pos + qlen)
                    pos += qlen
                    t
                }
                q in 1..99 -> deriveQuantTables(q)
                else -> return // q == 0 or q > 99 is reserved/invalid
            }

            val hdr = buildJpegHeader(type and 0x3F, width, height, dri, tables) ?: return
            header = hdr
        } else if (!sawStart) {
            // No start packet seen: can't build headers — discard the frame.
            return
        }

        val frag = payload.copyOfRange(pos, payload.size)
        val end = offset + frag.size
        if (end > frameBuf.size) {
            frameBuf = frameBuf.copyOf(end)
        }
        frag.copyInto(frameBuf, offset)
        if (end > written) written = end

        if (marker && sawStart) {
            val hdr = header ?: return
            var frame = ByteArray(hdr.size + written)
            hdr.copyInto(frame, 0)
            frameBuf.copyInto(frame, hdr.size, 0, written)
            // RFC 2435 scans may end implicitly; ensure an explicit EOI.
            if (frame.size < 2 || frame[frame.size - 2] != 0xFF.toByte() || frame[frame.size - 1] != 0xD9.toByte()) {
                frame = frame + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            }
            frames++
            onFrame(frame)
            frameBuf = ByteArray(0)
            written = 0
            header = null
            sawStart = false
        }
    }

    // ------------------------------------------------------------- JPEG header

    /**
     * Build the JPEG interchange-format header segments for a type-0/1
     * (baseline, YUV, single interleaved scan) frame, mirroring the RFC 2435
     * Appendix B/C reference code and FFmpeg's rtpdec_jpeg.c.
     * Returns null for unsupported types.
     */
    private fun buildJpegHeader(
        type: Int,
        widthBlocks: Int,
        heightBlocks: Int,
        dri: Int,
        qtables: ByteArray,
    ): ByteArray? {
        if (type > 1) return null
        val w = widthBlocks shl 3
        val h = heightBlocks shl 3
        val nTables = qtables.size / 64
        val out = java.io.ByteArrayOutputStream()

        fun marker(code: Int) {
            out.write(0xFF)
            out.write(code)
        }

        fun segment(code: Int, body: ByteArray) {
            marker(code)
            out.write((body.size + 2) ushr 8)
            out.write((body.size + 2) and 0xFF)
            out.write(body)
        }

        // SOI
        marker(0xD8)

        // APP0 / JFIF
        marker(0xE0)
        out.write(0x00); out.write(0x10)
        out.write("JFIF\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(0x01); out.write(0x02) // version 1.02
        out.write(0x00)                   // density units
        out.write(0x00); out.write(0x01) // X density
        out.write(0x00); out.write(0x01) // Y density
        out.write(0x00)                   // thumbnail width
        out.write(0x00)                   // thumbnail height

        // DRI (only when restart markers are present)
        if (dri > 0) {
            marker(0xDD)
            out.write(0x00); out.write(0x04)
            out.write(dri ushr 8); out.write(dri and 0xFF)
        }

        // DQT
        marker(0xDB)
        out.write(0x00); out.write(2 + nTables * 65)
        for (i in 0 until nTables) {
            out.write(i)
            out.write(qtables, i * 64, 64)
        }

        // DHT — standard Annex K tables (DC/AC x luma/chroma)
        segment(0xC4, STANDARD_DHT)

        // SOF0
        marker(0xC0)
        out.write(0x00); out.write(0x11) // length 17
        out.write(0x08)                  // precision
        out.write(h ushr 8); out.write(h and 0xFF)
        out.write(w ushr 8); out.write(w and 0xFF)
        out.write(0x03)                  // number of components
        out.write(0x01)                  // component 1 (Y)
        out.write(if (type == 1) 0x22 else 0x21) // h/v sampling (4:2:0 vs 4:2:2)
        out.write(0x00)                  // quantization table 0
        out.write(0x02)                  // component 2 (U)
        out.write(0x11)
        out.write(if (nTables == 2) 0x01 else 0x00)
        out.write(0x03)                  // component 3 (V)
        out.write(0x11)
        out.write(if (nTables == 2) 0x01 else 0x00)

        // SOS
        marker(0xDA)
        out.write(0x00); out.write(0x0C) // length 12
        out.write(0x03)                  // component count
        out.write(0x01); out.write(0x00) // Y: DC/AC table 0
        out.write(0x02); out.write(0x11) // U: DC/AC table 1
        out.write(0x03); out.write(0x11) // V: DC/AC table 1
        out.write(0x00)                  // spectral selection start
        out.write(0x3F)                  // spectral selection end
        out.write(0x00)                  // successive approximation

        return out.toByteArray()
    }

    /**
     * IJG scaled quantization tables for Q in 1..99 (RFC 2435 §4.2).
     * Base tables are the standard Annex K.1/K.2 tables.
     */
    private fun deriveQuantTables(q: Int): ByteArray {
        val s = if (q <= 50) 5000 / q else 200 - 2 * q
        val out = ByteArray(128)
        for (i in 0 until 128) {
            val v = (BASE_QUANTIZERS[i] * s + 50) / 100
            out[i] = (v.coerceIn(1, 255)).toByte()
        }
        return out
    }

    private companion object {
        /** Standard JPEG Annex K.1/K.2 base quantization tables (luma, chroma). */
        val BASE_QUANTIZERS = intArrayOf(
            16, 11, 12, 14, 12, 10, 16, 14,
            13, 14, 18, 17, 16, 19, 24, 40,
            26, 24, 22, 22, 24, 49, 35, 37,
            29, 40, 58, 51, 61, 60, 57, 51,
            56, 55, 64, 72, 92, 78, 64, 68,
            87, 69, 55, 56, 80, 109, 81, 87,
            95, 98, 103, 104, 103, 62, 77, 113,
            121, 112, 100, 120, 92, 101, 103, 99,
            17, 18, 18, 24, 21, 24, 47, 26,
            26, 47, 99, 66, 56, 66, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99,
        )

        /**
         * Standard Annex K Huffman tables (DC luma, DC chroma, AC luma,
         * AC chroma) in DHT segment payload form: for each table, a 1-byte
         * class/id ( then 16 code-length counts then the symbol values.
         */
        val STANDARD_DHT: ByteArray = run {
            val dcLumBits = byteArrayOf(0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0)
            val dcLumVals = ByteArray(12) { it.toByte() }
            val dcChrBits = byteArrayOf(0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0)
            val dcChrVals = ByteArray(12) { it.toByte() }
            val acLumBits = byteArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
            val acLumVals = intArrayOf(
                0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
                0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
                0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
                0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
                0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
                0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
                0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
                0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
                0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
                0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            )
            val acChrBits = byteArrayOf(0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
            val acChrVals = intArrayOf(
                0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
                0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
                0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
                0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
                0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
                0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
                0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
                0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
                0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
                0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            )
            val out = java.io.ByteArrayOutputStream()
            fun table(classId: Int, bits: ByteArray, vals: ByteArray) {
                out.write(classId)
                out.write(bits)
                out.write(vals)
            }
            table(0x00, dcLumBits, dcLumVals)
            table(0x01, dcChrBits, dcChrVals)
            table(0x10, acLumBits, acLumVals.map { it.toByte() }.toByteArray())
            table(0x11, acChrBits, acChrVals.map { it.toByte() }.toByteArray())
            out.toByteArray()
        }
    }
}
