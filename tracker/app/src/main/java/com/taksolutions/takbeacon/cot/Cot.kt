package com.taksolutions.takbeacon.cot

import com.taksolutions.takbeacon.data.EmergencyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A position fix, independent of android.location so CoT stays unit-testable. */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Height above the WGS84 ellipsoid, metres; null when unknown. */
    val hae: Double?,
    /** Horizontal accuracy (CE), metres. */
    val ce: Double?,
    /** Vertical accuracy (LE), metres. */
    val le: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val timeMs: Long,
    val source: String = "GPS",
)

data class Identity(
    val uid: String,
    val callsign: String,
    val team: String,
    val role: String,
    val device: String,
    val os: String,
    val version: String,
)

/**
 * Builds Cursor-on-Target events. Everything that reaches the wire is escaped
 * here: callsigns come from users, enrollment links and ATAK.
 */
object Cot {
    private const val UNKNOWN = "9999999.0"
    private val iso = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun time(ms: Long): String = iso.get()!!.format(Date(ms))

    fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (ch.code >= 0x20 || ch == '\t') append(ch)
        }
    }

    private fun num(v: Double?, digits: Int = 1) = v?.takeIf { it.isFinite() }?.let { "%.${digits}f".format(Locale.US, it) } ?: UNKNOWN

    private fun point(f: Fix) =
        """<point lat="${"%.7f".format(Locale.US, f.lat)}" lon="${"%.7f".format(Locale.US, f.lon)}" hae="${num(f.hae)}" ce="${num(f.ce)}" le="${num(f.le)}"/>"""

    private fun header(uid: String, type: String, how: String, now: Long, staleMs: Long) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><event version="2.0" uid="${esc(uid)}" type="$type" how="$how" time="${time(now)}" start="${time(now)}" stale="${time(now + staleMs)}">"""

    /** Self position report (PLI). */
    fun pli(id: Identity, fix: Fix, staleSeconds: Int, batteryPct: Int?, now: Long = System.currentTimeMillis()): String =
        buildString {
            append(header(id.uid, "a-f-G-U-C", "m-g", now, staleSeconds * 1000L))
            append(point(fix))
            append("<detail>")
            append("""<takv os="${esc(id.os)}" version="${esc(id.version)}" device="${esc(id.device)}" platform="TAK Beacon"/>""")
            append("""<contact callsign="${esc(id.callsign)}" endpoint="*:-1:stcp"/>""")
            append("""<uid Droid="${esc(id.callsign)}"/>""")
            append("""<precisionlocation altsrc="${if (fix.hae != null) esc(fix.source) else "???"}" geopointsrc="${esc(fix.source)}"/>""")
            append("""<__group role="${esc(id.role)}" name="${esc(id.team)}"/>""")
            if (batteryPct != null) append("""<status battery="$batteryPct"/>""")
            append("""<track course="${num(fix.courseDeg ?: 0.0, 2)}" speed="${num(fix.speedMps ?: 0.0, 2)}"/>""")
            append("</detail></event>")
        }

    fun emergencyUid(selfUid: String) = "$selfUid-9-1-1"

    /** An emergency alert, linked to the sender's PLI. */
    fun emergency(id: Identity, fix: Fix, type: EmergencyType, now: Long = System.currentTimeMillis()): String =
        buildString {
            append(header(emergencyUid(id.uid), type.cotType, "h-e", now, 10 * 60_000L))
            append(point(fix))
            append("<detail>")
            append("""<link uid="${esc(id.uid)}" type="a-f-G-U-C" relation="p-p"/>""")
            append("""<contact callsign="${esc(id.callsign)}-Alert"/>""")
            append("""<emergency type="${esc(type.detailType)}">${esc(id.callsign)}</emergency>""")
            append("</detail></event>")
        }

    /** Cancels this device's active emergency. */
    fun emergencyCancel(id: Identity, fix: Fix, now: Long = System.currentTimeMillis()): String =
        buildString {
            append(header(emergencyUid(id.uid), "b-a-o-can", "h-e", now, 10 * 60_000L))
            append(point(fix))
            append("<detail>")
            append("""<link uid="${esc(id.uid)}" type="a-f-G-U-C" relation="p-p"/>""")
            append("""<contact callsign="${esc(id.callsign)}-Alert"/>""")
            append("""<emergency cancel="true">${esc(id.callsign)}</emergency>""")
            append("</detail></event>")
        }

    /** Keep-alive ping; TAK servers answer with t-x-c-t-r. */
    fun ping(uid: String, now: Long = System.currentTimeMillis()): String =
        header("$uid-ping", "t-x-c-t", "m-g", now, 20_000L) +
            """<point lat="0.0" lon="0.0" hae="0.0" ce="$UNKNOWN" le="$UNKNOWN"/><detail/></event>"""
}
