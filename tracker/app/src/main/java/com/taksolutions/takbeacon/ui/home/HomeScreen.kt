package com.taksolutions.takbeacon.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.CoordFormat
import com.taksolutions.takbeacon.data.EmergencyType
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.geo.Coordinates
import com.taksolutions.takbeacon.net.LinkState
import com.taksolutions.takbeacon.net.LinkStatus
import com.taksolutions.takbeacon.service.RunState
import com.taksolutions.takbeacon.service.TrackerState
import com.taksolutions.takbeacon.ui.components.BeaconPulse
import com.taksolutions.takbeacon.ui.components.HoldToActivate
import com.taksolutions.takbeacon.ui.components.Readout
import com.taksolutions.takbeacon.ui.components.Section
import com.taksolutions.takbeacon.ui.components.StatusDot
import com.taksolutions.takbeacon.ui.theme.BeaconTheme
import com.taksolutions.takbeacon.ui.theme.CoordStyle
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    settings: BeaconSettings,
    tracker: TrackerState,
    servers: List<ServerConfig>,
    links: Map<String, LinkStatus>,
    contentPadding: PaddingValues,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEmergency: (EmergencyType) -> Unit,
    onCancelEmergency: () -> Unit,
    onCoordFormat: (CoordFormat) -> Unit,
    onOpenServers: () -> Unit,
    onLockToggle: () -> Unit,
) {
    val running = tracker.runState != RunState.STOPPED
    val status = BeaconTheme.status
    val stateColor = when {
        tracker.emergency != null -> status.sos
        tracker.runState == RunState.TRANSMITTING -> status.live
        tracker.runState == RunState.PAUSED_FOR_ATAK || tracker.runState == RunState.PAUSED_SATELLITE -> status.paused
        tracker.runState == RunState.STOPPED -> status.offline
        else -> status.waiting
    }
    // Re-render "x s ago" once a second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        tracker.emergency?.let { EmergencyBanner(it, onCancelEmergency) }

        // --- hero ---------------------------------------------------------------
        Section {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BeaconPulse(active = tracker.runState == RunState.TRANSMITTING || tracker.emergency != null, color = stateColor)
                Spacer(Modifier.height(4.dp))
                Text(headline(tracker.runState), style = MaterialTheme.typography.headlineSmall, color = stateColor)
                Text(
                    subline(tracker, settings, servers, links, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                IdentityRow(settings)
                Spacer(Modifier.height(16.dp))
                val locked = settings.locked
                if (running) {
                    OutlinedButton(onClick = onStop, enabled = !locked, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop beacon", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(onClick = onStart, enabled = !locked, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start beacon", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                LockToggle(locked = locked, hasPin = settings.hasPin, onClick = onLockToggle)
            }
        }

        // --- position -------------------------------------------------------------
        val fix = tracker.fix
        Section(
            "Position",
            trailing = {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    val all = CoordFormat.entries
                    onCoordFormat(all[(all.indexOf(settings.coordFormat) + 1) % all.size])
                }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.coordFormat.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Outlined.SwapHoriz, "Change format", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            },
        ) {
            Text(
                fix?.let { Coordinates.format(it.lat, it.lon, settings.coordFormat) } ?: if (running) "Acquiring…" else "—",
                style = CoordStyle,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Readout("ALT (HAE)", fix?.hae?.let { "%.0f m".format(it) } ?: "—", Modifier.weight(1f))
                Readout("SPEED", fix?.speedMps?.let { "%.1f %s".format(it * settings.speedUnit.fromMps, settings.speedUnit.label) } ?: "—", Modifier.weight(1f))
                Readout("HEADING", fix?.courseDeg?.let { "%03.0f° %s".format(it, Coordinates.cardinal(it.toFloat())) } ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Readout("ACCURACY", fix?.ce?.let { "±%.0f m".format(it) } ?: "—", Modifier.weight(1f))
                Readout("SOURCE", fix?.source ?: "—", Modifier.weight(1f))
                Readout("BATTERY", tracker.batteryPct?.let { "$it%" } ?: "—", Modifier.weight(1f))
            }
        }

        if (settings.showMap && fix != null && tracker.onSatellite) {
            Section("Map") {
                Text(
                    "Map preview is off while on satellite to save data. Your position is still reported.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (settings.showMap && fix != null) {
            Section("Map") {
                BeaconMap(
                    lat = fix.lat, lon = fix.lon, dark = settings.darkMap, color = stateColor,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }

        // --- servers ----------------------------------------------------------------
        Section("Servers", trailing = {
            Text("Manage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenServers).padding(4.dp))
        }) {
            if (servers.isEmpty()) {
                Text("No servers yet. Add one by scanning an enrollment QR code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            servers.forEach { s ->
                val link = links[s.id]
                val (color, text) = serverLine(s, link, running)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // --- emergency ----------------------------------------------------------------
        var type by rememberSaveable { mutableStateOf(EmergencyType.ALERT_911) }
        Section("Emergency") {
            tracker.emergency?.let { active ->
                EmergencyBanner(active, onCancelEmergency)
                return@Section
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmergencyType.entries.filter { it != EmergencyType.GEO_FENCE }.forEach { t ->
                    FilterChip(selected = t == type, onClick = { type = t }, label = { Text(t.label.removePrefix("Troops ")) })
                }
            }
            Spacer(Modifier.height(12.dp))
            HoldToActivate(
                label = "Hold to send ${type.label}",
                sublabel = if (settings.volumeSos) "Or press a volume key 5 times" else "Sent to every connected server",
                color = status.sos, onColor = status.onSos,
                onActivate = { onEmergency(type) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun IdentityRow(s: BeaconSettings) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(Color(s.team.argb), 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(s.callsign, style = MaterialTheme.typography.titleSmall)
        Text("  ·  ${s.team.cotName}  ·  ${s.role.cotName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmergencyBanner(type: EmergencyType, onCancel: () -> Unit) {
    val c = BeaconTheme.status
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.sos).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("EMERGENCY ACTIVE", color = c.onSos, style = MaterialTheme.typography.labelSmall)
            Text(type.label, color = c.onSos, style = MaterialTheme.typography.titleLarge)
        }
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = c.onSos, contentColor = c.sos),
        ) { Text("Cancel") }
    }
}

private fun headline(s: RunState) = when (s) {
    RunState.STOPPED -> "Beacon off"
    RunState.WAITING_FOR_FIX -> "Acquiring GPS"
    RunState.TRANSMITTING -> "Transmitting"
    RunState.PAUSED_FOR_ATAK -> "Paused for ATAK"
    RunState.PAUSED_SATELLITE -> "Paused on satellite"
    RunState.NO_SERVERS -> "No servers"
}

private fun subline(t: TrackerState, s: BeaconSettings, servers: List<ServerConfig>, links: Map<String, LinkStatus>, now: Long): String {
    val connected = links.values.count { it.state == LinkState.CONNECTED }
    val enabled = servers.count { it.enabled }
    return when (t.runState) {
        RunState.STOPPED -> if (servers.isEmpty()) "Add a server, then start the beacon" else "Not sharing your position"
        RunState.NO_SERVERS -> "Enable or add a server to start reporting"
        RunState.WAITING_FOR_FIX -> "Waiting for a location fix · $connected/$enabled connected"
        RunState.PAUSED_FOR_ATAK -> "ATAK is open and reporting your position"
        RunState.PAUSED_SATELLITE -> "On a satellite network, and satellite data is off in Settings"
        RunState.TRANSMITTING -> {
            val ago = t.lastSentMs?.let { "last report ${((now - it) / 1000).coerceAtLeast(0)}s ago" } ?: "first report pending"
            val every = t.effectiveIntervalSeconds ?: s.intervalSeconds
            val where = listOfNotNull(
                if (enabled > 0) "$connected/$enabled connected" else null,
                if (s.udpBroadcast) "UDP ${s.udpHost}:${s.udpPort}" else null,
            ).joinToString(" + ")
            (if (t.onSatellite) "Via satellite · " else "") + "Every ${every}s · $where · $ago" +
                (t.udpError?.let { "\nUDP: $it" } ?: "")
        }
    }
}

@Composable
private fun serverLine(s: ServerConfig, link: LinkStatus?, running: Boolean): Pair<Color, String> {
    val c = BeaconTheme.status
    return when {
        s.needsEnrollment -> c.waiting to "Sign-in needed for a certificate"
        !s.enabled -> c.offline to "Disabled"
        !running -> c.offline to "${s.host}:${s.port}"
        link == null || link.state == LinkState.CONNECTING -> c.waiting to "Connecting…"
        link.state == LinkState.CONNECTED -> c.live to "Connected · ${s.host}:${s.port}"
        else -> MaterialTheme.colorScheme.error to (link.message ?: "Error")
    }
}

/** The beacon lock: freezes on/off, settings and servers until the PIN is entered. */
@Composable
private fun LockToggle(locked: Boolean, hasPin: Boolean, onClick: () -> Unit) {
    val c = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .background(if (locked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                locked -> "Locked · tap to unlock"
                hasPin -> "Unlocked · tap to lock"
                else -> "Lock with a PIN"
            },
            color = c, style = MaterialTheme.typography.labelLarge,
        )
    }
}
