package com.taksolutions.takbeacon.net

import com.taksolutions.takbeacon.cot.Cot
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.security.CertStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

enum class LinkState { CONNECTING, CONNECTED, ERROR }

data class LinkStatus(
    val state: LinkState,
    val message: String? = null,
    val lastSentMs: Long? = null,
    /** Reconnecting stopped after repeated certificate failures; [TakConnection.retryNow] resumes. */
    val suspended: Boolean = false,
)

/**
 * One streaming connection to a TAK server (TLS on 8089, or plain TCP).
 * Reconnects with backoff until [stop]. Incoming traffic is read and discarded:
 * a client that never reads stalls the server's writes and gets dropped.
 *
 * Outgoing events: routine position reports keep only the newest (an old
 * position is worthless after a reconnect); emergency alerts and cancels have
 * their own queue, are sent first and are never pushed out by positions.
 *
 * Certificate failures: a server that keeps rejecting this device's certificate
 * (or presents one the device doesn't trust) is not retried forever, which
 * could get the phone's address banned (TAK Server runs fail2ban). After
 * [MAX_CREDENTIAL_FAILURES] in a row the connection waits for [retryNow]
 * (re-enrollment, the user's Retry, or a new server configuration).
 */
class TakConnection(
    val server: ServerConfig,
    private val certs: CertStore,
    private val selfUid: () -> String,
    private val pingIntervalMs: () -> Long,
    private val onStatus: (String, LinkStatus) -> Unit,
) {
    private val latestPosition = AtomicReference<String?>(null)
    private val priority = Channel<String>(PRIORITY_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val retry = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var lastSent: Long? = null

    /** Queues [xml]; [priority] for emergency alerts and cancels. */
    fun send(xml: String, priority: Boolean = false) {
        if (priority) this.priority.trySend(xml) else latestPosition.set(xml)
        wake.trySend(Unit)
    }

    /** Resumes a connection that stopped retrying after certificate failures. */
    fun retryNow() { retry.trySend(Unit) }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(this) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(scope: CoroutineScope) {
        var backoff = 2_000L
        var credentialFailures = 0
        while (scope.isActive) {
            onStatus(server.id, LinkStatus(LinkState.CONNECTING, "Connecting to ${server.host}:${server.port}", lastSent))
            var socket: Socket? = null
            var connectedAt = 0L
            var failure: Exception? = null
            val readError = AtomicReference<IOException?>(null)
            try {
                socket = open()
                connectedAt = System.currentTimeMillis()
                onStatus(server.id, LinkStatus(LinkState.CONNECTED, null, lastSent))
                val s = socket
                val reader = scope.launch(Dispatchers.IO) { drain(s, readError) }
                try {
                    pump(s.getOutputStream(), reader)
                } finally {
                    reader.cancel()
                }
            } catch (e: CancellationException) {
                runCatching { socket?.close() }
                throw e
            } catch (e: Exception) {
                failure = e
            }
            runCatching { socket?.close() }

            // With TLS 1.3 a server rejects a client certificate *after* the handshake
            // completes on our side: the link "connects", then drops with an alert. So a
            // link only counts as healthy once it has stayed up for a while.
            val healthy = connectedAt > 0 && System.currentTimeMillis() - connectedAt >= HEALTHY_AFTER_MS
            val cause = listOfNotNull(readError.get(), failure).firstOrNull(::isCredentialFailure) ?: failure
            when {
                cause != null && isCredentialFailure(cause) -> credentialFailures++
                healthy || connectedAt == 0L -> credentialFailures = 0
            }
            if (healthy) backoff = 2_000L
            if (credentialFailures >= MAX_CREDENTIAL_FAILURES) {
                onStatus(server.id, LinkStatus(LinkState.ERROR, "${describe(cause!!)}. Stopped retrying: re-enroll, or tap Retry.", lastSent, suspended = true))
                retry.tryReceive() // a stale request must not skip the wait
                retry.receive()
                // One more attempt: if the server still refuses, stop again straight away.
                credentialFailures = MAX_CREDENTIAL_FAILURES - 1
                backoff = 2_000L
                continue
            }
            onStatus(server.id, LinkStatus(LinkState.ERROR, cause?.let(::describe) ?: "Server closed the connection", lastSent))
            // A Retry tap also cuts an ordinary backoff short.
            withTimeoutOrNull(backoff) { retry.receive() }
            backoff = min(backoff * 2, 60_000L)
        }
    }

    private fun open(): Socket {
        val raw = Socket()
        raw.keepAlive = true
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(server.host, server.port), 15_000)
        if (server.transport == Transport.TCP) return raw
        val (ctx, verifyHost) = certs.sslContext(server.id, server.pinnedServerSha256)
        val tls = ctx.socketFactory.createSocket(raw, server.host, server.port, true) as SSLSocket
        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        if (verifyHost) {
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        tls.soTimeout = 0
        tls.startHandshake()
        return tls
    }

    /** Writes emergencies first, then the newest position; pings when idle so dead links surface. */
    private suspend fun pump(out: OutputStream, reader: Job) {
        while (reader.isActive) {
            val xml = priority.tryReceive().getOrNull()
                ?: latestPosition.getAndSet(null)
                // Woken: look again (the wake may be for an event already sent). Idle: ping.
                ?: if (withTimeoutOrNull(pingIntervalMs()) { wake.receive() } != null) continue else Cot.ping(selfUid())
            out.write(xml.toByteArray(Charsets.UTF_8))
            out.flush()
            if (!xml.contains("type=\"t-x-c-t\"")) {
                lastSent = System.currentTimeMillis()
                onStatus(server.id, LinkStatus(LinkState.CONNECTED, null, lastSent))
            }
        }
        throw IOException("Server closed the connection")
    }

    /** Reads and discards; a read error (such as a TLS alert) is kept for [run] to classify. */
    private fun drain(s: Socket, error: AtomicReference<IOException?>) {
        val buf = ByteArray(16 * 1024)
        val input = s.getInputStream()
        try {
            while (input.read(buf) >= 0) { /* discard */ }
        } catch (e: IOException) {
            error.set(e)
        }
    }

    companion object {
        /** Consecutive certificate failures before reconnecting stops. */
        const val MAX_CREDENTIAL_FAILURES = 5
        /** A link that stays up this long is healthy: failure count and backoff reset. */
        private const val HEALTHY_AFTER_MS = 30_000L
        private const val PRIORITY_CAPACITY = 32

        /**
         * A failure about certificates (ours rejected, or theirs untrusted), which
         * retrying won't fix; as opposed to an outage (no route, refused, timeout,
         * reset mid-handshake), which it will.
         */
        fun isCredentialFailure(e: Throwable): Boolean {
            val chain = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            if (chain.any { it is CertificateException }) return true
            // Any TLS error carrying a certificate alert: during the handshake, or (TLS 1.3)
            // on the first read after it, when the server rejects our certificate.
            if (chain.none { it is javax.net.ssl.SSLException }) return false
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            val alerts = listOf("bad_certificate", "certificate_unknown", "certificate_required", "unknown_ca",
                "certificate_revoked", "certificate_expired", "unsupported_certificate", "access_denied", "trust anchor")
            val handshake = chain.firstOrNull { it is SSLHandshakeException }
            return alerts.any { it in text } || handshake?.cause is GeneralSecurityException
        }

        fun describe(e: Throwable): String = when {
            e is UnknownHostException -> "Unknown host"
            e is SSLHandshakeException && e.message?.contains("certificate_unknown", true) == true ->
                "Server rejected this device's certificate"
            e is SSLHandshakeException && e.message?.contains("bad_certificate", true) == true ->
                "Server rejected this device's certificate"
            e is javax.net.ssl.SSLException && listOf("certificate_required", "bad_certificate", "unknown_ca", "certificate_unknown")
                .any { e.message?.contains(it, true) == true } -> "Server rejected this device's certificate"
            e is SSLHandshakeException && (e.cause?.message ?: e.message ?: "").contains("Trust anchor", true) ->
                "Server certificate is not trusted"
            e is SSLHandshakeException -> "TLS handshake failed: ${e.message ?: ""}".trim()
            e is SocketException && e.message?.contains("ECONNREFUSED", true) == true -> "Connection refused"
            e is java.net.SocketTimeoutException -> "Timed out"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
