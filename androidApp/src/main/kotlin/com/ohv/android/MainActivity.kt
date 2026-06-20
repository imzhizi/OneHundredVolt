package com.ohv.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohv.android.components.UpdateDialog
import com.ohv.android.features.navigation.AppNavHost
import com.ohv.android.theme.OhvTheme

class MainActivity : ComponentActivity() {

    /**
     * Android 13+ (API 33) 需要运行时申请 POST_NOTIFICATIONS 权限，
     * 否则 MediaSessionService 的前台通知不会显示，
     * 极端情况下 Android 14+ 可能直接杀进程。
     *
     * 用户拒绝后下次启动还会再次询问，符合 Android 13+ 规范。
     */
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 拒绝后 MediaSessionService 仍会播放，但锁屏通知不显示。
        // 不再二次弹窗，避免骚扰用户。
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        requestNotificationPermissionIfNeeded()

        setContent {
            OhvTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    // v1.6：从 StateFlow 读取 hasToken，避免主线程直接读 EncryptedSharedPreferences
                    // 导致冷启动卡顿（首次访问需解密 master key，50-200ms）。
                    // OhvApplication.onCreate 在 IO 线程预读并更新 StateFlow。
                    val hasToken by OhvApplication.hasToken.collectAsStateWithLifecycle()

                    AppNavHost(
                        isLoggedIn = hasToken,
                        onLoginComplete = { OhvApplication.setLoggedIn(true) },
                        onLogout = { OhvApplication.setLoggedIn(false) }
                    )

                    // 启动时更新弹窗（频率控制在 OhvApplication 里）
                    val pendingUpdate by OhvApplication.pendingUpdate.collectAsState()
                    val update = pendingUpdate
                    if (update != null) {
                        UpdateDialog(
                            updateInfo = update,
                            onDismiss = { OhvApplication.consumePendingUpdate() },
                            onInstallReady = { OhvApplication.consumePendingUpdate() }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        // POST_NOTIFICATIONS 仅 Android 13+ (API 33) 需要运行时申请
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}