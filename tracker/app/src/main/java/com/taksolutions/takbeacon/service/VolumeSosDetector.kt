package com.taksolutions.takbeacon.service

import android.content.Context
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat

/**
 * Hardware SOS: five volume-key presses within [WINDOW_MS] fire [onTrigger].
 *
 * A media session with a remote volume provider receives volume keys while it
 * is the active session — including with the screen off — without an
 * accessibility service. The cost is that, while enabled, the volume keys stop
 * adjusting media volume; the settings screen says so.
 */
class VolumeSosDetector(context: Context, private val onTrigger: () -> Unit) {

    private val presses = ArrayDeque<Long>()
    private val session = MediaSessionCompat(context, "TAKBeaconSos")

    private val provider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 10, 5) {
        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) press()
        }
    }

    fun start() {
        session.setPlaybackToRemote(provider)
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 0f)
                .build()
        )
        session.isActive = true
    }

    fun stop() {
        session.isActive = false
        session.release()
    }

    @Synchronized
    private fun press() {
        val now = SystemClock.elapsedRealtime()
        presses.addLast(now)
        while (presses.isNotEmpty() && now - presses.first() > WINDOW_MS) presses.removeFirst()
        if (presses.size >= PRESSES) {
            presses.clear()
            onTrigger()
        }
    }

    companion object {
        const val PRESSES = 5
        const val WINDOW_MS = 3_000L
    }
}
