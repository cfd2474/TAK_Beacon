package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.cot.Fix
import com.taksolutions.takbeacon.data.CoordFormat
import com.taksolutions.takbeacon.geo.Coordinates
import com.taksolutions.takbeacon.mdm.ManagedConfig
import com.taksolutions.takbeacon.service.FixFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTest {

    private fun fix(ce: Double?, lat: Double = 33.0, speed: Double? = 0.0, source: String = "GPS") =
        Fix(lat = lat, lon = -117.0, hae = null, ce = ce, le = null, speedMps = speed, courseDeg = null, timeMs = 0, source = source)

    @Test fun degreesDecimalMinutes() {
        assertEquals("33°07.407'N  117°39.259'W", Coordinates.format(33.123456, -117.654321, CoordFormat.DDM))
        assertEquals("0°30.000'S", Coordinates.ddm(-0.5, "N", "S"))
        // Rounds up into the next degree rather than showing 60 minutes.
        assertEquals("34°00.000'N", Coordinates.ddm(33.9999999, "N", "S"))
    }

    @Test fun reportsOnlyWithinTheLimit() {
        val f = FixFilter()
        assertFalse(f.offer(fix(80.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50))
        assertNull(f.reportable(0, 50))
        // Shown anyway, so the operator sees where the phone thinks it is.
        assertEquals(80.0, f.shown(0, 50)!!.ce!!, 0.0)
        // No accuracy at all is not trusted.
        assertFalse(f.offer(fix(null), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50))
        assertTrue(f.offer(fix(12.0), gps = true, takenAtMs = 1_000, nowMs = 1_000, limitM = 50))
        assertEquals(12.0, f.reportable(1_000, 50)!!.ce!!, 0.0)
        // A raised limit lets the 80 m fix through.
        assertTrue(f.offer(fix(80.0), gps = true, takenAtMs = 2_000, nowMs = 2_000, limitM = 100))
    }

    @Test fun poorFixesDoNotReplaceAGoodOne() {
        val f = FixFilter()
        f.offer(fix(10.0, lat = 33.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50)
        f.offer(fix(200.0, lat = 33.01), gps = true, takenAtMs = 1_000, nowMs = 1_000, limitM = 50)
        assertEquals(33.0, f.reportable(1_000, 50)!!.lat, 0.0)
    }

    @Test fun confidenceDecaysWithoutFreshFixes() {
        val f = FixFilter()
        f.offer(fix(20.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50)
        // Within the grace period: unchanged.
        assertEquals(20.0, f.reportable(2_000, 50)!!.ce!!, 1e-9)
        // Then at least walking pace: 20 m + 1 m/s * 28 s = 48 m, still in.
        assertEquals(48.0, f.reportable(30_000, 50)!!.ce!!, 1e-9)
        // 20 + 31 = 51 m: out.
        assertNull(f.reportable(33_000, 50))
        // Faster when it was moving fast: 20 + 25 m/s * 1 s.
        val car = FixFilter()
        car.offer(fix(20.0, speed = 25.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50)
        assertNotNull(car.reportable(3_000, 50))
        assertNull(car.reportable(4_000, 50))
        // Never once five minutes old, however generous the limit.
        val still = FixFilter()
        still.offer(fix(5.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 500)
        assertNotNull(still.reportable(FixFilter.MAX_HOLD_MS, 500))
        assertNull(still.reportable(FixFilter.MAX_HOLD_MS + 1, 500))
    }

    @Test fun networkFixesWhenGpsIsGoneOrWorse() {
        val f = FixFilter()
        f.offer(fix(8.0, lat = 33.0), gps = true, takenAtMs = 0, nowMs = 0, limitM = 50)
        // Fresh good GPS beats a 30 m network fix.
        assertFalse(f.offer(fix(30.0, lat = 33.001, speed = null, source = "NETWORK"), gps = false, takenAtMs = 1_000, nowMs = 1_000, limitM = 50))
        assertEquals(33.0, f.reportable(1_000, 50)!!.lat, 0.0)
        // GPS goes quiet: by 25 s its error (8 + 23) is over the network fix's 30 m.
        assertTrue(f.offer(fix(30.0, lat = 33.001, speed = null, source = "NETWORK"), gps = false, takenAtMs = 25_000, nowMs = 25_000, limitM = 50))
        assertEquals("NETWORK", f.reportable(25_000, 50)!!.source)
        // A newer network fix replaces the older network one.
        assertTrue(f.offer(fix(45.0, lat = 33.002, speed = null, source = "NETWORK"), gps = false, takenAtMs = 40_000, nowMs = 40_000, limitM = 50))
        // GPS back: it takes over.
        assertTrue(f.offer(fix(15.0, lat = 33.003), gps = true, takenAtMs = 41_000, nowMs = 41_000, limitM = 50))
        assertEquals("GPS", f.reportable(41_000, 50)!!.source)
        // A network fix over the limit never counts.
        val n = FixFilter()
        assertFalse(n.offer(fix(900.0, speed = null, source = "NETWORK"), gps = false, takenAtMs = 0, nowMs = 0, limitM = 50))
        assertNull(n.reportable(0, 50))
    }

    @Test fun latestIsKeptForEmergencies() {
        val f = FixFilter()
        val poor = fix(300.0)
        f.offer(poor, gps = true, takenAtMs = 0, nowMs = 0, limitM = 50)
        assertNull(f.reportable(0, 50))
        assertSame(poor, f.latest)
    }

    @Test fun mdmAccuracyLimit() {
        val c = ManagedConfig.parse(mapOf("location_accuracy_m" to 25)) { null }!!
        assertEquals(25, c.accuracyLimitM)
        assertTrue(ManagedConfig.FIELD_ACCURACY_LIMIT in c.managedFields)
        assertEquals(100, ManagedConfig.parse(mapOf("location_accuracy_m" to "100")) { null }!!.accuracyLimitM)
        // Out of range counts as unset.
        assertNull(ManagedConfig.parse(mapOf("location_accuracy_m" to 2, "show_contacts" to true)) { null }!!.accuracyLimitM)
        assertNull(ManagedConfig.parse(mapOf("location_accuracy_m" to 5000, "show_contacts" to true)) { null }!!.accuracyLimitM)
    }
}
