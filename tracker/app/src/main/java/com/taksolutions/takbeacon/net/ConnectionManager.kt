package com.taksolutions.takbeacon.net

import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.security.CertStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps one [TakConnection] per enabled server, rebuilding a connection when
 * its configuration changes. Statuses are published for the UI and the bridge.
 */
class ConnectionManager(
    private val certs: CertStore,
    private val selfUid: () -> String,
    private val pingIntervalMs: () -> Long,
) {

    private val connections = mutableMapOf<String, TakConnection>()
    private val _status = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())
    val status: StateFlow<Map<String, LinkStatus>> = _status.asStateFlow()

    @Synchronized
    fun sync(servers: List<ServerConfig>, scope: CoroutineScope) {
        val wanted = servers.filter { it.enabled && !it.needsEnrollment }.associateBy { it.id }
        val iter = connections.entries.iterator()
        while (iter.hasNext()) {
            val (id, conn) = iter.next()
            if (wanted[id] != conn.server) {
                conn.stop()
                iter.remove()
                _status.update { it - id }
            }
        }
        for ((id, server) in wanted) {
            if (id !in connections) {
                val conn = TakConnection(server, certs, selfUid, pingIntervalMs) { sid, st ->
                    // A late callback from a connection that was just replaced must not resurrect it.
                    synchronized(this) { if (connections[sid]?.server == server) _status.update { it + (sid to st) } }
                }
                connections[id] = conn
                conn.start(scope)
            }
        }
    }

    /** [priority] for emergency alerts and cancels: sent first, never dropped for positions. */
    @Synchronized
    fun broadcast(xml: String, priority: Boolean = false): Int {
        connections.values.forEach { it.send(xml, priority) }
        return connections.size
    }

    /** Resumes a server that stopped retrying after certificate failures (or cuts its backoff short). */
    @Synchronized
    fun retry(id: String) { connections[id]?.retryNow() }

    @Synchronized
    fun stopAll() {
        connections.values.forEach { it.stop() }
        connections.clear()
        _status.value = emptyMap()
    }

    fun connectedCount(): Int = _status.value.values.count { it.state == LinkState.CONNECTED }
}
