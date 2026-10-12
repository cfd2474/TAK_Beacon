package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.data.BeaconSettings
import com.taksolutions.takbeacon.net.UdpBroadcaster
import com.taksolutions.takbeacon.service.ReportPolicy
import com.taksolutions.takbeacon.service.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket

class ReportPolicyTest {
    private val base = BeaconSettings(uid = "u", callsign = "c", intervalSeconds = 10, satelliteIntervalSeconds = 60)

    private fun state(
        s: BeaconSettings = base, servers: Int = 1, fix: Boolean = true, atak: Boolean = false,
        sat: Boolean = false, emergency: Boolean = false,
    ) = ReportPolicy.runState(s, servers, fix, atak, sat, emergency)

    @Test fun transmitsNormally() = assertEquals(RunState.TRANSMITTING, state())

    @Test fun slowsDownOnSatellite() {
        assertEquals(10, ReportPolicy.intervalSeconds(base, onSatellite = false))
        assertEquals(60, ReportPolicy.intervalSeconds(base, onSatellite = true))
        // A slower normal interval is never sped up by the satellite one.
        assertEquals(300, ReportPolicy.intervalSeconds(base.copy(intervalSeconds = 300), onSatellite = true))
    }

    @Test fun satelliteOnByDefault() = assertEquals(RunState.TRANSMITTING, state(sat = true))

    @Test fun satelliteOffPauses() =
        assertEquals(RunState.PAUSED_SATELLITE, state(s = base.copy(satelliteData = false), sat = true))

    @Test fun emergencyGoesOverSatelliteEvenWhenOff() =
        assertEquals(RunState.TRANSMITTING, state(s = base.copy(satelliteData = false), sat = true, emergency = true))

    @Test fun udpAloneIsEnoughToReport() {
        assertEquals(RunState.NO_SERVERS, state(servers = 0))
        assertEquals(RunState.TRANSMITTING, state(s = base.copy(udpBroadcast = true), servers = 0))
    }

    @Test fun atakPauseStillApplies() = assertEquals(RunState.PAUSED_FOR_ATAK, state(atak = true))

    @Test fun udpOffByDefault() = assertEquals(false, base.udpBroadcast)
}

class UdpBroadcasterTest {
    @Test fun sendsCotAsOneDatagram() {
        DatagramSocket(0).use { rx ->
            rx.soTimeout = 5_000
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val udp = UdpBroadcaster().also { it.start(scope) }
            val xml = "<event uid=\"x\" type=\"a-f-G-U-C\"/>"
            udp.send("127.0.0.1", rx.localPort, xml)
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            rx.receive(p)
            assertEquals(xml, String(p.data, 0, p.length, Charsets.UTF_8))
            udp.stop()
            scope.cancel()
        }
    }
}
