import Foundation

/// 专辑（对应爱发电的合集）
struct Album: Identifiable, Codable, Equatable, Hashable {
    let id: String          // album_id
    var creatorId: String   // 所属创作者 user_id
    var title: String
    var coverUrl: String?
    var description: String?
    var audioCount: Int     // 有音频的帖子数量
    var totalDuration: TimeInterval  // 总时长（秒）
    var sortOrder: Int      // 在创作者页面的显示顺序
    var lastSyncedAt: Date?
}
