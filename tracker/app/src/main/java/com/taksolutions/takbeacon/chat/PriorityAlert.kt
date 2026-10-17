package com.taksolutions.takbeacon.chat

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.R
import com.taksolutions.takbeacon.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A priority alert waiting for the user to acknowledge it. */
data class ActiveAlert(
    val keyword: String,
    val fromCallsign: String,
    val text: String,
    /** The chat thread it came from (null for a test). */
    val threadKey: String?,
    val timeMs: Long,
    /** The alarm is still sounding (it stops after [PriorityAlert.SOUND_MS] even unacknowledged). */
    val sounding: Boolean = true,
)

/**
 * Keyword alert mode: a message matched one of the user's priority keywords. Sounds an
 * alarm on the alarm stream at full volume (heard on silent and vibrate, and through Do
 * Not Disturb when the user has let TAK Beacon override it), vibrates, and posts a
 * priority notification that stays until acknowledged. The sound stops after two
 * minutes or on acknowledgement, whichever comes first; the alarm volume and Do Not
 * Disturb are put back as they were.
 */
class PriorityAlert(private val app: BeaconApp) {

    private val _state = MutableStateFlow<ActiveAlert?>(null)
    val state: StateFlow<ActiveAlert?> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrating = false
    /** What the alarm volume and Do Not Disturb were before the alert changed them. */
    private var savedVolume: Int? = null
    private var savedFilter: Int? = null
    private val timeout = Runnable { silence() }

    fun trigger(keyword: String, fromCallsign: String, text: String, threadKey: String?) = main.post {
        Log.i(TAG, "priority alert: keyword \"$keyword\" from $fromCallsign")
        _state.value = ActiveAlert(keyword, fromCallsign, text, threadKey, System.currentTimeMillis())
        startSound()
        // Another match while alerting starts the two minutes again.
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, SOUND_MS)
        post()
    }

    fun acknowledge() = main.post {
        main.removeCallbacks(timeout)
        _state.value = null
        stopSound()
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    /** The notification was swiped away without acknowledging: put it back. */
    fun reshow() = main.post { if (_state.value != null) post() }

    private fun silence() {
        stopSound()
        _state.value = _state.value?.copy(sounding = false)
        post()
    }

    // --- sound ------------------------------------------------------------------

    private fun startSound() {
        val am = app.getSystemService(AudioManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        if (savedFilter == null && nm.isNotificationPolicyAccessGranted && alarmsBlocked(nm)) {
            savedFilter = nm.currentInterruptionFilter
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                .onFailure { savedFilter = null; Log.w(TAG, "can't lift Do Not Disturb: ${it.message}") }
        }
        raiseVolume(am, attempt = 0)
        if (player == null) {
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(ALARM_AUDIO)
                    setDataSource(app, Uri.parse("android.resource://${app.packageName}/${R.raw.priority_alert}"))
                    isLooping = true
                    setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "alarm sound failed: ${it.message}") }.getOrNull()
        }
        if (!vibrating) {
            @Suppress("DEPRECATION")
            runCatching { vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), ALARM_AUDIO) }
            vibrating = true
        }
    }

    /**
     * Saves the alarm volume and sets it to maximum. While Do Not Disturb mutes the alarm
     * stream its volume reads 0, so wait (briefly) for lifting Do Not Disturb to unmute it:
     * saving the muted 0 would leave the user's alarms at minimum afterwards.
     */
    private fun raiseVolume(am: AudioManager, attempt: Int) {
        if (savedVolume != null || _state.value?.sounding != true) return
        // Muted reads as 0, below the alarm stream's minimum (Do Not Disturb doesn't show in isStreamMute).
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1) else 1
        val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
        if (now < min || am.isStreamMute(AudioManager.STREAM_ALARM)) {
            // Still muted after ~1.5 s: Do Not Disturb wins (no override access); leave the volume alone.
            if (attempt < 10) main.postDelayed({ raiseVolume(am, attempt + 1) }, 150)
            return
        }
        savedVolume = now
        Log.i(TAG, "alarm volume $now -> max")
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            .onFailure { Log.w(TAG, "can't raise alarm volume: ${it.message}") }
    }

    private fun stopSound() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        if (vibrating) runCatching { vibrator().cancel() }
        vibrating = false
        val am = app.getSystemService(AudioManager::class.java)
        savedVolume?.let { v ->
            // Only if the user didn't change it meanwhile.
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == am.getStreamMaxVolume(AudioManager.STREAM_ALARM)) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
            }
        }
        savedVolume = null
        val nm = app.getSystemService(NotificationManager::class.java)
        savedFilter?.let { f ->
            if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                runCatching { nm.setInterruptionFilter(f) }
            }
        }
        savedFilter = null
    }

    /** Do Not Disturb is on in a mode that silences alarms. */
    private fun alarmsBlocked(nm: NotificationManager): Boolean = when (nm.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= 28 &&
            (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
        else -> false
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)

    // --- notification -----------------------------------------------------------

    private fun post() {
        val a = _state.value ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            app, 7001,
            Intent(app, MainActivity::class.java).setAction(ACTION_OPEN_ALERT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun broadcast(code: Int, action: String) = PendingIntent.getBroadcast(
            app, code, Intent(app, PriorityAlertReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Priority alert: ${a.keyword}"
        val n = NotificationCompat.Builder(app, BeaconApp.CHANNEL_PRIORITY)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle(title)
            .setContentText("${a.fromCallsign}: ${a.text}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${a.fromCallsign}: ${a.text}"))
            .setSubText(if (a.sounding) null else "Waiting for acknowledgement")
            .setWhen(a.timeMs).setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            // Its own group, so Android doesn't fold it under the beacon's status notification.
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(7003, ACTION_DISMISSED))
            .addAction(R.drawable.ic_stat_beacon, "Acknowledge", broadcast(7002, ACTION_ACK))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(app, BeaconApp.CHANNEL_PRIORITY).setSmallIcon(R.drawable.ic_stat_beacon)
                    .setContentTitle(title).setContentText("Priority message. Unlock to read it.").build()
            )
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val TAG = "PriorityAlert"
        /** The alarm sounds for this long unless acknowledged first. */
        const val SOUND_MS = 120_000L
        /** Older messages (a backlog delivered on reconnect) are kept but don't sound the alarm. */
        const val MAX_AGE_MS = 15 * 60_000L
        private const val NOTIFICATION_ID = 7000
        private const val GROUP = "priority_alert"
        const val ACTION_OPEN_ALERT = "com.taksolutions.takbeacon.OPEN_ALERT"
        const val ACTION_ACK = "com.taksolutions.takbeacon.ALERT_ACK"
        const val ACTION_DISMISSED = "com.taksolutions.takbeacon.ALERT_DISMISSED"

        private val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

/** The alert notification's Acknowledge action, and its swipe-away. */
class PriorityAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alerts = BeaconApp.from(context).alerts
        when (intent.action) {
            PriorityAlert.ACTION_ACK -> alerts.acknowledge()
            PriorityAlert.ACTION_DISMISSED -> alerts.reshow()
        }
    }
}
