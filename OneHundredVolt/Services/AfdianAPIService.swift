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

    // MARK: - 2. 获取创作者专辑列表（已购买 bought=1）

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

            let albums = resp.data.list
                .filter { $0.bought == 1 }  // 只取已购买的专辑
                .enumerated()
                .map { index, item in
                    Album(
                        id: item.albumId,
                        creatorId: creatorId,
                        title: item.title,
                        coverUrl: item.cover,
                        description: item.content?.isEmpty == false ? item.content : nil,
                        audioCount: item.postCount,
                        totalDuration: 0,   // 目录接口才能计算总时长
                        sortOrder: (page - 1) * 20 + index,
                        lastSyncedAt: nil
                    )
                }
            allAlbums.append(contentsOf: albums)
            hasMore = resp.data.hasMore == 1
            page += 1
        }
        return allAlbums
    }

    // MARK: - 3. 获取专辑完整目录（get-album-catalog，一次返回全部）

    func fetchAlbumCatalog(albumId: String) async throws -> [AudioItem] {
        let data = try await request(
            path: "/api/user/get-album-catalog",
            params: ["album_id": albumId]
        )
        let resp = try JSONDecoder().decode(AlbumCatalogResponse.self, from: data)
        guard resp.ec == 200 else { throw APIError.apiError(resp.em ?? "未知错误") }

        // 找到第一个有 creatorId 的帖子，其 userId 即为 creatorId
        let creatorId = resp.data.list.first?.userId ?? ""

        return resp.data.list
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
                    audioUrl: nil   // 目录不含 URL，播放时再获取
                )
            }
            .sorted { $0.sortOrder < $1.sortOrder }
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
