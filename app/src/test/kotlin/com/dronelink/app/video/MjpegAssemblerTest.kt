package com.dronelink.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MjpegAssemblerTest {

    private fun jpeg(seed: Int): ByteArray {
        val b = ByteArray(64)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        for (i in 2 until 62) b[i] = (seed + i).toByte()
        b[62] = 0xFF.toByte(); b[63] = 0xD9.toByte()
        return b
    }

    @Test fun singleChunkFrame() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j = jpeg(1)
        a.feed(j)
        assertEquals(1, a.frames)
        assertEquals(1, frames.size)
        assertArrayEquals(j, frames[0])
    }

    @Test fun junkBeforeSoiDiscarded() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j = jpeg(2)
        a.feed(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        a.feed(j)
        assertEquals(1, frames.size)
        assertArrayEquals(j, frames[0])
    }

    @Test fun markerSplitAcrossChunks() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j = jpeg(3)
        // Feed everything except the final 0xD9, then the 0xD9 alone.
        a.feed(j.copyOf(j.size - 1))
        assertEquals(0, frames.size)
        a.feed(byteArrayOf(0xD9.toByte()))
        assertEquals(1, frames.size)
        assertArrayEquals(j, frames[0])
    }

    @Test fun soiSplitAcrossChunks() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j = jpeg(4)
        // SOI split: feed 0xFF, then rest of frame.
        a.feed(byteArrayOf(0xFF.toByte()))
        a.feed(j.copyOfRange(1, j.size))
        assertEquals(1, frames.size)
        assertArrayEquals(j, frames[0])
    }

    @Test fun twoFramesInOneChunk() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j1 = jpeg(5)
        val j2 = jpeg(6)
        a.feed(j1 + j2)
        assertEquals(2, frames.size)
        assertArrayEquals(j1, frames[0])
        assertArrayEquals(j2, frames[1])
    }

    @Test fun frameSplitAcrossManyChunks() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j = jpeg(7)
        for (i in j.indices step 7) {
            a.feed(j.copyOfRange(i, minOf(i + 7, j.size)))
        }
        assertEquals(1, frames.size)
        assertArrayEquals(j, frames[0])
    }

    @Test fun interleavedJunkBetweenFrames() {
        val frames = mutableListOf<ByteArray>()
        val a = MjpegAssembler { frames += it }
        val j1 = jpeg(8)
        val j2 = jpeg(9)
        a.feed(j1 + byteArrayOf(0x00, 0xFF.toByte(), 0x00) + j2)
        assertEquals(2, frames.size)
        assertArrayEquals(j1, frames[0])
        assertArrayEquals(j2, frames[1])
    }
}
