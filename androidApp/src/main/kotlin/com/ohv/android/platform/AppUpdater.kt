package com.ohv.android.platform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 应用内 OTA 更新管理器
 *
 * 功能：
 * 1. 通过 GitHub API 检测最新版本
 * 2. 从国内镜像站下载 APK（带进度回调）
 * 3. 触发系统安装器安装新版本
 *
 * v1.6 改动：
 * 1. Android 10+ (API 29) 使用 MediaStore.Downloads 写入公共 Downloads 目录，
 *    兼容 scoped storage；旧 API 用 Environment.getExternalStoragePublicDirectory()
 *    在 Android 10+ 会 EACCES 失败。
 * 2. Android 9 及以下用 UUID 文件名（update-<uuid>.apk）防止并发重试竞态。
 *    Android 10+ MediaStore 自动生成唯一文件名，无需 UUID。
 */
object AppUpdater {

    private const val REPO = "imzhizi/OneHundredVolt"

    // 版本信息检查 URL，按优先级降级依次尝试
    private val VERSION_JSON_URLS = listOf(
        // 1. jsDelivr CDN：国内稳定可访问
        "https://cdn.jsdelivr.net/gh/$REPO@main/version.json",
        // 2. ghproxy 镜像：国内备用
        "https://ghproxy.com/https://raw.githubusercontent.com/$REPO/main/version.json",
        // 3. GitHub 官方：开了代理时的兜底
        "https://raw.githubusercontent.com/$REPO/main/version.json"
    )

    // 旧版（Android 9 及以下）下载目录的子目录名
    private const val UPDATE_DIR = "updates"
    // 旧版文件名（保留以兼容旧版本检查 / 清理场景；新版使用 UUID 文件名）
    private const val LEGACY_APK_FILE_NAME = "update.apk"

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Android 10+ (API 29) 保存的最新 APK 的 MediaStore URI。
     * 用于在 downloadApk → getDownloadedApk → installApk 之间传递下载产物。
     */
    private var lastDownloadUri: Uri? = null

    /**
     * 旧版（Android 9 及以下）的下载路径。
     * 每次下载生成新的 UUID 文件，避免并发重试竞态。
     */
    private var lastLegacyFile: File? = null

    // ── 数据模型 ────────────────────────────────────────────────────────

    @Serializable
    data class VersionJson(
        val versionName: String,
        val versionCode: Int,
        val tag: String,
        val downloadUrl: String,
        val changelog: String = ""
    )

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val tag: String,
        val downloadUrl: String,
        val changelog: String,
        val sizeBytes: Long
    )

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val contentLength: Long,
        val done: Boolean = false
    ) {
        val percent: Float
            get() = if (contentLength > 0) (bytesDownloaded.toFloat() / contentLength * 100f) else 0f
    }

    // ── 版本检测 ───────────────────────────────────────────────────────

    /**
     * 检查结果：null = 无更新，UpdateInfo = 有更新，throw = 全部节点都失败
     *
     * 依次尝试 VERSION_JSON_URLS 中的地址，任一成功即返回结果；
     * 全部失败时抛出最后一个异常。
     */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        var lastError: Exception = IOException("No URLs to try")

        for (url in VERSION_JSON_URLS) {
            try {
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    lastError = IOException("HTTP ${response.code} from $url")
                    continue
                }
                val bodyStr = response.body?.string()
                if (bodyStr == null) {
                    lastError = IOException("Empty body from $url")
                    continue
                }

                val remote = json.decodeFromString<VersionJson>(bodyStr)
                if (remote.versionCode <= currentVersionCode) return null

                return UpdateInfo(
                    versionName = remote.versionName,
                    versionCode = remote.versionCode,
                    tag = remote.tag,
                    downloadUrl = remote.downloadUrl,
                    changelog = remote.changelog,
                    sizeBytes = 0L
                )
            } catch (e: Exception) {
                lastError = e
                // 继续尝试下一个地址
            }
        }

        throw lastError
    }

    // ── APK 下载 ───────────────────────────────────────────────────────

    /**
     * 根据 GitHub 直链构建备用下载 URL 列表。
     *
     * 主 URL（来自 version.json）失败时，依次尝试已知可用的镜像。
     * 下载地址形如：https://github.com/owner/repo/releases/download/tag/file.apk
     */
    private fun buildDownloadUrls(primaryUrl: String): List<String> {
        // 从 GitHub 直链中提取 tag 和文件名，构造镜像地址
        val githubReleaseRegex = Regex("github\\.com/([^/]+/[^/]+)/releases/download/([^/]+)/(.+)")
        val match = githubReleaseRegex.find(primaryUrl)
        return if (match != null) {
            val (repo, tag, filename) = match.destructured
            listOf(
                primaryUrl,                                                       // github.com 直链（有代理时可用）
                "https://ghproxy.com/https://github.com/$repo/releases/download/$tag/$filename", // ghproxy
                "https://ghfast.com/https://github.com/$repo/releases/download/$tag/$filename",  // ghfast
            )
        } else {
            listOf(primaryUrl)
        }
    }

    /**
     * 下载 APK 文件，主 URL 失败自动降级到镜像。
     *
     * - Android 10+ (API 29)：使用 MediaStore.Downloads 写入公共 Downloads，
     *   自动生成唯一文件名，兼容 scoped storage。
     * - Android 9 及以下：写入 app 私有目录（Context.getExternalFilesDir）下的
     *   updates/ 子目录，文件名带 UUID 防止并发重试竞态。
     *
     * @param context Context
     * @param url 来自 version.json 的主下载地址
     * @return Flow<DownloadProgress> 下载进度流
     */
    fun downloadApk(context: Context, url: String): Flow<DownloadProgress> = flow {
        val urls = buildDownloadUrls(url)
        var lastError: Exception = IOException("No download URLs")

        for (downloadUrl in urls) {
            try {
                val request = Request.Builder().url(downloadUrl).build()
                val response = httpClient.newCall(request).execute()

                if (response.code !in 200..299) {
                    lastError = IOException("HTTP ${response.code} from $downloadUrl")
                    continue
                }

                val body = response.body
                if (body == null) {
                    lastError = IOException("Empty body from $downloadUrl")
                    continue
                }

                val contentLength = body.contentLength()

                val finalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+：通过 MediaStore 写入公共 Downloads 目录
                    downloadViaMediaStore(context, body.byteStream(), contentLength) { downloaded ->
                        emit(DownloadProgress(downloaded, contentLength))
                    }
                } else {
                    // Android 9 及以下：写入 app 私有目录 + UUID 文件名
                    downloadViaFile(context, body.byteStream(), contentLength) { downloaded ->
                        emit(DownloadProgress(downloaded, contentLength))
                    }
                }

                emit(DownloadProgress(finalBytes, contentLength, done = true))
                return@flow  // 下载成功，退出
            } catch (e: IOException) {
                lastError = e
                // 继续尝试下一个地址
            }
        }

        // 全部失败
        emit(DownloadProgress(0L, 0L, done = true))
        throw lastError
    }.flowOn(Dispatchers.IO)

    /**
     * Android 10+：通过 MediaStore.Downloads 写入公共 Downloads 目录。
     * 自动生成唯一文件名，scoped storage 下也能写入。
     *
     * @return 最终写入字节数
     */
    private suspend fun downloadViaMediaStore(
        context: Context,
        input: java.io.InputStream,
        contentLength: Long,
        emitProgress: suspend (Long) -> Unit
    ): Long {
        val resolver = context.contentResolver
        val fileName = "ohv-update-${UUID.randomUUID()}.apk"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        try {
            val downloaded = resolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    total += read
                    emitProgress(total)
                }
                output.flush()
                total
            } ?: throw IOException("Failed to open output stream for $uri")

            // 标记下载完成，对其他 app 可见
            val finalValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, finalValues, null, null)

            lastDownloadUri = uri
            return downloaded
        } catch (e: Exception) {
            // 失败时清理未完成的 MediaStore 条目
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Android 9 及以下：写入 app 私有目录（Context.getExternalFilesDir），
     * 文件名带 UUID 防止并发重试竞态。
     *
     * @return 最终写入字节数
     */
    private suspend fun downloadViaFile(
        context: Context,
        input: java.io.InputStream,
        contentLength: Long,
        emitProgress: suspend (Long) -> Unit
    ): Long {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val updateDir = File(baseDir, UPDATE_DIR).apply { mkdirs() }
        val apkFile = File(updateDir, "update-${UUID.randomUUID()}.apk")

        // 清理旧文件（仅清理本应用的旧下载）
        updateDir.listFiles()?.forEach { it.delete() }

        try {
            val downloaded = apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    total += read
                    emitProgress(total)
                }
                output.flush()
                total
            }

            lastLegacyFile = apkFile
            return downloaded
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
    }

    /**
     * 取消正在进行的下载（删除不完整文件）。
     */
    fun cancelDownload(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lastDownloadUri?.let {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            lastDownloadUri = null
        } else {
            lastLegacyFile?.delete()
            lastLegacyFile = null
        }
    }

    /**
     * 获取已下载的 APK 的 Uri（ContentResolver URI 或 FileProvider URI）。
     * 返回 null 表示未下载或已删除。
     */
    fun getDownloadedApk(context: Context): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：返回 MediaStore URI，但需要验证文件还在
            val uri = lastDownloadUri ?: return null
            return try {
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) uri else null
                } ?: null
            } catch (_: Exception) {
                null
            }
        } else {
            // Android 9 及以下：返回 FileProvider URI（基于 lastLegacyFile）
            val file = lastLegacyFile?.takeIf { it.exists() && it.length() > 100_000 }
                ?: return null
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else {
                    Uri.fromFile(file)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── 安装触发 ───────────────────────────────────────────────────────

    /**
     * 触发系统安装器安装已下载的 APK。
     *
     * @param context Context
     * @param uri 已下载 APK 的 Uri（来自 getDownloadedApk）
     */
    fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(intent)
    }
}