package com.taksolutions.takbeacon.bridge

import android.os.Bundle
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.data.Role
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.enroll.EnrollmentLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A server connection (and optionally the operator's identity) sent from ATAK. */
class AtakProfile(
    val serverName: String,
    val host: String,
    val port: Int,
    val transport: Transport,
    val clientP12: ByteArray?,
    val clientPassword: String?,
    val trustP12: ByteArray?,
    val trustPassword: String?,
    val callsign: String?,
    val uid: String?,
    val team: String?,
    val role: String?,
) {
    companion object {
        private const val MAX_P12 = 64 * 1024

        fun fromBundle(b: Bundle): AtakProfile {
            val host = EnrollmentLink.cleanHost(b.getString(BridgeProtocol.KEY_HOST).orEmpty())
            require(EnrollmentLink.validHost(host)) { "Invalid host" }
            val port = b.getInt(BridgeProtocol.KEY_PORT, 8089)
            require(EnrollmentLink.validPort(port)) { "Invalid port" }
            val client = b.getByteArray(BridgeProtocol.KEY_CLIENT_P12)
            val trust = b.getByteArray(BridgeProtocol.KEY_TRUST_P12)
            require((client?.size ?: 0) <= MAX_P12 && (trust?.size ?: 0) <= MAX_P12) { "Certificate too large" }
            return AtakProfile(
                serverName = b.getString(BridgeProtocol.KEY_SERVER_NAME)?.take(64)?.takeIf { it.isNotBlank() } ?: host,
                host = host,
                port = port,
                transport = Transport.fromWire(b.getString(BridgeProtocol.KEY_TRANSPORT)),
                clientP12 = client,
                clientPassword = b.getString(BridgeProtocol.KEY_CLIENT_PASSWORD),
                trustP12 = trust,
                trustPassword = b.getString(BridgeProtocol.KEY_TRUST_PASSWORD),
                callsign = b.getString(BridgeProtocol.KEY_CALLSIGN)?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
                uid = b.getString(BridgeProtocol.KEY_UID)?.trim()?.take(64)
                    ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "-_.:" } },
                team = b.getString(BridgeProtocol.KEY_TEAM),
                role = b.getString(BridgeProtocol.KEY_ROLE),
            )
        }
    }
}

/** A profile from an ATAK build the user hasn't approved yet. */
object PendingProfile {
    data class Pending(val profile: AtakProfile, val caller: BridgeService.Caller)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun offer(p: AtakProfile, caller: BridgeService.Caller) { _pending.value = Pending(p, caller) }
    fun clear() { _pending.value = null }
}

/**
 * Saves an ATAK profile: replaces the certificates of a server already set up
 * for the same endpoint, or adds a new one; adopts ATAK's identity so the
 * operator is one marker on the map whichever app is reporting.
 */
class ProfileApplier(private val app: BeaconApp) {

    suspend fun apply(p: AtakProfile): Result<ServerConfig> = runCatching {
        val existing = app.servers.findByEndpoint(p.host, p.port)
        // The MDM's server is read-only, and so are the identity fields it sets.
        if (existing?.managed == true) return@runCatching existing
        val mdm = app.managed.state.value.fields
        val id = existing?.id ?: EnrollmentClient.newServerId()
        if (p.trustP12 != null) app.certs.saveTrustP12(id, p.trustP12, p.trustPassword.orEmpty())
        if (p.clientP12 != null) app.certs.saveClientP12(id, p.clientP12, p.clientPassword.orEmpty())
        val server = (existing ?: ServerConfig(id = id, name = p.serverName, host = p.host, port = p.port)).copy(
            name = p.serverName,
            transport = p.transport,
            enabled = true,
            hasClientCert = app.certs.hasClient(id),
            hasTrustStore = app.certs.hasTrust(id),
            needsEnrollment = p.transport == Transport.SSL && !app.certs.hasClient(id),
            source = "ATAK",
        )
        app.servers.upsert(server)
        app.settings.update { s ->
            s.copy(
                callsign = p.callsign?.takeUnless { "callsign" in mdm } ?: s.callsign,
                uid = p.uid ?: s.uid,
                team = Team.fromCot(p.team)?.takeUnless { "team" in mdm } ?: s.team,
                role = Role.fromCot(p.role)?.takeUnless { "role" in mdm } ?: s.role,
            )
        }
        server
    }
}
