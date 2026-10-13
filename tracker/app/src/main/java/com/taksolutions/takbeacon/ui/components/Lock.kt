package com.taksolutions.takbeacon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.security.PinLock
import kotlinx.coroutines.launch

/** "Locked" strip with an Unlock button, shown on screens the lock freezes. */
@Composable
fun LockedBanner(text: String, onUnlock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(10.dp))
        Text("$text Enter the PIN to make changes.", Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onUnlock) { Text("Unlock") }
    }
}

/**
 * Enter a PIN, or a new one twice. [submit] does the work and returns an error
 * to show, or null to close.
 */
@Composable
fun PinDialog(
    title: String,
    message: String,
    askCurrent: Boolean,
    askNew: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    submit: suspend (current: String, new: String) -> String?,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pinOpts = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    val digits = { v: String -> v.filter(Char::isDigit).take(PinLock.MAX_LENGTH) }
    val ready = (!askCurrent || current.length >= PinLock.MIN_LENGTH) &&
        (!askNew || (PinLock.isValidPin(new) && new == again))

    ScrollingDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (askCurrent) OutlinedTextField(current, { current = digits(it); error = null }, label = { Text("PIN") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOpts,
                    modifier = Modifier.fillMaxWidth())
                if (askNew) {
                    OutlinedTextField(new, { new = digits(it); error = null }, label = { Text("New PIN (${PinLock.MIN_LENGTH}–${PinLock.MAX_LENGTH} digits)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOpts,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(again, { again = digits(it); error = null }, label = { Text("New PIN again") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOpts,
                        isError = again.isNotEmpty() && again != new, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !busy, onClick = {
                busy = true
                scope.launch {
                    val e = submit(current, new)
                    busy = false
                    if (e == null) onDismiss() else { error = e; current = "" }
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
