package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.service.AdaptiveReportPolicy
import com.taksolutions.takbeacon.service.Motion
import com.taksolutions.takbeacon.service.MotionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AdaptiveReportingTest {

    /** ~1 metre of latitude. */
    private val m = 1.0 / 111_320

    /** Feeds one fix per second for [seconds]; returns the seconds at which it asked to report now. */
    private fun MotionTracker.drive(
        start: Long, seconds: Int, lat0: Double, speedMps: Double, gpsSpeed: Double? = speedMps, jitterM: Double = 0.0,
        rnd: Random = Random(1),
    ): Pair<Long, List<Long>> {
        val bumps = mutableListOf<Long>()
        var t = start
        for (i in 0 until seconds) {
            val lat = lat0 + i * speedMps * m + (rnd.nextDouble() - 0.5) * 2 * jitterM * m
            if (update(t * 1000, lat, 0.0, gpsSpeed, 5.0)) bumps += t
            t++
        }
        return t to bumps
    }

    @Test fun gpsDriftIsStationary() {
        val tr = MotionTracker()
        // 2 minutes sitting still: +-6 m drift and 1 mph (0.45 m/s) of Doppler jitter.
        tr.drive(0, 120, 37.0, 0.0, gpsSpeed = 0.45, jitterM = 6.0)
        assertEquals(Motion.STATIONARY, tr.motion)
    }

    @Test fun walkingThenDrivingStepsUpImmediately() {
        val tr = MotionTracker()
        val (t1, _) = tr.drive(0, 60, 37.0, 0.0, gpsSpeed = 0.0)
        assertEquals(Motion.STATIONARY, tr.motion)
        val (t2, walkBumps) = tr.drive(t1, 30, 37.0, 1.4)
        assertEquals(Motion.WALKING, tr.motion)
        assertTrue("walking reported at once", walkBumps.isNotEmpty() && walkBumps.first() - t1 <= 3)
        val (_, driveBumps) = tr.drive(t2, 20, 37.0 + 30 * 1.4 * m, 12.0)
        assertEquals(Motion.DRIVING, tr.motion)
        assertTrue(driveBumps.isNotEmpty() && driveBumps.first() - t2 <= 2)
        tr.drive(t2 + 20, 10, 38.0, 25.0)
        assertEquals(Motion.FAST, tr.motion)
    }

    @Test fun trafficLightDoesNotDropToStationary() {
        val tr = MotionTracker()
        val (t1, _) = tr.drive(0, 40, 37.0, 12.0)
        assertEquals(Motion.DRIVING, tr.motion)
        // A 45 s red light: below the 60 s settle time, so still Driving.
        tr.drive(t1, 45, 37.0 + 40 * 12 * m, 0.0, gpsSpeed = 0.0)
        assertEquals(Motion.DRIVING, tr.motion)
        // Parked: after the settle time it drops (no report is forced on the way down).
        val tr2 = MotionTracker()
        val (u1, _) = tr2.drive(0, 40, 37.0, 12.0)
        val (_, down) = tr2.drive(u1, 150, 37.0 + 40 * 12 * m, 0.0, gpsSpeed = 0.0)
        assertEquals(Motion.STATIONARY, tr2.motion)
        assertTrue(down.isEmpty())
    }

    @Test fun positionJumpsAreNotSpeed() {
        // Driving at 12 m/s (Driving tier), then one fix jumps 3 km: still Driving, not Fast.
        val tr = MotionTracker()
        val (t1, _) = tr.drive(0, 30, 37.0, 12.0)
        assertEquals(Motion.DRIVING, tr.motion)
        tr.update(t1 * 1000, 37.03, 0.0, 12.0, 5.0)
        tr.drive(t1 + 1, 20, 37.03, 12.0)
        assertEquals(Motion.DRIVING, tr.motion)
        // Parked with GPS reporting 0: a 100 m jump doesn't count as walking off.
        val still = MotionTracker()
        val (u1, _) = still.drive(0, 60, 37.0, 0.0, gpsSpeed = 0.0)
        assertFalse(still.update(u1 * 1000, 37.0 + 100 * m, 0.0, 0.0, 5.0))
        assertEquals(Motion.STATIONARY, still.motion)
        // Without GPS speed, track speed is used, but impossible values are ignored.
        val noSpeed = MotionTracker()
        noSpeed.drive(0, 30, 37.0, 12.0, gpsSpeed = null)
        assertEquals(Motion.DRIVING, noSpeed.motion)
    }

    @Test fun inaccurateFixesAreIgnored() {
        val tr = MotionTracker()
        assertFalse(tr.update(0, 37.0, 0.0, 30.0, 500.0))
        assertEquals(Motion.STATIONARY, tr.motion)
    }

    @Test fun intervals() {
        assertEquals(180, AdaptiveReportPolicy.intervalSeconds(Motion.STATIONARY, 80, false, false, 60))
        assertEquals(30, AdaptiveReportPolicy.intervalSeconds(Motion.WALKING, 80, false, false, 60))
        assertEquals(10, AdaptiveReportPolicy.intervalSeconds(Motion.DRIVING, null, false, false, 60))
        assertEquals(5, AdaptiveReportPolicy.intervalSeconds(Motion.FAST, 80, false, false, 60))
        // Low battery stretches, unless charging.
        assertEquals(45, AdaptiveReportPolicy.intervalSeconds(Motion.WALKING, 20, false, false, 60))
        assertEquals(60, AdaptiveReportPolicy.intervalSeconds(Motion.WALKING, 15, false, false, 60))
        assertEquals(30, AdaptiveReportPolicy.intervalSeconds(Motion.WALKING, 10, true, false, 60))
        assertEquals(360, AdaptiveReportPolicy.intervalSeconds(Motion.STATIONARY, 10, false, false, 60))
        // Satellite: never faster than its own rate.
        assertEquals(60, AdaptiveReportPolicy.intervalSeconds(Motion.FAST, 80, false, true, 60))
        assertEquals(180, AdaptiveReportPolicy.intervalSeconds(Motion.STATIONARY, 80, false, true, 60))
    }

    @Test fun turns() {
        assertTrue(AdaptiveReportPolicy.turned(10.0, 50.0, 10.0, 6))
        assertTrue(AdaptiveReportPolicy.turned(350.0, 25.0, 10.0, 6)) // across north
        assertFalse(AdaptiveReportPolicy.turned(10.0, 30.0, 10.0, 6)) // small bend
        assertFalse(AdaptiveReportPolicy.turned(10.0, 90.0, 1.0, 6)) // walking pace: jitter
        assertFalse(AdaptiveReportPolicy.turned(10.0, 90.0, 10.0, 2)) // too soon
        assertFalse(AdaptiveReportPolicy.turned(null, 90.0, 10.0, 6))
    }
}
