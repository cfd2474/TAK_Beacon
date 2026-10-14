package com.taksolutions.takbeacon.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taksolutions.takbeacon.chat.ChatData
import com.taksolutions.takbeacon.chat.ChatManager
import com.taksolutions.takbeacon.chat.ChatMessage
import com.taksolutions.takbeacon.chat.ChatThread
import com.taksolutions.takbeacon.chat.SendState
import com.taksolutions.takbeacon.chat.ThreadKind
import com.taksolutions.takbeacon.ui.components.Section
import java.text.DateFormat
import java.util.Date

/** A conversation as listed: stored, or one of the rooms always offered. */
data class ThreadItem(val thread: ChatThread, val last: ChatMessage?, val unread: Int)

fun threadItems(data: ChatData, offered: List<ChatThread>): List<ThreadItem> {
    val stored = data.threads.map { t ->
        ThreadItem(t, data.messagesOf(t.key).lastOrNull(), data.unread(t.key))
    }
    val extra = offered.filter { o -> stored.none { it.thread.key == o.key } }.map { ThreadItem(it, null, 0) }
    // Rooms with news first, then the offered rooms.
    return stored.sortedByDescending { it.last?.timeMs ?: 0 } + extra
}

@Composable
fun MessagesScreen(
    data: ChatData,
    offered: List<ChatThread>,
    openKey: String?,
    onOpen: (String?) -> Unit,
    canSend: Boolean,
    sendBlockedReason: String?,
    connected: Boolean,
    onSend: (String, String) -> String?,
    onUnlock: () -> Unit,
    contentPadding: PaddingValues,
) {
    if (openKey != null) {
        val thread = data.thread(openKey) ?: offered.firstOrNull { it.key == openKey }
        if (thread != null) {
            BackHandler { onOpen(null) }
            ThreadView(thread, data.messagesOf(openKey), canSend, sendBlockedReason, connected,
                { text -> onSend(openKey, text) }, onUnlock, { onOpen(null) }, contentPadding)
            return
        }
    }
    val items = threadItems(data, offered)
    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 8.dp, 16.dp, contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!connected) item {
            Text("Messages arrive while the beacon is on and connected to a server.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(items, key = { it.thread.key }) { item -> ThreadRow(item) { onOpen(item.thread.key) } }
    }
}

@Composable
private fun ThreadRow(item: ThreadItem, onClick: () -> Unit) {
    Section(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(
                    when (item.thread.kind) {
                        ThreadKind.DIRECT -> Icons.Filled.Person
                        ThreadKind.ALL_ROOMS -> Icons.Filled.Public
                        else -> Icons.Filled.Groups
                    },
                    null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.thread.title + if (item.thread.kind == ThreadKind.TEAM) " (team)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (item.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.last?.let { (if (it.outgoing) "You: " else if (item.thread.kind != ThreadKind.DIRECT) "${it.fromCallsign}: " else "") + it.text }
                        ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                item.last?.let { Text(shortTime(it.timeMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (item.unread > 0) Badge { Text(item.unread.toString()) }
            }
        }
    }
}

@Composable
private fun ThreadView(
    thread: ChatThread,
    messages: List<ChatMessage>,
    canSend: Boolean,
    blockedReason: String?,
    connected: Boolean,
    onSend: (String) -> String?,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    var draft by rememberSaveable(thread.key) { mutableStateOf("") }
    var error by remember(thread.key) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    fun send(text: String) {
        error = onSend(text)
        if (error == null) draft = ""
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text(thread.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (thread.kind) {
                        ThreadKind.DIRECT -> "Direct message"
                        ThreadKind.ALL_ROOMS -> "Everyone on the server"
                        ThreadKind.TEAM -> "Team room"
                        ThreadKind.GROUP -> "Group"
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item {
                Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(messages, key = { it.id }) { m -> Bubble(m, showSender = thread.kind != ThreadKind.DIRECT) }
        }
        if (canSend) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatManager.QUICK_REPLIES.forEach { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft, { draft = it.take(1_000) },
                    placeholder = { Text(if (connected) "Message" else "Message (start the beacon to send)") },
                    modifier = Modifier.weight(1f), maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                )
                IconButton(enabled = draft.isNotBlank(), onClick = { send(draft) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(blockedReason ?: "Replies are off", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (blockedReason?.startsWith("Locked") == true) TextButton(onClick = onUnlock) { Text("Unlock") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, showSender: Boolean) {
    val mine = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine && showSender) Text(m.fromCallsign, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                shortTime(m.timeMs) + if (mine) " · " + when (m.state) {
                    SendState.SENDING -> "Sending"
                    SendState.SENT -> "Sent"
                    SendState.DELIVERED -> "Delivered"
                    SendState.READ -> "Read"
                    SendState.FAILED -> "Not sent"
                    null -> ""
                } else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (m.state == SendState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun shortTime(ms: Long): String {
    val df = if (System.currentTimeMillis() - ms < 20 * 3600_000L) DateFormat.getTimeInstance(DateFormat.SHORT)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return df.format(Date(ms))
}
