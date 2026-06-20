package com.ohv.shared.platform

/**
 * Web 会话清理器（expect/actual）
 *
 * 用途：用户登出时清除爱发电在 WebView 中的会话痕迹，
 *      避免下次打开登录页时自动已登录。
 *
 * v1.6 新增：用于替代 iOS AfdianAPIService.logout() 中的
 * `WKWebsiteDataStore.removeData(ofTypes:)`（无差别清空所有站点数据）。
 *
 * 注意：当前 expect/actual 实现仅清理 afdian.com 域名下的会话，
 *       不影响其他站点的 cookie / 缓存。
 *
 * iOS 端在 Batch 5 接入 Shared framework 后可直接复用本类；
 * 当前 iOS AfdianAPIService.swift 暂时保留 Swift 原生实现（带 displayName 过滤）。
 */
expect class WebSessionCleaner() {
    /**
     * 清除爱发电域名的会话（cookie + 缓存）。
     * 应在用户登出后调用。
     */
    suspend fun clearAfdianSession()
}