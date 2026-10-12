package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.cot.Cot
import com.taksolutions.takbeacon.cot.Fix
import com.taksolutions.takbeacon.cot.Identity
import com.taksolutions.takbeacon.data.EmergencyType
import com.taksolutions.takbeacon.enroll.EnrollmentClient
import com.taksolutions.takbeacon.enroll.EnrollmentLink
import com.taksolutions.takbeacon.geo.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class CoordinatesTest {
    private fun assertMgrs(expected: String, lat: Double, lon: Double, toleranceM: Int = 2) {
        val got = Coordinates.mgrs(lat, lon)!!
        val (ez, eSq, eE, eN) = expected.split(" ")
        val (gz, gSq, gE, gN) = got.split(" ")
        assertEquals("zone/band for $lat,$lon", ez, gz)
        assertEquals("100k square for $lat,$lon", eSq, gSq)
        assertTrue("easting $got vs $expected", kotlin.math.abs(eE.toInt() - gE.toInt()) <= toleranceM)
        assertTrue("northing $got vs $expected", kotlin.math.abs(eN.toInt() - gN.toInt()) <= toleranceM)
    }

    @Test fun mgrsOrigin() = assertMgrs("31N AA 66021 00000", 0.0, 0.0)
    @Test fun mgrsWashingtonMonument() = assertMgrs("18S UJ 23487 06483", 38.8895, -77.0352)
    @Test fun mgrsSouthernHemisphere() = assertMgrs("56H LH 34900 52288", -33.8568, 151.2153) // checked against pyproj
    @Test fun mgrsPolarIsNull() = assertNull(Coordinates.mgrs(85.0, 10.0))

    @Test fun dms() {
        assertEquals("38°53'22.20\"N", Coordinates.dms(38.8895, "N", "S"))
        assertEquals("77°02'06.72\"W", Coordinates.dms(-77.0352, "E", "W"))
    }
}

class CotTest {
    private val id = Identity("ANDROID-abc", "Bravo <1> & \"co\"", "Cyan", "Team Member", "Pixel", "34", "1.0.0")
    private val fix = Fix(38.8895, -77.0352, 12.5, 4.0, null, 1.5, 270.0, 0L)

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    @Test fun pliIsWellFormedAndEscaped() {
        val xml = Cot.pli(id, fix, 120, 80, now = 1_700_000_000_000)
        val ev = parse(xml)
        assertEquals("a-f-G-U-C", ev.getAttribute("type"))
        assertEquals("ANDROID-abc", ev.getAttribute("uid"))
        assertEquals("2023-11-14T22:13:20.000Z", ev.getAttribute("time"))
        assertEquals("2023-11-14T22:15:20.000Z", ev.getAttribute("stale"))
        val contact = ev.getElementsByTagName("contact").item(0) as org.w3c.dom.Element
        assertEquals("Bravo <1> & \"co\"", contact.getAttribute("callsign"))
        val point = ev.getElementsByTagName("point").item(0) as org.w3c.dom.Element
        assertEquals("9999999.0", point.getAttribute("le"))
        val group = ev.getElementsByTagName("__group").item(0) as org.w3c.dom.Element
        assertEquals("Cyan", group.getAttribute("name"))
    }

    @Test fun emergencyAndCancel() {
        val e = parse(Cot.emergency(id, fix, EmergencyType.ALERT_911))
        assertEquals("b-a-o-tbl", e.getAttribute("type"))
        assertEquals("ANDROID-abc-9-1-1", e.getAttribute("uid"))
        val c = parse(Cot.emergencyCancel(id, fix))
        assertEquals("b-a-o-can", c.getAttribute("type"))
        assertEquals(e.getAttribute("uid"), c.getAttribute("uid"))
    }

    @Test fun pingIsWellFormed() {
        assertEquals("t-x-c-t", parse(Cot.ping("ANDROID-abc")).getAttribute("type"))
    }
}

class EnrollmentLinkTest {
    @Test fun atakEnrollQr() {
        val r = EnrollmentLink.parse("tak://com.atakmap.app/enroll?host=tak.example.com&username=alice&token=s3cr%26t")!!
        assertEquals("tak.example.com", r.host)
        assertEquals("alice", r.username)
        assertEquals("s3cr&t", r.secret)
        assertEquals(8089, r.port)
        assertEquals(8446, r.csrPort)
        assertTrue(r.canAutoSubmit)
    }

    @Test fun ownScheme() {
        val r = EnrollmentLink.parse("takbeacon://enroll?host=tak.example.com&port=9089&csrPort=9446&password=pw&username=bob&callsign=B1&team=Red&role=Medic")!!
        assertEquals(9089, r.port)
        assertEquals(9446, r.csrPort)
        assertEquals("pw", r.secret)
        assertEquals("B1", r.callsign)
        assertEquals("Red", r.team)
    }

    @Test fun hostOnlyNeedsForm() {
        val r = EnrollmentLink.parse("takbeacon://enroll?host=tak.example.com")!!
        assertFalse(r.canAutoSubmit)
    }

    @Test fun connectionCsv() {
        val r = EnrollmentLink.parse("My Server,tak.example.com,8089,ssl")!!
        assertEquals("My Server", r.name)
        assertEquals("tak.example.com", r.host)
    }

    @Test fun rejectsJunk() {
        assertNull(EnrollmentLink.parse("https://example.com"))
        assertNull(EnrollmentLink.parse("tak://com.atakmap.app/enroll?host=bad host"))
        assertNull(EnrollmentLink.parse("takbeacon://enroll?username=x"))
    }

    @Test fun cleanHost() {
        assertEquals("tak.example.com", EnrollmentLink.cleanHost("https://tak.example.com:8443/path"))
        assertEquals("fd00::1", EnrollmentLink.cleanHost("[fd00::1]:8089"))
    }
}

class EnrollmentParsingTest {
    @Test fun nameEntriesAnyAttributeOrder() {
        val xml = """<ns2:certificateConfig xmlns:ns2="com.bbn.marti.config"><nameEntries>
            <nameEntry name="O" value="TAK"/><nameEntry value="Ops" name="OU"/></nameEntries></ns2:certificateConfig>"""
        assertEquals(listOf("O" to "TAK", "OU" to "Ops"), EnrollmentClient.nameEntries(xml))
    }

    @Test fun signResponseRejectsEmpty() {
        val r = runCatching { EnrollmentClient.parseSignResponse("<enrollment></enrollment>") }
        assertNotNull(r.exceptionOrNull())
    }
}
