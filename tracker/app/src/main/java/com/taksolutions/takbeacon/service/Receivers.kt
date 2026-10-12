package com.taksolutions.takbeacon.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.taksolutions.takbeacon.BeaconApp
import kotlinx.coroutines.launch

/**
 * Resumes tracking after a reboot or app update, if the user turned on
 * "Start on boot" and tracking was running before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = BeaconApp.from(context)
        app.appScope.launch {
            try {
                val s = app.settings.current()
                // From the background, a location service only works with "Allow all the time".
                val bg = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (s.startOnBoot && s.wasRunning && bg) {
                    runCatching { BeaconService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BeaconService.ACTION_STOP) BeaconService.stop(context)
    }
}
