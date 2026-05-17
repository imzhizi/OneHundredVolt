package com.ohv.android.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import java.util.concurrent.TimeUnit

/**
 * 应用内 OTA 更新管理器
 *
 * 功能：
 * 1. 通过 GitHub API 检测最新版本
 * 2. 从国内镜像站下载 APK（带进度回调）
 * 3. 触发系统安装器安装新版本
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

    private const val UPDATE_DIR = "updates"
    private const val APK_FILE_NAME = "update.apk"

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
     * 下载 APK 文件到外部存储，主 URL 失败自动降级到镜像。
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

                // 下载到公共 Downloads 目录，文件管理器可见，安装器也可直接访问
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val apkFile = File(downloadsDir, APK_FILE_NAME)

                // 清理旧文件
                if (apkFile.exists()) apkFile.delete()

                var downloaded = 0L
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            emit(DownloadProgress(downloaded, contentLength))
                        }
                        output.flush()
                    }
                }

                emit(DownloadProgress(contentLength.coerceAtLeast(downloaded), contentLength, done = true))
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
     * 取消正在进行的下载（删除不完整文件）。
     */
    fun cancelDownload(context: Context) {
        getApkFile().delete()
    }

    /**
     * 获取已下载的 APK 文件（如果存在且完整）。
     */
    fun getDownloadedApk(context: Context): File? {
        val file = getApkFile()
        return if (file.exists() && file.length() > 100_000) file else null
    }

    private fun getApkFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, APK_FILE_NAME)
    }

    // ── 安装触发 ───────────────────────────────────────────────────────

    /**
     * 触发系统安装器安装已下载的 APK。
     */
    fun installApk(context: Context, apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

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
