package com.taksolutions.takbeacon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.taksolutions.takbeacon.data.ServerRepository
import com.taksolutions.takbeacon.data.SettingsRepository
import com.taksolutions.takbeacon.enroll.DataPackageImporter
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.net.ConnectionManager
import com.taksolutions.takbeacon.security.CertStore
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached so the network layer can read it without suspending. */
    @Volatile var uid: String = ""
        private set
    @Volatile var trustedAtakSigner: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        certs = CertStore(this)
        servers = ServerRepository(this, certs)
        connections = ConnectionManager(certs) { uid }
        enrollment = EnrollmentClient(certs, BuildConfig.VERSION_NAME)
        dataPackages = DataPackageImporter(certs)
        // Identity must exist before anything is sent; this is a tiny one-time write.
        runBlocking {
            settings.ensureIdentity()
            settings.current().let { uid = it.uid; trustedAtakSigner = it.trustedAtakSigner }
        }
        appScope.launch { settings.settings.collect { uid = it.uid; trustedAtakSigner = it.trustedAtakSigner } }
        createChannels()
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
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"

        fun from(context: Context) = context.applicationContext as BeaconApp
    }
}
