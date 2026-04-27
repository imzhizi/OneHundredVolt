import Foundation
import WebKit

/// 爱发电 API 服务层
/// 所有接口均已通过真实账号验证（详见 docs/API.md）
final class AfdianAPIService {

    static let shared = AfdianAPIService()
    private init() {}

    private let baseURL = "https://afdian.com"
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        return URLSession(configuration: config)
    }()

    // MARK: - Cookie

    private var authToken: String? {
        KeychainService.load(forKey: KeychainService.authTokenKey)
    }

    var isLoggedIn: Bool { authToken != nil }

    func logout() {
        KeychainService.delete(forKey: KeychainService.authTokenKey)
        // 同时清除 WKWebView 的所有 cookie / 缓存，避免再次打开登录页时自动已登录
        clearWebViewCookies()
    }

    private func clearWebViewCookies() {
        let dataStore = WKWebsiteDataStore.default()
        let dataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
        dataStore.fetchDataRecords(ofTypes: dataTypes) { records in
            dataStore.removeData(ofTypes: dataTypes, for: records) {}
        }
    }

    // MARK: - 私有请求方法

    private func request(path: String, params: [String: String] = [:]) async throws -> Data {
        guard let token = authToken else {
            await MainActor.run {
                NotificationCenter.default.post(name: .tokenExpired, object: nil)
            }
            throw APIError.notLoggedIn
        }

        guard var components = URLComponents(string: baseURL + path) else {
            throw APIError.invalidURL
        }
        if !params.isEmpty {
            components.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components.url else { throw APIError.invalidURL }

        var req = URLRequest(url: url)
        req.setValue("auth_token=\(token)", forHTTPHeaderField: "Cookie")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
            forHTTPHeaderField: "User-Agent"
        )

        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            if code == 401 {
                await MainActor.run {
                    NotificationCenter.default.post(name: .tokenExpired, object: nil)
                }
            }
            throw APIError.httpError(code)
        }
        return data
    }

    // MARK: - 1. 获取支持的创作者列表

    func fetchSponsoringCreators() async throws -> [Creator] {
        let data = try await request(path: "/api/my/sponsoring")
        let resp = try JSONDecoder().decode(SponsoringResponse.self, from: data)
        guard resp.ec == 200 else { throw APIError.apiError(resp.em ?? "未知错误") }

        return resp.data.sponsoring.map { item in
            Creator(
                id: item.user.userId,
                name: item.user.name,
                avatarUrl: item.user.avatar,
                urlSlug: item.user.urlSlug,
                doing: item.user.creator?.doing,
                lastSyncedAt: nil,
                isSelected: true
            )
        }
    }

    // MARK: - 2. 获取创作者专辑列表（纯列表拉取，不做权限探测）
    //
    // bought=1  → isAccessible=true（已购买）
    // bought=0 且 postCount=0 → 跳过，不入库
    // bought=0 且 postCount>0 → isAccessible=false（暂定，由 SyncService 探测后更新）
    //
    // 权限探测（probeAlbumAccessibility）由 SyncService 在拿到列表后逐一调用，
    // 以便在进度条上实时反馈

    func fetchAlbums(creatorId: String) async throws -> [Album] {
        var allAlbums: [Album] = []
        var page = 1
        var hasMore = true

        while hasMore {
            let data = try await request(
                path: "/api/user/get-album-list",
                params: ["user_id": creatorId, "page": "\(page)", "per_page": "20"]
            )
            let resp = try JSONDecoder().decode(AlbumListResponse.self, from: data)
            guard resp.ec == 200 else { throw APIError.apiError(resp.em ?? "未知错误") }

            for item in resp.data.list {
                guard item.postCount > 0 else { continue }
                let album = Album(
                    id: item.albumId,
                    creatorId: creatorId,
                    title: item.title,
                    coverUrl: item.cover,
                    description: item.content?.isEmpty == false ? item.content : nil,
                    audioCount: item.postCount,
                    totalDuration: 0,
                    sortOrder: allAlbums.count,
                    lastSyncedAt: nil,
                    isAccessible: item.bought == 1  // bought=0 暂设 false，SyncService 会探测并更新
                )
                allAlbums.append(album)
            }

            hasMore = resp.data.hasMore == 1
            page += 1
        }
        return allAlbums
    }

    /// 探测专辑播放权限：取目录第一集，调 get-detail 检查 has_right
    /// - Returns: true = 免费可播放；false = 付费未购买或探测失败
    /// - Note: 调用方负责在调用前 sleep，控制 QPS
    func probeAlbumAccessibility(albumId: String) async -> Bool {
        guard let catalogData = try? await request(
            path: "/api/user/get-album-catalog",
            params: ["album_id": albumId, "page": "1"]
        ),
        let catalog = try? JSONDecoder().decode(AlbumCatalogResponse.self, from: catalogData),
        let firstPost = catalog.data.list.first(where: { $0.hasAudio == 1 })
        else { return false }

        guard let detailData = try? await request(
            path: "/api/post/get-detail",
            params: ["post_id": firstPost.postId]
        ),
        let detail = try? JSONDecoder().decode(PostDetailResponse.self, from: detailData)
        else { return false }

        return detail.data?.post.hasRight == 1
    }

    // MARK: - 3. 获取专辑目录（支持分页）

    func fetchAlbumCatalog(albumId: String) async throws -> [AudioItem] {
        var allItems: [AudioItem] = []
        var creatorId = ""
        var page = 1
        var hasMore = true

        while hasMore {
            let data = try await request(
                path: "/api/user/get-album-catalog",
                params: ["album_id": albumId, "page": "\(page)"]
            )
            let resp = try JSONDecoder().decode(AlbumCatalogResponse.self, from: data)
            guard resp.ec == 200 else { throw APIError.apiError(resp.em ?? "未知错误") }

            if creatorId.isEmpty, let first = resp.data.list.first {
                creatorId = first.userId
            }

            let items = resp.data.list
                .filter { $0.hasAudio == 1 }
                .map { post in
                    AudioItem(
                        id: post.postId,
                        albumId: albumId,
                        creatorId: creatorId,
                        title: post.title,
                        coverUrl: post.cover,
                        duration: TimeInterval(post.ext?.audioDuration ?? 0),
                        sortOrder: post.rank ?? 0,
                        publishTime: Date(timeIntervalSince1970: TimeInterval(post.publishTime ?? 0)),
                        audioUrl: nil
                    )
                }
            allItems.append(contentsOf: items)
            hasMore = resp.data.hasMore == 1
            page += 1
        }

        return allItems.sorted { $0.sortOrder < $1.sortOrder }
    }

    // MARK: - 4. 获取单个帖子详情（播放时获取带签名的 audio URL）

    func fetchAudioURL(postId: String) async throws -> String {
        let data = try await request(
            path: "/api/post/get-detail",
            params: ["post_id": postId]
        )
        let resp = try JSONDecoder().decode(PostDetailResponse.self, from: data)
        guard resp.ec == 200 else { throw APIError.apiError(resp.em ?? "未知错误") }
        guard let audio = resp.data?.post.audio, !audio.isEmpty else {
            throw APIError.noAudioURL
        }
        return audio
    }
}

// MARK: - 错误类型

enum APIError: LocalizedError {
    case notLoggedIn
    case invalidURL
    case httpError(Int)
    case apiError(String)
    case noAudioURL

    var errorDescription: String? {
        switch self {
        case .notLoggedIn:      return "请先登录爱发电"
        case .invalidURL:       return "无效的请求地址"
        case .httpError(let c): return "网络错误（\(c)）"
        case .apiError(let m):  return m
        case .noAudioURL:       return "该内容暂无音频或无播放权限"
        }
    }
}

// MARK: - Response 解码模型

private struct SponsoringResponse: Decodable {
    let ec: Int
    let em: String?
    let data: SponsoringData

    struct SponsoringData: Decodable {
        let sponsoring: [SponsoringItem]
    }
    struct SponsoringItem: Decodable {
        let user: SponsoringUser
    }
    struct SponsoringUser: Decodable {
        let userId: String
        let name: String
        let avatar: String?
        let urlSlug: String
        let creator: SponsoringCreator?

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case name, avatar
            case urlSlug = "url_slug"
            case creator
        }
    }
    struct SponsoringCreator: Decodable {
        let doing: String?
    }
}

private struct AlbumListResponse: Decodable {
    let ec: Int
    let em: String?
    let data: AlbumListData

    struct AlbumListData: Decodable {
        let list: [AlbumItem]
        let hasMore: Int

        enum CodingKeys: String, CodingKey {
            case list
            case hasMore = "has_more"
        }
    }
    struct AlbumItem: Decodable {
        let albumId: String
        let title: String
        let cover: String?
        let content: String?
        let postCount: Int
        let bought: Int

        enum CodingKeys: String, CodingKey {
            case albumId = "album_id"
            case title, cover, content
            case postCount = "post_count"
            case bought
        }
    }
}

private struct AlbumCatalogResponse: Decodable {
    let ec: Int
    let em: String?
    let data: CatalogData

    struct CatalogData: Decodable {
        let list: [CatalogPost]
        let hasMore: Int

        enum CodingKeys: String, CodingKey {
            case list
            case hasMore = "has_more"
        }
    }
    struct CatalogPost: Decodable {
        let postId: String
        let userId: String
        let title: String
        let cover: String?
        let hasAudio: Int
        let rank: Int?
        let publishTime: Int?
        let ext: PostExt?

        enum CodingKeys: String, CodingKey {
            case postId = "post_id"
            case userId = "user_id"
            case title, cover, rank
            case hasAudio = "has_audio"
            case publishTime = "publish_time"
            case ext
        }
    }
    struct PostExt: Decodable {
        let audioDuration: Int?
        enum CodingKeys: String, CodingKey {
            case audioDuration = "audio_duration"
        }
    }
}

private struct PostDetailResponse: Decodable {
    let ec: Int
    let em: String?
    let data: PostDetailData?

    struct PostDetailData: Decodable {
        let post: PostDetail
    }
    struct PostDetail: Decodable {
        let postId: String
        let title: String
        let audio: String?
        let hasRight: Int?

        enum CodingKeys: String, CodingKey {
            case postId = "post_id"
            case title, audio
            case hasRight = "has_right"
        }
    }
}
