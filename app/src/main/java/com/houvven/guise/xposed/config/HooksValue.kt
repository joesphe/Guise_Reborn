package com.houvven.guise.xposed.config

/**
 * Xposed Configuration option
 */
object HooksValue {

    const val NET_NONE = -1
    const val NET_UNHOOK = 0
    const val NET_WIFI = 1
    const val NET_MOBILE_2G = 2
    const val NET_MOBILE_3G = 3
    const val NET_MOBILE_4G = 4
    const val NET_MOBILE_5G = 5

    const val SCREENSHOTS_UNHOOK = -1
    const val SCREENSHOTS_DISABLE = 0
    const val SCREENSHOTS_ENABLE = 1

    // Coordinate systems for the location latitude/longitude conversion.
    const val COORD_WGS84 = "WGS84"   // GPS standard: Google/OSM/VirtualRegion
    const val COORD_GCJ02 = "GCJ02"   // Mars: WeChat/腾讯/高德/腾讯地图
    const val COORD_BD09 = "BD09"     // Baidu
}