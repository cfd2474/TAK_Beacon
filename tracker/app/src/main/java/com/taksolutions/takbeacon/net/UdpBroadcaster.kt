package com.taksolutions.takbeacon.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Sends CoT as UDP datagrams, the way ATAK shares situational awareness on a
 * local network or mesh: by default to the SA multicast group 239.2.3.1:6969,
 * or to any unicast host:port. Unencrypted and fire-and-forget.
 */
class UdpBroadcaster {

    private val outbox = Channel<Triple<String, Int, String>>(16, BufferOverflow.DROP_OLDEST)
    private var socket: MulticastSocket? = null
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    @Volatile var lastSentMs: Long? = null
        private set

    fun start(scope: CoroutineScope) {
        if (socket != null) return
        val s = MulticastSocket().apply { timeToLive = TTL }
        socket = s
        scope.launch(Dispatchers.IO) {
            for ((host, port, xml) in outbox) {
                try {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
                    lastSentMs = System.currentTimeMillis()
                    _lastError.value = null
                } catch (e: Exception) {
                    // No route on cellular, Wi-Fi off, bad host: report it, keep going.
                    _lastError.value = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun send(host: String, port: Int, xml: String) {
        outbox.trySend(Triple(host, port, xml))
    }

    fun stop() {
        outbox.close()
        socket?.close()
        socket = null
    }

    companion object {
        /** ATAK's default multicast TTL: stays on the local network segment. */
        const val TTL = 1
    }
}
