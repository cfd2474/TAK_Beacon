package com.taksolutions.takbeacon.enroll

import android.util.Base64
import com.taksolutions.takbeacon.data.ServerConfig
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.security.CapturingTrustManager
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.security.PinnedTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager

sealed interface EnrollResult {
    data class Success(val server: ServerConfig) : EnrollResult
    /** The enrollment port presented a certificate the device does not trust. */
    data class NeedsTrust(val sha256: String, val subject: String, val issuer: String) : EnrollResult
    data class Failure(val message: String) : EnrollResult
}

/**
 * TAK certificate enrollment, as served by TAK Server and OpenTAKServer on the
 * CSR port (8446 by default):
 *
 *   GET  /Marti/api/tls/config                 → subject name entries for the CSR
 *   POST /Marti/api/tls/signClient/v2?clientUid  → signed client cert + CA chain
 *
 * Both use HTTP basic auth with the enrollment username and password/token.
 */
class EnrollmentClient(private val certs: CertStore, private val appVersion: String) {

    suspend fun enroll(
        req: EnrollRequest,
        uid: String,
        pinnedSha256: String?,
        existingId: String? = null,
    ): EnrollResult = withContext(Dispatchers.IO) {
        val user = req.username?.trim().orEmpty()
        val secret = req.secret.orEmpty()
        if (user.isEmpty() || secret.isEmpty()) return@withContext EnrollResult.Failure("Username and password are required")
        val base = "https://${hostForUrl(req.host)}:${req.csrPort}"
        try {
            val config = request("$base/Marti/api/tls/config", "GET", user, secret, pinnedSha256, null, "application/xml")
            val subject = X500NameBuilder(BCStyle.INSTANCE).apply {
                addRDN(BCStyle.CN, user)
                nameEntries(config).forEach { (k, v) ->
                    val oid = runCatching { BCStyle.INSTANCE.attrNameToOID(k) }.getOrNull()
                    if (oid != null && !k.equals("CN", true) && v.isNotBlank()) addRDN(oid, v)
                }
            }.build()

            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val csr = JcaPKCS10CertificationRequestBuilder(subject, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val pem = "-----BEGIN CERTIFICATE REQUEST-----\n" +
                Base64.encodeToString(csr.encoded, Base64.DEFAULT).trim() +
                "\n-----END CERTIFICATE REQUEST-----\n"

            val signUrl = "$base/Marti/api/tls/signClient/v2?clientUid=${enc(uid)}&version=${enc(appVersion)}"
            val body = request(signUrl, "POST", user, secret, pinnedSha256, pem, "application/json")
            val (signed, cas) = parseSignResponse(body)

            val id = existingId ?: newServerId()
            certs.saveClientKey(id, kp.private, listOf(signed) + cas)
            if (cas.isNotEmpty()) certs.saveTrustCerts(id, cas)
            EnrollResult.Success(
                ServerConfig(
                    id = id,
                    name = req.name?.takeIf { it.isNotBlank() } ?: req.host,
                    host = req.host,
                    port = req.port,
                    transport = Transport.SSL,
                    hasClientCert = true,
                    hasTrustStore = cas.isNotEmpty(),
                    pinnedServerSha256 = if (cas.isEmpty()) pinnedSha256 else null,
                    csrPort = req.csrPort,
                    enrollUsername = user,
                    source = "enrollment",
                )
            )
        } catch (e: HttpError) {
            EnrollResult.Failure(
                when (e.code) {
                    401, 403 -> "The server rejected the username or password"
                    404 -> "This server does not offer certificate enrollment on port ${req.csrPort}"
                    else -> "Enrollment failed (HTTP ${e.code})"
                }
            )
        } catch (e: SSLHandshakeException) {
            if (pinnedSha256 != null) return@withContext EnrollResult.Failure("Server certificate changed since it was trusted")
            val cert = capture(req.host, req.csrPort)
                ?: return@withContext EnrollResult.Failure("Secure connection failed: ${e.message}")
            EnrollResult.NeedsTrust(CertStore.sha256Hex(cert), cert.subjectX500Principal.name, cert.issuerX500Principal.name)
        } catch (e: Exception) {
            EnrollResult.Failure(com.taksolutions.takbeacon.net.TakConnection.describe(e))
        }
    }

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun request(
        url: String, method: String, user: String, secret: String,
        pinnedSha256: String?, body: String?, accept: String,
    ): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            if (pinnedSha256 != null) {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(PinnedTrustManager(pinnedSha256)), SecureRandom())
                conn.sslSocketFactory = ctx.socketFactory
                // The pinned leaf *is* the identity check; its name may not match the address.
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            val auth = Base64.encodeToString("$user:$secret".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Accept", accept)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code)
            return conn.inputStream.use { it.readBytes(MAX_RESPONSE) }
        } finally {
            conn.disconnect()
        }
    }

    /** Handshakes once with a recording trust manager to show the user what the server presented. */
    private fun capture(host: String, port: Int): X509Certificate? {
        val tm = CapturingTrustManager()
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        runCatching {
            (ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket).use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 10_000)
                s.soTimeout = 10_000
                s.startHandshake()
            }
        }
        return tm.captured
    }

    companion object {
        private const val MAX_RESPONSE = 256 * 1024

        private fun java.io.InputStream.readBytes(limit: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IllegalStateException("Response too large")
            }
            return out.toString("UTF-8")
        }

        fun newServerId(): String = java.util.UUID.randomUUID().toString()

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun hostForUrl(h: String) = if (h.contains(':')) "[$h]" else h

        /** `<nameEntry name="O" value="TAK"/>` pairs, attribute order not assumed. */
        fun nameEntries(xml: String): List<Pair<String, String>> =
            Regex("<(?:\\w+:)?nameEntry\\b([^>]*)/?>").findAll(xml).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val name = Regex("name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
                val value = Regex("value=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
                if (name != null && value != null) name to value else null
            }.toList()

        /**
         * signClient/v2 answers JSON when asked (`signedCert`, `ca0`, `ca1`, …) and
         * XML otherwise (`<signedCert>`, `<ca>`); both carry base64 DER or PEM.
         */
        fun parseSignResponse(body: String): Pair<X509Certificate, List<X509Certificate>> {
            val trimmed = body.trim()
            val (signed, cas) = if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                val cas = o.keys().asSequence().filter { it.matches(Regex("ca\\d+")) }
                    .sortedBy { it.removePrefix("ca").toInt() }.map { o.getString(it) }.toList()
                o.getString("signedCert") to cas
            } else {
                val signed = Regex("<signedCert>([^<]+)</signedCert>").find(trimmed)?.groupValues?.get(1)
                    ?: throw IllegalStateException("No certificate in the server's response")
                signed to Regex("<ca>([^<]+)</ca>").findAll(trimmed).map { it.groupValues[1] }.toList()
            }
            return parseCert(signed) to cas.map(::parseCert)
        }

        fun parseCert(text: String): X509Certificate {
            val b64 = text.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
            val der = java.util.Base64.getDecoder().decode(b64)
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
    }
}
