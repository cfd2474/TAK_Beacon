package com.taksolutions.takbeacon.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.BuildConfig
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.CoordFormat
import com.taksolutions.takbeacon.data.Role
import com.taksolutions.takbeacon.data.SpeedUnit
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.ui.components.KeyValueRow
import com.taksolutions.takbeacon.ui.components.LockedBanner
import com.taksolutions.takbeacon.ui.components.Section
import com.taksolutions.takbeacon.ui.theme.Mono

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    s: BeaconSettings,
    contentPadding: PaddingValues,
    onChange: ((BeaconSettings) -> BeaconSettings) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onKiosk: (Boolean) -> Unit,
    onBatterySettings: () -> Unit,
    onForgetAtak: () -> Unit,
    onPin: (PinAction) -> Unit,
) {
    CompositionLocalProvider(LocalSettingsLocked provides s.locked) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (s.locked) LockedBanner("Settings are locked.") { onPin(PinAction.UNLOCK) }
        Section("Identity") {
            var callsign by remember(s.callsign) { mutableStateOf(s.callsign) }
            OutlinedTextField(
                callsign,
                { callsign = it.take(40) },
                label = { Text("Callsign") },
                singleLine = true,
                enabled = !s.locked,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    if (callsign != s.callsign && callsign.isNotBlank()) {
                        TextButton(enabled = !s.locked, onClick = { onChange { it.copy(callsign = callsign) } }) { Text("Save") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text("Team", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Team.entries.forEach { t ->
                    val selected = t == s.team
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(t.argb))
                            .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                            .clickable(enabled = !s.locked) { onChange { it.copy(team = t) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, t.cotName, tint = if (t.argb == 0xFFFFFFFF || t == Team.YELLOW) Color.Black else Color.White)
                    }
                }
            }
            Text(s.team.cotName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Text("Role", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.entries.forEach { r -> LockChip(r == s.role, { onChange { it.copy(role = r) } }, { Text(r.cotName) }) }
            }
        }

        Section("Reporting") {
            Text("Report every", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30, 60, 120, 300).forEach { sec ->
                    LockChip(s.intervalSeconds == sec, { onChange { it.copy(intervalSeconds = sec, staleSeconds = maxOf(it.staleSeconds, sec * 3)) } },
                        { Text(if (sec < 60) "${sec}s" else "${sec / 60}m") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Marker goes stale after", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 120, 300, 600, 1800).forEach { sec ->
                    LockChip(s.staleSeconds == sec, { onChange { it.copy(staleSeconds = sec) } }, { Text("${sec / 60}m") },
                        enabled = sec >= s.intervalSeconds * 2)
                }
            }
        }

        Section("Satellite") {
            ToggleRow(
                "Use satellite data",
                "Keep reporting over satellite (such as T-Mobile T-Satellite) when it is the only network. " +
                    "Reports slow down to save data and the map preview turns off. Emergencies are always sent.",
                s.satelliteData,
            ) { v -> onChange { it.copy(satelliteData = v) } }
            if (s.satelliteData) {
                Spacer(Modifier.height(8.dp))
                Text("Report over satellite every", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 300, 600).forEach { sec ->
                        LockChip(s.satelliteIntervalSeconds == sec, { onChange { it.copy(satelliteIntervalSeconds = sec) } },
                            { Text(if (sec < 60) "${sec}s" else "${sec / 60}m") })
                    }
                }
            }
        }

        Section("UDP broadcast") {
            ToggleRow(
                "Broadcast position over UDP",
                "Also send each report as CoT over UDP, for ATAK and other TAK clients on the same network or mesh. " +
                    "Not encrypted: anyone on the network can see it.",
                s.udpBroadcast,
            ) { v -> onChange { it.copy(udpBroadcast = v) } }
            if (s.udpBroadcast) {
                var host by remember(s.udpHost) { mutableStateOf(s.udpHost) }
                var port by remember(s.udpPort) { mutableStateOf(s.udpPort.toString()) }
                val validPort = port.toIntOrNull()?.let { it in 1..65535 } == true
                val validHost = host.isNotBlank() && host.all { it.isLetterOrDigit() || it in ".-:" }
                val changed = host != s.udpHost || port != s.udpPort.toString()
                val save = { if (changed && validHost && validPort) onChange { it.copy(udpHost = host, udpPort = port.toInt()) } }
                // The keyboard's Done saves too, so an edit isn't lost by leaving the screen.
                val done = KeyboardActions(onDone = { save() })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim().take(253) }, label = { Text("Address") }, singleLine = true, enabled = !s.locked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = done, isError = !validHost, modifier = Modifier.weight(2f))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, enabled = !s.locked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = done, isError = !validPort, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Default ${BeaconSettings.DEFAULT_UDP_HOST}:${BeaconSettings.DEFAULT_UDP_PORT} is ATAK's SA multicast.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !s.locked, onClick = {
                        onChange { it.copy(udpHost = BeaconSettings.DEFAULT_UDP_HOST, udpPort = BeaconSettings.DEFAULT_UDP_PORT) }
                    }) { Text("Default") }
                    TextButton(enabled = !s.locked && changed && validHost && validPort, onClick = save) { Text("Save") }
                }
            }
        }

        Section("Display") {
            Text("Coordinates", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoordFormat.entries.forEach { f -> LockChip(s.coordFormat == f, { onChange { it.copy(coordFormat = f) } }, { Text(f.label) }) }
            }
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedUnit.entries.forEach { u -> LockChip(s.speedUnit == u, { onChange { it.copy(speedUnit = u) } }, { Text(u.label) }) }
            }
            ToggleRow("Show map", "OpenStreetMap preview on the Beacon screen", s.showMap) { v -> onChange { it.copy(showMap = v) } }
            ToggleRow("Dark map", "Inverted tiles for low light", s.darkMap, enabled = s.showMap) { v -> onChange { it.copy(darkMap = v) } }
        }

        Section("Safety") {
            ToggleRow(
                "Volume-key SOS",
                "Press a volume key 5 times within 3 seconds to send a 911 alert, even with the screen off. While on, volume keys don't change media volume.",
                s.volumeSos,
            ) { v -> onChange { it.copy(volumeSos = v) } }
            ToggleRow("Kiosk lock", "Pin TAK Beacon to the screen so it can't be left by accident", s.kioskLock, onToggle = onKiosk)
        }

        Section("ATAK") {
            ToggleRow(
                "Pause while ATAK is open",
                "Requires the TAK Beacon ATAK plugin. Avoids two markers for one person.",
                s.atakAutoPause,
            ) { v -> onChange { it.copy(atakAutoPause = v) } }
            if (s.trustedAtakSigner != null) {
                Spacer(Modifier.height(8.dp))
                Text("Trusted ATAK build", style = MaterialTheme.typography.labelLarge)
                Text(CertStore.prettyFingerprint(s.trustedAtakSigner).take(47) + "…", fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !s.locked, onClick = onForgetAtak) { Text("Forget — ask again next time") }
            }
        }

        Section("Startup & battery") {
            ToggleRow(
                "Start on boot",
                "Resume reporting after a restart, if the beacon was on. Needs location access \"all the time\".",
                s.startOnBoot,
                onToggle = onStartOnBoot,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("Battery optimization settings") }
            Text(
                "Set TAK Beacon to \"Unrestricted\" so reports keep flowing with the screen off.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section("Lock") {
            Text(
                if (!s.hasPin) "Set a PIN to lock TAK Beacon: while locked, the beacon can't be switched on or off and " +
                    "settings and servers can't be changed. Emergency alerts still work."
                else if (s.locked) "Locked. Enter the PIN to change anything."
                else "PIN set. Lock TAK Beacon from the Beacon screen.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!s.hasPin) {
                    OutlinedButton(onClick = { onPin(PinAction.SET) }) { Text("Set PIN") }
                } else if (!s.locked) {
                    OutlinedButton(onClick = { onPin(PinAction.CHANGE) }) { Text("Change PIN") }
                    TextButton(onClick = { onPin(PinAction.REMOVE) }) { Text("Remove PIN") }
                } else {
                    OutlinedButton(onClick = { onPin(PinAction.UNLOCK) }) { Text("Unlock") }
                }
            }
        }

        Section("About") {
            KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValueRow("Device UID", s.uid)
        }
        Spacer(Modifier.height(8.dp))
    }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val on = enabled && !LocalSettingsLocked.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = on) { onToggle(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = on)
    }
}

enum class PinAction { SET, CHANGE, REMOVE, UNLOCK }

private val LocalSettingsLocked = compositionLocalOf { false }

/** A filter chip that is also disabled while the PIN lock is on. */
@Composable
private fun LockChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, enabled: Boolean = true) {
    FilterChip(selected, onClick, label, enabled = enabled && !LocalSettingsLocked.current)
}
