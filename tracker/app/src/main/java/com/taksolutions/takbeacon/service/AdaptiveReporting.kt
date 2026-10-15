package com.taksolutions.takbeacon.service

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How the phone is moving, from its recent fixes. */
enum class Motion(val label: String) {
    STATIONARY("Stationary"), WALKING("Walking"), DRIVING("Driving"), FAST("Fast")
}

/**
 * Classifies movement from real displacement over time, not from GPS speed alone:
 * a phone sitting still drifts a few metres and can report 1-3 mph of jitter.
 *
 * Stepping up a tier is immediate (and asks for a report now); stepping down
 * waits until the slower tier has held for a while, so a traffic light or a
 * pause on foot doesn't flip the rate back and forth.
 */
class MotionTracker {

    private class Sample(val tMs: Long, val lat: Double, val lon: Double, val speedMps: Double?)

    private val samples = ArrayDeque<Sample>()
    private var lowerSince: Long? = null

    var motion: Motion = Motion.STATIONARY
        private set

    /** Last speed estimate, metres per second. */
    var speedMps: Double = 0.0
        private set

    /**
     * Feeds one fix ([tMs] on a monotonic clock). Returns true when movement stepped
     * up a tier, which should be reported straight away.
     */
    fun update(tMs: Long, lat: Double, lon: Double, gpsSpeedMps: Double?, accuracyM: Double?): Boolean {
        // A wildly inaccurate fix (network location indoors) says nothing about movement.
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return false
        samples.addLast(Sample(tMs, lat, lon, gpsSpeedMps))
        while (samples.size > 2 && tMs - samples.first().tMs > WINDOW_MS) samples.removeFirst()

        val raw = classify(tMs)
        return when {
            raw.ordinal > motion.ordinal -> { motion = raw; lowerSince = null; true }
            raw.ordinal < motion.ordinal -> {
                val since = lowerSince ?: tMs.also { lowerSince = it }
                if (tMs - since >= settleMs(motion)) { motion = raw; lowerSince = null }
                false
            }
            else -> { lowerSince = null; false }
        }
    }

    private fun classify(tMs: Long): Motion {
        val newest = samples.last()
        // Displacement over the last minute: below this, it is GPS drift.
        val anchor = samples.firstOrNull { tMs - it.tMs <= STATIONARY_WINDOW_MS } ?: samples.first()
        val moved = metres(anchor.lat, anchor.lon, newest.lat, newest.lon)
        val gps = newest.speedMps
        val spanMs = tMs - samples.first().tMs
        val drift = moved < STATIONARY_RADIUS_M && (gps ?: 0.0) < STATIONARY_GPS_MPS
        // GPS says it isn't moving: a one-off position jump (tunnel exit, reacquisition) isn't movement.
        val jump = gps != null && gps < JUMP_GPS_MPS && moved < JUMP_RADIUS_M
        if ((drift || jump) && spanMs >= MIN_SPAN_MS) {
            speedMps = 0.0
            return Motion.STATIONARY
        }
        // GPS (Doppler) speed is accurate while moving; only without it, speed from the
        // track over the last few seconds, ignoring impossible values from position jumps.
        speedMps = gps ?: run {
            val recent = samples.firstOrNull { tMs - it.tMs <= SPEED_WINDOW_MS && tMs - it.tMs >= 3_000 }
            recent?.let { metres(it.lat, it.lon, newest.lat, newest.lon) / ((tMs - it.tMs) / 1000.0) }
                ?.takeIf { it <= MAX_PLAUSIBLE_MPS } ?: speedMps
        }
        return when {
            speedMps < WALK_MAX_MPS -> if (moved < STATIONARY_RADIUS_M && spanMs < MIN_SPAN_MS) motion else Motion.WALKING
            speedMps < DRIVE_MAX_MPS -> Motion.DRIVING
            else -> Motion.FAST
        }
    }

    private fun settleMs(from: Motion) = when (from) {
        Motion.STATIONARY -> 0L
        Motion.WALKING -> 30_000L
        Motion.DRIVING -> 60_000L
        Motion.FAST -> 20_000L
    }

    companion object {
        const val MAX_ACCURACY_M = 50.0
        private const val WINDOW_MS = 90_000L
        private const val STATIONARY_WINDOW_MS = 60_000L
        private const val SPEED_WINDOW_MS = 15_000L
        /** Less data than this can't call the phone stationary. */
        private const val MIN_SPAN_MS = 20_000L
        const val STATIONARY_RADIUS_M = 20.0
        private const val STATIONARY_GPS_MPS = 1.0
        private const val JUMP_GPS_MPS = 0.5
        private const val JUMP_RADIUS_M = 150.0
        /** Faster than this, a speed from the track is a position jump, not movement. */
        private const val MAX_PLAUSIBLE_MPS = 70.0
        /** ~6 mph. */
        const val WALK_MAX_MPS = 2.7
        /** ~30 mph. */
        const val DRIVE_MAX_MPS = 13.4

        /** Great-circle distance in metres. */
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(min(1.0, sqrt(a)))
        }
    }
}

/** Report interval in Adaptive mode. Pure, so it is unit-tested. */
object AdaptiveReportPolicy {

    fun baseSeconds(m: Motion) = when (m) {
        Motion.STATIONARY -> 180
        Motion.WALKING -> 30
        Motion.DRIVING -> 10
        Motion.FAST -> 5
    }

    /** Seconds between reports: by movement, stretched on low battery, never faster than satellite allows. */
    fun intervalSeconds(m: Motion, batteryPct: Int?, charging: Boolean, onSatellite: Boolean, satelliteSeconds: Int): Int {
        var s = baseSeconds(m).toDouble()
        if (batteryPct != null && !charging) {
            if (batteryPct <= 15) s *= 2.0 else if (batteryPct <= 20) s *= 1.5
        }
        var out = min(s.roundToInt(), MAX_SECONDS)
        if (onSatellite) out = maxOf(out, satelliteSeconds)
        return out
    }

    /**
     * A turn worth reporting before the timer (corner pegging, as in APRS SmartBeaconing):
     * moving at a real speed, heading changed by more than [TURN_DEGREES] since the last
     * report, and at least [MIN_TURN_GAP_S] since it.
     */
    fun turned(lastCourse: Double?, course: Double?, speedMps: Double, secondsSinceReport: Long): Boolean {
        if (lastCourse == null || course == null || speedMps < TURN_MIN_SPEED_MPS || secondsSinceReport < MIN_TURN_GAP_S) return false
        val d = abs(((course - lastCourse) % 360 + 540) % 360 - 180)
        return d >= TURN_DEGREES
    }

    const val MAX_SECONDS = 600
    const val TURN_DEGREES = 30.0
    const val TURN_MIN_SPEED_MPS = 3.0
    const val MIN_TURN_GAP_S = 5L
    /** Movement-triggered reports are never closer together than this. */
    const val MIN_GAP_S = 3L
}
