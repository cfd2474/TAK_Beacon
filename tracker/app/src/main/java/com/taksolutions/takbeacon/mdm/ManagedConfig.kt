package com.taksolutions.takbeacon.mdm

import com.taksolutions.takbeacon.data.Role
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.data.Transport
import java.security.MessageDigest

/**
 * A TAK server and identity pushed by an MDM as Android managed configuration
 * (app restrictions, declared in res/xml/app_restrictions.xml).
 *
 * Every key is optional: an MDM leaves a key out when it has no value for this
 * device (for example a per-device variable that isn't set), so each one falls
 * back to a default or to the user's own setting. An empty configuration means
 * "not managed": MDMs remove a config by sending an empty one.
 */
data class ManagedConfig(
    /** Free text; changing it makes the app apply (and re-enroll) again. */
    val version: String? = null,
    val serverName: String? = null,
    val serverHost: String? = null,
    val serverPort: Int = DEFAULT_PORT,
    val transport: Transport = Transport.SSL,
    val enrollPort: Int = DEFAULT_ENROLL_PORT,
    val enrollUsername: String? = null,
    val enrollPassword: String? = null,
    /** SHA-256 of a self-signed server certificate to trust for enrollment. */
    val serverCertSha256: String? = null,
    val clientP12: ByteArray? = null,
    val clientP12Password: String? = null,
    val trustP12: ByteArray? = null,
    val trustP12Password: String? = null,
    val callsign: String? = null,
    val team: Team? = null,
    val role: Role? = null,
    /** CoT type (map symbol) of the position report. */
    val cotType: String? = null,
    /** TAK chat: off, read only or full. */
    val messaging: com.taksolutions.takbeacon.data.MessagingMode? = null,
    /** Turn the beacon on when the config arrives and on every boot (the user can still stop it). */
    val startBeacon: Boolean = false,
) {
    val hasServer: Boolean get() = !serverHost.isNullOrBlank()
    val hasClientCert: Boolean get() = clientP12 != null
    val canEnroll: Boolean get() = !enrollUsername.isNullOrBlank() && !enrollPassword.isNullOrBlank()

    /** Which of the user's own settings this config takes over (shown read-only). */
    val managedFields: Set<String>
        get() = buildSet {
            if (!callsign.isNullOrBlank()) add(FIELD_CALLSIGN)
            if (team != null) add(FIELD_TEAM)
            if (role != null) add(FIELD_ROLE)
            if (cotType != null) add(FIELD_COT_TYPE)
            if (messaging != null) add(FIELD_MESSAGING)
            if (startBeacon) add(FIELD_START_ON_BOOT)
        }

    /** Identifies what the server side of this config needs; equal values mean nothing to redo. */
    fun serverFingerprint(): String = sha256(
        listOf(
            version, serverHost?.trim()?.lowercase(), serverPort, transport.wire, enrollPort,
            enrollUsername, enrollPassword, serverCertSha256?.let(::normalizeSha256),
            clientP12?.let(::sha256), clientP12Password, trustP12?.let(::sha256), trustP12Password,
        ).joinToString("\u0000") { it?.toString() ?: "" }
    )

    /** Keeps secrets out of logs. */
    override fun toString(): String =
        "ManagedConfig(version=$version, server=${serverHost}:$serverPort/${transport.wire}, enroll=$enrollPort, " +
            "user=${enrollUsername != null}, password=${enrollPassword != null}, clientP12=${clientP12 != null}, " +
            "trustP12=${trustP12 != null}, callsign=$callsign, team=$team, role=$role, cotType=$cotType, startBeacon=$startBeacon)"

    companion object {
        const val DEFAULT_PORT = 8089
        const val DEFAULT_ENROLL_PORT = 8446

        const val FIELD_CALLSIGN = "callsign"
        const val FIELD_TEAM = "team"
        const val FIELD_ROLE = "role"
        const val FIELD_START_ON_BOOT = "start_on_boot"
        const val FIELD_COT_TYPE = "cot_type"
        const val FIELD_MESSAGING = "messaging"

        // Restriction keys (res/xml/app_restrictions.xml). Secrets contain "password"
        // so MDM consoles mask them.
        const val K_VERSION = "managed_config_version"
        const val K_SERVER_NAME = "server_name"
        const val K_SERVER_HOST = "server_host"
        const val K_SERVER_PORT = "server_port"
        const val K_SERVER_PROTOCOL = "server_protocol"
        const val K_ENROLL_PORT = "enroll_port"
        const val K_ENROLL_USERNAME = "enroll_username"
        const val K_ENROLL_PASSWORD = "enroll_password"
        const val K_SERVER_CERT_SHA256 = "server_cert_sha256"
        const val K_CLIENT_P12 = "client_p12_base64"
        const val K_CLIENT_P12_PASSWORD = "client_p12_password"
        const val K_TRUST_P12 = "trust_p12_base64"
        const val K_TRUST_P12_PASSWORD = "trust_p12_password"
        const val K_CALLSIGN = "callsign"
        const val K_TEAM = "team"
        const val K_ROLE = "role"
        const val K_START_BEACON = "start_beacon"
        const val K_COT_TYPE = "cot_type"
        const val K_MESSAGING = "messaging"

        /**
         * Reads a restrictions bundle flattened to a map. Returns null when nothing usable
         * is set, which means the device is not managed. Values of the wrong type, and
         * unparseable ones, count as unset rather than failing the whole config.
         */
        fun parse(values: Map<String, Any?>, decodeBase64: (String) -> ByteArray?): ManagedConfig? {
            fun str(k: String) = (values[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && !isUnexpanded(it) }
            fun int(k: String, def: Int, range: IntRange = 1..65535): Int = when (val v = values[k]) {
                is Int -> v
                is Long -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            }?.takeIf { it in range } ?: def
            fun bool(k: String): Boolean = when (val v = values[k]) {
                is Boolean -> v
                is String -> v.trim().equals("true", ignoreCase = true)
                else -> false
            }
            fun bytes(k: String) = str(k)?.let { runCatching { decodeBase64(it.replace(Regex("\\s"), "")) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }

            val c = ManagedConfig(
                version = str(K_VERSION),
                serverName = str(K_SERVER_NAME),
                serverHost = str(K_SERVER_HOST)?.let(::cleanHost),
                serverPort = int(K_SERVER_PORT, DEFAULT_PORT),
                transport = Transport.fromWire(str(K_SERVER_PROTOCOL)),
                enrollPort = int(K_ENROLL_PORT, DEFAULT_ENROLL_PORT),
                enrollUsername = str(K_ENROLL_USERNAME),
                enrollPassword = (values[K_ENROLL_PASSWORD] as? String)?.takeIf { it.isNotEmpty() && !isUnexpanded(it) },
                serverCertSha256 = str(K_SERVER_CERT_SHA256)?.let(::normalizeSha256),
                clientP12 = bytes(K_CLIENT_P12),
                clientP12Password = values[K_CLIENT_P12_PASSWORD] as? String,
                trustP12 = bytes(K_TRUST_P12),
                trustP12Password = values[K_TRUST_P12_PASSWORD] as? String,
                callsign = str(K_CALLSIGN)?.take(40),
                team = str(K_TEAM)?.let { t -> Team.fromCot(t) ?: Team.entries.firstOrNull { it.name.equals(t, true) } },
                role = str(K_ROLE)?.let { r -> Role.fromCot(r) ?: Role.entries.firstOrNull { it.name.equals(r, true) } },
                startBeacon = bool(K_START_BEACON),
                cotType = str(K_COT_TYPE)?.takeIf(com.taksolutions.takbeacon.data.BeaconSettings::validCotType),
                messaging = when (str(K_MESSAGING)?.lowercase()) {
                    "off" -> com.taksolutions.takbeacon.data.MessagingMode.OFF
                    "read_only" -> com.taksolutions.takbeacon.data.MessagingMode.READ_ONLY
                    "full" -> com.taksolutions.takbeacon.data.MessagingMode.FULL
                    else -> null
                },
            )
            return c.takeIf { it.hasServer || it.managedFields.isNotEmpty() || it.version != null }
        }

        /** "https://tak.example.com:8443/" and similar down to the bare host. */
        fun cleanHost(raw: String): String =
            raw.trim().substringAfter("://").substringBefore('/').let { h ->
                // Keep IPv6 literals ("[::1]" or bare) intact; strip a ":port" from names.
                if (h.startsWith("[")) h.substringAfter('[').substringBefore(']')
                else if (h.count { it == ':' } == 1) h.substringBefore(':') else h
            }

        fun normalizeSha256(s: String): String? =
            s.replace(":", "").replace(" ", "").lowercase().takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }

        /** An MDM placeholder that wasn't substituted, such as "${device.name}". */
        private fun isUnexpanded(s: String) = s.contains("\${")

        private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        private fun sha256(s: String): String = sha256(s.toByteArray())
    }
}
