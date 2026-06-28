@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ohv.shared.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDictionaryRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setObject
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
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
 * 实现说明：
 *  - 直接用 NSMutableDictionary（ObjC 类）组装查询
 *  - kSecClass* 常量转 NSString 后传给 setObject（解决 NSCopyingProtocol 类型不匹配）
 *  - SecItemAdd/Copy/Delete 接受 NSMutableDictionary（自动桥接到 CFDictionaryRef）
 *
 * 历史：
 *  - v1.6 phase 1：临时降级为 NSUserDefaults（KMP cinterop Keychain 编译失败）
 *  - v1.7 Defer 2：用 NSMutableDictionary + 显式 NSString 转换绕过 CFBridgingRetain
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SecureStorage actual constructor() {

    private val service = "com.onehundredvolt.afdian"

    // kSecClass* 常量在 KMP 中是 CPointer<__CFString>?，setObject 需要 NSCopyingProtocol
    // 用 as NSString 转换（Foundation 的 NSString 兼容 CFString）
    private val classKey = kSecClass as NSString
    private val classValue = kSecClassGenericPassword as NSString
    private val serviceKey = kSecAttrService as NSString
    private val accountKey = kSecAttrAccount as NSString
    private val valueDataKey = kSecValueData as NSString
    private val returnDataKey = kSecReturnData as NSString
    private val matchLimitKey = kSecMatchLimit as NSString
    private val matchLimitOneValue = kSecMatchLimitOne as NSString

    actual fun save(key: String, value: String) {
        delete(key)
        val data = (value as NSString).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding) ?: return

        val query = NSMutableDictionary()
        query.setObject(classValue, forKey = classKey)
        query.setObject(service, forKey = serviceKey)
        query.setObject(key, forKey = accountKey)
        query.setObject(data, forKey = valueDataKey)

        SecItemAdd(CFBridgingRetain(query) as CFDictionaryRef, null)
    }

    actual fun get(key: String): String? {
        val query = NSMutableDictionary()
        query.setObject(classValue, forKey = classKey)
        query.setObject(service, forKey = serviceKey)
        query.setObject(key, forKey = accountKey)
        query.setObject(true, forKey = returnDataKey)
        query.setObject(matchLimitOneValue, forKey = matchLimitKey)

        val raw = SecItemCopyMatching(CFBridgingRetain(query) as CFDictionaryRef, null)
        val result = CFBridgingRetain(raw) as? NSData
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
        val query = NSMutableDictionary()
        query.setObject(classValue, forKey = classKey)
        query.setObject(service, forKey = serviceKey)
        query.setObject(key, forKey = accountKey)

        SecItemDelete(CFBridgingRetain(query) as CFDictionaryRef)
    }
}