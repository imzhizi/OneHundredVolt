package com.ohv.shared.platform

import android.content.Context

actual class KeyValueStore actual constructor() {

    private val prefs by lazy {
        AndroidContext.context.getSharedPreferences("ohv_prefs", Context.MODE_PRIVATE)
    }

    actual fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
    actual fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    actual fun putFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).commit() }
    actual fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    actual fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    actual fun remove(key: String) { prefs.edit().remove(key).commit() }
    actual fun clear() { prefs.edit().clear().commit() }
}
