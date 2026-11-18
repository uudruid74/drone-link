package com.dronelink.app

import android.net.Network

enum class JoinState { IDLE, REQUESTING, CONNECTED, LOST, UNAVAILABLE, KILLED }

/**
 * Process-wide join status. Written by DroneLinkService callbacks, read by
 * MainActivity for display. Volatile fields only — no listener machinery.
 */
object JoinStatus {
    @Volatile
    var state: JoinState = JoinState.IDLE

    @Volatile
    var detail: String = ""

    @Volatile
    var joinedNetwork: Network? = null
}
