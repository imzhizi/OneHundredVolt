import Foundation
import Shared

/// 同步服务（v1.7 Phase C.3：iOS 切到 Shared.SyncService）
///
/// 之前 iOS 自行编排 DatabaseService + AfdianAPIService 的同步流程
/// 现改用 Shared.SyncService 统一编排：
///  - iOS 只剩薄包装 + iOS 特有的 @Observable 状态镜像 + Notification 桥接

// MARK: - iOS SyncState（与 Shared.SyncState 桥接）

enum SyncState {
    case idle
    case syncing(message: String, progress: Double)
    case success
    case failed

    /// Shared.SyncState → iOS SyncState
    static func from(_ shared: Shared.SyncState) -> SyncState {
        if shared is Shared.SyncState.Idle { return .idle }
        if shared is Shared.SyncState.Success { return .success }
        if let s = shared as? Shared.SyncState.Syncing {
            return .syncing(message: s.message, progress: s.progress)
        }
        if shared is Shared.SyncState.Failed { return .failed }
        return .idle
    }
}

// MARK: - SyncService 主体

@Observable
final class SyncService {

    static let shared = SyncService()

    var state: SyncState = .idle {
        didSet { if case .success = state { postCompleteNotification() } }
    }

    var isSyncing: Bool {
        if case .syncing = state { return true }
        return false
    }

    var lastSyncDate: Date? {
        // Shared.lastSyncDate 返回 KotlinLong?，需取 int64Value
        guard let ms = backend.lastSyncDate else { return nil }
        if ms.int64Value <= 0 { return nil }
        return Date(timeIntervalSince1970: TimeInterval(ms.int64Value) / 1000.0)
    }

    private let backend: Shared.SyncService

    init(backend: Shared.SyncService? = nil) {
        if let backend {
            self.backend = backend
        } else {
            let api = Shared.AfdianApiService(secureStorage: Shared.SecureStorage())
            let db = Shared.DatabaseService.companion.shared
            let kv = Shared.KeyValueStore()
            self.backend = Shared.SyncService(api: api, db: db, kvStore: kv)
        }
        registerCallback()
        self.backend.recoverIfNeeded()
    }

    private func registerCallback() {
        backend.setOnStateChangedCallback { [weak self] sharedState in
            Task { @MainActor in
                if let failed = sharedState as? Shared.SyncState.Failed {
                    SharedErrorBridge.handle(failed.error)
                }
                self?.state = SyncState.from(sharedState)
            }
        }
    }

    // MARK: - 启动恢复

    func recoverIfNeeded() {
        backend.recoverIfNeeded()
    }

    // MARK: - 同步入口

    func fullSync(selectedCreatorIds: [String]) async {
        do {
            try await backend.fullSync(selectedCreatorIds: selectedCreatorIds)
        } catch {
            // 同步失败由 Shared 内置的 Failed state 处理
        }
    }

    // MARK: - 通知

    private func postCompleteNotification() {
        Task { @MainActor in
            NotificationCenter.default.post(name: .didSyncComplete, object: nil)
        }
    }
}

/// iOS 启动检查和 Debug 手工检查共用的增量更新入口。
@Observable
final class IncrementalUpdateCoordinator {
    static let shared = IncrementalUpdateCoordinator()

    private let api = AfdianAPIService.shared
    private let backend: Shared.IncrementalUpdateService

    private init() {
        backend = Shared.IncrementalUpdateService(
            api: AfdianAPIService.shared.sharedBackend,
            db: Shared.DatabaseService.companion.shared
        )
    }

    func checkDue() async -> String {
        guard api.isLoggedIn else { return "未登录，跳过增量检查" }
        do {
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let result = try await backend.checkDueAlbums(nowMs: nowMs)
            let status = statusText(result)
            NotificationCenter.default.post(name: .didIncrementalUpdate, object: nil)
            return status
        } catch {
            return "增量检查失败：\(error.localizedDescription)"
        }
    }

    func checkAll() async -> String {
        guard api.isLoggedIn else { return "未登录，无法检查" }
        do {
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let result = try await backend.checkAllAlbums(nowMs: nowMs)
            let status = statusText(result)
            NotificationCenter.default.post(name: .didIncrementalUpdate, object: nil)
            return status
        } catch {
            return "增量检查失败：\(error.localizedDescription)"
        }
    }

    func markAllDue() {
        Shared.DatabaseService.companion.shared.markAllAlbumsDue()
    }

    private func statusText(_ result: Shared.IncrementalUpdateResult) -> String {
        if result.failures.isEmpty {
            return "完成：新增 \(result.addedCount)，变更 \(result.changedCount)"
        }
        return "完成：新增 \(result.addedCount)，失败 \(result.failures.count)"
    }
}

extension Notification.Name {
    static let didSyncComplete = Notification.Name("OneHundredVolt.didSyncComplete")
    static let didIncrementalUpdate = Notification.Name("OneHundredVolt.didIncrementalUpdate")
}
