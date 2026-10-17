package com.taksolutions.takbeacon.ui.alert

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.chat.ChatManager
import com.taksolutions.takbeacon.ui.MainActivity
import com.taksolutions.takbeacon.ui.theme.BeaconTheme

/**
 * The priority alert, full screen: over the lock screen (from the notification's
 * full-screen intent, waking the display) and over other apps (PriorityAlert launches
 * it when the user is in another app). It shows only the alert, never the rest of
 * TAK Beacon: opening the conversation asks for the phone to be unlocked first.
 * It closes itself once the alert is acknowledged, here or anywhere else.
 */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Only Acknowledge closes it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })

        val alerts = BeaconApp.from(this).alerts
        setContent {
            BeaconTheme {
                val alert by alerts.state.collectAsStateWithLifecycle()
                LaunchedEffect(alert) { if (alert == null) finish() }
                val a = alert ?: return@BeaconTheme
                Box(
                    Modifier.fillMaxSize().background(Color(0xE6000000)).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val on = MaterialTheme.colorScheme.onErrorContainer
                            Text("Priority alert: ${a.keyword}", style = MaterialTheme.typography.headlineSmall, color = on)
                            Text(a.fromCallsign, style = MaterialTheme.typography.labelLarge, color = on,
                                modifier = Modifier.padding(top = 8.dp))
                            Text(a.text, style = MaterialTheme.typography.bodyLarge, color = on)
                            if (!a.sounding) Text("Alarm stopped after 2 minutes.", style = MaterialTheme.typography.bodySmall, color = on)
                            Button(
                                onClick = { alerts.acknowledge() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            ) { Text("Acknowledge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp)) }
                            a.threadKey?.let { key ->
                                TextButton(onClick = { openConversation(key) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Acknowledge and open conversation", color = on)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BeaconApp.from(this).alerts.alertScreenShown()
    }

    /**
     * Opens the conversation in TAK Beacon, after the user unlocks the phone if it is locked,
     * and only then acknowledges: a cancelled unlock leaves the alert up.
     */
    private fun openConversation(key: String) {
        val open = {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(ChatManager.ACTION_OPEN_THREAD)
                    .putExtra(ChatManager.EXTRA_THREAD, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            BeaconApp.from(this).alerts.acknowledge()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { open() }
            })
        } else open()
    }
}
