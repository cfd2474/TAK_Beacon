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
                val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                // "Start on boot" means start: whether or not it ran before the restart.
                // After an update (which kills the app), resume if it was running.
                val fg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val start = (s.startOnBoot && bg) || (updated && s.wasRunning && fg)
                // If Android refuses a background start, the ATAK plugin reopens the app to start it.
                if (start) runCatching { BeaconService.start(context) }
            } finally {
                pending.finish()
            }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A locked beacon cannot be switched off from the notification either.
        if (intent.action == BeaconService.ACTION_STOP && !BeaconApp.from(context).locked) BeaconService.stop(context)
    }
}
