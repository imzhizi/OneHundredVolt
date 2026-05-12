import Foundation

/// 音频条目（对应爱发电的帖子 post，has_audio == 1）
struct AudioItem: Identifiable, Codable, Equatable, Hashable {
    let id: String              // post_id
    var albumId: String         // 所属专辑 album_id
    var creatorId: String       // 所属创作者 user_id
    var title: String
    var coverUrl: String?
    var duration: TimeInterval  // 音频时长（秒），来自 ext.audio_duration
    var sortOrder: Int          // 在专辑内的排序（rank）
    var publishTime: Date

    // MARK: - 本地播放进度（完全本地存储，不同步爱发电）
    // 通过 PlaybackProgressStore 管理，不存在此模型中

    // MARK: - 运行时（不持久化）
    /// 音频直链 URL（带签名，有时效性，每次播放前重新获取）
    var audioUrl: String?

    enum CodingKeys: String, CodingKey {
        case id, albumId, creatorId, title, coverUrl, duration, sortOrder, publishTime
        // audioUrl 故意排除，不持久化
    }
}

extension AudioItem {
    /// 是否已开始播放（本地进度 > 0）
    func progress(from store: PlaybackProgressStore) -> TimeInterval {
        store.progress(for: id)
    }

    /// 进度百分比 0.0 ~ 1.0
    func progressRatio(from store: PlaybackProgressStore) -> Double {
        guard duration > 0 else { return 0 }
        return min(1.0, store.progress(for: id) / duration)
    }
}
