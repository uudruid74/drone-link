package com.dronelink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class Rfc2435ReassemblerTest {

    /** Build an RFC 2435 payload: main JPEG header (+ restart/quant headers when
     *  applicable) + fragment. Mirrors the drone camera's payload shape. */
    private fun payload(
        offset: Int,
        fragment: ByteArray,
        type: Int = 0x41,       // type 1 + restart bit 0x40
        q: Int = 0x96,          // 150 -> in-band quantization tables
        width: Int = 80,        // 640 px
        height: Int = 60,       // 480 px
        dri: Int = 40,
        tables: ByteArray? = null,
    ): ByteArray {
        val hdr = ByteArray(8)
        hdr[1] = ((offset ushr 16) and 0xFF).toByte()
        hdr[2] = ((offset ushr 8) and 0xFF).toByte()
        hdr[3] = (offset and 0xFF).toByte()
        hdr[4] = type.toByte()
        hdr[5] = q.toByte()
        hdr[6] = width.toByte()
        hdr[7] = height.toByte()
        var p = hdr
        if ((type and 0x40) != 0 && offset == 0) {
            p += byteArrayOf((dri ushr 8).toByte(), (dri and 0xFF).toByte(), 0xFF.toByte(), 0xFF.toByte())
        }
        if (q >= 128 && offset == 0) {
            val t = tables ?: ByteArray(128) { (it + 1).toByte() }
            p += byteArrayOf(0, 0, ((t.size ushr 8) and 0xFF).toByte(), (t.size and 0xFF).toByte())
            p += t
        }
        return p + fragment
    }

    /** A synthetic entropy scan of [n] bytes (not a full JPEG). */
    private fun scan(n: Int): ByteArray = ByteArray(n) { (0x10 + (it % 0x70)).toByte() }

    /** All marker positions (FF xx) in a frame, with the following 2 payload bytes. */
    private fun markers(frame: ByteArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < frame.size - 1) {
            if (frame[i] == 0xFF.toByte() && frame[i + 1] != 0xFF.toByte() && frame[i + 1] != 0x00.toByte()) {
                out += (frame[i + 1].toInt() and 0xFF) to i
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    @Test fun reassemblesMultiFragmentFrameWithRestartAndQuantHeaders() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }

        val tables = ByteArray(128) { (it % 250 + 1).toByte() }
        val frag0 = scan(40)
        val frag1 = scan(50)
        val frag2 = scan(30)

        r.feed(payload(0, frag0, tables = tables), marker = false)
        r.feed(payload(40, frag1), marker = false)
        r.feed(payload(90, frag2), marker = true)

        assertEquals(1, emitted.size)
        assertEquals(1, r.frames)
        val frame = emitted[0]

        // SOI
        assertTrue("frame starts with SOI",
            frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte())

        // APP0 JFIF
        val jfif = frame.indexOfList("JFIF\u0000".toByteArray(Charsets.ISO_8859_1))
        assertTrue("JFIF APP0 present", jfif > 0)

        // DRI (restart markers)
        val driIdx = indexOfSegment(frame, 0xDD)
        assertTrue("DRI segment present", driIdx >= 0)
        assertEquals(40, ((frame[driIdx + 4].toInt() and 0xFF) shl 8) or (frame[driIdx + 5].toInt() and 0xFF))

        // DQT with the exact in-band tables
        val dqtIdx = indexOfSegment(frame, 0xDB)
        assertTrue("DQT segment present", dqtIdx >= 0)
        val dataLen = ((frame[dqtIdx + 2].toInt() and 0xFF) shl 8) or (frame[dqtIdx + 3].toInt() and 0xFF)
        assertEquals(2 + 2 * 65, dataLen)
        assertEquals("table 0 bytes verbatim", tables.copyOfRange(0, 64).toList(),
            frame.copyOfRange(dqtIdx + 5, dqtIdx + 5 + 64).toList())
        assertEquals("table 1 bytes verbatim", tables.copyOfRange(64, 128).toList(),
            frame.copyOfRange(dqtIdx + 70, dqtIdx + 70 + 64).toList())

        // SOF0: height/width in pixels, 3 components, 4:2:0 sampling
        val sofIdx = indexOfSegment(frame, 0xC0)
        assertTrue("SOF0 present", sofIdx >= 0)
        assertEquals(480, ((frame[sofIdx + 5].toInt() and 0xFF) shl 8) or (frame[sofIdx + 6].toInt() and 0xFF))
        assertEquals(640, ((frame[sofIdx + 7].toInt() and 0xFF) shl 8) or (frame[sofIdx + 8].toInt() and 0xFF))
        assertEquals(3, frame[sofIdx + 9].toInt() and 0xFF)
        assertEquals(0x22, frame[sofIdx + 11].toInt() and 0xFF) // Y sampling 2x2

        // SOS present, scan fragments verbatim in order
        val sosIdx = indexOfSegment(frame, 0xDA)
        assertTrue("SOS present", sosIdx >= 0)
        val scanBytes = frag0 + frag1 + frag2
        assertTrue("scan fragments verbatim", frame.indexOfList(scanBytes) >= 0)

        // EOI at end
        assertTrue("ends with EOI",
            frame[frame.size - 2] == 0xFF.toByte() && frame[frame.size - 1] == 0xD9.toByte())
    }

    @Test fun singlePacketFrameEmitsImmediately() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, scan(20)), marker = true)
        assertEquals(1, emitted.size)
        val frame = emitted[0]
        assertTrue(frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte())
        assertTrue(frame[frame.size - 2] == 0xFF.toByte() && frame[frame.size - 1] == 0xD9.toByte())
        assertTrue("scan bytes included", frame.indexOfList(scan(20)) >= 0)
    }

    @Test fun derivedQuantTablesForQIn1to99() {
        // q=75 -> S = 200 - 2*75 = 50; base luma 16 -> 8, 11 -> 6, ... 99 -> 50
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, scan(10), q = 75, tables = null), marker = true)
        val frame = emitted[0]
        val dqtIdx = indexOfSegment(frame, 0xDB)
        assertTrue("DQT present", dqtIdx >= 0)
        // first luma entries: 16*50/100=8, 11*50/100=6 (IJG integer division: (v*S+50)/100)
        assertEquals(8, frame[dqtIdx + 5].toInt() and 0xFF)
        assertEquals(6, frame[dqtIdx + 6].toInt() and 0xFF)
        // last chroma entry: 99 -> 50
        assertEquals(50, frame[dqtIdx + 133].toInt() and 0xFF)
    }

    @Test fun noRestartHeaderMeansNoDriSegment() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, scan(10), type = 0x01, q = 75), marker = true)
        val frame = emitted[0]
        assertEquals(-1, indexOfSegment(frame, 0xDD))
    }

    @Test fun markerWithoutStartPacketDropsFrame() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(100, scan(10)), marker = true) // first packet offset != 0
        assertEquals(0, emitted.size)
        assertEquals(0, r.frames)
    }

    @Test fun partialFrameThenNewFrameDiscardsPartial() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, scan(8)), marker = false)
        r.feed(payload(0, scan(12)), marker = false)
        r.feed(payload(12, scan(8)), marker = true)
        assertEquals(1, emitted.size)
        assertTrue("only frame B emitted", emitted[0].indexOfList(scan(12) + scan(8)) >= 0)
    }

    @Test fun undersizedPayloadsIgnored() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(ByteArray(4), marker = false)
        r.feed(payload(0, scan(12)), marker = true)
        assertEquals(1, emitted.size)
    }

    @Test fun emptyFragmentAtOffsetFillsGaps() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, scan(2)), marker = false)
        r.feed(payload(5, scan(6)), marker = true)
        assertEquals(1, emitted.size)
        val frame = emitted[0]
        // header contains SOI etc., then scan placed at offset 5 after header
        val sosIdx = indexOfSegment(frame, 0xDA)
        val payloadStart = sosIdx + 2 + ((frame[sosIdx + 2].toInt() and 0xFF) shl 8 or (frame[sosIdx + 3].toInt() and 0xFF))
        assertEquals("first fragment byte 0", scan(2)[0].toInt() and 0xFF, frame[payloadStart].toInt() and 0xFF)
        assertEquals("first fragment byte 1", scan(2)[1].toInt() and 0xFF, frame[payloadStart + 1].toInt() and 0xFF)
        assertEquals("gap byte 2 is zero", 0, frame[payloadStart + 2].toInt() and 0xFF)
        assertEquals("gap byte 4 is zero", 0, frame[payloadStart + 4].toInt() and 0xFF)
        assertTrue("second fragment at offset 5", frame.indexOfList(scan(6)) >= 0)
    }

    @Test fun invalidQuantTableLengthDropsPacket() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        // q=150 but a 4-byte quant header claiming length 1 (not multiple of 64)
        val bad = ByteArray(8) { 0 } + byteArrayOf(0, 0, 0, 1)
        r.feed(bad + scan(10), marker = true)
        assertEquals(0, emitted.size)
        // frame without restart headers still works
        r.feed(payload(0, scan(10), q = 75), marker = true)
        assertEquals(1, emitted.size)
    }

    // ------------------------------------------------------------- helpers

    private fun indexOfSegment(frame: ByteArray, markerCode: Int): Int {
        var i = 0
        while (i < frame.size - 1) {
            if (frame[i] == 0xFF.toByte() && (frame[i + 1].toInt() and 0xFF) == markerCode) return i
            i++
        }
        return -1
    }

    private fun ByteArray.indexOfList(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
