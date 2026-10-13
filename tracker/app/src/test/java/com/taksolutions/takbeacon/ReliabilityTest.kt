package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.cot.Cot
import com.taksolutions.takbeacon.cot.Fix
import com.taksolutions.takbeacon.cot.Identity
import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.data.EmergencyType
import com.taksolutions.takbeacon.mdm.ManagedConfig
import com.taksolutions.takbeacon.net.TakConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class ReliabilityTest {

    @Test fun certificateProblemsStopRetrying() {
        assertTrue(TakConnection.isCredentialFailure(SSLHandshakeException("Received fatal alert: bad_certificate")))
        assertTrue(TakConnection.isCredentialFailure(SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertTrue(TakConnection.isCredentialFailure(SSLHandshakeException("Received fatal alert: certificate_required")))
        assertTrue(TakConnection.isCredentialFailure(
            SSLHandshakeException("handshake").apply { initCause(CertificateException("Trust anchor for certification path not found.")) }
        ))
        assertTrue(TakConnection.isCredentialFailure(
            SSLHandshakeException("handshake").apply { initCause(CertPathValidatorException("expired")) }
        ))
        // TLS 1.3: the rejection arrives on the first read after the handshake.
        assertTrue(TakConnection.isCredentialFailure(SSLException("Read error: ssl=0x1: SSLV3_ALERT_BAD_CERTIFICATE alert number 42")))
        assertTrue(TakConnection.isCredentialFailure(SSLException("Received fatal alert: certificate_required")))
        assertFalse(TakConnection.isCredentialFailure(SSLException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")))
        // A wrapped one counts too.
        assertTrue(TakConnection.isCredentialFailure(SSLException("x", SSLHandshakeException("Received fatal alert: unknown_ca"))))
    }

    @Test fun outagesKeepRetrying() {
        assertFalse(TakConnection.isCredentialFailure(UnknownHostException("tak.example.com")))
        assertFalse(TakConnection.isCredentialFailure(ConnectException("ECONNREFUSED")))
        assertFalse(TakConnection.isCredentialFailure(SocketTimeoutException("timeout")))
        assertFalse(TakConnection.isCredentialFailure(SocketException("Connection reset")))
        // Reset in the middle of a handshake is a network problem, not a certificate one.
        assertFalse(TakConnection.isCredentialFailure(
            SSLHandshakeException("Connection reset by peer").apply { initCause(SocketException("Connection reset")) }
        ))
        assertFalse(TakConnection.isCredentialFailure(SSLHandshakeException("Read error: ssl=0x7: I/O error during system call")))
    }

    @Test fun cotTypeValidation() {
        assertTrue(BeaconSettings.validCotType("a-f-G-U-C"))
        assertTrue(BeaconSettings.validCotType("a-f-G-E-V-C"))
        assertTrue(BeaconSettings.validCotType("a-f-A"))
        assertFalse(BeaconSettings.validCotType(""))
        assertFalse(BeaconSettings.validCotType("b-a-o-tbl"))
        assertFalse(BeaconSettings.validCotType("a-f-G\" uid=\"x"))
        assertFalse(BeaconSettings.validCotType("a-f"))
        BeaconSettings.COT_TYPE_PRESETS.forEach { (t, _) -> assertTrue(t, BeaconSettings.validCotType(t)) }
    }

    @Test fun reportAndAlertsUseTheMapSymbol() {
        val id = Identity("ANDROID-1", "E7", "Cyan", "Team Member", "dev", "34", "1.3.4", type = "a-f-G-E-V-C")
        val fix = Fix(37.0, -122.0, 1.0, 5.0, null, 0.0, 0.0, 0L)
        assertTrue(Cot.pli(id, fix, 120, 80).contains("""uid="ANDROID-1" type="a-f-G-E-V-C""""))
        assertTrue(Cot.emergency(id, fix, EmergencyType.ALERT_911).contains("""<link uid="ANDROID-1" type="a-f-G-E-V-C""""))
        assertTrue(Cot.emergencyCancel(id, fix).contains("""type="a-f-G-E-V-C" relation="p-p""""))
        // The default stays ATAK's ground unit.
        assertTrue(Cot.pli(id.copy(type = BeaconSettings.DEFAULT_COT_TYPE), fix, 120, 80).contains("""type="a-f-G-U-C""""))
    }

    @Test fun mdmCotType() {
        val c = ManagedConfig.parse(mapOf("cot_type" to "a-f-G-E-V-C")) { null }!!
        assertEquals("a-f-G-E-V-C", c.cotType)
        assertTrue("cot_type" in c.managedFields)
        assertNull(ManagedConfig.parse(mapOf("cot_type" to "not a type")) { null })
    }
}
