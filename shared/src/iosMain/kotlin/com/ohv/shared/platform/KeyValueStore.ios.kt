package com.ohv.shared.platform

import platform.Foundation.NSUserDefaults

/**
 * iOS actual：使用 NSUserDefaults 实现键值持久化
 */
actual class KeyValueStore actual constructor() {

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun putString(key: String, value: String) = defaults.setObject(value, key)
    actual fun getString(key: String): String? = defaults.stringForKey(key)

    actual fun putLong(key: String, value: Long) = defaults.setDouble(value.toDouble(), key)
    actual fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) != null) defaults.doubleForKey(key).toLong() else default

    actual fun putFloat(key: String, value: Float) = defaults.setFloat(value, key)
    actual fun getFloat(key: String, default: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else default

    actual fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, key)
    actual fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default

    actual fun remove(key: String) = defaults.removeObjectForKey(key)
    actual fun clear() {
        // 只清除 app 自己的 key，不清除系统 key
        val dict = defaults.dictionaryRepresentation()
        dict.keys.forEach { key -> defaults.removeObjectForKey(key as String) }
    }
}
