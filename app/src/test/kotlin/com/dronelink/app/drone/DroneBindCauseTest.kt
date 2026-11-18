package com.dronelink.app.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneBindCauseTest {

    @Test
    fun vpnActiveMentionsRemedy() {
        val msg = DroneBindCause.describe(true, "EPERM (Operation not permitted)")
        assertTrue(msg.contains("Bypass VPN"))
        assertTrue(msg.contains("EPERM"))
    }

    @Test
    fun noVpnKeepsRawError() {
        assertEquals("socket bind failed: boom", DroneBindCause.describe(false, "boom"))
    }
}
