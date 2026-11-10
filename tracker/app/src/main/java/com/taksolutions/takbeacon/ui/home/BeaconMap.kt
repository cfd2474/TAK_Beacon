package com.taksolutions.takbeacon.ui.home

import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.taksolutions.takbeacon.contacts.Contact
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An OpenStreetMap view with the operator's position and the other users the
 * server reports. It follows the operator until the map is dragged; the
 * locate button brings it back. [onFullScreen] adds the full-screen button
 * ([expanded]: it shows the exit icon instead).
 */
@Composable
fun BeaconMap(
    lat: Double,
    lon: Double,
    dark: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    contacts: List<Contact> = emptyList(),
    onContact: (Contact) -> Unit = {},
    onFullScreen: (() -> Unit)? = null,
    expanded: Boolean = false,
) {
    val context = LocalContext.current
    var follow by rememberSaveable { mutableStateOf(true) }
    val tap by rememberUpdatedState(onContact)
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir.resolve("osmdroid")
            osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
        }
    }
    val others = remember { ContactsOverlay { tap(it) } }
    val dot = remember { PositionDot() }
    DisposableEffect(map) {
        map.overlays.add(others)
        map.overlays.add(dot)
        // Dragging (or pinching) the map stops following the operator.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        map.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_POINTER_DOWN -> follow = false
                MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) follow = false
            }
            false
        }
        map.onResume()
        onDispose {
            map.setOnTouchListener(null)
            map.onPause()
            map.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { m ->
            m.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
            val p = GeoPoint(lat, lon)
            dot.point = p
            dot.fill.color = color.toArgb()
            others.contacts = contacts
            if (follow) m.controller.setCenter(p)
            m.invalidate()
        })
        Column(
            Modifier.align(Alignment.BottomEnd).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!follow) {
                SmallFloatingActionButton(
                    onClick = { follow = true; map.controller.animateTo(GeoPoint(lat, lon)) },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) { Icon(Icons.Filled.MyLocation, "Follow me") }
            }
            if (onFullScreen != null) {
                SmallFloatingActionButton(onClick = onFullScreen, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (expanded) Icon(Icons.Filled.FullscreenExit, "Exit full screen")
                    else Icon(Icons.Filled.Fullscreen, "Full screen")
                }
            }
        }
    }
}

/** The operator's position: a fixed-size dot with a white ring, whatever the zoom. */
private class PositionDot : Overlay() {
    var point: GeoPoint? = null
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val px = android.graphics.Point()

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val p = point ?: return
        mapView.projection.toPixels(p, px)
        val r = 14f * mapView.context.resources.displayMetrics.density
        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), r, fill)
        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), r, ring)
    }
}

/**
 * Other users: a dot in their team colour with their callsign, a wedge for their
 * heading while moving, faded once reports are being missed. Tapping one picks it.
 */
private class ContactsOverlay(private val onTap: (Contact) -> Unit) : Overlay() {
    @Volatile var contacts: List<Contact> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
    }
    private val wedge = Path()
    private val px = android.graphics.Point()

    override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = mapView.context.resources.displayMetrics
        val r = 9f * d.density
        ring.strokeWidth = 2f * d.density
        label.textSize = 12f * d.scaledDensity
        halo.textSize = label.textSize
        halo.strokeWidth = 3f * d.density
        val now = System.currentTimeMillis()
        for (c in contacts) {
            mapView.projection.toPixels(GeoPoint(c.lat, c.lon), px)
            val x = px.x.toFloat()
            val y = px.y.toFloat()
            val alpha = if (c.fading(now)) 110 else 255
            fill.color = (c.team?.argb ?: NO_TEAM).toInt()
            fill.alpha = alpha
            ring.alpha = alpha
            label.alpha = alpha
            halo.alpha = alpha * 3 / 4
            val course = c.courseDeg
            if (course != null && (c.speedMps ?: 0.0) >= MOVING_MPS) {
                // Screen y grows downwards; 0 deg is north (up).
                val a = Math.toRadians(course)
                val tipX = x + (r * 2.1f * sin(a)).toFloat()
                val tipY = y - (r * 2.1f * cos(a)).toFloat()
                val side = Math.toRadians(32.0)
                wedge.reset()
                wedge.moveTo(tipX, tipY)
                wedge.lineTo(x + (r * sin(a - side)).toFloat(), y - (r * cos(a - side)).toFloat())
                wedge.lineTo(x + (r * sin(a + side)).toFloat(), y - (r * cos(a + side)).toFloat())
                wedge.close()
                canvas.drawPath(wedge, fill)
            }
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, ring)
            val ty = y + r + label.textSize + 2f * d.density
            canvas.drawText(c.callsign, x, ty, halo)
            canvas.drawText(c.callsign, x, ty, label)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val reach = 28f * mapView.context.resources.displayMetrics.density
        val hit = contacts.map { c ->
            mapView.projection.toPixels(GeoPoint(c.lat, c.lon), px)
            c to hypot(px.x - e.x, px.y - e.y)
        }.filter { it.second <= reach }.minByOrNull { it.second }?.first ?: return false
        onTap(hit)
        return true
    }

    companion object {
        private const val NO_TEAM = 0xFF9E9E9EL
        private const val MOVING_MPS = 0.5
    }
}
