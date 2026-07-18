package com.ohv.android.features.onboarding

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ohv.android.theme.OhvColors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.platform.SecureStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页（对应 iOS LoginWebView.swift）
 *
 * 流程：
 *  1. WebView 加载 https://afdian.com/login
 *  2. 页面跳离 /login 后，轮询 CookieManager 提取 auth_token
 *  3. 找到 token → 存入 SecureStorage → 导航到 CreatorSelectScreen
 *  4. 用户也可手动点「已登录，继续」触发同样逻辑
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginWebViewScreen(
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val secureStorage = remember { SecureStorage() }
    val scope = rememberCoroutineScope()

    var isPageLoading by remember { mutableStateOf(true) }
    var isConfirming by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    // ── 从 CookieManager 提取 token ──────────────────────────────────────────
    fun extractToken(): String? {
        val cookieStr = CookieManager.getInstance().getCookie("afdian.com") ?: return null
        val candidates = listOf("auth_token", "authToken", "token", "auth", "session", "sid")
        for (name in candidates) {
            val regex = Regex("(?:^|;\\s*)${Regex.escape(name)}=([^;]+)")
            val match = regex.find(cookieStr)
            if (match != null) return match.groupValues[1]
        }
        // 宽泛匹配：含 token 或 auth 的 cookie
        val tokenRegex = Regex("(?:^|;\\s*)(\\w*(?:token|auth)\\w*)=([^;]+)", RegexOption.IGNORE_CASE)
        return tokenRegex.find(cookieStr)?.groupValues?.get(2)
    }

    // ── 轮询（最多 20 次，每次 500ms）────────────────────────────────────────
    fun startPolling() {
        scope.launch {
            repeat(20) {
                val token = extractToken()
                if (token != null) {
                    secureStorage.save(AfdianApiService.AUTH_TOKEN_KEY, token)
                    onLoginSuccess()
                    return@launch
                }
                delay(500)
            }
            // 轮询结束仍未找到：保留页面，用户可点击「已登录，继续」手动验证
        }
    }

    // ── 手动确认 ─────────────────────────────────────────────────────────────
    fun confirmLogin() {
        isConfirming = true
        val token = extractToken()
        if (token != null) {
            secureStorage.save(AfdianApiService.AUTH_TOKEN_KEY, token)
            isConfirming = false
            onLoginSuccess()
        } else {
            isConfirming = false
            val allCookies = CookieManager.getInstance().getCookie("afdian.com") ?: ""
            errorMessage = if (allCookies.isEmpty()) {
                "未检测到爱发电 Cookie，请确认已在网页中完成登录"
            } else {
                "已检测到 Cookie，但未找到登录凭据：\n$allCookies"
            }
            showError = true
        }
    }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "登录爱发电",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = OhvColors.Accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(OhvColors.Background)
        ) {
            // 加载指示器
            if (isPageLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = OhvColors.Accent,
                        strokeWidth = 2.dp
                    )
                    Text("正在加载...", color = OhvColors.SecondaryText, fontSize = 13.sp)
                }
            }

            // WebView（占满剩余空间）
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isPageLoading = false
                                CookieManager.getInstance().flush()

                                val path = url?.let { android.net.Uri.parse(it).path } ?: return
                                val isLoginPage = path == "/login" || path.startsWith("/login")
                                if (!isLoginPage && url.contains("afdian.com")) {
                                    startPolling()
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }

                        loadUrl("https://afdian.com/login")
                    }
                }
            )

            // 底部按钮区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OhvColors.Background)
            ) {
                HorizontalDivider(color = OhvColors.Separator)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 主按钮：已登录，继续
                    Button(
                        onClick = { confirmLogin() },
                        enabled = !isConfirming,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OhvColors.Accent,
                            contentColor = Color.Black
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        if (isConfirming) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp
                                )
                                Text("验证中...", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text("已登录，继续 →", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }

                }
            }
        }
    }

    // 错误弹窗
    if (showError) {
        AlertDialog(
            onDismissRequest = { showError = false },
            title = { Text("验证失败", color = OhvColors.White) },
            text = { Text(errorMessage, color = OhvColors.SecondaryText, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showError = false }) {
                    Text("好", color = OhvColors.Accent)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }
}
