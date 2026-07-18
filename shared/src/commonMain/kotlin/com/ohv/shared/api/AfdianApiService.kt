package com.ohv.shared.api

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import com.ohv.shared.diagnostics.DebugDiagnostics
import com.ohv.shared.platform.SecureStorage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

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
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1_000L
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
        DebugDiagnostics.log("auth", "logged out")
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

    /**
     * Kotlin exceptions other than ApiError are fatal when they cross the
     * Objective-C boundary. Normalize transport and decode failures first so
     * Swift can render an error instead of terminating the app.
     */
    private suspend fun <T> apiCall(operation: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            DebugDiagnostics.log("api", "operation failed", "ERROR", mapOf(
                "operation" to operation,
                "errorType" to e::class.simpleName.orEmpty(),
                "error" to (e.message ?: "unknown")
            ))
            throw ApiError.NetworkError
        }

    // ─── 私有请求方法 ──────────────────────────────────────────────────────────

    private suspend fun request(path: String, params: Map<String, String> = emptyMap()): io.ktor.client.statement.HttpResponse {
        val token = secureStorage.get(AUTH_TOKEN_KEY)
            ?: run {
                DebugDiagnostics.log("api", "request blocked: not logged in", "WARN", mapOf("path" to path))
                throw ApiError.NotLoggedIn
            }

        repeat(MAX_TRANSIENT_RETRIES + 1) { attempt ->
            DebugDiagnostics.log("api", "request started", details = mapOf(
                "path" to path,
                "params" to params.entries.joinToString("&") { (key, value) -> "$key=$value" },
                "attempt" to (attempt + 1).toString()
            ))

            try {
                val response = client.get("$BASE_URL$path") {
                    params.forEach { (k, v) -> parameter(k, v) }
                    header("Cookie", "auth_token=$token")
                    header("Accept", "application/json")
                    header(
                        "User-Agent",
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
                    )
                }
                DebugDiagnostics.log("api", "response received", details = mapOf(
                    "path" to path,
                    "status" to response.status.value.toString()
                ))
                if (response.status.value == 200) {
                    return response
                }

                val error = ApiError.HttpError(response.status.value)
                if (!isTransientHttpStatus(response.status.value) || attempt == MAX_TRANSIENT_RETRIES) {
                    throw error
                }
                retry(path, attempt, "http_${response.status.value}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                if (attempt == MAX_TRANSIENT_RETRIES) {
                    DebugDiagnostics.log("api", "request failed", "ERROR", mapOf(
                        "path" to path,
                        "errorType" to e::class.simpleName.orEmpty(),
                        "error" to (e.message ?: "unknown")
                    ))
                    throw e
                }
                retry(path, attempt, e::class.simpleName.orEmpty())
            }
        }

        error("Unreachable request retry state")
    }

    private fun isTransientHttpStatus(status: Int): Boolean = status == 429 || status in 500..599

    private suspend fun retry(path: String, attempt: Int, reason: String) {
        val delayMs = RETRY_BASE_DELAY_MS * (attempt + 1)
        DebugDiagnostics.log("api", "retrying transient request", "WARN", mapOf(
            "path" to path,
            "attempt" to (attempt + 1).toString(),
            "delayMs" to delayMs.toString(),
            "reason" to reason
        ))
        delay(delayMs)
    }

    // ─── 1. 获取支持的创作者列表 ───────────────────────────────────────────────

    @Throws(CancellationException::class, ApiError::class)
    suspend fun fetchSponsoringCreators(): List<Creator> = apiCall("fetchSponsoringCreators") {
        val resp = request("/api/my/sponsoring").body<SponsoringResponse>()
        if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")
        val data = resp.data ?: throw ApiError.ApiResponseError("响应缺少创作者数据")

        data.sponsoring.map { item ->
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

    @Throws(CancellationException::class, ApiError::class)
    suspend fun fetchAlbums(creatorId: String): List<Album> = apiCall("fetchAlbums") {
        val allAlbums = mutableListOf<Album>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val resp = request(
                "/api/user/get-album-list",
                mapOf("user_id" to creatorId, "page" to "$page", "per_page" to "20")
            ).body<AlbumListResponse>()
            if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")
            val data = resp.data ?: throw ApiError.ApiResponseError("响应缺少专辑数据")

            for (item in data.list) {
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
            hasMore = data.hasMore == 1
            page++
        }
        allAlbums
    }

    /**
     * 探测专辑播放权限：取目录第一集，调 get-detail 检查 has_right
     * 调用方负责在调用前 delay，控制 QPS
     */
    @Throws(CancellationException::class, ApiError::class)
    suspend fun probeAlbumAccessibility(albumId: String): Boolean = apiCall("probeAlbumAccessibility") {
        val catalog = request(
            "/api/user/get-album-catalog",
            mapOf("album_id" to albumId, "page" to "1")
        ).body<AlbumCatalogResponse>()
        if (catalog.ec != 200) throw ApiError.ApiResponseError(catalog.em ?: "未知错误")
        val data = catalog.data ?: throw ApiError.ApiResponseError("响应缺少专辑目录")

        val firstPost = data.list.firstOrNull { it.hasAudio == 1 } ?: return@apiCall false

        val detail = request(
            "/api/post/get-detail",
            mapOf("post_id" to firstPost.postId)
        ).body<PostDetailResponse>()
        if (detail.ec != 200) throw ApiError.ApiResponseError(detail.em ?: "未知错误")

        detail.data?.post?.hasRight == 1
    }

    // ─── 3. 获取专辑目录（支持分页）──────────────────────────────────────────

    @Throws(CancellationException::class, ApiError::class)
    suspend fun fetchAlbumCatalog(albumId: String): List<AudioItem> = apiCall("fetchAlbumCatalog") {
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
            val data = resp.data ?: throw ApiError.ApiResponseError("响应缺少专辑目录")

            if (creatorId.isEmpty()) {
                creatorId = data.list.firstOrNull()?.userId ?: ""
            }

            data.list
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

            hasMore = data.hasMore == 1
            page++
        }

        allItems.sortedBy { it.sortOrder }
    }

    // ─── 4. 获取单个帖子的音频直链 ────────────────────────────────────────────

    @Throws(CancellationException::class, ApiError::class)
    suspend fun fetchAudioUrl(postId: String): String = apiCall("fetchAudioUrl") {
        val resp = request(
            "/api/post/get-detail",
            mapOf("post_id" to postId)
        ).body<PostDetailResponse>()
        if (resp.ec != 200) throw ApiError.ApiResponseError(resp.em ?: "未知错误")
        resp.data?.post?.audio?.takeIf { it.isNotEmpty() }
            ?: throw ApiError.NoAudioUrl
    }
}
