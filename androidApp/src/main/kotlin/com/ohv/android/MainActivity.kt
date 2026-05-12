package com.ohv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import com.ohv.android.features.navigation.AppNavHost
import com.ohv.android.theme.OhvTheme
import com.ohv.shared.platform.SecureStorage

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            OhvTheme {
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
