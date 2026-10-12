package com.taksolutions.takbeacon.ui.home

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay

/** A small OpenStreetMap view centred on the current fix. */
@Composable
fun BeaconMap(lat: Double, lon: Double, dark: Boolean, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
    val dot = remember { PositionDot() }
    DisposableEffect(map) {
        map.overlays.add(dot)
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        val p = GeoPoint(lat, lon)
        dot.point = p
        dot.fill.color = color.toArgb()
        m.controller.setCenter(p)
        m.invalidate()
    })
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
