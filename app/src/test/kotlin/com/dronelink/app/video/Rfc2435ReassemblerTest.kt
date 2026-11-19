package com.dronelink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class Rfc2435ReassemblerTest {

    /** Build an RFC 2435 payload: 8-byte JPEG header + fragment. */
    private fun payload(offset: Int, fragment: ByteArray): ByteArray {
        val hdr = ByteArray(8)
        // fragment offset is bytes 1..3 big-endian (byte 0 = type-specific)
        hdr[1] = ((offset ushr 16) and 0xFF).toByte()
        hdr[2] = ((offset ushr 8) and 0xFF).toByte()
        hdr[3] = (offset and 0xFF).toByte()
        hdr[4] = 0x01       // type (baseline)
        hdr[5] = 0x50       // Q
        hdr[6] = 0x01       // width (placeholder)
        hdr[7] = 0x0E       // height (placeholder)
        return hdr + fragment
    }

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),                      // SOI
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,          // APP0
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
        0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01,
        0x00, 0x00,                                        // ... entropy bytes
        0xFF.toByte(), 0xD9.toByte(),                      // EOI
    )

    @Test fun reassemblesMultiFragmentFrameByOffset() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }

        val frag0 = jpeg.copyOfRange(0, 7)
        val frag1 = jpeg.copyOfRange(7, 13)
        val frag2 = jpeg.copyOfRange(13, jpeg.size)

        r.feed(payload(0, frag0), marker = false)
        r.feed(payload(7, frag1), marker = false)
        r.feed(payload(13, frag2), marker = true)

        assertEquals(1, emitted.size)
        assertTrue("frame must match the original JPEG byte-for-byte",
            Arrays.equals(jpeg, emitted[0]))
        assertEquals(1, r.frames)
    }

    @Test fun singlePacketFrameEmitsImmediately() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(0, jpeg), marker = true)
        assertEquals(1, emitted.size)
        assertTrue(Arrays.equals(jpeg, emitted[0]))
    }

    @Test fun partialFrameThenNewFrameDiscardsPartial() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }

        // Frame A: one fragment, never completed (no marker).
        r.feed(payload(0, jpeg.copyOfRange(0, 10)), marker = false)
        // Frame B starts (offset 0) before A completed — A must be discarded.
        val fragB0 = jpeg.copyOfRange(0, 9)
        val fragB1 = jpeg.copyOfRange(9, jpeg.size)
        r.feed(payload(0, fragB0), marker = false)
        r.feed(payload(9, fragB1), marker = true)

        assertEquals(1, emitted.size)
        assertTrue("only the complete frame B is emitted",
            Arrays.equals(jpeg, emitted[0]))
    }

    @Test fun undersizedPayloadsIgnored() {
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(ByteArray(4), marker = false)   // < 8 bytes: not a valid JPEG header
        r.feed(payload(0, jpeg), marker = true)
        assertEquals(1, emitted.size)
    }

    @Test fun emptyFragmentAtOffsetFillsGaps() {
        // A fragment can start at a non-zero offset; bytes before it are
        // zero (the camera sent the frame in sparse fragments).
        val emitted = mutableListOf<ByteArray>()
        val r = Rfc2435Reassembler { emitted += it }
        r.feed(payload(5, jpeg), marker = true)
        assertEquals(1, emitted.size)
        val frame = emitted[0]
        assertEquals(5 + jpeg.size, frame.size)
        assertEquals(0, frame[0].toInt() and 0xFF)
        assertTrue(Arrays.equals(jpeg, frame.copyOfRange(5, frame.size)))
        assertFalse("frame must not start with SOI at byte 0 (sparse fill)",
            frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte())
    }
}
