package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.data.Role
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.data.Transport
import com.taksolutions.takbeacon.mdm.ManagedConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ManagedConfigTest {
    private fun parse(vararg kv: Pair<String, Any?>) =
        ManagedConfig.parse(mapOf(*kv)) { Base64.getDecoder().decode(it) }

    @Test fun emptyMeansNotManaged() {
        assertNull(parse())
        assertNull(parse("server_host" to "", "callsign" to "  "))
        // Defaults alone (as an MDM console may send them) don't make a device managed.
        assertNull(parse("server_port" to 8089, "server_protocol" to "ssl", "start_beacon" to false))
    }

    @Test fun serverWithDefaults() {
        val c = parse("server_host" to "tak.example.com", "enroll_username" to "op1", "enroll_password" to "pw")!!
        assertEquals("tak.example.com", c.serverHost)
        assertEquals(8089, c.serverPort)
        assertEquals(8446, c.enrollPort)
        assertEquals(Transport.SSL, c.transport)
        assertTrue(c.canEnroll)
        assertFalse(c.hasClientCert)
        assertTrue(c.managedFields.isEmpty())
    }

    @Test fun hostIsCleanedAndPortsValidated() {
        val c = parse("server_host" to "https://tak.example.com:8443/path", "server_port" to "8087",
            "server_protocol" to "TCP", "enroll_port" to 99999)!!
        assertEquals("tak.example.com", c.serverHost)
        assertEquals(8087, c.serverPort)
        assertEquals(Transport.TCP, c.transport)
        assertEquals(8446, c.enrollPort) // out of range: default
        assertEquals("fd00::1", ManagedConfig.cleanHost("[fd00::1]"))
    }

    @Test fun unexpandedVariablesCountAsUnset() {
        val c = parse("server_host" to "tak.example.com", "callsign" to "\${device.name}", "team" to "\${attribute.Team}")!!
        assertNull(c.callsign)
        assertNull(c.team)
        assertTrue(c.managedFields.isEmpty())
    }

    @Test fun identityByCotNameOrEnum() {
        val c = parse("callsign" to "E7-ALPHA", "team" to "Dark Blue", "role" to "forward_observer", "start_beacon" to true)!!
        assertEquals("E7-ALPHA", c.callsign)
        assertEquals(Team.DARK_BLUE, c.team)
        assertEquals(Role.FORWARD_OBSERVER, c.role)
        assertTrue(c.startBeacon)
        assertEquals(setOf("callsign", "team", "role", "start_on_boot"), c.managedFields)
        assertFalse(c.hasServer)
    }

    @Test fun base64CertificatesAndFingerprint() {
        val p12 = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(p12)
        val c = parse("server_host" to "h", "client_p12_base64" to b64.chunked(3).joinToString("\n"),
            "server_cert_sha256" to "AB:".repeat(31) + "AB")!!
        assertArrayEquals(p12, c.clientP12)
        assertEquals("ab".repeat(32), c.serverCertSha256)
        assertNull(parse("server_host" to "h", "server_cert_sha256" to "nothex")!!.serverCertSha256)
    }

    @Test fun fingerprintTracksServerPartOnly() {
        val a = parse("server_host" to "h", "enroll_username" to "u", "enroll_password" to "p", "callsign" to "A")!!
        val sameServer = parse("server_host" to "H", "enroll_username" to "u", "enroll_password" to "p", "callsign" to "B")!!
        val newPassword = parse("server_host" to "h", "enroll_username" to "u", "enroll_password" to "q")!!
        val newVersion = parse("server_host" to "h", "enroll_username" to "u", "enroll_password" to "p", "managed_config_version" to "2")!!
        assertEquals(a.serverFingerprint(), sameServer.serverFingerprint())
        assertNotEquals(a.serverFingerprint(), newPassword.serverFingerprint())
        assertNotEquals(a.serverFingerprint(), newVersion.serverFingerprint())
    }

    @Test fun secretsStayOutOfToString() {
        val c = parse("server_host" to "h", "enroll_username" to "u", "enroll_password" to "s3cret-pw")!!
        assertFalse(c.toString().contains("s3cret-pw"))
    }
}
