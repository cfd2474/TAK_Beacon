package com.taksolutions.takbeacon.service

import com.taksolutions.takbeacon.cot.Fix
import com.taksolutions.takbeacon.data.EmergencyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RunState { STOPPED, WAITING_FOR_FIX, TRANSMITTING, PAUSED_FOR_ATAK, PAUSED_SATELLITE, NO_SERVERS }

data class TrackerState(
    val runState: RunState = RunState.STOPPED,
    val fix: Fix? = null,
    val lastSentMs: Long? = null,
    val emergency: EmergencyType? = null,
    val batteryPct: Int? = null,
    /** On a constrained satellite network (e.g. T-Mobile T-Satellite). */
    val onSatellite: Boolean = false,
    /** Seconds between reports right now (slower over satellite). */
    val effectiveIntervalSeconds: Int? = null,
    val udpError: String? = null,
)

/** Process-wide tracker state, written by [BeaconService], read by the UI and the ATAK bridge. */
object BeaconStatus {
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    /** Last time the ATAK plugin reported ATAK in the foreground (elapsed-realtime ms). */
    @Volatile var atakForegroundAtMs: Long = 0L

    fun update(f: (TrackerState) -> TrackerState) = _state.update(f)

    fun atakInForeground(nowElapsed: Long): Boolean =
        atakForegroundAtMs != 0L && nowElapsed - atakForegroundAtMs < ATAK_HEARTBEAT_TIMEOUT_MS

    /** The plugin sends a heartbeat every few seconds; three missed ones resume tracking. */
    const val ATAK_HEARTBEAT_TIMEOUT_MS = 15_000L
}
