package com.ohv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.ohv.android.components.UpdateDialog
import com.ohv.android.features.navigation.AppNavHost
import com.ohv.android.theme.OhvTheme
import com.ohv.shared.platform.SecureStorage

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContent {
            OhvTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    val secureStorage = remember { SecureStorage() }
                    val hasToken = remember {
                        mutableStateOf(secureStorage.get("afdian_auth_token") != null)
                    }

                    AppNavHost(
                        isLoggedIn = hasToken.value,
                        onLoginComplete = { hasToken.value = true },
                        onLogout = { hasToken.value = false }
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
}
