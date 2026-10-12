package com.taksolutions.takbeacon.geo

import com.taksolutions.takbeacon.data.CoordFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** WGS84 → UTM/MGRS and human-readable coordinate formatting. */
object Coordinates {

    fun format(lat: Double, lon: Double, format: CoordFormat): String = when (format) {
        CoordFormat.DD -> "%.6f, %.6f".format(lat, lon)
        CoordFormat.DMS -> "${dms(lat, "N", "S")}  ${dms(lon, "E", "W")}"
        CoordFormat.MGRS -> mgrs(lat, lon) ?: "%.6f, %.6f".format(lat, lon)
    }

    fun dms(value: Double, pos: String, neg: String): String {
        val a = abs(value)
        var d = floor(a).toInt()
        var m = floor((a - d) * 60).toInt()
        var s = (a - d - m / 60.0) * 3600
        if (s >= 59.995) { s = 0.0; m += 1 }
        if (m >= 60) { m = 0; d += 1 }
        return "%d°%02d'%05.2f\"%s".format(d, m, s, if (value >= 0) pos else neg)
    }

    data class Utm(val zone: Int, val band: Char, val easting: Double, val northing: Double)

    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWXX"

    /** UTM for -80 ≤ lat ≤ 84; null in the polar (UPS) regions. */
    fun utm(lat: Double, lon: Double): Utm? {
        if (lat < -80.0 || lat > 84.0 || lon < -180.0 || lon > 180.0) return null
        var zone = (floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        // Norway and Svalbard exceptions.
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32
        if (lat >= 72.0) {
            zone = when {
                lon >= 0.0 && lon < 9.0 -> 31
                lon >= 9.0 && lon < 21.0 -> 33
                lon >= 21.0 && lon < 33.0 -> 35
                lon >= 33.0 && lon < 42.0 -> 37
                else -> zone
            }
        }
        val band = BANDS[floor((lat + 80.0) / 8.0).toInt().coerceIn(0, 20)]

        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        val phi = Math.toRadians(lat)
        val lambda0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val n = A / sqrt(1 - e2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val a = cos(phi) * (Math.toRadians(lon) - lambda0)
        val m = A * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi -
            (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi) +
            (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi) -
            (35 * e2 * e2 * e2 / 3072) * sin(6 * phi))

        val easting = K0 * n * (a + (1 - t + c) * a * a * a / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a * a * a * a * a / 120) + 500000.0
        var northing = K0 * (m + n * tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a * a * a * a * a * a / 720))
        if (lat < 0) northing += 10_000_000.0
        return Utm(zone, band, easting, northing)
    }

    private val COL_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    /** MGRS at 1 m precision, e.g. "18S UJ 23487 06483"; null in polar regions. */
    fun mgrs(lat: Double, lon: Double): String? {
        val u = utm(lat, lon) ?: return null
        val e = floor(u.easting).toLong()
        val n = floor(u.northing).toLong()
        val col = COL_SETS[(u.zone - 1) % 3][((e / 100_000).toInt() - 1).coerceIn(0, 7)]
        val rowIdx = ((n / 100_000).toInt() + if (u.zone % 2 == 0) 5 else 0) % 20
        val row = ROWS[rowIdx]
        return "%d%c %c%c %05d %05d".format(u.zone, u.band, col, row, e % 100_000, n % 100_000)
    }

    /** Compass bearing to an 8-point name. */
    fun cardinal(deg: Float): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
}
