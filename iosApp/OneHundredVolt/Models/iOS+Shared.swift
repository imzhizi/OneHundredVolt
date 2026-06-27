import Foundation
import Shared

// MARK: - 类型别名：Shared Models（iOS 直接复用）
//
// v1.7 Phase B：用 typealias 替代 Swift struct + 转换层
//  - Swift 代码继续用 Creator / Album / AudioItem 名字
//  - 实际指向 Shared.Creator / Shared.Album / Shared.AudioItem
//  - Date/Long、Int/Int32 等类型差异通过 Swift extension 桥接

typealias Creator = Shared.Creator
typealias Album = Shared.Album
typealias AudioItem = Shared.AudioItem

// MARK: - SwiftUI Identifiable 协议
//
// Kotlin data class 的 `val id` 不会自动让 Swift 识别为 Identifiable
// 需要显式 conformance 才能在 ForEach 中使用

extension Creator: Identifiable {}
extension Album: Identifiable {}
extension AudioItem: Identifiable {}

// MARK: - Swift 友好扩展（Date / URL / 计算属性）

extension Creator {
    /// 最后同步时间（Swift Date 类型）
    var lastSyncedAtDate: Date? {
        get {
            guard let ms = lastSyncedAt else { return nil }
            return Date(timeIntervalSince1970: TimeInterval(ms.int64Value) / 1000.0)
        }
        set {
            if let newValue {
                lastSyncedAt = KotlinLong(value: Int64(newValue.timeIntervalSince1970 * 1000.0))
            } else {
                lastSyncedAt = nil
            }
        }
    }

    /// 爱发电创作者主页 URL
    var afdianPageURL: URL? { URL(string: afdianPageUrl) }
}

extension Album {
    var lastSyncedAtDate: Date? {
        get {
            guard let ms = lastSyncedAt else { return nil }
            return Date(timeIntervalSince1970: TimeInterval(ms.int64Value) / 1000.0)
        }
        set {
            if let newValue {
                lastSyncedAt = KotlinLong(value: Int64(newValue.timeIntervalSince1970 * 1000.0))
            } else {
                lastSyncedAt = nil
            }
        }
    }
}

extension AudioItem {
    var publishTimeDate: Date {
        get { Date(timeIntervalSince1970: TimeInterval(publishTime) / 1000.0) }
        set { publishTime = Int64(newValue.timeIntervalSince1970 * 1000.0) }
    }

    /// 进度百分比 0.0 ~ 1.0
    func progressRatio(progress: Double) -> Double {
        if duration <= 0 { return 0 }
        return min(1.0, progress / duration)
    }
}