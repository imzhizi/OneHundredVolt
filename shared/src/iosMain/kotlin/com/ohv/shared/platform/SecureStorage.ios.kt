package com.ohv.shared.platform

import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.*

/**
 * iOS actual：使用 Keychain 实现安全存储
 * 对应 iOS KeychainService.swift 的逻辑
 */
actual class SecureStorage actual constructor() {

    private val service = "com.onehundredvolt.afdian"

    actual fun save(key: String, value: String) {
        val data = value.encodeToByteArray().toNSData() ?: return
        delete(key) // 先删旧值

        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to key,
            kSecValueData to data
        )
        SecItemAdd(query as Map<Any?, *>, null)
    }

    actual fun get(key: String): String? {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to key,
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne
        )
        val result = nativeHeap.alloc<kotlinx.cinterop.ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query as Map<Any?, *>, result.ptr)
        if (status != errSecSuccess) return null
        val data = result.value as? NSData ?: return null
        return data.toByteArray().decodeToString()
    }

    actual fun delete(key: String) {
        val query = mapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to key
        )
        SecItemDelete(query as Map<Any?, *>)
    }
}

// MARK: - ByteArray <-> NSData 转换

private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return NSData.create(bytes = this.refTo(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray {
    return ByteArray(length.toInt()).also { arr ->
        arr.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
