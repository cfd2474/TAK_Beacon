package com.taksolutions.takbeacon.enroll

import java.net.URI
import java.net.URLDecoder

/** What a QR code, deep link, or form asks us to enroll with. */
data class EnrollRequest(
    val host: String,
    val port: Int = 8089,
    val csrPort: Int = 8446,
    val username: String? = null,
    val secret: String? = null,
    val name: String? = null,
    val callsign: String? = null,
    val team: String? = null,
    val role: String? = null,
) {
    val canAutoSubmit get() = !username.isNullOrBlank() && !secret.isNullOrBlank()
}

/**
 * Parses the enrollment formats in use across the TAK ecosystem:
 *
 *  - takbeacon://enroll?host=…&username=…&token=…   (this app's own scheme)
 *  - tak://com.atakmap.app/enroll?host=…&username=…&token=…   (ATAK enrollment QR)
 *  - Name,host,port,ssl                               (ATAK server connection QR)
 */
object EnrollmentLink {

    fun parse(text: String): EnrollRequest? {
        val t = text.trim()
        return when {
            t.startsWith("takbeacon://", true) || t.startsWith("tak://", true) -> parseUri(t)
            t.count { it == ',' } == 3 -> parseConnectionCsv(t)
            else -> null
        }
    }

    private fun parseUri(s: String): EnrollRequest? {
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val path = (uri.host.orEmpty() + uri.path.orEmpty()).lowercase()
        if (!path.contains("enroll")) return null
        val q = query(uri.rawQuery)
        val host = q["host"]?.let(::cleanHost)?.takeIf(::validHost) ?: return null
        return EnrollRequest(
            host = host,
            port = q["port"]?.toIntOrNull()?.takeIf(::validPort) ?: 8089,
            csrPort = (q["csrport"] ?: q["csr_port"])?.toIntOrNull()?.takeIf(::validPort) ?: 8446,
            username = q["username"]?.takeIf { it.isNotBlank() },
            secret = (q["token"] ?: q["password"])?.takeIf { it.isNotBlank() },
            name = q["name"]?.take(64),
            callsign = q["callsign"]?.take(40),
            team = q["team"],
            role = q["role"],
        )
    }

    private fun parseConnectionCsv(s: String): EnrollRequest? {
        val (name, host, port, proto) = s.split(',').map { it.trim() }
        if (!proto.equals("ssl", true) && !proto.equals("tcp", true)) return null
        val h = cleanHost(host).takeIf(::validHost) ?: return null
        return EnrollRequest(host = h, port = port.toIntOrNull()?.takeIf(::validPort) ?: return null, name = name.take(64))
    }

    /** Keys are lower-cased; values URL-decoded. */
    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null
            else kv.substring(0, i).lowercase() to runCatching { URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()

    /** Accepts "host", "host:port", or a pasted URL, and returns the bare host. */
    fun cleanHost(s: String): String =
        s.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').let {
            if (it.startsWith("[")) it.substringBefore(']').removePrefix("[")
            else if (it.count { c -> c == ':' } == 1) it.substringBefore(':') else it
        }

    fun validHost(h: String) = h.isNotEmpty() && h.length <= 253 && h.all { it.isLetterOrDigit() || it in ".-:_" }
    fun validPort(p: Int) = p in 1..65535
}
