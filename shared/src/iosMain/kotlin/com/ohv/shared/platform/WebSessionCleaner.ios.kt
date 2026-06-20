package com.ohv.shared.platform

import platform.Foundation.NSHTTPCookieStorage

/**
 * iOS WebSessionCleaner 实现
 *
 * 使用 NSHTTPCookieStorage 删除 afdian.com 域名的 cookie。
 * 不影响其他站点的会话。
 *
 * 注意：iOS 端当前 AfdianAPIService.swift 直接使用 WKWebsiteDataStore
 * 并按 displayName 过滤（详见 iosApp 端改动）。本类用于 Batch 5 接入
 * Shared framework 后统一调用，行为与 Swift 端一致。
 */
actual class WebSessionCleaner {
    actual suspend fun clearAfdianSession() {
        val storage = NSHTTPCookieStorage.sharedHTTPCookieStorage
        val cookies = storage.cookies ?: return
        cookies
            .filter { cookie ->
                val domain = cookie.domain ?: ""
                domain.contains("afdian.com") || domain.contains("afdian")
            }
            .forEach { storage.deleteCookie(it) }
    }
}