package com.taksolutions.takbeacon.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.bridge.PendingProfile
import com.taksolutions.takbeacon.bridge.ProfileApplier
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.Role
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.enroll.EnrollRequest
import com.taksolutions.takbeacon.enroll.EnrollResult
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.security.PinLock
import com.taksolutions.takbeacon.service.BeaconStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EnrollUi {
    data object Idle : EnrollUi
    /** Show the sign-in form, pre-filled from a link or QR code. */
    data class Form(val request: EnrollRequest, val error: String? = null) : EnrollUi
    data class Working(val message: String) : EnrollUi
    data class NeedsTrust(val request: EnrollRequest, val sha256: String, val subject: String, val issuer: String) : EnrollUi
    data class Done(val server: ServerConfig) : EnrollUi
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = BeaconApp.from(application)

    val settings: StateFlow<BeaconSettings?> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val servers = app.servers.servers
    val links = app.connections.status
    val tracker = BeaconStatus.state
    val pendingProfile = PendingProfile.pending

    private val _enroll = MutableStateFlow<EnrollUi>(EnrollUi.Idle)
    val enroll: StateFlow<EnrollUi> = _enroll.asStateFlow()

    /** One-shot messages for the snackbar. */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }
    fun toast(msg: String) { _toast.value = msg }

    /** True (and tells the user) when the PIN lock forbids a change. */
    private fun blocked(): Boolean {
        if (!app.locked) return false
        _toast.value = "Locked: enter the PIN to make changes"
        return true
    }

    fun updateSettings(f: (BeaconSettings) -> BeaconSettings) {
        if (blocked()) return
        viewModelScope.launch { app.settings.update(f) }
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        if (blocked()) return
        viewModelScope.launch { app.servers.setEnabled(id, enabled) }
    }

    fun deleteServer(id: String) {
        if (blocked()) return
        doDeleteServer(id)
    }

    private fun doDeleteServer(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.servers.remove(id) }
        _toast.value = "Server removed"
    }

    fun certSummary(id: String): String? = app.certs.clientSummary(id)

    // --- enrollment -----------------------------------------------------------

    fun showEnrollForm(request: EnrollRequest = EnrollRequest(host = "")) {
        if (blocked()) return
        _enroll.value = EnrollUi.Form(request)
    }
    fun dismissEnroll() { _enroll.value = EnrollUi.Idle }

    /** From a QR code or deep link: submit straight away when it carries credentials. */
    fun handleEnrollRequest(req: EnrollRequest) {
        if (blocked()) return
        if (req.canAutoSubmit) enroll(req) else showEnrollForm(req)
    }

    fun enroll(req: EnrollRequest, pinnedSha256: String? = null) {
        _enroll.value = EnrollUi.Working("Requesting a certificate from ${req.host}…")
        viewModelScope.launch {
            val s = app.settings.current()
            // Re-enrolling an existing server keeps its id (and its place in the list).
            val existing = app.servers.findByEndpoint(req.host, req.port)
            when (val r = app.enrollment.enroll(req, s.uid, pinnedSha256, existing?.id)) {
                is EnrollResult.Success -> {
                    val server = existing?.let { r.server.copy(enabled = it.enabled, name = req.name ?: it.name) } ?: r.server
                    app.servers.upsert(server)
                    app.settings.update { cur ->
                        cur.copy(
                            callsign = req.callsign ?: cur.callsign,
                            team = Team.fromCot(req.team) ?: cur.team,
                            role = Role.fromCot(req.role) ?: cur.role,
                        )
                    }
                    _enroll.value = EnrollUi.Done(server)
                }
                is EnrollResult.NeedsTrust -> _enroll.value = EnrollUi.NeedsTrust(req, r.sha256, r.subject, r.issuer)
                is EnrollResult.Failure -> _enroll.value = EnrollUi.Form(req, r.message)
            }
        }
    }

    /** Enrolls a server that came from a data package without a client certificate. */
    fun enrollExisting(server: ServerConfig) = showEnrollForm(
        EnrollRequest(host = server.host, port = server.port, csrPort = server.csrPort, name = server.name, username = server.enrollUsername)
    )

    // --- data packages and manual setup ---------------------------------------

    fun importDataPackage(uri: Uri) {
        if (blocked()) return
        doImportDataPackage(uri)
    }

    private fun doImportDataPackage(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver().openInputStream(uri)?.use { app.dataPackages.import(it) }
                    ?: error("Could not open the file")
            }
        }
        result.onSuccess { imported ->
            imported.servers.forEach { app.servers.upsert(it) }
            val n = imported.servers.size
            _toast.value = buildString {
                append("Imported $n server${if (n == 1) "" else "s"}")
                imported.warnings.firstOrNull()?.let { append(" · $it") }
            }
            // A package that only carried a trust store needs the user to sign in.
            imported.servers.firstOrNull { it.needsEnrollment }?.let(::enrollExisting)
        }.onFailure { _toast.value = it.message ?: "Import failed" }
    }

    fun addManualServer(
        name: String, host: String, port: Int, transport: Transport,
        clientUri: Uri?, clientPassword: String, trustUri: Uri?, trustPassword: String,
        onDone: (String?) -> Unit,
    ) {
        if (blocked()) { onDone("Locked: enter the PIN to make changes"); return }
        doAddManualServer(name, host, port, transport, clientUri, clientPassword, trustUri, trustPassword, onDone)
    }

    private fun doAddManualServer(
        name: String, host: String, port: Int, transport: Transport,
        clientUri: Uri?, clientPassword: String, trustUri: Uri?, trustPassword: String,
        onDone: (String?) -> Unit,
    ) = viewModelScope.launch {
        val id = EnrollmentClient.newServerId()
        val error = withContext(Dispatchers.IO) {
            runCatching {
                trustUri?.let { app.certs.saveTrustP12(id, read(it), trustPassword) }
                clientUri?.let { app.certs.saveClientP12(id, read(it), clientPassword) }
            }.exceptionOrNull()
        }
        if (error != null) {
            app.certs.delete(id)
            onDone(error.message ?: "Could not read the certificate")
            return@launch
        }
        app.servers.upsert(
            ServerConfig(
                id = id, name = name.ifBlank { host }, host = host, port = port, transport = transport,
                hasClientCert = clientUri != null, hasTrustStore = trustUri != null,
                needsEnrollment = transport == Transport.SSL && clientUri == null, source = "manual",
            )
        )
        onDone(null)
        if (transport == Transport.SSL && clientUri == null) {
            enrollExisting(app.servers.findByEndpoint(host, port)!!)
        }
    }

    private fun read(uri: Uri): ByteArray =
        app.contentResolver().openInputStream(uri)?.use { s ->
            val bytes = s.readBytes()
            require(bytes.size <= 64 * 1024) { "Certificate file is too large" }
            bytes
        } ?: error("Could not open the file")

    private fun BeaconApp.contentResolver() = applicationContext.contentResolver

    // --- ATAK profile approval ------------------------------------------------

    fun approvePendingProfile() {
        if (blocked()) return
        doApprovePendingProfile()
    }

    private fun doApprovePendingProfile() = viewModelScope.launch {
        val p = PendingProfile.pending.value ?: return@launch
        PendingProfile.clear()
        app.settings.update { it.copy(trustedAtakSigner = p.caller.signerSha256) }
        ProfileApplier(app).apply(p.profile)
            .onSuccess { _toast.value = "Added ${it.name} from ATAK" }
            .onFailure { _toast.value = it.message ?: "Could not apply the ATAK profile" }
    }

    fun rejectPendingProfile() = PendingProfile.clear()

    fun forgetAtak() = updateSettings { it.copy(trustedAtakSigner = null) }

    // --- PIN lock -------------------------------------------------------------

    /** Sets a PIN, or changes it (then [current] must match). Returns an error, or null. */
    suspend fun setPin(current: String?, newPin: String): String? {
        val s = app.settings.current()
        if (!PinLock.isValidPin(newPin)) return "Use ${PinLock.MIN_LENGTH} to ${PinLock.MAX_LENGTH} digits"
        if (s.hasPin) checkPin(s, current.orEmpty())?.let { return it }
        val h = withContext(Dispatchers.Default) { PinLock.hash(newPin) }
        app.settings.update { it.copy(pinHash = h.hash, pinSalt = h.salt) }
        return null
    }

    /** Removes the PIN (and so the lock). Returns an error, or null. */
    suspend fun removePin(current: String): String? {
        val s = app.settings.current()
        checkPin(s, current)?.let { return it }
        app.settings.update { it.copy(pinHash = null, pinSalt = null, locked = false) }
        return null
    }

    /** Not a user setting, so the lock doesn't apply. */
    fun markPermissionsPrompted() = viewModelScope.launch {
        app.settings.update { it.copy(permissionsPrompted = true) }
    }

    fun lock() = viewModelScope.launch {
        if (app.settings.current().hasPin) app.settings.update { it.copy(locked = true) }
    }

    /** Unlocks with [pin]. Returns an error, or null. */
    suspend fun unlock(pin: String): String? {
        val s = app.settings.current()
        checkPin(s, pin)?.let { return it }
        app.settings.update { it.copy(locked = false) }
        return null
    }

    private suspend fun checkPin(s: BeaconSettings, pin: String): String? {
        val wait = PinLock.waitMs()
        if (wait > 0) return "Too many wrong PINs. Try again in ${(wait + 999) / 1000} s"
        val ok = withContext(Dispatchers.Default) {
            s.hasPin && PinLock.matches(pin, PinLock.Hashed(s.pinHash!!, s.pinSalt!!))
        }
        if (ok) { PinLock.recordSuccess(); return null }
        PinLock.recordFailure()
        return "Wrong PIN"
    }
}
