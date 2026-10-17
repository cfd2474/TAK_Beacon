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
import androidx.compose.material3.Button
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
    missingPermissions: List<String> = emptyList(),
    onGrantPermissions: () -> Unit = {},
    /** Settings the MDM sets (ManagedConfig.FIELD_*): shown read-only. */
    managedFields: Set<String> = emptySet(),
    managedByOrg: Boolean = false,
    onClearMessages: () -> Unit = {},
    onTestAlert: () -> Unit = {},
    /** TAK Beacon may lift Do Not Disturb for a priority alert. */
    dndAccess: Boolean = true,
    onDndAccess: () -> Unit = {},
) {
    val mCallsign = "callsign" in managedFields
    val mTeam = "team" in managedFields
    val mRole = "role" in managedFields
    val mBoot = "start_on_boot" in managedFields
    val mCot = "cot_type" in managedFields
    val mMode = "reporting_mode" in managedFields
    val mKeywords = "alert_keywords" in managedFields
    val adaptive = s.reportingMode == com.taksolutions.takbeacon.data.ReportingMode.ADAPTIVE
    CompositionLocalProvider(LocalSettingsLocked provides s.locked) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (s.locked) LockedBanner("Settings are locked.") { onPin(PinAction.UNLOCK) }
        if (missingPermissions.isNotEmpty()) {
            // Not a setting: works while locked, so a locked beacon can still be fixed.
            Section("Permissions") {
                Text(
                    "Missing: ${missingPermissions.joinToString()}. TAK Beacon needs these to keep reporting " +
                        "with the screen off, after a restart and when ATAK starts it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
            }
        }
        if (managedByOrg) {
            Text(
                "Managed by your organization. Settings it provides can't be changed here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Section("Identity") {
            var callsign by remember(s.callsign) { mutableStateOf(s.callsign) }
            OutlinedTextField(
                callsign,
                { callsign = it.take(40) },
                label = { Text("Callsign") },
                singleLine = true,
                enabled = !s.locked && !mCallsign,
                supportingText = if (mCallsign) ({ Text("Set by your organization") }) else null,
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
                            .clickable(enabled = !s.locked && !mTeam) { onChange { it.copy(team = t) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, t.cotName, tint = if (t.argb == 0xFFFFFFFF || t == Team.YELLOW) Color.Black else Color.White)
                    }
                }
            }
            Text(s.team.cotName + if (mTeam) " \u00B7 set by your organization" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Text("Role", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.entries.forEach { r -> LockChip(r == s.role, { onChange { it.copy(role = r) } }, { Text(r.cotName) }, enabled = !mRole) }
            }
            if (mRole) Text("Set by your organization", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        Section("Reporting") {
            ToggleRow(
                "Adaptive reporting",
                (if (mMode) "Set by your organization. " else "") +
                    "Report by movement: every 3 min when still, 30 s walking, 10 s driving, 5 s fast, " +
                    "and at once when you start moving or turn. Longer on low battery. Emergencies are unaffected.",
                adaptive, enabled = !mMode,
            ) { v -> onChange { it.copy(reportingMode = if (v) com.taksolutions.takbeacon.data.ReportingMode.ADAPTIVE else com.taksolutions.takbeacon.data.ReportingMode.FIXED) } }
            if (!adaptive) Text("Report every", style = MaterialTheme.typography.labelLarge)
            if (!adaptive) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Spacer(Modifier.height(8.dp))
            Text("Map symbol", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BeaconSettings.COT_TYPE_PRESETS.forEach { (type, label) ->
                    LockChip(s.pliCotType == type, { onChange { it.copy(pliCotType = type) } }, { Text(label) }, enabled = !mCot)
                }
                if (BeaconSettings.COT_TYPE_PRESETS.none { it.first == s.pliCotType }) {
                    LockChip(true, {}, { Text("Custom") }, enabled = !mCot)
                }
            }
            Text(
                s.pliCotType + if (mCot) " \u00B7 set by your organization" else "",
                style = MaterialTheme.typography.bodySmall, fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
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
                ToggleRow(
                    "Only when no server is connected",
                    "Routine reports go over UDP only while no TAK server is connected. Emergencies always do.",
                    s.udpOnlyWhenDisconnected,
                ) { v -> onChange { it.copy(udpOnlyWhenDisconnected = v) } }
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
                "Pause while ATAK is running",
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
                if (mBoot) "Set by your organization: the beacon starts when the phone starts and when the app opens."
                else "Resume reporting after a restart, if the beacon was on. Needs location access \"all the time\".",
                s.startOnBoot,
                enabled = !mBoot,
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

        if (s.messagingMode != com.taksolutions.takbeacon.data.MessagingMode.OFF) {
            Section("Messages") {
                Text(
                    "TAK chat sent to this device, to All Chat Rooms and to your team arrives while the beacon is on." +
                        if (s.messagingMode == com.taksolutions.takbeacon.data.MessagingMode.READ_ONLY) " Replies are turned off by your organization." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToggleRow("Notify for group chat", "All Chat Rooms and team messages notify, as well as direct messages.",
                    s.chatGroupNotify) { v -> onChange { it.copy(chatGroupNotify = v) } }
                ToggleRow("Send read receipts", "Let senders see when a direct message was delivered and read.",
                    s.chatReceipts) { v -> onChange { it.copy(chatReceipts = v) } }
                TextButton(enabled = !s.locked, onClick = onClearMessages) { Text("Clear messages on this device") }

                Spacer(Modifier.height(12.dp))
                Text("Priority alerts", style = MaterialTheme.typography.labelLarge)
                Text(
                    (if (mKeywords) "Set by your organization. " else "") +
                        "A message containing one of these words or phrases sounds an alarm at full volume, even on silent, " +
                        "until you acknowledge it (at most 2 minutes). Separate them with commas. Not while paused for ATAK.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var keywords by remember(s.alertKeywords) { mutableStateOf(s.alertKeywords.joinToString(", ")) }
                val parsed = com.taksolutions.takbeacon.chat.KeywordAlert.parse(keywords)
                OutlinedTextField(
                    keywords, { keywords = it.take(2_000) },
                    label = { Text("Keywords") }, placeholder = { Text("e.g. mayday, evacuate, shots fired") },
                    enabled = !s.locked && !mKeywords, minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(
                        enabled = !s.locked && !mKeywords && parsed != s.alertKeywords,
                        onClick = { onChange { it.copy(alertKeywords = parsed) } },
                    ) { Text("Save keywords") }
                    TextButton(onClick = onTestAlert) { Text("Test alert") }
                }
                if (!dndAccess) {
                    Text(
                        "To sound even when Do Not Disturb silences alarms, allow TAK Beacon to override Do Not Disturb.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedButton(onClick = onDndAccess, modifier = Modifier.padding(top = 6.dp)) { Text("Allow through Do Not Disturb") }
                }
            }
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
