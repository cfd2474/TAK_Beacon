package com.taksolutions.takbeacon.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taksolutions.takbeacon.contacts.Contact
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.geo.Coordinates
import com.taksolutions.takbeacon.ui.components.StatusDot
import kotlinx.coroutines.delay

/**
 * The map with other users, and the details of the one tapped (with a Message
 * button when [onMessage] is set).
 */
@Composable
fun ContactMap(
    settings: BeaconSettings,
    lat: Double,
    lon: Double,
    color: Color,
    contacts: List<Contact>,
    onMessage: ((Contact) -> Unit)?,
    modifier: Modifier = Modifier,
    onFullScreen: (() -> Unit)? = null,
    expanded: Boolean = false,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept so the details stay readable if the user drops off the map meanwhile.
    var last by remember { mutableStateOf<Contact?>(null) }
    BeaconMap(
        lat = lat, lon = lon, dark = settings.darkMap, color = color, modifier = modifier,
        contacts = contacts, onContact = { selected = it.uid; last = it },
        onFullScreen = onFullScreen, expanded = expanded,
    )
    val uid = selected ?: return
    val live = contacts.firstOrNull { it.uid == uid }
    SideEffect { if (live != null) last = live }
    val c = live ?: last ?: run { selected = null; return }
    ContactDialog(c, gone = live == null, settings, lat, lon, onMessage?.let { m -> { selected = null; m(c) } }) { selected = null }
}

/** The full-screen map: closes with Back, the exit button or the close button. */
@Composable
fun FullScreenMap(
    settings: BeaconSettings,
    lat: Double,
    lon: Double,
    color: Color,
    contacts: List<Contact>,
    onMessage: ((Contact) -> Unit)?,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ContactMap(settings, lat, lon, color, contacts, onMessage, Modifier.fillMaxSize(), onFullScreen = onClose, expanded = true)
            Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) { Icon(Icons.Filled.Close, "Close map") }
                if (settings.showContacts) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        usersLabel(contacts.size),
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

fun usersLabel(n: Int) = when (n) {
    0 -> "No other users"
    1 -> "1 other user"
    else -> "$n other users"
}

@Composable
private fun ContactDialog(
    c: Contact,
    gone: Boolean,
    s: BeaconSettings,
    myLat: Double,
    myLon: Double,
    onMessage: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val dist = Coordinates.distanceM(myLat, myLon, c.lat, c.lon)
    val bearing = Coordinates.bearingDeg(myLat, myLon, c.lat, c.lon)
    val ago = ((now - c.receivedMs) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Color((c.team?.argb ?: 0xFF9E9E9E).toInt()), 14.dp)
                Spacer(Modifier.width(10.dp))
                Text(c.callsign)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOfNotNull(c.team?.cotName, c.role).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("%s  ·  %03.0f° %s from you".format(Coordinates.formatDistance(dist, s.speedUnit), bearing,
                    Coordinates.cardinal(bearing.toFloat())), style = MaterialTheme.typography.titleMedium)
                val speed = c.speedMps
                if (speed != null && speed >= 0.5) {
                    Text("Moving %.0f %s".format(speed * s.speedUnit.fromMps, s.speedUnit.label) +
                        (c.courseDeg?.let { "  ·  heading %03.0f° %s".format(it, Coordinates.cardinal(it.toFloat())) } ?: ""))
                }
                Text(Coordinates.format(c.lat, c.lon, s.coordFormat), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        gone -> "No longer reported (last report ${agoText(ago)})"
                        c.fading(now) -> "Last report ${agoText(ago)}: reports are being missed"
                        else -> "Last report ${agoText(ago)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (gone || c.fading(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { if (onMessage != null) TextButton(onClick = onMessage) { Text("Message") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private fun agoText(s: Long) = when {
    s < 60 -> "${s}s ago"
    s < 3600 -> "${s / 60} min ago"
    else -> "${s / 3600} h ago"
}
