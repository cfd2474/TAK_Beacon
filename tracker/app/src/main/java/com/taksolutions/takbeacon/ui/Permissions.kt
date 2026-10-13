package com.taksolutions.takbeacon.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The runtime permissions TAK Beacon asks for, in the order Android requires. */
enum class Perm(val permission: String, val label: String) {
    LOCATION(Manifest.permission.ACCESS_FINE_LOCATION, "Precise location"),
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS", "Notifications"),
    // Only grantable once LOCATION is: Android asks for it on its own screen.
    BACKGROUND("android.permission.ACCESS_BACKGROUND_LOCATION", "Location all the time"),
    ;

    fun applies() = when (this) {
        LOCATION -> true
        NOTIFICATIONS -> Build.VERSION.SDK_INT >= 33
        BACKGROUND -> Build.VERSION.SDK_INT >= 29
    }

    companion object {
        fun missing(ctx: Context): List<Perm> = entries.filter {
            it.applies() && ContextCompat.checkSelfPermission(ctx, it.permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}
