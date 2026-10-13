package com.taksolutions.takbeacon.ui.components

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * An AlertDialog-shaped dialog for forms. Unlike AlertDialog it keeps itself
 * above the keyboard and scrolls as a whole, so every field and button stays
 * reachable in landscape with the keyboard up.
 */
@Composable
fun ScrollingDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // Handle insets ourselves: the dialog then shrinks to the space above the keyboard.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Fill the screen and resize for the keyboard, so imePadding below has room to act.
        val view = LocalView.current
        val window = remember(view) {
            generateSequence<Any>(view) { (it as? View)?.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        }
        // Posted: Dialog applies its own window parameters after this composes.
        SideEffect {
            view.post {
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                @Suppress("DEPRECATION")
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    if (icon != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.secondary) { icon() }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (title != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (icon != null) Alignment.Center else Alignment.CenterStart) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

