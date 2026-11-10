package com.taksolutions.takbeacon.contacts

import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.chat.GeoChat
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.net.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Another TAK user, from the last position report the server passed on. */
data class Contact(
    val uid: String,
    val callsign: String,
    val team: Team?,
    val role: String?,
    val lat: Double,
    val lon: Double,
    val hae: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    /** When the report arrived (this phone's clock). */
    val receivedMs: Long,
    /** When it goes stale, on this phone's clock. */
    val staleAtMs: Long,
    val serverId: String,
) {
    /** A report or two has been missed: shown faded. */
    fun fading(now: Long): Boolean = now - receivedMs > (staleAtMs - receivedMs) * 2 / 3
}

/**
 * Position reports of other users as TAK clients send them (`a-…` events with a
 * team or client version), and TAK Server's `t-x-d-d` when a client leaves.
 * Map markers people place are atoms too, but carry neither, so they are skipped.
 * Parsing uses GeoChat's bounded, entity-safe extraction.
 */
object ContactCot {

    sealed interface Event
    data class Position(val contact: Contact) : Event
    data class Gone(val uid: String) : Event

    /** True if [xml] might be a position report or a departure, cheaply, before parsing it. */
    fun isCandidate(xml: String): Boolean = xml.contains("type=\"a-") || xml.contains("type=\"t-x-d-d\"")

    private val USER_DETAIL = Regex("<(__group|takv)[\\s/>]")

    fun parse(xml: String, serverId: String, now: Long = System.currentTimeMillis()): Event? {
        if (xml.length > GeoChat.MAX_EVENT_CHARS) return null
        val event = GeoChat.tag(xml, "event") ?: return null
        val type = GeoChat.attr(event, "type") ?: return null
        if (type == "t-x-d-d") return GeoChat.tag(xml, "link")?.let { GeoChat.attr(it, "uid") }?.let(::Gone)
        if (!type.startsWith("a-") || !USER_DETAIL.containsMatchIn(xml)) return null
        val uid = GeoChat.attr(event, "uid")?.take(MAX_UID)?.takeIf { it.isNotBlank() } ?: return null
        val callsign = GeoChat.tag(xml, "contact")?.let { GeoChat.attr(it, "callsign") }?.trim()?.take(MAX_CALLSIGN)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val point = GeoChat.tag(xml, "point") ?: return null
        val lat = GeoChat.attr(point, "lat")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = GeoChat.attr(point, "lon")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        if (lat == 0.0 && lon == 0.0) return null // no fix yet
        val time = GeoChat.attr(event, "time")?.let(GeoChat::parseTime)
        val stale = GeoChat.attr(event, "stale")?.let(GeoChat::parseTime)
        // A report that went stale long ago (a server's cached copy) is not shown.
        if (stale != null && stale + CLOCK_SLACK_MS < now) return null
        // The sender's clock may be off: keep its stale period, counted from arrival.
        val window = (if (time != null && stale != null && stale > time) stale - time else DEFAULT_STALE_MS)
            .coerceIn(MIN_STALE_MS, MAX_STALE_MS)
        val group = GeoChat.tag(xml, "__group")
        val track = GeoChat.tag(xml, "track")
        return Position(
            Contact(
                uid = uid,
                callsign = callsign,
                team = group?.let { Team.fromCot(GeoChat.attr(it, "name")) },
                role = group?.let { GeoChat.attr(it, "role") }?.trim()?.take(MAX_CALLSIGN)?.takeIf { it.isNotEmpty() },
                lat = lat,
                lon = lon,
                hae = GeoChat.attr(point, "hae")?.toDoubleOrNull()?.takeIf { it.isFinite() && it < UNKNOWN },
                speedMps = track?.let { GeoChat.attr(it, "speed") }?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
                courseDeg = track?.let { GeoChat.attr(it, "course") }?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.rem(360.0),
                receivedMs = now,
                staleAtMs = now + window,
                serverId = serverId,
            )
        )
    }

    private const val MAX_UID = 128
    private const val MAX_CALLSIGN = 40
    private const val UNKNOWN = 9_000_000.0
    private const val DEFAULT_STALE_MS = 2 * 60_000L
    private const val MIN_STALE_MS = 20_000L
    private const val MAX_STALE_MS = 30 * 60_000L
    private const val CLOCK_SLACK_MS = 5 * 60_000L
}

/**
 * The other users the servers report, for the map. Held in memory only: users
 * leave when their report goes stale, when they disconnect, and when their
 * server's link drops (so stopping the beacon clears them).
 */
class ContactManager(private val app: BeaconApp) {

    private val _contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val contacts: StateFlow<Map<String, Contact>> = _contacts.asStateFlow()

    @Volatile private var enabled = true

    fun start() {
        app.appScope.launch {
            app.settings.settings.collect {
                enabled = it.showContacts
                if (!enabled) _contacts.value = emptyMap()
            }
        }
        app.appScope.launch {
            app.connections.status.collect { st ->
                _contacts.update { m -> m.filterValues { st[it.serverId]?.state == LinkState.CONNECTED } }
            }
        }
        app.appScope.launch {
            while (true) {
                delay(PRUNE_MS)
                val now = System.currentTimeMillis()
                if (_contacts.value.values.any { it.staleAtMs <= now }) _contacts.update { m -> m.filterValues { it.staleAtMs > now } }
            }
        }
        app.connections.onContact = { serverId, xml -> handle(serverId, xml) }
    }

    fun handle(serverId: String, xml: String) {
        if (!enabled) return
        when (val e = runCatching { ContactCot.parse(xml, serverId) }.getOrNull()) {
            is ContactCot.Position -> {
                val c = e.contact
                if (c.uid == app.uid) return // our own report echoed back
                _contacts.update { m -> if (c.uid !in m && m.size >= MAX_CONTACTS) m else m + (c.uid to c) }
            }
            is ContactCot.Gone -> _contacts.update { it - e.uid }
            null -> Unit
        }
    }

    companion object {
        private const val PRUNE_MS = 5_000L
        /** A bound on memory and drawing; far beyond what a beacon map can show usefully. */
        const val MAX_CONTACTS = 500
    }
}
