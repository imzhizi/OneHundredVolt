package com.ohv.shared.platform

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android actual：使用 EncryptedSharedPreferences 实现安全存储
 */
actual class SecureStorage actual constructor() {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(AndroidContext.context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            AndroidContext.context,
            "ohv_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    actual fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun get(key: String): String? = prefs.getString(key, null)

    actual fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
