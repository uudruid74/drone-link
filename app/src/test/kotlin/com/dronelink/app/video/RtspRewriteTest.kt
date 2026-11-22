package com.dronelink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspRewriteTest {

    @Test fun parsesClientPortRange() {
        val req = "SETUP rtsp://127.0.0.1:38159/webcam/track0 RTSP/1.0\r\n" +
            "Transport: RTP/AVP/UDP;unicast;client_port=18180-18181\r\n" +
            "CSeq: 3\r\n\r\n"
        assertEquals(18180 to 18181, RtspRewrite.clientPorts(req))
    }

    @Test fun returnsNullWhenNoClientPort() {
        val req = "OPTIONS rtsp://127.0.0.1:38159/webcam RTSP/1.0\r\nCSeq: 1\r\n\r\n"
        assertNull(RtspRewrite.clientPorts(req))
    }

    @Test fun rewriteSwapsToGivenBase() {
        val req = "SETUP rtsp://127.0.0.1:38159/webcam/track0 RTSP/1.0\r\n" +
            "Transport: RTP/AVP/UDP;unicast;client_port=18180-18181\r\n" +
            "CSeq: 3\r\n\r\n"
        val out = RtspRewrite.rewriteClientPort(req, 40000)
        assertEquals(40000 to 40001, RtspRewrite.clientPorts(out))
        // Everything else must be left untouched.
        assertEquals(req.replace("client_port=18180-18181", "client_port=40000-40001"), out)
    }

    @Test fun rewriteLeavesNoMatchRequestUntouched() {
        val req = "PLAY rtsp://127.0.0.1:38159/webcam RTSP/1.0\r\nCSeq: 4\r\n\r\n"
        assertEquals(req, RtspRewrite.rewriteClientPort(req, 40000))
    }
}
