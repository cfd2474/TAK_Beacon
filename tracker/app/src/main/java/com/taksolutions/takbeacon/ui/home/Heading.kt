package com.taksolutions.takbeacon.ui.home

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.taksolutions.takbeacon.cot.Fix
import kotlin.math.abs

/** Below this speed GPS course is noise; the compass gives the heading instead. */
const val COURSE_MIN_MPS = 1.0

/**
 * The operator's true heading for the map arrow, in degrees: the GPS course while
 * moving, the compass (corrected to true north) while still or slow. Null when
 * neither is available; the map then shows a plain dot. The compass is only
 * listened to while this is on screen and the operator isn't moving.
 */
@Composable
fun rememberHeading(fix: Fix): Float? {
    val context = LocalContext.current
    val course = fix.courseDeg?.takeIf { (fix.speedMps ?: 0.0) >= COURSE_MIN_MPS }
    val moving = course != null
    var compass by remember { mutableStateOf<Float?>(null) }
    val where by rememberUpdatedState(fix)
    DisposableEffect(moving) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = if (moving) null else sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val listener = sensor?.let { CompassListener(context, { where }) { compass = it } }
        if (listener != null) sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            listener?.let { sm?.unregisterListener(it) }
            compass = null
        }
    }
    return course?.toFloat() ?: compass
}

/** Rotation vector to a smoothed true heading, allowing for how the screen is turned. */
private class CompassListener(
    private val context: Context,
    private val fix: () -> Fix,
    private val onHeading: (Float) -> Unit,
) : SensorEventListener {
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private var smoothed: Float? = null
    private var published: Float? = null
    private var declination: Float? = null
    private var declinationAt = 0L

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, e.values)
        @Suppress("DEPRECATION")
        val turn = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val (x, y) = when (turn) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        // Where the phone faces, at any tilt: the screen's top edge when flat, out of the
        // back when upright (dash mount, held up), a mix of both in between. The sum of
        // the two directions keeps a horizontal part at every tilt, where getOrientation's
        // azimuth breaks down as the phone stands up. World axes: x east, y north, z up.
        val east = remapped[1] - remapped[2]
        val north = remapped[4] - remapped[5]
        if (east * east + north * north < 1e-4f) return // face up and turned over: no answer
        val magnetic = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat()
        val heading = norm(magnetic + declination())
        // Low-pass along the shorter way round, so 359 -> 1 doesn't swing through south.
        val s = smoothed?.let { norm(it + SMOOTHING * delta(it, heading)) } ?: heading
        smoothed = s
        if (published == null || abs(delta(published!!, s)) >= MIN_CHANGE_DEG) {
            published = s
            onHeading(s)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Magnetic to true north, for where the operator is (refreshed every few minutes). */
    private fun declination(): Float {
        val now = System.currentTimeMillis()
        declination?.takeIf { now - declinationAt < 5 * 60_000L }?.let { return it }
        val f = fix()
        return GeomagneticField(f.lat.toFloat(), f.lon.toFloat(), (f.hae ?: 0.0).toFloat(), now).declination
            .also { declination = it; declinationAt = now }
    }

    private fun norm(d: Float) = ((d % 360f) + 360f) % 360f
    private fun delta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f

    companion object {
        private const val SMOOTHING = 0.2f
        private const val MIN_CHANGE_DEG = 1.5f
    }
}
