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

    /// Shared.SyncState → iOS SyncState（error 信息丢失，详情需 catch 后处理）
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

extension Notification.Name {
    static let didSyncComplete = Notification.Name("OneHundredVolt.didSyncComplete")
}