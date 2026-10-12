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
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

enum class LinkState { CONNECTING, CONNECTED, ERROR }

data class LinkStatus(
    val state: LinkState,
    val message: String? = null,
    val lastSentMs: Long? = null,
)

/**
 * One streaming connection to a TAK server (TLS on 8089, or plain TCP).
 * Reconnects with backoff until [stop]. Incoming traffic is read and discarded:
 * a client that never reads stalls the server's writes and gets dropped.
 */
class TakConnection(
    val server: ServerConfig,
    private val certs: CertStore,
    private val selfUid: () -> String,
    private val pingIntervalMs: () -> Long,
    private val onStatus: (String, LinkStatus) -> Unit,
) {
    // Stale positions are worthless; keep only the newest few while offline.
    private val outbox = Channel<String>(16, BufferOverflow.DROP_OLDEST)
    private var job: Job? = null
    @Volatile private var lastSent: Long? = null

    fun send(xml: String) { outbox.trySend(xml) }

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
        while (scope.isActive) {
            onStatus(server.id, LinkStatus(LinkState.CONNECTING, "Connecting to ${server.host}:${server.port}", lastSent))
            var socket: Socket? = null
            try {
                socket = open()
                onStatus(server.id, LinkStatus(LinkState.CONNECTED, null, lastSent))
                backoff = 2_000L
                val s = socket
                val reader = scope.launch(Dispatchers.IO) { drain(s) }
                try {
                    pump(s.getOutputStream(), reader)
                } finally {
                    reader.cancel()
                }
            } catch (e: CancellationException) {
                runCatching { socket?.close() }
                throw e
            } catch (e: Exception) {
                onStatus(server.id, LinkStatus(LinkState.ERROR, describe(e), lastSent))
            }
            runCatching { socket?.close() }
            delay(backoff)
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

    /** Writes queued events; pings when idle so dead links surface as write errors. */
    private suspend fun pump(out: OutputStream, reader: Job) {
        while (reader.isActive) {
            val xml = withTimeoutOrNull(pingIntervalMs()) { outbox.receive() } ?: Cot.ping(selfUid())
            out.write(xml.toByteArray(Charsets.UTF_8))
            out.flush()
            if (!xml.contains("type=\"t-x-c-t\"")) {
                lastSent = System.currentTimeMillis()
                onStatus(server.id, LinkStatus(LinkState.CONNECTED, null, lastSent))
            }
        }
        throw IOException("Server closed the connection")
    }

    private fun drain(s: Socket) {
        val buf = ByteArray(16 * 1024)
        val input = s.getInputStream()
        try {
            while (input.read(buf) >= 0) { /* discard */ }
        } catch (_: IOException) {
        }
    }

    companion object {
        fun describe(e: Throwable): String = when {
            e is UnknownHostException -> "Unknown host"
            e is SSLHandshakeException && e.message?.contains("certificate_unknown", true) == true ->
                "Server rejected this device's certificate"
            e is SSLHandshakeException && e.message?.contains("bad_certificate", true) == true ->
                "Server rejected this device's certificate"
            e is SSLHandshakeException && (e.cause?.message ?: e.message ?: "").contains("Trust anchor", true) ->
                "Server certificate is not trusted"
            e is SSLHandshakeException -> "TLS handshake failed: ${e.message ?: ""}".trim()
            e is SocketException && e.message?.contains("ECONNREFUSED", true) == true -> "Connection refused"
            e is java.net.SocketTimeoutException -> "Timed out"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
