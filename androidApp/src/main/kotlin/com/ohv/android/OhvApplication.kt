package com.ohv.android

import android.app.Application
import android.util.Log
import com.ohv.shared.platform.AndroidContext
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.platform.AppUpdater
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.platform.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OhvApplication : Application() {

    companion object {
        // 启动时检测到的待更新信息，MainActivity 订阅后弹窗
        private val _pendingUpdate = MutableStateFlow<AppUpdater.UpdateInfo?>(null)
        val pendingUpdate: StateFlow<AppUpdater.UpdateInfo?> = _pendingUpdate.asStateFlow()

        /**
         * v1.6 改动：是否已登录 auth_token，从 SecureStorage 异步加载
         * （避免 MainActivity 冷启动时主线程读 EncryptedSharedPreferences 卡 50-200ms）。
         *
         * 初始值为 false，Application.onCreate 后立即在 IO 线程读取并 emit 新值。
         * MainActivity 通过 collectAsStateWithLifecycle 订阅本 StateFlow。
         */
        private val _hasToken = MutableStateFlow(false)
        val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

        /** 登录成功后由 LoginWebViewScreen 调用 */
        fun setLoggedIn(loggedIn: Boolean) {
            _hasToken.value = loggedIn
        }

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
    private val secureStorage by lazy { SecureStorage() }

    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
        AudioPlayerManager.init(this)

        // v1.6：在 IO 线程异步预读 token，避免 MainActivity 主线程首次访问
        // EncryptedSharedPreferences 时的 50-200ms 卡顿（首次访问需解密 master key）
        appScope.launch {
            val token = withContext(Dispatchers.IO) {
                secureStorage.get(AfdianApiService.AUTH_TOKEN_KEY)
            }
            _hasToken.value = token != null
        }

        checkUpdateOnLaunch()
    }

    @Suppress("DEPRECATION")
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
