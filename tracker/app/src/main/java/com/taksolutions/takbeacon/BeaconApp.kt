package com.taksolutions.takbeacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.taksolutions.takbeacon.data.ServerRepository
import com.taksolutions.takbeacon.data.SettingsRepository
import com.taksolutions.takbeacon.enroll.DataPackageImporter
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.chat.ChatManager
import com.taksolutions.takbeacon.chat.PriorityAlert
import com.taksolutions.takbeacon.mdm.ManagedConfigManager
import com.taksolutions.takbeacon.net.ConnectionManager
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.service.BeaconStatus
import com.taksolutions.takbeacon.service.ReportPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class BeaconApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var certs: CertStore
        private set
    lateinit var servers: ServerRepository
        private set
    lateinit var connections: ConnectionManager
        private set
    lateinit var enrollment: EnrollmentClient
        private set
    lateinit var dataPackages: DataPackageImporter
        private set
    lateinit var managed: ManagedConfigManager
        private set
    lateinit var chat: ChatManager
        private set
    lateinit var alerts: PriorityAlert
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached so the network layer can read it without suspending. */
    @Volatile var uid: String = ""
        private set
    @Volatile var trustedAtakSigner: String? = null
        private set
    /** The PIN lock is on: on/off, settings and servers must not change. */
    @Volatile var locked: Boolean = false
        private set
    /** The beacon was on when the app last stopped (an update or crash doesn't clear it). */
    @Volatile var wasRunning: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        certs = CertStore(this)
        servers = ServerRepository(this, certs)
        connections = ConnectionManager(certs, { uid }) {
            if (BeaconStatus.state.value.onSatellite) ReportPolicy.SATELLITE_PING_MS else ReportPolicy.PING_MS
        }
        enrollment = EnrollmentClient(certs, BuildConfig.VERSION_NAME)
        dataPackages = DataPackageImporter(certs)
        // Identity must exist before anything is sent; this is a tiny one-time write.
        runBlocking {
            settings.ensureIdentity()
            settings.current().let { uid = it.uid; trustedAtakSigner = it.trustedAtakSigner; locked = it.locked; wasRunning = it.wasRunning }
        }
        appScope.launch {
            settings.settings.collect { uid = it.uid; trustedAtakSigner = it.trustedAtakSigner; locked = it.locked; wasRunning = it.wasRunning }
        }
        createChannels()
        alerts = PriorityAlert(this)
        chat = ChatManager(this)
        chat.start()
        managed = ManagedConfigManager(this)
        managed.register()
        appScope.launch { managed.apply() }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
                .apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE }
        )
        // Keyword alerts: the app plays the alarm itself (on the alarm stream), so the channel is silent.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PRIORITY, getString(R.string.channel_priority), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_PRIORITY = "priority_alerts"

        fun from(context: Context) = context.applicationContext as BeaconApp
    }
}
