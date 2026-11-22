package com.dronelink.app.video

import android.view.TextureView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake IjkBridge: no natives, deterministic frames. */
internal class FakeBridge : IjkBridge {
    var optionsCount = 0
    var dataSourceUrl: String? = null
    var prepared = false
    var released = false
    var currentJpeg: ByteArray? = null

    override fun setOptions() {
        optionsCount++
    }

    override fun setDataSource(url: String) {
        dataSourceUrl = url
    }

    override fun attachSurface(texture: TextureView) {
        // no-op in tests
    }

    override fun prepareAsync() {
        // no-op
    }

    override fun start() {
        prepared = true
    }

    override fun captureJpeg(): ByteArray? = currentJpeg

    override var onPrepared: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun release() {
        released = true
    }
}

class IjkRtspClientTest {

    @Test
    fun `start configures bridge and prepares`() {
        val bridge = FakeBridge()
        val client = RtspClient(bridge = bridge)
        client.start()

        assertEquals(1, bridge.optionsCount)
        assertEquals("rtsp://192.168.1.1:7070/webcam", bridge.dataSourceUrl)
        assertNotNull(bridge.onPrepared)
        assertNotNull(bridge.onError)
        assertNull(client.error)

        bridge.onPrepared?.invoke()
        assertTrue(bridge.prepared)
        assertTrue(client.running)
        assertNotNull(client.sessionId)
        client.stop()
    }

    @Test
    fun `frames are captured and published as JPEG`() {
        val bridge = FakeBridge()
        val client = RtspClient(bridge = bridge)
        client.start()
        bridge.onPrepared?.invoke()

        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02, 0x03,
            0xFF.toByte(), 0xD9.toByte(),
        )
        bridge.currentJpeg = jpeg

        // grabLoop polls every 125ms; wait for a published frame.
        val deadline = System.currentTimeMillis() + 3000
        var frameBytes: ByteArray? = null
        while (System.currentTimeMillis() < deadline) {
            frameBytes = client.latestFrame
            if (frameBytes != null) break
            Thread.sleep(50)
        }

        assertNotNull("no frame published", frameBytes)
        val bytes = frameBytes!!
        assertTrue("JPEG SOI", bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        assertTrue("JPEG EOI", bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte())
        assertTrue(bytes.contentEquals(jpeg))
        assertEquals(1L, client.framesDecoded)
        assertTrue(client.framesBytes >= bytes.size.toLong())
        assertTrue(client.frameVersion >= 1)

        // awaitFrame returns immediately when a newer frame exists.
        val v = client.awaitFrame(client.frameVersion - 1, 100)
        assertTrue(v >= client.frameVersion - 1)
        client.stop()
    }

    @Test
    fun `no frame before prepared`() {
        val bridge = FakeBridge()
        val client = RtspClient(bridge = bridge)
        client.start()

        Thread.sleep(400)
        assertNull(client.latestFrame)
        assertEquals(0L, client.framesDecoded)
        client.stop()
    }

    @Test
    fun `bridge error stops client and records error`() {
        val bridge = FakeBridge()
        val client = RtspClient(bridge = bridge)
        client.start()
        bridge.onError?.invoke("what=1 extra=2")

        assertNotNull(client.error)
        assertTrue(client.error!!.contains("ijk"))
        assertFalse(client.running)
    }

    @Test
    fun `stop releases bridge`() {
        val bridge = FakeBridge()
        val client = RtspClient(bridge = bridge)
        client.start()
        client.stop()
        assertTrue(bridge.released)
        assertFalse(client.running)
    }
}
