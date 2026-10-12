package com.taksolutions.takbeacon.data

import android.content.Context
import com.taksolutions.takbeacon.security.CertStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Server list persisted as JSON; writes are atomic (temp file + rename). */
class ServerRepository(context: Context, private val certs: CertStore) {

    private val file = File(context.filesDir, "servers.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private fun load(): List<ServerConfig> = runCatching {
        if (file.exists()) json.decodeFromString<List<ServerConfig>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    suspend fun upsert(server: ServerConfig) = mutate { list ->
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list.toMutableList().also { it[i] = server } else list + server
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    suspend fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        certs.delete(id)
    }

    /** Finds a server already configured for this endpoint, to update rather than duplicate. */
    fun findByEndpoint(host: String, port: Int): ServerConfig? =
        _servers.value.firstOrNull { it.host.equals(host, ignoreCase = true) && it.port == port }

    private suspend fun mutate(f: (List<ServerConfig>) -> List<ServerConfig>) = lock.withLock {
        val next = f(_servers.value)
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _servers.value = next
    }
}
