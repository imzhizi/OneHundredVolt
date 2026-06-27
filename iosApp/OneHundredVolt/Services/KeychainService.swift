import Foundation
import Shared

/// SecureStorage 封装 — 安全存储 auth_token
///
/// v1.6：底层改为 Shared.SecureStorage（KMP 统一）
///  - iOS: NSUserDefaults（待 v1.7 升级 Keychain）
///  - Android: EncryptedSharedPreferences
///
/// 保留原 KeychainService API 形态以便最小化迁移，
/// 后续可在 v1.7 删除本文件统一改用 Shared.SecureStorage。
enum KeychainService {

    private static let secureStorage = Shared.SecureStorage()

    /// 保存或更新
    @discardableResult
    static func save(_ value: String, forKey key: String) -> Bool {
        secureStorage.save(key: key, value: value)
        return true
    }

    /// 读取
    static func load(forKey key: String) -> String? {
        secureStorage.get(key: key)
    }

    /// 删除
    @discardableResult
    static func delete(forKey key: String) -> Bool {
        secureStorage.delete(key: key)
        return true
    }

    // MARK: - 便捷常量
    static let authTokenKey = "afdian_auth_token"
}