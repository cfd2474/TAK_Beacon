package com.taksolutions.takbeacon.service

import com.taksolutions.takbeacon.data.BeaconSettings

/** Decides whether and how often to report. Pure, so it is unit-tested. */
object ReportPolicy {

    /** Keep-alive pings: every 30 s normally, every 2 min over satellite. */
    const val PING_MS = 30_000L
    const val SATELLITE_PING_MS = 120_000L

    fun intervalSeconds(s: BeaconSettings, onSatellite: Boolean): Int =
        if (onSatellite) maxOf(s.intervalSeconds, s.satelliteIntervalSeconds) else s.intervalSeconds

    fun runState(
        s: BeaconSettings,
        servers: Int,
        hasFix: Boolean,
        atakRunning: Boolean,
        onSatellite: Boolean,
        emergency: Boolean,
    ): RunState = when {
        servers == 0 && !s.udpBroadcast -> RunState.NO_SERVERS
        // An emergency goes out on whatever network there is.
        onSatellite && !s.satelliteData && !emergency -> RunState.PAUSED_SATELLITE
        !emergency && s.atakAutoPause && atakRunning -> RunState.PAUSED_FOR_ATAK
        !hasFix -> RunState.WAITING_FOR_FIX
        else -> RunState.TRANSMITTING
    }
}
