package com.houvven.guise.xposed

import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import com.houvven.guise.ContextAmbient
import com.houvven.guise.xposed.config.ModuleConfig
import io.github.libxposed.service.XposedService

object PackageConfig {

    lateinit var current: ModuleConfig

    const val PREF_FILE_NAME = "XposedDeployInfo"
    private const val MIGRATION_STATE_FILE = "xposed_config_storage_v2"
    private const val LOCAL_SOURCE_INITIALIZED = "local_source_initialized"

    // 推送到远程配置库后，读回校验的重试次数/间隔
    private const val REMOTE_SYNC_ATTEMPTS = 6
    private const val REMOTE_SYNC_INTERVAL_MS = 80L

    val configurationRevision = mutableIntStateOf(0)

    val safePrefs: SharedPreferences
        get() = ContextAmbient.getSharedPreferences(PREF_FILE_NAME)

    internal fun migrateToRemotePreferences(service: XposedService) {
        val local = safePrefs
        val remote = service.getRemotePreferences(PREF_FILE_NAME)
        val migrationState = ContextAmbient.getSharedPreferences(MIGRATION_STATE_FILE)
        if (!migrationState.getBoolean(LOCAL_SOURCE_INITIALIZED, false)) {
            if (remote.all.isNotEmpty()) {
                replacePreferences(local, remote.all)
            } else {
                replacePreferences(remote, local.all)
            }
            migrationState.edit(commit = true) {
                putBoolean(LOCAL_SOURCE_INITIALIZED, true)
            }
            return
        }
        replacePreferences(remote, local.all)
    }

    /**
     * 把本地配置推送到 LSPosed 的远程配置库，并读回校验直到落地。
     *
     * 目标进程只在启动时读一次远程配置；如果推送还没落地就重启目标进程，会读到上一次
     * 的旧值（现象：切换模板后第一次重启还是上一个位置，第二次才对）。这里通过
     * “写入 → 重新取句柄读回比对 → 重试” 来保证返回 true 时远程库确实已是新值。
     *
     * @return 是否已确认推送成功（false 表示当前无服务连接或多次重试仍未同步）。
     */
    fun notifyConfigurationsChanged(): Boolean {
        val service = ContextAmbient.xposedService
        val local = safePrefs.all
        if (service != null) {
            repeat(REMOTE_SYNC_ATTEMPTS) { attempt ->
                runCatching {
                    replacePreferences(service.getRemotePreferences(PREF_FILE_NAME), local)
                }
                // 每次都重新取句柄，尽量绕开对象级缓存
                val applied = runCatching {
                    service.getRemotePreferences(PREF_FILE_NAME).all
                }.getOrNull()
                val synced = applied != null &&
                    applied.size == local.size &&
                    local.all { (key, value) -> applied[key] == value }
                if (synced) {
                    configurationRevision.intValue++
                    return true
                }
                if (attempt < REMOTE_SYNC_ATTEMPTS - 1) {
                    runCatching { Thread.sleep(REMOTE_SYNC_INTERVAL_MS) }
                }
            }
        }
        configurationRevision.intValue++
        return false
    }

    private fun replacePreferences(
        destination: SharedPreferences,
        values: Map<String, *>,
    ) {
        destination.edit(commit = true) {
            clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }

    fun doRefresh(packageName: String) {
        val prefs = ModernXposedPreferences.current
        current = prefs.getString(packageName, null)
            ?.let(ModuleConfig::fromJson)
            ?: ModuleConfig(enabled = false)
        current.packageName = packageName
    }
}

internal object ModernXposedPreferences {
    lateinit var current: SharedPreferences
}
