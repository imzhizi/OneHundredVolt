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
import com.ohv.android.features.navigation.AppNavHost
import com.ohv.android.theme.OhvTheme
import com.ohv.shared.platform.SecureStorage
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // App 始终深色 UI，状态栏图标必须为浅色（白色），不管系统主题
        // 必须在 enableEdgeToEdge 之后设置，否则会被覆盖
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
                }
            }
        }
    }
}
