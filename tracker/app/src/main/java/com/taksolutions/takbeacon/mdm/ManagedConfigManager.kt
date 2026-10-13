package com.taksolutions.takbeacon.mdm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.enroll.EnrollRequest
import com.taksolutions.takbeacon.enroll.EnrollResult
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.service.BeaconService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the UI shows about MDM management. */
data class ManagedState(
    val managed: Boolean = false,
    /** User settings the MDM sets (see [ManagedConfig.managedFields]); shown read-only. */
    val fields: Set<String> = emptySet(),
    val serverId: String? = null,
    /** A problem applying the server, in words for the user; null when all is well. */
    val problem: String? = null,
    /** Enrollment stopped at a server certificate nobody vouched for. */
    val pendingTrust: PendingTrust? = null,
)

data class PendingTrust(val sha256: String, val subject: String, val issuer: String)

/**
 * Applies the MDM's managed configuration: at start-up, at boot, and whenever
 * Android reports the restrictions changed. MDMs may re-send an unchanged config
 * on every sync, so the server is only (re)provisioned when its part of the
 * config differs from what was last applied successfully.
 */
class ManagedConfigManager(private val app: BeaconApp) {

    private val prefs = app.getSharedPreferences("managed_config", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _state = MutableStateFlow(
        ManagedState(
            managed = prefs.getBoolean(P_MANAGED, false),
            fields = prefs.getStringSet(P_FIELDS, emptySet()).orEmpty(),
            serverId = prefs.getString(P_SERVER_ID, null),
            problem = prefs.getString(P_PROBLEM, null),
        )
    )
    val state: StateFlow<ManagedState> = _state.asStateFlow()

    fun isManagedServer(id: String) = _state.value.serverId == id

    /** Listens for config changes while the app runs; Android only tells running apps. */
    fun register() {
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    app.appScope.launch { apply() }
                }
            },
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun read(): ManagedConfig? {
        val rm = app.getSystemService(RestrictionsManager::class.java) ?: return null
        val b = runCatching { rm.applicationRestrictions }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val values = b.keySet().associateWith { b.get(it) }
        return ManagedConfig.parse(values) { Base64.decode(it, Base64.DEFAULT) }
    }

    /**
     * Applies the current config. [trustSha256] is a server certificate the user
     * just accepted for a managed server that isn't vouched for otherwise.
     */
    suspend fun apply(trustSha256: String? = null) = lock.withLock {
        val cfg = read()
        if (cfg == null) {
            if (_state.value.managed) unmanage()
            return@withLock
        }
        Log.i(TAG, "applying $cfg")

        val before = _state.value
        app.settings.update { s ->
            s.copy(
                callsign = cfg.callsign ?: s.callsign,
                team = cfg.team ?: s.team,
                role = cfg.role ?: s.role,
                startOnBoot = s.startOnBoot || cfg.startBeacon,
            )
        }

        var serverId: String? = null
        var problem: String? = null
        var pending: PendingTrust? = null
        var provisioned = false
        if (!cfg.hasServer) {
            before.serverId?.let { app.servers.remove(it) }
            prefs.edit().remove(P_APPLIED).apply()
        } else {
            val existing = before.serverId?.let(app.servers::get)
            val fp = cfg.serverFingerprint()
            if (existing != null && trustSha256 == null && fp == prefs.getString(P_APPLIED, null)) {
                serverId = existing.id
                problem = before.problem
            } else {
                val r = provision(cfg, existing, trustSha256)
                serverId = r.serverId
                problem = r.problem
                pending = r.pendingTrust
                provisioned = r.problem == null
                if (r.done) prefs.edit().putString(P_APPLIED, fp).apply()
            }
        }

        // Turn the beacon on when the config first asks for it, or when the server is new.
        val startWanted = cfg.startBeacon && (!prefs.getBoolean(P_STARTED, false) || provisioned)
        prefs.edit()
            .putBoolean(P_MANAGED, true)
            .putStringSet(P_FIELDS, cfg.managedFields)
            .putString(P_SERVER_ID, serverId)
            .putString(P_PROBLEM, problem)
            .putBoolean(P_STARTED, cfg.startBeacon)
            .apply()
        _state.value = ManagedState(true, cfg.managedFields, serverId, problem, pending)
        if (startWanted) startBeacon()
    }

    private class Provisioned(val serverId: String, val problem: String?, val pendingTrust: PendingTrust? = null, val done: Boolean)

    private suspend fun provision(cfg: ManagedConfig, existing: ServerConfig?, trustSha256: String?): Provisioned {
        val host = cfg.serverHost!!
        // Adopt a server the user already added for the same endpoint, rather than duplicate it.
        val id = existing?.id ?: app.servers.findByEndpoint(host, cfg.serverPort)?.id ?: EnrollmentClient.newServerId()
        val name = cfg.serverName ?: host
        val base = (existing ?: app.servers.get(id) ?: ServerConfig(id = id, name = name, host = host)).copy(
            name = name, host = host, port = cfg.serverPort, transport = cfg.transport, csrPort = cfg.enrollPort,
            enrollUsername = cfg.enrollUsername, managed = true, source = SOURCE,
        )

        if (cfg.transport == Transport.TCP) {
            app.certs.delete(id)
            app.servers.upsert(base.copy(hasClientCert = false, hasTrustStore = false, needsEnrollment = false, pinnedServerSha256 = null))
            return Provisioned(id, null, done = true)
        }

        // A trust store is useful with either kind of client certificate.
        if (cfg.trustP12 != null) {
            val ok = runCatching { CertStore.loadP12(cfg.trustP12, cfg.trustP12Password.orEmpty()) }.isSuccess
            if (!ok) return failed(base, "The trust store from your organization could not be opened (check its password)")
            app.certs.saveTrustP12(id, cfg.trustP12, cfg.trustP12Password.orEmpty())
        }

        if (cfg.clientP12 != null) {
            val ok = runCatching { CertStore.loadP12(cfg.clientP12, cfg.clientP12Password.orEmpty()) }.isSuccess
            if (!ok) return failed(base, "The client certificate from your organization could not be opened (check its password)")
            app.certs.saveClientP12(id, cfg.clientP12, cfg.clientP12Password.orEmpty())
            app.servers.upsert(
                base.copy(
                    hasClientCert = true, hasTrustStore = app.certs.hasTrust(id), needsEnrollment = false,
                    pinnedServerSha256 = cfg.serverCertSha256,
                )
            )
            return Provisioned(id, null, done = true)
        }

        if (!cfg.canEnroll) {
            // Nothing to get a certificate with: the user signs in from the Servers screen.
            app.servers.upsert(base.copy(needsEnrollment = !app.certs.hasClient(id), hasClientCert = app.certs.hasClient(id)))
            return Provisioned(id, if (app.certs.hasClient(id)) null else "Sign in to this server to get a certificate", done = true)
        }

        // A certificate the user accepted stays trusted for this address, even if enrollment then fails.
        if (trustSha256 != null) prefs.edit().putString(P_TRUSTED_SHA, "$host|${cfg.enrollPort}|$trustSha256").apply()
        val accepted = prefs.getString(P_TRUSTED_SHA, null)?.split('|')
            ?.takeIf { it.size == 3 && it[0] == host && it[1] == cfg.enrollPort.toString() }?.get(2)
        val pinned = trustSha256 ?: cfg.serverCertSha256 ?: accepted
        val req = EnrollRequest(host = host, port = cfg.serverPort, csrPort = cfg.enrollPort,
            username = cfg.enrollUsername, secret = cfg.enrollPassword, name = name)
        return when (val r = app.enrollment.enroll(req, app.settings.current().uid, pinned, id)) {
            is EnrollResult.Success -> {
                app.servers.upsert(
                    r.server.copy(
                        name = name, managed = true, source = SOURCE, enabled = existing?.enabled ?: true,
                        // An MDM trust store vouches for the server too.
                        hasTrustStore = r.server.hasTrustStore || app.certs.hasTrust(id),
                    )
                )
                Provisioned(id, null, done = true)
            }
            is EnrollResult.NeedsTrust -> {
                app.servers.upsert(base.copy(needsEnrollment = true))
                Provisioned(id, "Confirm this server's certificate to finish setting it up",
                    PendingTrust(r.sha256, r.subject, r.issuer), done = false)
            }
            is EnrollResult.Failure -> failed(base, r.message)
        }
    }

    /** Keeps the server visible (needing a certificate) with the reason; retried on the next apply. */
    private suspend fun failed(base: ServerConfig, problem: String): Provisioned {
        app.servers.upsert(base.copy(needsEnrollment = !app.certs.hasClient(base.id)))
        return Provisioned(base.id, problem, done = false)
    }

    /** The MDM removed the config: drop what it provided; the user's own settings stay as they are. */
    private suspend fun unmanage() {
        Log.i(TAG, "managed configuration removed")
        _state.value.serverId?.let { app.servers.remove(it) }
        prefs.edit().clear().apply()
        _state.value = ManagedState()
    }

    private fun startBeacon() {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // From the background Android may refuse; the beacon then starts at boot or when the app opens.
        if (fine) runCatching { BeaconService.start(app) }.onFailure { Log.i(TAG, "start deferred: ${it.message}") }
    }

    companion object {
        private const val TAG = "ManagedConfig"
        const val SOURCE = "Your organization (MDM)"
        private const val P_MANAGED = "managed"
        private const val P_FIELDS = "fields"
        private const val P_SERVER_ID = "server_id"
        private const val P_PROBLEM = "problem"
        private const val P_APPLIED = "applied_server_fingerprint"
        private const val P_STARTED = "start_beacon_applied"
        private const val P_TRUSTED_SHA = "trusted_server_sha256"
    }
}
