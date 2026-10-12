package com.taksolutions.takbeacon.ui.servers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.enroll.EnrollRequest
import com.taksolutions.takbeacon.enroll.EnrollmentLink
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.ui.EnrollUi
import com.taksolutions.takbeacon.ui.theme.Mono

/** Every dialog of the enrollment flow, driven by [EnrollUi]. */
@Composable
fun EnrollDialogs(
    state: EnrollUi,
    onSubmit: (EnrollRequest) -> Unit,
    onTrust: (EnrollRequest, String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        EnrollUi.Idle -> Unit
        is EnrollUi.Form -> EnrollForm(state.request, state.error, onSubmit, onDismiss)
        is EnrollUi.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Enrolling") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(state.message)
                }
            },
        )
        is EnrollUi.NeedsTrust -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Trust this server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.request.host} presented a certificate this device doesn't recognise. Only continue if this fingerprint matches what your administrator gave you.")
                    Text("SHA-256", style = MaterialTheme.typography.labelSmall)
                    Text(CertStore.prettyFingerprint(state.sha256), fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    Text("Subject: ${state.subject}", style = MaterialTheme.typography.bodySmall)
                    Text("Issuer: ${state.issuer}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { onTrust(state.request, state.sha256) }) { Text("Trust and continue") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is EnrollUi.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enrolled") },
            text = { Text("${state.server.name} is set up. Start the beacon to begin reporting.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
    }
}

@Composable
private fun EnrollForm(initial: EnrollRequest, error: String?, onSubmit: (EnrollRequest) -> Unit, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf(initial.host) }
    var name by remember { mutableStateOf(initial.name.orEmpty()) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var csrPort by remember { mutableStateOf(initial.csrPort.toString()) }
    var user by remember { mutableStateOf(initial.username.orEmpty()) }
    var secret by remember { mutableStateOf(initial.secret.orEmpty()) }
    val cleanHost = EnrollmentLink.cleanHost(host)
    val valid = EnrollmentLink.validHost(cleanHost) && port.toIntOrNull()?.let(EnrollmentLink::validPort) == true &&
        csrPort.toIntOrNull()?.let(EnrollmentLink::validPort) == true && user.isNotBlank() && secret.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to a TAK server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(host, { host = it }, label = { Text("Server address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Streaming") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(csrPort, { csrPort = it.filter(Char::isDigit).take(5) }, label = { Text("Enrollment") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Password or token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSubmit(initial.copy(
                    host = cleanHost, name = name.ifBlank { null }, port = port.toInt(), csrPort = csrPort.toInt(),
                    username = user.trim(), secret = secret,
                ))
            }) { Text("Enroll") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Manual server: address plus optional .p12 files (for servers without enrollment). */
@Composable
fun ManualServerDialog(
    onSave: (name: String, host: String, port: Int, transport: Transport, clientUri: Uri?, clientPw: String, trustUri: Uri?, trustPw: String) -> Unit,
    onDismiss: () -> Unit,
    error: String?,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8089") }
    var transport by remember { mutableStateOf(Transport.SSL) }
    var clientUri by remember { mutableStateOf<Uri?>(null) }
    var trustUri by remember { mutableStateOf<Uri?>(null) }
    var clientPw by remember { mutableStateOf("") }
    var trustPw by remember { mutableStateOf("") }
    val pickClient = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { clientUri = it }
    val pickTrust = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { trustUri = it }
    val cleanHost = EnrollmentLink.cleanHost(host)
    val valid = EnrollmentLink.validHost(cleanHost) && port.toIntOrNull()?.let(EnrollmentLink::validPort) == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server manually") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Server address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(transport == Transport.SSL, { transport = Transport.SSL; if (port == "8087") port = "8089" }, { Text("TLS") })
                    FilterChip(transport == Transport.TCP, { transport = Transport.TCP; if (port == "8089") port = "8087" }, { Text("TCP (unencrypted)") })
                }
                if (transport == Transport.SSL) {
                    Spacer(Modifier.height(4.dp))
                    Text("Leave the client certificate empty to sign in for one after saving.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { pickClient.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (clientUri == null) "Client certificate (.p12)…" else "Client certificate selected")
                    }
                    if (clientUri != null) OutlinedTextField(clientPw, { clientPw = it }, label = { Text("Client certificate password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { pickTrust.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (trustUri == null) "Trust store (.p12)…" else "Trust store selected")
                    }
                    if (trustUri != null) OutlinedTextField(trustPw, { trustPw = it }, label = { Text("Trust store password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Positions are sent unencrypted. Use only on a trusted network.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(name.trim(), cleanHost, port.toInt(), transport,
                    clientUri.takeIf { transport == Transport.SSL }, clientPw,
                    trustUri.takeIf { transport == Transport.SSL }, trustPw)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
