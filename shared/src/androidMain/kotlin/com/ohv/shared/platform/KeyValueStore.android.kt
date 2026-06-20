package com.ohv.shared.platform

import android.content.Context

/**
 * Android KeyValueStore 实现（基于 SharedPreferences）
 *
 * v1.6 改动：
 * 所有写操作从 .commit()（同步阻塞落盘）改为 .apply()（异步落盘）
 * 避免在主线程调用时阻塞 UI，修复 review Bug #14。
 */
actual class KeyValueStore actual constructor() {

    private val prefs by lazy {
        AndroidContext.context.getSharedPreferences("ohv_prefs", Context.MODE_PRIVATE)
    }

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
    actual fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    actual fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
    actual fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    actual fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    actual fun clear() {
        prefs.edit().clear().apply()
    }
}