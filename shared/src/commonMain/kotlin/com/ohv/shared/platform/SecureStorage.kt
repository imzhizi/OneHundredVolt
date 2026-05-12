package com.ohv.shared.platform

/**
 * 安全存储接口（expect/actual）
 * iOS actual → Keychain
 * Android actual → EncryptedSharedPreferences
 */
expect class SecureStorage() {
    fun save(key: String, value: String)
    fun get(key: String): String?
    fun delete(key: String)
}
