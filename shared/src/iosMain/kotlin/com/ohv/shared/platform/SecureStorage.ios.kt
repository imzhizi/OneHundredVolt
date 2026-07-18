@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ohv.shared.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS actual：使用 Keychain 存储敏感数据（v1.7 Defer 2）
 *
 * 实现说明：使用 CoreFoundation 原生字典组装查询，确保 Security.framework
 * 收到的 kCFBooleanTrue 和 CFString 类型与 C API 契约一致。
 *
 * 历史：
 *  - v1.6 phase 1：临时降级为 NSUserDefaults（KMP cinterop Keychain 编译失败）
 *  - v1.7 Defer 2：用 CoreFoundation 字典绕过 Kotlin/Native ObjC 容器桥接差异
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SecureStorage actual constructor() {

    private val service = "com.onehundredvolt.afdian"

    private fun baseQuery(key: String): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(null, 0, null, null)!!
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(service))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(key))
        return query
    }

    actual fun save(key: String, value: String) {
        delete(key)
        val data = (value as NSString).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding) ?: return

        val query = baseQuery(key)
        CFDictionarySetValue(query, kSecValueData, CFBridgingRetain(data))

        SecItemAdd(query as CFDictionaryRef, null)
    }

    actual fun get(key: String): String? {
        val query = baseQuery(key)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        val result = memScoped {
            val resultRef = alloc<COpaquePointerVar>()
            val status = SecItemCopyMatching(query as CFDictionaryRef, resultRef.ptr)
            if (status != errSecSuccess) return null
            val rawValue = resultRef.value?.rawValue ?: return null
            interpretObjCPointerOrNull<NSData>(rawValue)
        }

        return result?.let { nsData ->
            val len = nsData.length.toInt()
            val bytes = ByteArray(len)
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
            }
            bytes.decodeToString()
        }
    }

    actual fun delete(key: String) {
        val query = baseQuery(key)

        SecItemDelete(query as CFDictionaryRef)
    }
}
