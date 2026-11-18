package com.houvven.guise.xposed.config

import com.houvven.guise.xposed.PackageConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class ModuleConfig(
    @Transient var packageName: String = "",
    var enabled: Boolean = true,
    var brand: String = "",
    var manufacturer: String = "",
    var model: String = "",
    var product: String = "",
    var device: String = "",
    var board: String = "",
    var hardware: String = "",
    var buildId: String = "",
    var androidVersion: String = "",
    var sdkInt: Int = -1,
    var densityDpi: Int = -1,
    var networkType: Int = HooksValue.NET_UNHOOK,
    var fingerPrint: String = "",
    var wifiSSID: String = "",
    var wifiBSSID: String = "",
    var wifiMacAddress: String = "",
    var simOperator: String = "",
    var simOperatorName: String = "",
    var simCountry: String = "",
    var imei: String = "",
    var phoneNum: String = "",
    var androidId: String = "",
    var lac: Int = -1,
    var cid: Int = -1,
    var language: String = "",
    var timeZone: String = "",
    var longitude: Double = -1.0,
    var latitude: Double = -1.0,
    // Coordinate systems of the values above (input) and of the value handed to the
    // target app (output). See [CoordTransform]. Defaults keep WGS-84 -> WGS-84 = raw.
    var inputCoordSystem: String = HooksValue.COORD_WGS84,
    var outputCoordSystem: String = HooksValue.COORD_WGS84,
    var randomOffset: Boolean = false,
    var randomOffsetMeters: Double = DEFAULT_RANDOM_OFFSET_METERS,
    var makeWifiLocationFail: Boolean = false,
    var makeCellLocationFail: Boolean = false,

    var versionCode: Int = -1,
    var versionName: String = "",
    var batteryLevel: Int = -1,
    var screenshotsFlag: Int = HooksValue.SCREENSHOTS_UNHOOK,
    var passContacts: Boolean = false,
    var passPhoto: Boolean = false,
    var passVideo: Boolean = false,
    var passAudio: Boolean = false,
    var passApplications: Boolean = false,
) {
    val isEnable: Boolean get() = enabled

    /**
     * Latitude/longitude converted from [inputCoordSystem] to [outputCoordSystem],
     * ready to be handed to the target app. When either coordinate is unset (-1.0) the
     * value is returned unchanged.
     */
    fun outputCoordinates(): Pair<Double, Double> {
        if (latitude == -1.0 || longitude == -1.0) return latitude to longitude
        if (inputCoordSystem == outputCoordSystem) return latitude to longitude
        return CoordTransform.convert(latitude, longitude, inputCoordSystem, outputCoordSystem)
    }

    fun toJson() = json.encodeToString(serializer(), this)

    fun hasSameParameters(other: ModuleConfig): Boolean =
        copy(packageName = "", enabled = false) == other.copy(packageName = "", enabled = false)

    fun parameterSignature(): String =
        json.encodeToString(serializer(), copy(packageName = "", enabled = false))

    /**
     * 以本配置（模板）为准，保留 [existing] 中**本配置未显式指定**的字段。
     *
     * 序列化时 `encodeDefaults = false`，所以「本配置的 JSON 里没有某个 key」
     * 就等价于「模板没有对该字段表态」。应用模板时若整体覆盖，
     * 用户在应用配置里额外设置、模板又没写的字段（例如为了测试一致性
     * 而设的 `networkType`）会被冲成默认值。这里把这些字段沿用过来。
     *
     * 注意：模板显式写了某字段时仍以模板为准，因此模板依然能正常改写/重置
     * 它自己声明过的参数。
     */
    fun mergedOnto(existing: ModuleConfig): ModuleConfig {
        val out = copy()
        val explicitKeys = runCatching {
            json.parseToJsonElement(toJson()).jsonObject.keys
        }.getOrNull() ?: return out
        val existingObj = runCatching {
            json.parseToJsonElement(existing.toJson()).jsonObject
        }.getOrNull() ?: return out

        for (key in existingObj.keys) {
            if (key in explicitKeys) continue
            if (key == "enabled") continue
            val field = runCatching { ModuleConfig::class.java.getDeclaredField(key) }
                .getOrNull() ?: continue
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                field.set(out, field.get(existing))
            }
        }
        return out
    }

    fun toModuleConfigState() = ModuleConfigState.of(this)

    companion object {
        const val DEFAULT_RANDOM_OFFSET_METERS = 5.0
        const val MAX_RANDOM_OFFSET_METERS = 100_000.0

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(value: String) = json.decodeFromString(serializer(), value)

        fun get(packageName: String): ModuleConfig {
            val config = PackageConfig.safePrefs.getString(packageName, null)?.let { fromJson(it) }
            config?.packageName = packageName
            return config ?: ModuleConfig(packageName = packageName, enabled = false)
        }

        fun getAllSaved(): List<ModuleConfig> =
            PackageConfig.safePrefs.all.mapNotNull { (packageName, value) ->
                val json = value as? String ?: return@mapNotNull null
                runCatching { fromJson(json) }.getOrNull()?.apply {
                    this.packageName = packageName
                }
            }
    }
}
