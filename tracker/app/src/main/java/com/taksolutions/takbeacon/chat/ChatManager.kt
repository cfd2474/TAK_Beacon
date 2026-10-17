package com.taksolutions.takbeacon.chat

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.R
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.MessagingMode
import com.taksolutions.takbeacon.service.BeaconStatus
import com.taksolutions.takbeacon.service.RunState
import com.taksolutions.takbeacon.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * TAK chat for the beacon: receives messages from the server stream, keeps them,
 * notifies, sends delivered/read receipts, and sends replies. Messages only flow
 * while the beacon is on (that is when it is connected).
 */
class ChatManager(private val app: BeaconApp) {

    private val store = ChatStore(app)
    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<ChatData> = _data.asStateFlow()

    /** The thread on screen while the app is in front: no notification for it. */
    @Volatile var visibleThread: String? = null

    @Volatile private var settings: BeaconSettings? = null

    fun start() {
        app.appScope.launch { app.settings.settings.collect { settings = it } }
        app.connections.onChat = { serverId, xml -> handle(serverId, xml) }
        app.connections.onChatWritten = { id -> setState(id, SendState.SENT) }
    }

    private val mode get() = settings?.messagingMode ?: MessagingMode.FULL

    /** Replying is allowed: messaging fully on and the PIN lock off. */
    val canSend: Boolean get() = mode == MessagingMode.FULL && !app.locked

    private fun me(s: BeaconSettings) = GeoChat.Me(s.uid, s.callsign, s.pliCotType)

    // --- receiving ------------------------------------------------------------

    fun handle(serverId: String, xml: String) {
        val s = settings ?: return
        if (mode == MessagingMode.OFF) return
        when (val ev = runCatching { GeoChat.parse(xml) }.getOrNull()) {
            is GeoChat.Message -> receive(s, serverId, ev)
            is GeoChat.Receipt -> ev.let { r -> setState(r.messageId, if (r.read) SendState.READ else SendState.DELIVERED) }
            null -> Unit
        }
    }

    private fun receive(s: BeaconSettings, serverId: String, m: GeoChat.Message) {
        if (m.senderUid == s.uid) return // our own message echoed back
        if (_data.value.messages.any { it.id == m.messageId }) return // same message via another server
        val direct = m.roomId == s.uid || (m.parent == GeoChat.PARENT_ROOT && m.roomId != GeoChat.ALL_CHAT_ROOMS && m.roomName == s.callsign)
        val thread = when {
            direct -> ChatThread("dm:${m.senderUid}", m.senderCallsign, ThreadKind.DIRECT, peerUid = m.senderUid,
                peerCallsign = m.senderCallsign, serverId = serverId)
            m.roomId == GeoChat.ALL_CHAT_ROOMS -> ChatThread(KEY_ALL, GeoChat.ALL_CHAT_ROOMS, ThreadKind.ALL_ROOMS, serverId = serverId)
            m.parent == GeoChat.PARENT_TEAMS -> ChatThread("team:${m.roomId}", m.roomName, ThreadKind.TEAM, roomId = m.roomId,
                members = m.groupUids, serverId = serverId)
            else -> ChatThread("grp:${m.roomId}", m.roomName, ThreadKind.GROUP, roomId = m.roomId, members = m.groupUids, serverId = serverId)
        }
        val msg = ChatMessage(m.messageId, thread.key, m.senderUid, m.senderCallsign, m.text,
            minOf(m.timeMs, System.currentTimeMillis()), outgoing = false, serverId = serverId)
        update { d ->
            val old = d.thread(thread.key)
            val merged = if (old == null) thread else old.copy(
                title = thread.title, peerCallsign = thread.peerCallsign ?: old.peerCallsign, serverId = serverId,
                members = (old.members + thread.members + m.senderUid).distinct().take(256),
            )
            d.copy(threads = d.threads.filter { it.key != thread.key } + merged, messages = d.messages + msg).pruned()
        }
        if (direct && s.chatReceipts) {
            app.connections.sendChat(GeoChat.receipt(me(s), m.messageId, read = false, m.senderUid, m.senderCallsign), serverId)
        }
        // A priority keyword sounds the alarm, from any room (even with group notifications off),
        // but not while paused for ATAK (unless the user turned that on), and not for an old
        // message delivered late.
        val keyword = KeywordAlert.match(m.text, s.alertKeywords)?.takeIf {
            (s.alertWhilePausedForAtak || BeaconStatus.state.value.runState != RunState.PAUSED_FOR_ATAK) &&
                System.currentTimeMillis() - msg.timeMs < PriorityAlert.MAX_AGE_MS
        }
        // The alert notification carries this message; a chat notification beside it would bury it.
        if (keyword != null) { app.alerts.trigger(keyword, m.senderCallsign, m.text, thread.key); return }
        val groupMuted = thread.kind != ThreadKind.DIRECT && !s.chatGroupNotify
        if (!groupMuted) notifyThread(thread.key)
    }

    // --- reading ------------------------------------------------------------

    /** The user opened [key]: mark it read, clear its notification, send read receipts. */
    fun markRead(key: String) {
        val s = settings ?: return
        val unread = _data.value.messages.filter { it.threadKey == key && !it.outgoing && !it.read }
        if (unread.isEmpty()) { cancelNotification(key); return }
        update { d -> d.copy(messages = d.messages.map { if (it.threadKey == key && !it.outgoing) it.copy(read = true) else it }) }
        cancelNotification(key)
        val t = _data.value.thread(key)
        if (s.chatReceipts && mode != MessagingMode.OFF && t?.kind == ThreadKind.DIRECT) {
            unread.forEach { m ->
                app.connections.sendChat(GeoChat.receipt(me(s), m.id, read = true, m.fromUid, m.fromCallsign), m.serverId)
            }
        }
    }

    // --- sending ------------------------------------------------------------

    /** Sends [text] to thread [key]. Returns null, or why it couldn't be sent. */
    fun send(key: String, text: String): String? {
        val s = settings ?: return "Not ready"
        val body = text.trim().take(1_000)
        if (body.isEmpty()) return "Empty message"
        if (app.locked) return "Locked: unlock to reply"
        if (mode != MessagingMode.FULL) return "Replies are turned off by your organization"
        val t = _data.value.thread(key) ?: threadFor(key, s) ?: return "Unknown conversation"
        val id = UUID.randomUUID().toString()
        val xml = when (t.kind) {
            ThreadKind.DIRECT -> GeoChat.direct(me(s), t.peerUid!!, t.peerCallsign ?: t.title, body, messageId = id)
            ThreadKind.ALL_ROOMS -> GeoChat.allRooms(me(s), body, messageId = id)
            ThreadKind.TEAM -> GeoChat.team(me(s), t.roomId ?: s.team.cotName, t.members, body, messageId = id)
            ThreadKind.GROUP -> GeoChat.team(me(s), t.roomId ?: t.title, t.members, body, messageId = id)
                .replace("parent=\"${GeoChat.PARENT_TEAMS}\"", "parent=\"UserGroups\"")
        }
        val queued = app.connections.sendChat(xml, t.serverId, tag = id)
        val msg = ChatMessage(id, t.key, s.uid, s.callsign, body, System.currentTimeMillis(), outgoing = true,
            serverId = t.serverId, state = if (queued > 0) SendState.SENDING else SendState.FAILED)
        update { d -> d.copy(threads = if (d.thread(t.key) == null) d.threads + t else d.threads, messages = d.messages + msg).pruned() }
        return if (queued > 0) null else "Not connected: start the beacon to send"
    }

    /** The rooms always offered, even before anyone has written in them. */
    fun threadFor(key: String, s: BeaconSettings): ChatThread? = when {
        key == KEY_ALL -> ChatThread(KEY_ALL, GeoChat.ALL_CHAT_ROOMS, ThreadKind.ALL_ROOMS)
        key == teamKey(s) -> ChatThread(key, s.team.cotName, ThreadKind.TEAM, roomId = s.team.cotName)
        else -> null
    }

    fun teamKey(s: BeaconSettings) = "team:${s.team.cotName}"

    fun clear() {
        val threads = _data.value.threads
        _data.value = ChatData()
        threads.forEach { cancelNotification(it.key) }
        store.clear()
    }

    private fun setState(id: String, state: SendState) {
        if (_data.value.messages.none { it.id == id && it.outgoing }) return
        update { d ->
            d.copy(messages = d.messages.map { m ->
                // Never step back (a late "sent" after "read").
                if (m.id == id && m.outgoing && (m.state == null || m.state.ordinal < state.ordinal || m.state == SendState.FAILED)) m.copy(state = state) else m
            })
        }
    }

    private fun update(f: (ChatData) -> ChatData) {
        _data.update(f)
        val snapshot = _data.value
        app.appScope.launch { runCatching { store.save(snapshot) }.onFailure { Log.w(TAG, "save failed: ${it.message}") } }
    }

    // --- notifications --------------------------------------------------------

    private fun notifyThread(key: String) {
        // ATAK on this phone shows the message itself; the beacon keeps it quietly.
        if (BeaconStatus.state.value.runState == RunState.PAUSED_FOR_ATAK) return
        if (visibleThread == key) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val d = _data.value
        val t = d.thread(key) ?: return
        val unread = d.messagesOf(key).filter { !it.outgoing && !it.read }.takeLast(6)
        if (unread.isEmpty()) return
        val self = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(self)
        if (t.kind != ThreadKind.DIRECT) style.setConversationTitle(t.title).setGroupConversation(true)
        unread.forEach { m -> style.addMessage(m.text, m.timeMs, Person.Builder().setName(m.fromCallsign).setKey(m.fromUid).build()) }

        val open = PendingIntent.getActivity(
            app, key.hashCode(),
            Intent(app, MainActivity::class.java).setAction(ACTION_OPEN_THREAD).putExtra(EXTRA_THREAD, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(app, BeaconApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(app, BeaconApp.CHANNEL_MESSAGES).setSmallIcon(R.drawable.ic_stat_beacon)
                    .setContentTitle("TAK Beacon").setContentText("New message").build()
            )
            .setGroup(GROUP)
        if (canSend) {
            val replyIntent = PendingIntent.getBroadcast(
                app, key.hashCode(),
                Intent(app, ChatReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_THREAD, key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat_beacon, "Reply", replyIntent)
                    .addRemoteInput(input).setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
            )
        }
        runCatching { NotificationManagerCompat.from(app).notify(TAG, key.hashCode(), b.build()) }
    }

    fun cancelNotification(key: String) {
        NotificationManagerCompat.from(app).cancel(TAG, key.hashCode())
    }

    /** From the notification's Reply action. */
    fun replyFromNotification(key: String, text: String) {
        val error = send(key, text)
        cancelNotification(key)
        if (error == null) markRead(key)
        else Log.i(TAG, "notification reply not sent: $error")
    }

    companion object {
        private const val TAG = "TakChat"
        const val KEY_ALL = "all"
        private const val GROUP = "chat"
        const val ACTION_OPEN_THREAD = "com.taksolutions.takbeacon.OPEN_THREAD"
        const val ACTION_REPLY = "com.taksolutions.takbeacon.CHAT_REPLY"
        const val EXTRA_THREAD = "thread"
        const val KEY_REPLY = "reply"
        val QUICK_REPLIES = listOf("Copy", "En route", "Need assistance")
    }
}

/** The notification's inline Reply. */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChatManager.ACTION_REPLY) return
        val key = intent.getStringExtra(ChatManager.EXTRA_THREAD) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatManager.KEY_REPLY)?.toString() ?: return
        BeaconApp.from(context).chat.replyFromNotification(key, text)
    }
}
