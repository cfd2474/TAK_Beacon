package com.taksolutions.takbeacon.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.net.LinkState
import com.taksolutions.takbeacon.net.LinkStatus
import com.taksolutions.takbeacon.ui.components.KeyValueRow
import com.taksolutions.takbeacon.ui.components.LockedBanner
import com.taksolutions.takbeacon.ui.components.Section
import com.taksolutions.takbeacon.ui.components.StatusDot
import com.taksolutions.takbeacon.ui.theme.BeaconTheme

@Composable
fun ServersScreen(
    servers: List<ServerConfig>,
    links: Map<String, LinkStatus>,
    contentPadding: PaddingValues,
    certSummary: (String) -> String?,
    onScan: () -> Unit,
    onSignIn: () -> Unit,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEnroll: (ServerConfig) -> Unit,
    onDelete: (String) -> Unit,
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    managedProblem: String? = null,
    managedNeedsTrust: Boolean = false,
    onReviewTrust: (ServerConfig) -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf<ServerConfig?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (locked) item { LockedBanner("Servers are locked.", onUnlock) }
        item {
            Section("Add a server") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddTile(Icons.Outlined.QrCodeScanner, "Scan QR", Modifier.weight(1f), onScan, !locked)
                    AddTile(Icons.Outlined.Key, "Sign in", Modifier.weight(1f), onSignIn, !locked)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddTile(Icons.Outlined.FolderZip, "Data package", Modifier.weight(1f), onImport, !locked)
                    AddTile(Icons.Outlined.Tune, "Manual", Modifier.weight(1f), onManual, !locked)
                }
            }
        }
        if (servers.isEmpty()) {
            item {
                Text(
                    "Scan the enrollment QR code from your TAK server administrator, or import the data package they sent you.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        items(servers, key = { it.id }) { s ->
            ServerCard(
                s, links[s.id], certSummary(s.id), onToggle, onEnroll, locked,
                problem = if (s.managed) managedProblem else null,
                needsTrust = s.managed && managedNeedsTrust,
                onReviewTrust = { onReviewTrust(s) },
            ) { confirmDelete = s }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${s.name}?") },
            text = { Text("Its certificates are deleted from this device. You will need to enroll again to reconnect.") },
            confirmButton = { TextButton(onClick = { onDelete(s.id); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun AddTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(64.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ServerCard(
    s: ServerConfig,
    link: LinkStatus?,
    cert: String?,
    onToggle: (String, Boolean) -> Unit,
    onEnroll: (ServerConfig) -> Unit,
    locked: Boolean,
    problem: String? = null,
    needsTrust: Boolean = false,
    onReviewTrust: () -> Unit = {},
    onDelete: () -> Unit,
) {
    val c = BeaconTheme.status
    val (dot, state) = when {
        s.needsEnrollment -> c.waiting to "Needs a certificate"
        !s.enabled -> c.offline to "Disabled"
        link == null -> c.offline to "Idle (beacon off)"
        link.state == LinkState.CONNECTED -> c.live to "Connected"
        link.state == LinkState.CONNECTING -> c.waiting to "Connecting"
        else -> MaterialTheme.colorScheme.error to (link.message ?: "Error")
    }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dot)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The MDM's server is always on and can't be removed here.
            if (!s.managed) Switch(checked = s.enabled, onCheckedChange = { onToggle(s.id, it) }, enabled = !s.needsEnrollment && !locked)
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Address", "${s.host}:${s.port} (${if (s.transport == Transport.SSL) "TLS" else "TCP"})")
        KeyValueRow("Certificate", cert ?: if (s.transport == Transport.TCP) "Not used" else "None")
        KeyValueRow("Server trust", when {
            s.hasTrustStore -> "Server CA"
            s.pinnedServerSha256 != null -> "Pinned certificate"
            else -> "System CAs"
        })
        KeyValueRow("Added from", s.source)
        if (s.managed) {
            Text("Managed by your organization", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (needsTrust) {
                TextButton(onClick = onReviewTrust) { Text("Review certificate") }
            } else if (s.transport == Transport.SSL && (!s.managed || s.needsEnrollment)) {
                TextButton(enabled = !locked, onClick = { onEnroll(s) }) {
                    Icon(Icons.Outlined.Edit, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (s.needsEnrollment) "Sign in" else "Re-enroll")
                }
            }
            if (!s.managed) IconButton(enabled = !locked, onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remove server") }
        }
    }
}
