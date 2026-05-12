import Foundation

/// 本地播放进度存储（UserDefaults）
/// 完全本地管理，不与爱发电服务端同步
@Observable
final class PlaybackProgressStore {

    static let shared = PlaybackProgressStore()

    private let defaults = UserDefaults.standard
    private let progressKey       = "playback_progress_v1"
    private let lastPlayedKey     = "last_played_post_id"
    private let completedKey      = "playback_completed_v1"
    private let creatorLastPlayedKey = "creator_last_played_v1"

    /// 防抖写 UserDefaults（避免每次进度更新都同步写磁盘）
    private var persistDebounceTask: DispatchWorkItem?

    /// postId → 播放秒数
    private var cache: [String: TimeInterval] = [:]
    /// 已播完的单集 id 集合
    private(set) var completedIds: Set<String> = []

    private init() {
        if let dict = defaults.dictionary(forKey: progressKey) as? [String: Double] {
            cache = dict.mapValues { TimeInterval($0) }
        }
        if let arr = defaults.array(forKey: completedKey) as? [String] {
            completedIds = Set(arr)
        }
    }

    // MARK: - 读取

    func progress(for postId: String) -> TimeInterval {
        cache[postId] ?? 0
    }

    func isCompleted(_ postId: String) -> Bool {
        completedIds.contains(postId)
    }

    var lastPlayedPostId: String? {
        defaults.string(forKey: lastPlayedKey)
    }

    // MARK: - 写入

    func setProgress(_ seconds: TimeInterval, for postId: String) {
        cache[postId] = max(0, seconds)
        persist()
    }

    func setLastPlayed(postId: String, creatorId: String? = nil) {
        defaults.set(postId, forKey: lastPlayedKey)
        if let creatorId, !creatorId.isEmpty {
            var dict = (defaults.dictionary(forKey: creatorLastPlayedKey) as? [String: Date]) ?? [:]
            dict[creatorId] = Date()
            defaults.set(dict, forKey: creatorLastPlayedKey)
        }
    }

    func lastPlayedDate(for creatorId: String) -> Date? {
        (defaults.dictionary(forKey: creatorLastPlayedKey) as? [String: Date])?[creatorId]
    }

    /// 播放完成时调用：清除进度并标记为已完成
    func markCompleted(for postId: String) {
        cache.removeValue(forKey: postId)
        completedIds.insert(postId)
        persist()
        persistCompleted()
    }

    func clearProgress(for postId: String) {
        cache.removeValue(forKey: postId)
        persist()
    }

    func clearAll() {
        cache.removeAll()
        completedIds.removeAll()
        defaults.removeObject(forKey: progressKey)
        defaults.removeObject(forKey: lastPlayedKey)
        defaults.removeObject(forKey: completedKey)
    }

    // MARK: - 持久化（防抖：100ms 内多次调用只写一次）

    private func persist() {
        persistDebounceTask?.cancel()
        let task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.defaults.set(self.cache.mapValues { Double($0) }, forKey: self.progressKey)
        }
        persistDebounceTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1, execute: task)
    }

    private func persistCompleted() {
        defaults.set(Array(completedIds), forKey: completedKey)
    }
}
