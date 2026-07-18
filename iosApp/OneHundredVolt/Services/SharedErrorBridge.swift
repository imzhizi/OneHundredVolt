import Foundation
import Shared

// MARK: - Shared 错误类型别名（避免命名冲突）
typealias SharedNotLoggedInError = Shared.ApiError.NotLoggedIn
typealias SharedHttpError = Shared.ApiError.HttpError

/// Shared 错误 → iOS Notification 适配层
///
/// v1.6：当 iOS 调用 Shared.AfdianApiService 时（未来扩展），Kotlin ApiError
/// 通过 SharedErrorBridge 捕获并转换为 iOS NotificationCenter 事件，
/// 让现有 view 层继续以 Notification.Name.tokenExpired 等形式响应。
///
/// 当前 iOS 的同步和 API 包装都使用 Shared 实现，桥接层负责把 Kotlin
/// 异常转换为现有的 NotificationCenter 事件。
enum SharedErrorBridge {

    /// Shared.ApiError → iOS Notification
    static func handle(_ error: Shared.KotlinException) {
        if error is SharedNotLoggedInError {
            NotificationCenter.default.post(name: .tokenExpired, object: nil)
            return
        }
        if let httpError = error as? SharedHttpError, httpError.code == 401 {
            NotificationCenter.default.post(name: .tokenExpired, object: nil)
            return
        }
    }
}
