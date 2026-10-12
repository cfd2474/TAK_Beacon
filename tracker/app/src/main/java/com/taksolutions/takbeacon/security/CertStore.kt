package com.taksolutions.takbeacon.security

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate material per server, in app-private storage:
 *
 *   files/certs/<serverId>/client.p12 + client.pw   client identity
 *   files/certs/<serverId>/trust.p12  + trust.pw    CA(s) to trust for this server
 *
 * Passwords are sealed with [SecretBox]. Nothing here is included in backups.
 */
class CertStore(context: Context) {

    private val root = File(context.filesDir, "certs")

    private fun dir(serverId: String): File {
        require(serverId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "bad server id" }
        return File(root, serverId).apply { mkdirs() }
    }

    /** Stores a PKCS#12 client identity after proving it opens and holds a private key. */
    fun saveClientP12(serverId: String, p12: ByteArray, password: String) {
        val ks = loadP12(p12, password)
        require(ks.aliases().toList().any { ks.isKeyEntry(it) }) { "Client certificate file has no private key" }
        write(serverId, "client", p12, password)
    }

    /** Stores a PKCS#12 trust store after proving it opens and holds a certificate. */
    fun saveTrustP12(serverId: String, p12: ByteArray, password: String) {
        val ks = loadP12(p12, password)
        require(ks.size() > 0) { "Trust store is empty" }
        write(serverId, "trust", p12, password)
    }

    /** Builds and stores a client identity from an enrolled key and its signed chain. */
    fun saveClientKey(serverId: String, key: PrivateKey, chain: List<X509Certificate>) {
        val pw = randomPassword()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null) }
        ks.setKeyEntry("client", key, pw.toCharArray(), chain.toTypedArray())
        write(serverId, "client", toBytes(ks, pw), pw)
    }

    /** Builds and stores a trust store from CA certificates returned by enrollment. */
    fun saveTrustCerts(serverId: String, cas: List<X509Certificate>) {
        require(cas.isNotEmpty()) { "No CA certificates" }
        val pw = randomPassword()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null) }
        cas.forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
        write(serverId, "trust", toBytes(ks, pw), pw)
    }

    fun hasClient(serverId: String) = File(dir(serverId), "client.p12").exists()
    fun hasTrust(serverId: String) = File(dir(serverId), "trust.p12").exists()

    fun clientKeyStore(serverId: String): Pair<KeyStore, CharArray>? = read(serverId, "client")
    fun trustKeyStore(serverId: String): KeyStore? = read(serverId, "trust")?.first

    /** Describes the client certificate for the UI: subject CN and expiry. */
    fun clientSummary(serverId: String): String? = runCatching {
        val (ks, _) = clientKeyStore(serverId) ?: return null
        val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
        val cert = ks.getCertificate(alias) as X509Certificate
        val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1) ?: "?"
        "$cn · expires ${java.text.DateFormat.getDateInstance().format(cert.notAfter)}"
    }.getOrNull()

    fun delete(serverId: String) {
        File(root, serverId).takeIf { serverId.matches(Regex("[A-Za-z0-9-]{1,64}")) }?.deleteRecursively()
    }

    /**
     * TLS context for a streaming or API connection. When the server has its own
     * trust store or a pinned certificate, trust is exactly that and nothing else
     * (TAK servers commonly present certificates whose names don't match the
     * address dialled, so hostname checks only apply to system trust).
     *
     * @return the context, and whether the caller must verify the hostname.
     */
    fun sslContext(serverId: String?, pinnedSha256: String?): Pair<SSLContext, Boolean> {
        val kms = serverId?.let { clientKeyStore(it) }?.let { (ks, pw) ->
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }.keyManagers
        }
        val trust = serverId?.let { trustKeyStore(it) }
        val (tms, verifyHost) = when {
            trust != null -> TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trust) }.trustManagers to false
            pinnedSha256 != null -> arrayOf<TrustManager>(PinnedTrustManager(pinnedSha256)) to false
            else -> null to true
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kms, tms, SecureRandom())
        return ctx to verifyHost
    }

    private fun write(serverId: String, kind: String, p12: ByteArray, password: String) {
        val d = dir(serverId)
        atomicWrite(File(d, "$kind.p12"), p12)
        atomicWrite(File(d, "$kind.pw"), SecretBox.seal(password.toByteArray()))
    }

    private fun read(serverId: String, kind: String): Pair<KeyStore, CharArray>? {
        val d = File(root, serverId)
        val p12 = File(d, "$kind.p12").takeIf { it.exists() } ?: return null
        val pw = String(SecretBox.open(File(d, "$kind.pw").readBytes())).toCharArray()
        return KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, pw) } } to pw
    }

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        fun loadP12(p12: ByteArray, password: String): KeyStore = try {
            KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(p12), password.toCharArray()) }
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not open certificate file — wrong password?", e)
        }

        private fun toBytes(ks: KeyStore, pw: String): ByteArray =
            ByteArrayOutputStream().also { ks.store(it, pw.toCharArray()) }.toByteArray()

        private fun randomPassword(): String {
            val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        fun sha256Hex(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /** "ab:cd:…" grouping for display. */
        fun prettyFingerprint(hex: String): String = hex.chunked(2).joinToString(":").uppercase()
    }
}

/** Trusts exactly one leaf certificate, identified by SHA-256 — the user's on-first-use decision. */
class PinnedTrustManager(private val sha256Hex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        if (!CertStore.sha256Hex(leaf).equals(sha256Hex, ignoreCase = true)) {
            throw CertificateException("Server certificate changed since it was trusted")
        }
        leaf.checkValidity()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Records the server's certificate without trusting it, so the user can be shown
 * its fingerprint. Only ever used for a handshake whose result is discarded.
 */
class CapturingTrustManager : X509TrustManager {
    @Volatile var captured: X509Certificate? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("not a server")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        captured = chain?.firstOrNull()
        throw CertificateException("captured for review")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
