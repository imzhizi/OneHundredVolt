package com.ohv.shared.platform

/**
 * 键值持久化接口（expect/actual）
 * iOS actual → NSUserDefaults
 * Android actual → SharedPreferences
 */
expect class KeyValueStore() {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun putLong(key: String, value: Long)
    fun getLong(key: String, default: Long = 0L): Long
    fun putFloat(key: String, value: Float)
    fun getFloat(key: String, default: Float = 0f): Float
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun remove(key: String)
    fun clear()
}
