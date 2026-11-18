package com.houvven.guise.xposed.config

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinates conversion between WGS-84 / GCJ-02 / BD-09.
 *
 * - WGS-84: standard GPS coordinates used by Android [android.location.Location],
 *   Google/OSM, and by VirtualRegion's stored values.
 * - GCJ-02: "Mars coordinates" used by AMap/高德, Tencent/腾讯, WeChat 微信, 腾讯地图.
 * - BD-09: Baidu coordinates.
 *
 * Some Chinese apps (notably WeChat / Tencent) treat the location handed to them as
 * GCJ-02 directly instead of converting from WGS-84. That is why feeding WGS-84 to
 * WeChat lands a few hundred meters off; feeding the GCJ-02 value fixes it.
 *
 * The math is the well-known public-domain algorithm (same as coordtransform).
 */
object CoordTransform {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84 -> GCJ-02. Returns (lat, lon). */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    /** GCJ-02 -> WGS-84 (iterative inverse). Returns (lat, lon). */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var wgLat = lat
        var wgLon = lon
        repeat(3) {
            val (gLat, gLon) = wgs84ToGcj02(wgLat, wgLon)
            wgLat += lat - gLat
            wgLon += lon - gLon
        }
        return wgLat to wgLon
    }

    /** GCJ-02 -> BD-09. Returns (lat, lon). */
    fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        val bdLon = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return bdLat to bdLon
    }

    /** BD-09 -> GCJ-02. Returns (lat, lon). */
    fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLon = z * cos(theta)
        val gcjLat = z * sin(theta)
        return gcjLat to gcjLon
    }

    fun wgs84ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val (gLat, gLon) = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gLat, gLon)
    }

    fun bd09ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        val (gLat, gLon) = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gLat, gLon)
    }

    private fun toWgs84(lat: Double, lon: Double, from: String): Pair<Double, Double> = when (from) {
        HooksValue.COORD_GCJ02 -> gcj02ToWgs84(lat, lon)
        HooksValue.COORD_BD09 -> bd09ToWgs84(lat, lon)
        else -> lat to lon
    }

    private fun fromWgs84(lat: Double, lon: Double, to: String): Pair<Double, Double> = when (to) {
        HooksValue.COORD_GCJ02 -> wgs84ToGcj02(lat, lon)
        HooksValue.COORD_BD09 -> wgs84ToBd09(lat, lon)
        else -> lat to lon
    }

    /**
     * Convert a coordinate from the [from] system to the [to] system.
     * Unknown/blank codes are treated as WGS-84. Returns (lat, lon).
     */
    fun convert(lat: Double, lon: Double, from: String, to: String): Pair<Double, Double> {
        val (wLat, wLon) = toWgs84(lat, lon, from)
        return fromWgs84(wLat, wLon, to)
    }
}
