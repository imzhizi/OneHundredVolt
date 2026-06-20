package com.ohv.shared.platform

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android WebSessionCleaner 实现
 *
 * 使用 android.webkit.CookieManager 删除所有 cookie（包括 afdian.com）。
 *
 * 注意：CookieManager.removeAllCookies 不支持按域名过滤，但 Android 上
 * afdian.com 通常通过登录后写入的 auth_token cookie 维持会话，删除全部
 * cookie 即可达成登出目的。如未来需要保留其他站点的会话，需改用
 * CookieManager.getCookie(URL) → 仅删除特定值。
 */
actual class WebSessionCleaner {
    actual suspend fun clearAfdianSession() {
        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}