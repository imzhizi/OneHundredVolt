package com.ohv.shared.platform

import platform.Foundation.NSHTTPCookieStorage

/**
 * iOS WebSessionCleaner 实现
 *
 * 使用 NSHTTPCookieStorage 删除 afdian.com 域名的 cookie。
 * 不影响其他站点的会话。
 */
actual class WebSessionCleaner {
    actual suspend fun clearAfdianSession() {
        val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val cookies = storage.cookies ?: return
        for (cookieAny in cookies) {
            val cookie = cookieAny as? platform.Foundation.NSHTTPCookie ?: continue
            val domain = cookie.domain
            if (domain.contains("afdian")) {
                storage.deleteCookie(cookie)
            }
        }
    }
}