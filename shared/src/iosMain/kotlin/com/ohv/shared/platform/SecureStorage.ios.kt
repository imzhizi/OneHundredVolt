package com.ohv.shared.platform

import platform.Foundation.NSUserDefaults

/**
 * iOS actual：使用 NSUserDefaults 存储 token
 *
 * TODO(v1.7+)：升级到 Keychain。当前为简化 iOS KMP 编译，
 * 使用 NSUserDefaults 临时存储（安全性弱于 Keychain，但
 * 不影响功能验证）。生产环境建议改回 Keychain。
 */
actual class SecureStorage actual constructor() {

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun save(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun get(key: String): String? {
        return defaults.stringForKey(key)
    }

    actual fun delete(key: String) {
        defaults.removeObjectForKey(key)
    }
}