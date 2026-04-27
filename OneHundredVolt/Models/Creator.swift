import Foundation

/// 爱发电创作者（对应 /api/my/sponsoring 中的 user 对象）
struct Creator: Identifiable, Codable, Equatable, Hashable {
    let id: String          // user_id
    var name: String
    var avatarUrl: String?
    var urlSlug: String     // 拼接 https://afdian.com/a/{urlSlug}
    var doing: String?      // 创作类型，如"电影评论"
    var lastSyncedAt: Date?
    var isSelected: Bool    // 是否选择同步

    /// 创作者爱发电主页 URL
    var afdianPageURL: URL? {
        URL(string: "https://afdian.com/a/\(urlSlug)")
    }
}
