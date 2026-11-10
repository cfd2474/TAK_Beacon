package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.contacts.ContactCot
import com.taksolutions.takbeacon.data.SpeedUnit
import com.taksolutions.takbeacon.data.Team
import com.taksolutions.takbeacon.geo.Coordinates
import com.taksolutions.takbeacon.mdm.ManagedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {

    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    private fun pli(
        uid: String = "ANDROID-abc",
        type: String = "a-f-G-U-C",
        time: String = "2027-01-15T08:00:00.000Z",
        stale: String = "2027-01-15T08:06:30.000Z",
        detail: String = """<takv os="34" version="5.8" device="Pixel" platform="ATAK-CIV"/><contact callsign="Bravo &amp; 1" endpoint="*:-1:stcp"/><__group name="Dark Blue" role="Medic"/><track course="92.5" speed="3.2"/>""",
        point: String = """<point lat="33.123456" lon="-117.654321" hae="120.5" ce="9.9" le="9999999.0"/>""",
    ) = """<?xml version="1.0" encoding="UTF-8"?><event version="2.0" uid="$uid" type="$type" how="m-g" time="$time" start="$time" stale="$stale">$point<detail>$detail</detail></event>"""

    @Test fun parsesAUsersReport() {
        val c = (ContactCot.parse(pli(), "srv", now) as ContactCot.Position).contact
        assertEquals("ANDROID-abc", c.uid)
        assertEquals("Bravo & 1", c.callsign)
        assertEquals(Team.DARK_BLUE, c.team)
        assertEquals("Medic", c.role)
        assertEquals(33.123456, c.lat, 1e-9)
        assertEquals(-117.654321, c.lon, 1e-9)
        assertEquals(120.5, c.hae!!, 1e-9)
        assertEquals(3.2, c.speedMps!!, 1e-9)
        assertEquals(92.5, c.courseDeg!!, 1e-9)
        assertEquals("srv", c.serverId)
        // The sender's 6.5 minute stale period, counted from arrival.
        assertEquals(now + 390_000L, c.staleAtMs)
        assertFalse(c.fading(now + 200_000L))
        assertTrue(c.fading(now + 300_000L))
    }

    @Test fun senderClockSkewDoesNotMatter() {
        // Sent by a phone whose clock is 3 minutes slow: the stale period still counts from arrival.
        val c = (ContactCot.parse(pli(time = "2027-01-15T07:57:00.000Z", stale = "2027-01-15T07:59:00.000Z"), "s", now) as ContactCot.Position).contact
        assertEquals(now + 120_000L, c.staleAtMs)
    }

    @Test fun skipsWhatIsNotAUser() {
        // A marker someone placed: an atom with a callsign, but no team or client version.
        assertNull(ContactCot.parse(pli(type = "a-h-G", detail = """<contact callsign="Hostile 1"/>"""), "s", now))
        // Chat, pings and other non-atoms.
        assertNull(ContactCot.parse(pli(type = "b-t-f"), "s", now))
        assertNull(ContactCot.parse(pli(type = "t-x-c-t"), "s", now))
        // No position yet, no callsign, a broken point.
        assertNull(ContactCot.parse(pli(point = """<point lat="0.0" lon="0.0" hae="0" ce="9999999" le="9999999"/>"""), "s", now))
        assertNull(ContactCot.parse(pli(detail = """<__group name="Cyan" role="Team Member"/>"""), "s", now))
        assertNull(ContactCot.parse(pli(point = """<point lat="95" lon="10" hae="0" ce="1" le="1"/>"""), "s", now))
        // A report that went stale long ago (a cached copy).
        assertNull(ContactCot.parse(pli(time = "2027-01-15T07:00:00.000Z", stale = "2027-01-15T07:10:00.000Z"), "s", now))
        // Too large to be a report.
        assertNull(ContactCot.parse(pli(detail = "<x>" + "a".repeat(70_000) + "</x>"), "s", now))
    }

    @Test fun unknownValuesAreLeftOut() {
        val c = (ContactCot.parse(pli(
            point = """<point lat="1.5" lon="2.5" hae="9999999.0" ce="9999999.0" le="9999999.0"/>""",
            detail = """<takv platform="WinTAK"/><contact callsign="HQ"/><__group name="Chartreuse" role="HQ"/>""",
        ), "s", now) as ContactCot.Position).contact
        assertNull(c.hae)
        assertNull(c.team)
        assertNull(c.speedMps)
        assertNull(c.courseDeg)
    }

    @Test fun parsesADeparture() {
        val xml = """<event version="2.0" uid="x" type="t-x-d-d" how="h-g-i-g-o" time="2027-01-15T08:00:00Z" start="2027-01-15T08:00:00Z" stale="2027-01-15T08:00:20Z"><point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/><detail><link uid="ANDROID-abc" relation="p-p" type="a-f-G-U-C"/></detail></event>"""
        assertTrue(ContactCot.isCandidate(xml))
        assertEquals(ContactCot.Gone("ANDROID-abc"), ContactCot.parse(xml, "s", now))
    }

    @Test fun candidatesAreCheap() {
        assertTrue(ContactCot.isCandidate(pli()))
        assertFalse(ContactCot.isCandidate(pli(type = "b-t-f")))
    }

    @Test fun distanceAndBearing() {
        // One degree of latitude is about 111.2 km, due north.
        assertEquals(111_195.0, Coordinates.distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
        assertEquals(0.0, Coordinates.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Coordinates.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(270.0, Coordinates.bearingDeg(0.0, 0.0, 0.0, -1.0), 1e-6)
        assertEquals("850 m", Coordinates.formatDistance(850.0, SpeedUnit.KMH))
        assertEquals("1.5 km", Coordinates.formatDistance(1500.0, SpeedUnit.KMH))
        assertEquals("328 ft", Coordinates.formatDistance(100.0, SpeedUnit.MPH))
        assertEquals("2.0 mi", Coordinates.formatDistance(3218.7, SpeedUnit.MPH))
        assertEquals("1.0 NM", Coordinates.formatDistance(1852.0, SpeedUnit.KNOTS))
    }

    @Test fun mdmShowContacts() {
        val off = ManagedConfig.parse(mapOf("show_contacts" to false)) { null }!!
        assertEquals(false, off.showContacts)
        assertTrue(ManagedConfig.FIELD_SHOW_CONTACTS in off.managedFields)
        assertEquals(true, ManagedConfig.parse(mapOf("show_contacts" to "true")) { null }!!.showContacts)
        assertNull(ManagedConfig.parse(mapOf("alert_keywords" to "mayday")) { null }!!.showContacts)
    }
}
