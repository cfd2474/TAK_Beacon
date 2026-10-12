package com.taksolutions.takbeacon.enroll

import android.util.Xml
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.security.CertStore
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports server connections from a TAK data package (.zip with .pref files and
 * .p12 certificates), the way TAK Server and OpenTAKServer hand them out.
 *
 * The archive is read into memory with hard limits and nothing is written using
 * a path taken from it; certificates are matched by file name only.
 */
class DataPackageImporter(private val certs: CertStore) {

    data class Imported(val servers: List<ServerConfig>, val warnings: List<String>)

    fun import(input: InputStream): Imported {
        val files = readZip(input)
        val prefs = mutableMapOf<String, String>()
        files.filterKeys { it.endsWith(".pref", true) }.values.forEach { prefs += parsePref(it) }
        val count = prefs["count"]?.toIntOrNull() ?: if (prefs.containsKey("connectString0")) 1 else 0
        require(count > 0) { "No server connections found in this package" }

        fun byName(location: String?): ByteArray? {
            val base = location?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() ?: return null
            return files.entries.firstOrNull { it.key.substringAfterLast('/').lowercase() == base }?.value
        }

        val servers = mutableListOf<ServerConfig>()
        val warnings = mutableListOf<String>()
        for (i in 0 until count.coerceAtMost(16)) {
            val connect = prefs["connectString$i"] ?: continue
            val parts = connect.split(':')
            if (parts.size < 3) { warnings += "Skipped malformed connection \"$connect\""; continue }
            val host = parts.dropLast(2).joinToString(":")
            val port = parts[parts.size - 2].toIntOrNull()
            val proto = parts.last()
            if (port == null || !EnrollmentLink.validHost(host)) { warnings += "Skipped \"$connect\""; continue }
            if (proto.equals("quic", true)) { warnings += "$host: QUIC is not supported, skipped"; continue }

            val id = EnrollmentClient.newServerId()
            val trust = byName(prefs["caLocation$i"] ?: prefs["caLocation"])
            val client = byName(prefs["certificateLocation$i"] ?: prefs["certificateLocation"])
            val trustPw = prefs["caPassword$i"] ?: prefs["caPassword"] ?: DEFAULT_PASSWORD
            val clientPw = prefs["clientPassword$i"] ?: prefs["clientPassword"] ?: DEFAULT_PASSWORD
            val enrollWithTrust = (prefs["enrollForCertificateWithTrust$i"] ?: "false").toBoolean()
            val transport = Transport.fromWire(proto)

            var hasTrust = false
            var hasClient = false
            try {
                if (trust != null) { certs.saveTrustP12(id, trust, trustPw); hasTrust = true }
                if (client != null) { certs.saveClientP12(id, client, clientPw); hasClient = true }
            } catch (e: IllegalArgumentException) {
                certs.delete(id)
                warnings += "$host: ${e.message}"
                continue
            }
            val needsEnroll = transport == Transport.SSL && !hasClient
            if (needsEnroll && !enrollWithTrust) warnings += "$host: no client certificate; sign in to enroll one"
            servers += ServerConfig(
                id = id,
                name = prefs["description$i"]?.takeIf { it.isNotBlank() }?.take(64) ?: host,
                host = host,
                port = port,
                transport = transport,
                enabled = (prefs["enabled$i"] ?: "true").toBoolean(),
                hasClientCert = hasClient,
                hasTrustStore = hasTrust,
                needsEnrollment = needsEnroll,
                source = "data package",
            )
        }
        require(servers.isNotEmpty()) { warnings.firstOrNull() ?: "No usable server connections in this package" }
        return Imported(servers, warnings)
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (out.size >= MAX_ENTRIES) throw IllegalArgumentException("Package has too many files")
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    total += n
                    if (buf.size() > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw IllegalArgumentException("Package is too large")
                    }
                }
                out[e.name] = buf.toByteArray()
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("Not a data package (.zip)")
        return out
    }

    /** `<entry key="k" class="…">value</entry>` → k to value. */
    private fun parsePref(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        p.setInput(ByteArrayInputStream(bytes), null)
        var key: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> if (p.name == "entry") key = p.getAttributeValue(null, "key")
                XmlPullParser.TEXT -> key?.let { map[it] = p.text.trim() }
                XmlPullParser.END_TAG -> if (p.name == "entry") key = null
            }
        }
        return map
    }

    companion object {
        /** The TAK convention when a package does not state a password. */
        private const val DEFAULT_PASSWORD = "atakatak"
        private const val MAX_ENTRIES = 200
        private const val MAX_ENTRY_BYTES = 5L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    }
}
