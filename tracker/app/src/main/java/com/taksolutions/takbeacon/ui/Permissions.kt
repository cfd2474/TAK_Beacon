package com.taksolutions.takbeacon.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Everything TAK Beacon needs, in the order it asks. Runtime permissions use Android's
 * prompt; special ones ([special]) are switched on in a settings page the app opens.
 * [effect] is what doesn't work without it (shown in the banner).
 */
enum class Perm(val permission: String, val label: String, val effect: String, val special: Boolean = false) {
    LOCATION(Manifest.permission.ACCESS_FINE_LOCATION, "Precise location",
        "The beacon can't report your position."),
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", "Notifications",
        "No notifications for messages, priority alerts or the running beacon."),
    // Only grantable once LOCATION is: Android asks for it on its own screen.
    BACKGROUND("android.permission.ACCESS_BACKGROUND_LOCATION", "Location all the time",
        "Reports stop when the screen is off, and the beacon can't restart after a reboot."),
    BATTERY(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "Unrestricted battery",
        "Android may stop position reports while the screen is off.", special = true),
    OVERLAY(Manifest.permission.SYSTEM_ALERT_WINDOW, "Display over other apps",
        "Priority alerts can't open in front of the app you're using.", special = true),
    FULL_SCREEN("android.permission.USE_FULL_SCREEN_INTENT", "Full-screen alerts",
        "Priority alerts can't fill the screen when the phone is locked.", special = true),
    DND(Manifest.permission.ACCESS_NOTIFICATION_POLICY, "Do Not Disturb access",
        "Priority alerts are silent when Do Not Disturb blocks alarms.", special = true),
    ;

    /** Needed on this Android version (and, for the alert ones, only while chat is on). */
    fun applies(chatOn: Boolean) = when (this) {
        LOCATION, BATTERY -> true
        NOTIFICATIONS -> Build.VERSION.SDK_INT >= 33
        BACKGROUND -> Build.VERSION.SDK_INT >= 29
        OVERLAY, DND -> chatOn
        FULL_SCREEN -> chatOn && Build.VERSION.SDK_INT >= 34
    }

    fun granted(ctx: Context): Boolean = when (this) {
        BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        OVERLAY -> Settings.canDrawOverlays(ctx)
        FULL_SCREEN -> Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        DND -> ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        else -> ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** What the beacon needs to start at all (the rest is asked for on launch and from the banner). */
        val TO_START = setOf(LOCATION, NOTIFICATIONS)

        fun missing(ctx: Context, chatOn: Boolean = true): List<Perm> =
            entries.filter { it.applies(chatOn) && !it.granted(ctx) }
    }
}
