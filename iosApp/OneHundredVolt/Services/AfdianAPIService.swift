import Foundation
import Shared
import WebKit

/// iOS 错误类型（保留以兼容 view 层）
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

/// 爱发电 API 服务层（v1.7 Phase C.2：iOS 切到 Shared.AfdianApiService）
///
/// 之前 iOS 用 URLSession + Swift Codable 自行实现，现改用 Shared.AfdianApiService：
///  - HTTP/JSON 解析统一在 Shared（Ktor + Kotlinx.serialization）
///  - iOS 只剩薄包装 + iOS 特有的 Notification 桥接（401 → tokenExpired）
///  - APIError 仍用 iOS 的 enum（与 view 层兼容）
final class AfdianAPIService {

    static let shared = AfdianAPIService()
    private init() {}

    /// Shared 实现的 afdian API（Ktor + Kotlinx.serialization）
    private let backend = Shared.AfdianApiService(secureStorage: Shared.SecureStorage())

    /// auth_token 存储键（与 Shared.AfdianApiService 一致）
    static let authTokenKey = "afdian_auth_token"

    /// 登录态：依赖 Shared.SecureStorage 检查 auth_token
    var isLoggedIn: Bool { backend.isLoggedIn }

    /// 供 iOS 的 Shared 增量同步协调器复用同一个 Ktor 客户端和登录态。
    var sharedBackend: Shared.AfdianApiService { backend }

    /// 登出：删除 token + 清 WKWebView cookie
    func logout() {
        backend.logout()
        clearWebViewCookies()
    }

    /// 关停 Ktor 引擎（应用退出时调用，释放 OkHttp/URLSession 连接池）
    func close() {
        backend.close()
    }

    /// 清 WKWebView 中 afdian.com 相关数据（不影响其他站点）
    private func clearWebViewCookies() {
        Task.detached {
            try? await Shared.WebSessionCleaner().clearAfdianSession()
        }
        let dataStore = WKWebsiteDataStore.default()
        let dataTypes = WKWebsiteDataStore.allWebsiteDataTypes()
        dataStore.fetchDataRecords(ofTypes: dataTypes) { records in
            let afdianRecords = records.filter { record in
                record.displayName.lowercased().contains("afdian")
            }
            guard !afdianRecords.isEmpty else { return }
            dataStore.removeData(ofTypes: dataTypes, for: afdianRecords) {}
        }
    }

    // MARK: - API 方法（薄包装，转发到 Shared + iOS 错误处理）

    func fetchSponsoringCreators() async throws -> [Creator] {
        do {
            return try await backend.fetchSponsoringCreators()
        } catch let e as Shared.ApiError {
            handleApiError(e)
            throw mapApiError(e)
        }
    }

    func fetchAlbums(creatorId: String) async throws -> [Album] {
        do {
            return try await backend.fetchAlbums(creatorId: creatorId)
        } catch let e as Shared.ApiError {
            handleApiError(e)
            throw mapApiError(e)
        }
    }

    func probeAlbumAccessibility(albumId: String) async -> Bool {
        do {
            let result = try await backend.probeAlbumAccessibility(albumId: albumId)
            return result.boolValue
        } catch {
            return false
        }
    }

    func fetchAlbumCatalog(albumId: String) async throws -> [AudioItem] {
        do {
            return try await backend.fetchAlbumCatalog(albumId: albumId)
        } catch let e as Shared.ApiError {
            handleApiError(e)
            throw mapApiError(e)
        }
    }

    func fetchAudioURL(postId: String) async throws -> String {
        do {
            return try await backend.fetchAudioUrl(postId: postId)
        } catch let e as Shared.ApiError {
            handleApiError(e)
            throw mapApiError(e)
        }
    }

    // MARK: - iOS 错误桥接

    /// iOS 特有的 401 token expired 通知
    private func handleApiError(_ error: Shared.ApiError) {
        if error is Shared.ApiError.NotLoggedIn {
            postTokenExpired()
            return
        }
        if let http = error as? Shared.ApiError.HttpError, http.code == 401 {
            postTokenExpired()
        }
    }

    /// Shared.ApiError → iOS APIError（与 view 层兼容）
    private func mapApiError(_ error: Shared.ApiError) -> APIError {
        if error is Shared.ApiError.NotLoggedIn { return .notLoggedIn }
        if error is Shared.ApiError.InvalidUrl { return .invalidURL }
        if error is Shared.ApiError.NoAudioUrl { return .noAudioURL }
        if error is Shared.ApiError.NetworkError { return .apiError("网络连接失败，请稍后重试") }
        if let http = error as? Shared.ApiError.HttpError { return .httpError(Int(http.code)) }
        if let api = error as? Shared.ApiError.ApiResponseError { return .apiError(api.msg) }
        return .apiError("未知错误")
    }

    private func postTokenExpired() {
        Task { @MainActor in
            NotificationCenter.default.post(name: .tokenExpired, object: nil)
        }
    }
}
