package com.ohv.android

import android.app.Application
import android.util.Log
import com.ohv.shared.platform.AndroidContext
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.platform.AppUpdater
import com.ohv.shared.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OhvApplication : Application() {

    companion object {
        // 启动时检测到的待更新信息，MainActivity 订阅后弹窗
        private val _pendingUpdate = MutableStateFlow<AppUpdater.UpdateInfo?>(null)
        val pendingUpdate: StateFlow<AppUpdater.UpdateInfo?> = _pendingUpdate.asStateFlow()

        /** 弹窗显示后调用，清除 pending 状态 */
        fun consumePendingUpdate() {
            _pendingUpdate.value = null
        }

        // KV 存储 key
        private const val KEY_LAST_PROMPTED_VERSION = "ota_last_prompted_version_code"
        private const val KEY_LAST_PROMPTED_TIME    = "ota_last_prompted_time_ms"
        private const val PROMPT_INTERVAL_MS        = 7L * 24 * 60 * 60 * 1000 // 7 天
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kvStore by lazy { KeyValueStore() }

    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
        AudioPlayerManager.init(this)
        checkUpdateOnLaunch()
    }

    private fun checkUpdateOnLaunch() {
        val currentVersionCode = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { return }

        appScope.launch {
            try {
                val update = AppUpdater.checkForUpdate(currentVersionCode) ?: run {
                    Log.d("OTA", "Already up to date (code=$currentVersionCode)")
                    return@launch
                }

                val lastPromptedVersion = kvStore.getLong(KEY_LAST_PROMPTED_VERSION, 0L).toInt()
                val lastPromptedTime    = kvStore.getLong(KEY_LAST_PROMPTED_TIME, 0L)
                val now                 = System.currentTimeMillis()
                val isNewVersion        = update.versionCode > lastPromptedVersion
                val isWeekPassed        = (now - lastPromptedTime) >= PROMPT_INTERVAL_MS

                if (isNewVersion || isWeekPassed) {
                    Log.i("OTA", "Prompting update: v${update.versionName}" +
                            " (newVersion=$isNewVersion, weekPassed=$isWeekPassed)")
                    // 记录本次提示
                    kvStore.putLong(KEY_LAST_PROMPTED_VERSION, update.versionCode.toLong())
                    kvStore.putLong(KEY_LAST_PROMPTED_TIME, now)
                    _pendingUpdate.value = update
                } else {
                    Log.d("OTA", "Update v${update.versionName} available but skipped" +
                            " (prompted ${(now - lastPromptedTime) / 3600_000}h ago)")
                }
            } catch (e: Exception) {
                Log.w("OTA", "Update check failed: ${e.message}")
            }
        }
    }
}
