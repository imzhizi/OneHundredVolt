package com.ohv.shared.api

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import com.ohv.shared.platform.SecureStorage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * 爱发电 API 服务层
 * 移植自 iOS AfdianAPIService.swift
 * URLSession → Ktor，逻辑完全一致
 *
 * v1.6 改动：
 * 1. 添加 HttpTimeout（请求/连接/Socket 各 15s），对齐 iOS URLSession 配置
 * 2. 提供 close() 方法供调用方在退出时关闭引擎，释放底层连接池
 */
class AfdianApiService(private val secureStorage: SecureStorage) {

    companion object {
        const val AUTH_TOKEN_KEY = "afdian_auth_token"
        private const val BASE_URL = "https://afdian.com"

        /** HTTP 请求总超时（毫秒），对齐 iOS URLSession.timeoutIntervalForRequest = 15s */
        const val REQUEST_TIMEOUT_MS = 15_000L
        /** TCP 连接超时（毫秒） */
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** Socket 读写超时（毫秒） */
        const val SOCKET_TIMEOUT_MS = 30_000L
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
    }

    val isLoggedIn: Boolean
        get() = secureStorage.get(AUTH_TOKEN_KEY) != null

    fun logout() {
        secureStorage.delete(AUTH_TOKEN_KEY)
    }

    /**
     * 关闭底层 Ktor 引擎，释放 OkHttp / URLSession 连接池。
     *
     * 调用方应在应用退出前调用，避免引擎泄漏。
     * 关闭后再次调用任何 API 方法会抛 IllegalStateException。
     */
    fun close() {
        client.close()
    }

    // ─── 私有请求方法 ──────────────────────────────────────────────────────────

    private suspend fun request(path: String, params: Map<String, String> = emptyMap()): io.ktor.client.statement.HttpResponse {
        val token = secureStorage.get(AUTH_TOKEN_KEY)
            ?: throw ApiError.NotLoggedIn

        return client.get("$BASE_URL$path") {
            params.forEach { (k, v) -> parameter(k, v) }
            header("Cookie", "auth_token=$token")
            header("Accept", "application/json")
            header(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
            )
        }.also { resp ->
            if (resp.status.value != 200) {
                throw ApiError.HttpError(resp.status.value)
            }
        }
    }

    // ─── 1. 获取支持的创作者列表 ───────────────────────────────────────────────

    suspend fun fetchSponsoringCreators(): List<Creator> {
        val resp = request("/api/my/sponsoring").body<SponsoringResponse>()
        if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")

        return resp.data.sponsoring.map { item ->
            Creator(
                id = item.user.userId,
                name = item.user.name,
                avatarUrl = item.user.avatar,
                urlSlug = item.user.urlSlug,
                doing = item.user.creator?.doing,
                isSelected = true
            )
        }
    }

    // ─── 2. 获取创作者专辑列表 ────────────────────────────────────────────────

    suspend fun fetchAlbums(creatorId: String): List<Album> {
        val allAlbums = mutableListOf<Album>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val resp = request(
                "/api/user/get-album-list",
                mapOf("user_id" to creatorId, "page" to "$page", "per_page" to "20")
            ).body<AlbumListResponse>()
            if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")

            for (item in resp.data.list) {
                if (item.postCount <= 0) continue
                allAlbums.add(
                    Album(
                        id = item.albumId,
                        creatorId = creatorId,
                        title = item.title,
                        coverUrl = item.cover,
                        description = item.content?.takeIf { it.isNotEmpty() },
                        audioCount = item.postCount,
                        totalDuration = 0.0,
                        sortOrder = allAlbums.size,
                        isAccessible = item.bought == 1
                    )
                )
            }
            hasMore = resp.data.hasMore == 1
            page++
        }
        return allAlbums
    }

    /**
     * 探测专辑播放权限：取目录第一集，调 get-detail 检查 has_right
     * 调用方负责在调用前 delay，控制 QPS
     */
    suspend fun probeAlbumAccessibility(albumId: String): Boolean {
        return try {
            val catalog = request(
                "/api/user/get-album-catalog",
                mapOf("album_id" to albumId, "page" to "1")
            ).body<AlbumCatalogResponse>()

            val firstPost = catalog.data.list.firstOrNull { it.hasAudio == 1 } ?: return false

            val detail = request(
                "/api/post/get-detail",
                mapOf("post_id" to firstPost.postId)
            ).body<PostDetailResponse>()

            detail.data?.post?.hasRight == 1
        } catch (e: Exception) {
            false
        }
    }

    // ─── 3. 获取专辑目录（支持分页）──────────────────────────────────────────

    suspend fun fetchAlbumCatalog(albumId: String): List<AudioItem> {
        val allItems = mutableListOf<AudioItem>()
        var creatorId = ""
        var page = 1
        var hasMore = true

        while (hasMore) {
            val resp = request(
                "/api/user/get-album-catalog",
                mapOf("album_id" to albumId, "page" to "$page")
            ).body<AlbumCatalogResponse>()
            if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")

            if (creatorId.isEmpty()) {
                creatorId = resp.data.list.firstOrNull()?.userId ?: ""
            }

            resp.data.list
                .filter { it.hasAudio == 1 }
                .mapTo(allItems) { post ->
                    AudioItem(
                        id = post.postId,
                        albumId = albumId,
                        creatorId = creatorId,
                        title = post.title,
                        coverUrl = post.cover,
                        duration = (post.ext?.audioDuration ?: 0).toDouble(),
                        sortOrder = post.rank ?: 0L,
                        publishTime = (post.publishTime ?: 0L) * 1000L // 秒 → 毫秒
                    )
                }

            hasMore = resp.data.hasMore == 1
            page++
        }

        return allItems.sortedBy { it.sortOrder }
    }

    // ─── 4. 获取单个帖子的音频直链 ────────────────────────────────────────────

    suspend fun fetchAudioUrl(postId: String): String {
        val resp = request(
            "/api/post/get-detail",
            mapOf("post_id" to postId)
        ).body<PostDetailResponse>()
        if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")
        return resp.data?.post?.audio?.takeIf { it.isNotEmpty() }
            ?: throw ApiError.NoAudioUrl
    }
}
