package com.taksolutions.takbeacon.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.BuildConfig
import com.taksolutions.takbeacon.R
import com.taksolutions.takbeacon.cot.Cot
import com.taksolutions.takbeacon.cot.Fix
import com.taksolutions.takbeacon.cot.Identity
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.EmergencyType
import com.taksolutions.takbeacon.geo.Coordinates
import com.taksolutions.takbeacon.net.NetworkWatcher
import com.taksolutions.takbeacon.net.UdpBroadcaster
import com.taksolutions.takbeacon.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The tracker: a location foreground service that sends PLI to every enabled
 * server on the configured interval, sends emergencies, and steps aside while
 * ATAK is in the foreground (so the operator isn't on the map twice).
 */
class BeaconService : LifecycleService() {

    private val app by lazy { BeaconApp.from(this) }
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var sos: VolumeSosDetector? = null
    private var network: NetworkWatcher? = null
    private var udp: UdpBroadcaster? = null

    @Volatile private var settings: BeaconSettings? = null
    @Volatile private var lastFix: Fix? = null
    @Volatile private var lastFixElapsed = 0L
    @Volatile private var lastGpsElapsed = 0L
    private var lastPliElapsed = 0L
    private var lastEmergencyElapsed = 0L
    private var lastNotificationText = ""

    private val listener = LocationListener { loc -> onLocation(loc) }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!hasLocationPermission() || (intent?.action == ACTION_STOP && !started)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) begin()
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_EMERGENCY -> intent.getStringExtra(EXTRA_EMERGENCY)
                ?.let { runCatching { EmergencyType.valueOf(it) }.getOrNull() }
                ?.let(::raiseEmergency)
            ACTION_CANCEL_EMERGENCY -> cancelEmergency()
        }
        return START_STICKY
    }

    private var started = false

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin() {
        started = true
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TAKBeacon:tracking").apply { acquire() }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (locationManager.allProviders.contains(provider)) {
                locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            }
        }
        BeaconStatus.update { it.copy(runState = RunState.WAITING_FOR_FIX) }
        lifecycleScope.launch { app.settings.update { it.copy(wasRunning = true) } }

        network = NetworkWatcher(this).also { w ->
            w.start()
            lifecycleScope.launch { w.onSatellite.collect { sat -> BeaconStatus.update { it.copy(onSatellite = sat) } } }
        }

        lifecycleScope.launch {
            app.settings.settings.combine(app.servers.servers) { s, list -> s to list }.collect { (s, list) ->
                val prevSos = settings?.volumeSos
                settings = s
                app.connections.sync(list, lifecycleScope)
                if (prevSos != s.volumeSos) toggleSos(s.volumeSos)
                toggleUdp(s.udpBroadcast)
            }
        }
        lifecycleScope.launch { tickLoop() }
    }

    private suspend fun tickLoop() {
        while (lifecycleScope.isActive) {
            tick()
            delay(1_000)
        }
    }

    private fun tick() {
        val s = settings ?: return
        val now = SystemClock.elapsedRealtime()
        val fix = lastFix
        val state = BeaconStatus.state.value
        val emergency = state.emergency
        val serverCount = app.servers.servers.value.count { it.enabled && !it.needsEnrollment }
        val onSatellite = state.onSatellite
        val runState = ReportPolicy.runState(
            s, serverCount, hasFix = fix != null, atakInFront = BeaconStatus.atakInForeground(now),
            onSatellite = onSatellite, emergency = emergency != null,
        )
        val interval = ReportPolicy.intervalSeconds(s, onSatellite)
        val battery = batteryPct()

        if (runState == RunState.TRANSMITTING && fix != null && now - lastPliElapsed >= interval * 1000L) {
            lastPliElapsed = now
            // Over satellite the marker must outlive the slower reporting.
            val stale = if (onSatellite) maxOf(s.staleSeconds, interval * 3) else s.staleSeconds
            send(Cot.pli(identity(s), fix, stale, battery))
            BeaconStatus.update { it.copy(lastSentMs = System.currentTimeMillis()) }
        }
        // Keep an active emergency alive on the map.
        if (emergency != null && fix != null && now - lastEmergencyElapsed >= EMERGENCY_REPEAT_MS) {
            lastEmergencyElapsed = now
            send(Cot.emergency(identity(s), fix, emergency))
        }
        BeaconStatus.update {
            it.copy(
                runState = runState, batteryPct = battery, effectiveIntervalSeconds = interval,
                udpError = if (s.udpBroadcast) udp?.lastError?.value else null,
            )
        }
        updateNotification(runState, s)
    }

    private fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        // Network fixes only fill gaps; never overwrite a recent GPS fix.
        if (!isGps && now - lastGpsElapsed < 15_000L) return
        if (isGps) lastGpsElapsed = now
        val fix = Fix(
            lat = loc.latitude,
            lon = loc.longitude,
            hae = if (loc.hasAltitude()) loc.altitude else null,
            ce = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            le = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            courseDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            timeMs = loc.time,
            source = if (isGps) "GPS" else "NETWORK",
        )
        val first = lastFix == null
        lastFix = fix
        lastFixElapsed = now
        BeaconStatus.update { it.copy(fix = fix) }
        if (first) lastPliElapsed = 0L // report the first fix immediately
    }

    private fun raiseEmergency(type: EmergencyType) {
        BeaconStatus.update { it.copy(emergency = type) }
        lastEmergencyElapsed = 0L
        val s = settings
        val fix = lastFix
        if (s != null && fix != null) {
            lastEmergencyElapsed = SystemClock.elapsedRealtime()
            send(Cot.emergency(identity(s), fix, type))
            send(Cot.pli(identity(s), fix, s.staleSeconds, batteryPct()))
        }
        vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
    }

    private fun cancelEmergency() {
        val s = settings
        val fix = lastFix
        if (BeaconStatus.state.value.emergency != null && s != null && fix != null) {
            send(Cot.emergencyCancel(identity(s), fix))
        }
        BeaconStatus.update { it.copy(emergency = null) }
        vibrate(longArrayOf(0, 120))
    }

    /** To every connected TAK server and, when enabled, the UDP destination. */
    private fun send(xml: String) {
        app.connections.broadcast(xml)
        val s = settings ?: return
        if (s.udpBroadcast) udp?.send(s.udpHost, s.udpPort, xml)
    }

    private fun toggleUdp(enabled: Boolean) {
        if (enabled && udp == null) {
            udp = runCatching { UdpBroadcaster().also { it.start(lifecycleScope) } }.getOrNull()
        } else if (!enabled && udp != null) {
            udp?.stop()
            udp = null
        }
    }

    private fun toggleSos(enabled: Boolean) {
        sos?.stop()
        sos = if (enabled) VolumeSosDetector(this) {
            ContextCompat.getMainExecutor(this).execute { raiseEmergency(EmergencyType.ALERT_911) }
        }.also { it.start() } else null
    }

    private fun identity(s: BeaconSettings) = Identity(
        uid = s.uid,
        callsign = s.callsign,
        team = s.team.cotName,
        role = s.role.cotName,
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        os = Build.VERSION.SDK_INT.toString(),
        version = BuildConfig.VERSION_NAME,
    )

    private fun batteryPct(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun updateNotification(state: RunState, s: BeaconSettings) {
        val connected = app.connections.connectedCount()
        val text = when (state) {
            RunState.TRANSMITTING -> {
                val f = lastFix
                (if (BeaconStatus.state.value.onSatellite) "Satellite · " else "") +
                    "${s.callsign} · $connected connected · " + (f?.let { Coordinates.format(it.lat, it.lon, s.coordFormat) } ?: "")
            }
            RunState.WAITING_FOR_FIX -> "Waiting for GPS fix"
            RunState.PAUSED_FOR_ATAK -> "Paused — ATAK is reporting your position"
            RunState.PAUSED_SATELLITE -> "Paused on satellite — satellite data is off in Settings"
            RunState.NO_SERVERS -> "No servers configured"
            RunState.STOPPED -> "Stopped"
        }.let { t -> BeaconStatus.state.value.emergency?.let { "EMERGENCY: ${it.label} · $t" } ?: t }
        val key = text + app.locked
        if (key == lastNotificationText) return
        lastNotificationText = key
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BeaconApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .apply { if (!app.locked) addAction(0, getString(R.string.notif_stop), stop) }
            .build()
    }

    private fun vibrate(pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun shutdown() {
        lifecycleScope.launch { app.settings.update { it.copy(wasRunning = false) } }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(listener)
        sos?.stop()
        sos = null
        network?.stop()
        network = null
        udp?.stop()
        udp = null
        app.connections.stopAll()
        wakeLock?.takeIf { it.isHeld }?.release()
        BeaconStatus.update { it.copy(runState = RunState.STOPPED, emergency = null, onSatellite = false) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EMERGENCY_REPEAT_MS = 30_000L
        const val ACTION_START = "com.taksolutions.takbeacon.START"
        const val ACTION_STOP = "com.taksolutions.takbeacon.STOP"
        const val ACTION_EMERGENCY = "com.taksolutions.takbeacon.EMERGENCY"
        const val ACTION_CANCEL_EMERGENCY = "com.taksolutions.takbeacon.CANCEL_EMERGENCY"
        const val EXTRA_EMERGENCY = "emergency"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, BeaconService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            if (BeaconStatus.state.value.runState == RunState.STOPPED) return
            context.startService(Intent(context, BeaconService::class.java).setAction(ACTION_STOP))
        }

        fun emergency(context: Context, type: EmergencyType) = ContextCompat.startForegroundService(
            context, Intent(context, BeaconService::class.java).setAction(ACTION_EMERGENCY).putExtra(EXTRA_EMERGENCY, type.name),
        )

        fun cancelEmergency(context: Context) {
            if (BeaconStatus.state.value.runState == RunState.STOPPED) return
            context.startService(Intent(context, BeaconService::class.java).setAction(ACTION_CANCEL_EMERGENCY))
        }
    }
}
