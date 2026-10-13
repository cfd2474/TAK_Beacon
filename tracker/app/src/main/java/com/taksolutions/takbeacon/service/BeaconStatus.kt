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

    /**
     * ATAK is running with the TAK Beacon plugin loaded: set by the plugin's heartbeat,
     * cleared when it signs off or its process dies (the bridge loses its binding).
     * No timeout: a sleeping phone delays ATAK's heartbeats without ATAK having gone.
     */
    @Volatile var atakRunning: Boolean = false

    fun update(f: (TrackerState) -> TrackerState) = _state.update(f)

}
