package com.taksolutions.takbeacon.chat

import android.content.Context
import com.taksolutions.takbeacon.security.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ThreadKind { DIRECT, ALL_ROOMS, TEAM, GROUP }

enum class SendState { SENDING, SENT, DELIVERED, READ, FAILED }

@Serializable
data class ChatThread(
    /** "dm:<uid>", "all", "team:<name>" or "grp:<id>". */
    val key: String,
    val title: String,
    val kind: ThreadKind,
    /** The other person, for a direct thread. */
    val peerUid: String? = null,
    val peerCallsign: String? = null,
    /** The room id as TAK knows it (team name, group id). */
    val roomId: String? = null,
    /** Known member UIDs of a team or group room (for addressing replies). */
    val members: List<String> = emptyList(),
    /** Server the last message came from; replies go back the same way. */
    val serverId: String? = null,
)

@Serializable
data class ChatMessage(
    val id: String,
    val threadKey: String,
    val fromUid: String,
    val fromCallsign: String,
    val text: String,
    val timeMs: Long,
    val outgoing: Boolean,
    val serverId: String? = null,
    /** Outgoing only. */
    val state: SendState? = null,
    /** Incoming only: seen in the app. */
    val read: Boolean = false,
)

@Serializable
data class ChatData(
    val threads: List<ChatThread> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
) {
    fun messagesOf(key: String) = messages.filter { it.threadKey == key }.sortedBy { it.timeMs }
    fun unread(key: String? = null) = messages.count { !it.outgoing && !it.read && (key == null || it.threadKey == key) }
    fun thread(key: String) = threads.firstOrNull { it.key == key }

    /** Keeps the newest [MAX_MESSAGES] from the last [MAX_AGE_MS]; drops threads left empty. */
    fun pruned(now: Long = System.currentTimeMillis()): ChatData {
        val kept = messages.filter { now - it.timeMs < MAX_AGE_MS }.sortedBy { it.timeMs }.takeLast(MAX_MESSAGES)
        val keys = kept.map { it.threadKey }.toSet()
        return ChatData(threads.filter { it.key in keys }, kept)
    }

    companion object {
        const val MAX_MESSAGES = 500
        const val MAX_AGE_MS = 30L * 24 * 3600_000
    }
}

/** Messages on the device: one file, encrypted with the app's hardware-backed key, never backed up. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.bin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): ChatData = runCatching {
        if (!file.exists()) ChatData()
        else json.decodeFromString<ChatData>(String(SecretBox.open(file.readBytes()))).pruned()
    }.getOrDefault(ChatData())

    fun save(data: ChatData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(SecretBox.seal(json.encodeToString(data).toByteArray()))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun clear() { file.delete() }
}
