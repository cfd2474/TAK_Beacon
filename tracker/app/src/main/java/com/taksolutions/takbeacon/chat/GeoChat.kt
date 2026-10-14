package com.taksolutions.takbeacon.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * TAK chat ("GeoChat") as ATAK sends it over a TAK server: `b-t-f` messages and
 * the `b-t-f-d` / `b-t-f-r` delivered and read receipts. Format taken from the
 * public TAK convention and checked against ATAK-CIV 5.8.
 *
 * Parsing reads only the few attributes needed, from one event of bounded size,
 * and decodes just the five predefined XML entities and character references:
 * no DTDs, no external entities.
 */
object GeoChat {

    const val ALL_CHAT_ROOMS = "All Chat Rooms"
    const val PARENT_ROOT = "RootContactGroup"
    const val PARENT_TEAMS = "TeamGroups"
    /** Larger events are not chat; they are skipped unparsed. */
    const val MAX_EVENT_CHARS = 64 * 1024
    private const val MAX_TEXT = 4_000

    sealed interface Event

    /** A chat message. [roomId] is the recipient UID for a direct message, else the room. */
    data class Message(
        val messageId: String,
        val senderUid: String,
        val senderCallsign: String,
        val roomId: String,
        val roomName: String,
        val parent: String,
        val text: String,
        val timeMs: Long,
        /** UIDs listed in the chat group (sender first); for team rooms, its members. */
        val groupUids: List<String>,
    ) : Event

    data class Receipt(val messageId: String, val read: Boolean, val fromUid: String) : Event

    /** True if [xml] might be chat, cheaply, before parsing it. */
    fun isChat(xml: String): Boolean = xml.contains("type=\"b-t-f")

    fun parse(xml: String): Event? {
        if (xml.length > MAX_EVENT_CHARS) return null
        val event = tag(xml, "event") ?: return null
        val type = attr(event, "type") ?: return null
        val time = attr(event, "time")?.let(::parseTime) ?: System.currentTimeMillis()
        return when (type) {
            "b-t-f" -> {
                val chat = tag(xml, "__chat") ?: return null
                val messageId = attr(chat, "messageId") ?: return null
                val senderUid = tag(xml, "chatgrp")?.let { attr(it, "uid0") }
                    ?: tag(xml, "link")?.let { attr(it, "uid") } ?: return null
                val text = remarks(xml) ?: return null
                val grp = tag(xml, "chatgrp")
                val uids = if (grp == null) emptyList() else
                    generateSequence(0) { it + 1 }.map { attr(grp, "uid$it") }.takeWhile { it != null }.filterNotNull().take(256).toList()
                Message(
                    messageId = messageId,
                    senderUid = senderUid,
                    senderCallsign = attr(chat, "senderCallsign") ?: senderUid,
                    roomId = attr(chat, "id") ?: return null,
                    roomName = attr(chat, "chatroom") ?: ALL_CHAT_ROOMS,
                    parent = attr(chat, "parent") ?: PARENT_ROOT,
                    text = text.take(MAX_TEXT),
                    timeMs = time,
                    groupUids = uids,
                )
            }
            "b-t-f-d", "b-t-f-r" -> {
                val r = tag(xml, "__chatreceipt")
                val id = r?.let { attr(it, "messageId") } ?: attr(event, "uid") ?: return null
                val from = tag(xml, "link")?.let { attr(it, "uid") } ?: r?.let { tag(xml, "chatgrp") }?.let { attr(it, "uid0") } ?: return null
                Receipt(id, read = type == "b-t-f-r", fromUid = from)
            }
            else -> null
        }
    }

    /** A direct message to [toUid]/[toCallsign], as ATAK sends one. */
    fun direct(me: Me, toUid: String, toCallsign: String, text: String, now: Long = System.currentTimeMillis(),
               messageId: String = UUID.randomUUID().toString()): String =
        message(me, roomId = toUid, roomName = toCallsign, parent = PARENT_ROOT, members = listOf(me.uid, toUid),
            remarksTo = toUid, dests = listOf("callsign" to toCallsign), text = text, now = now, messageId = messageId)

    /** A message to All Chat Rooms: no destination, the server sends it to everyone. */
    fun allRooms(me: Me, text: String, now: Long = System.currentTimeMillis(), messageId: String = UUID.randomUUID().toString()): String =
        message(me, roomId = ALL_CHAT_ROOMS, roomName = ALL_CHAT_ROOMS, parent = PARENT_ROOT, members = listOf(me.uid, ALL_CHAT_ROOMS),
            remarksTo = ALL_CHAT_ROOMS, dests = emptyList(), text = text, now = now, messageId = messageId)

    /** A message to a team room: addressed to each known member, as ATAK does. */
    fun team(me: Me, team: String, memberUids: List<String>, text: String, now: Long = System.currentTimeMillis(),
             messageId: String = UUID.randomUUID().toString()): String {
        val others = memberUids.filter { it != me.uid }.distinct()
        return message(me, roomId = team, roomName = team, parent = PARENT_TEAMS, members = listOf(me.uid) + others,
            remarksTo = null, dests = others.map { "uid" to it }, text = text, now = now, messageId = messageId)
    }

    /** A delivered or read receipt for [messageId], sent back to its sender. */
    fun receipt(me: Me, messageId: String, read: Boolean, toUid: String, toCallsign: String, now: Long = System.currentTimeMillis()): String =
        buildString {
            append(header(messageId, if (read) "b-t-f-r" else "b-t-f-d", "m-g", now, 24 * 3600_000L))
            append(POINT)
            append("<detail>")
            append("""<__chatreceipt parent="$PARENT_ROOT" groupOwner="false" messageId="${esc(messageId)}" chatroom="${esc(toCallsign)}" id="${esc(toUid)}" senderCallsign="${esc(me.callsign)}">""")
            append("""<chatgrp uid0="${esc(me.uid)}" uid1="${esc(toUid)}" id="${esc(toUid)}"/></__chatreceipt>""")
            append("""<link uid="${esc(me.uid)}" type="${esc(me.type)}" relation="p-p"/>""")
            append("""<marti><dest callsign="${esc(toCallsign)}"/></marti>""")
            append("</detail></event>")
        }

    data class Me(val uid: String, val callsign: String, val type: String = "a-f-G-U-C")

    private fun message(
        me: Me, roomId: String, roomName: String, parent: String, members: List<String>, remarksTo: String?,
        dests: List<Pair<String, String>>, text: String, now: Long, messageId: String,
    ): String = buildString {
        append(header("GeoChat.${me.uid}.$roomId.$messageId", "b-t-f", "h-g-i-g-o", now, 24 * 3600_000L))
        append(POINT)
        append("<detail>")
        append("""<__chat parent="$parent" groupOwner="false" messageId="${esc(messageId)}" chatroom="${esc(roomName)}" id="${esc(roomId)}" senderCallsign="${esc(me.callsign)}">""")
        append("<chatgrp")
        members.forEachIndexed { i, u -> append(""" uid$i="${esc(u)}"""") }
        append(""" id="${esc(roomId)}"/></__chat>""")
        append("""<link uid="${esc(me.uid)}" type="${esc(me.type)}" relation="p-p"/>""")
        append("""<remarks source="BAO.F.ATAK.${esc(me.uid)}"""")
        if (remarksTo != null) append(""" to="${esc(remarksTo)}"""")
        append(""" time="${time(now)}">${esc(text.take(MAX_TEXT))}</remarks>""")
        if (dests.isNotEmpty()) {
            append("<marti>")
            dests.forEach { (k, v) -> append("""<dest $k="${esc(v)}"/>""") }
            append("</marti>")
        }
        append("</detail></event>")
    }

    // Chat carries no meaningful position; ATAK accepts an unknown one.
    private const val POINT = """<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>"""

    private fun header(uid: String, type: String, how: String, now: Long, staleMs: Long) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><event version="2.0" uid="${esc(uid)}" type="$type" how="$how" time="${time(now)}" start="${time(now)}" stale="${time(now + staleMs)}">"""

    // --- minimal, safe extraction -------------------------------------------------

    /** The start tag `<name ...>` (attributes only), or null. */
    private fun tag(xml: String, name: String): String? {
        val m = Regex("<$name(\\s[^>]*)?/?>").find(xml) ?: return null
        return m.groupValues[1]
    }

    private fun attr(tag: String, name: String): String? =
        Regex("(?:^|\\s)$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag)?.let { unescape(it.groupValues[2].ifEmpty { it.groupValues[3] }) }

    private fun remarks(xml: String): String? {
        val m = Regex("<remarks(\\s[^>]*)?>(.*?)</remarks>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return unescape(m.groupValues[2]).trim().takeIf { it.isNotEmpty() }
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val cp = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                    if (cp != null && Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else ""
                }
            }
        }
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun time(ms: Long) = fmt().format(Date(ms))
    private fun parseTime(s: String): Long? = runCatching {
        val t = if (s.contains('.')) s else s.replace("Z", ".000Z")
        fmt().parse(t.replace(Regex("\\.(\\d{3})\\d*Z$"), ".$1Z"))?.time
    }.getOrNull()
}
