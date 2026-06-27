import Foundation
import Shared

/// 本地播放进度存储（Shared.PlaybackProgressStore 的 iOS 适配层）
///
/// v1.6：基于 shared module 的 PlaybackProgressStore（KMP）作为后端
///  - Shared 用 KeyValueStore 替代 UserDefaults（iOS 实际仍写 NSUserDefaults）
///  - Shared 用 15s 定时落盘 + flushToDisk() 立即写（替代原 100ms 防抖）
///
/// @Observable 保留以维持 SwiftUI views 的观察语义（completedIds 需本地镜像）
@Observable
final class PlaybackProgressStore {

    static let shared = PlaybackProgressStore()

    private let backend: Shared.PlaybackProgressStore = Shared.PlaybackProgressStore.companion.shared

    /// 已播完的单集 id 集合（本地镜像，SwiftUI 直接访问以建立 @Observable 响应追踪）
    private(set) var completedIds: Set<String> = []

    private var observerTask: Task<Void, Never>?

    private init() {
        completedIds = Set(backend.completedIds())

        // 轮询同步 completedIds（Shared 后端无 AsyncSequence 暴露）
        observerTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self else { return }
                let ids = Set(self.backend.completedIds())
                await MainActor.run {
                    if self.completedIds != ids { self.completedIds = ids }
                }
            }
        }
    }

    deinit {
        observerTask?.cancel()
    }

    // MARK: - 读取

    func progress(for postId: String) -> TimeInterval {
        backend.progress(postId: postId)
    }

    func isCompleted(_ postId: String) -> Bool {
        completedIds.contains(postId)
    }

    var lastPlayedPostId: String? {
        backend.lastPlayedPostId
    }

    func lastPlayedDate(for creatorId: String) -> Date? {
        backend.lastPlayedDate(creatorId: creatorId).map {
            Date(timeIntervalSince1970: TimeInterval($0.int64Value) / 1000.0)
        }
    }

    // MARK: - 写入

    func setProgress(_ seconds: TimeInterval, for postId: String) {
        backend.setProgress(seconds: seconds, postId: postId)
    }

    /// 立即同步写磁盘（用于暂停、seek、杀进程等关键事件）
    func flushToDisk() {
        backend.flushToDisk()
    }

    func setLastPlayed(postId: String, creatorId: String?) {
        backend.setLastPlayed(postId: postId, creatorId: creatorId)
    }

    func markCompleted(for postId: String) {
        backend.markCompleted(postId: postId)
        completedIds.insert(postId)
    }

    func clearProgress(for postId: String) {
        backend.clearProgress(postId: postId)
        completedIds.remove(postId)
    }

    func clearAll() {
        backend.clearAll()
        completedIds.removeAll()
    }
}