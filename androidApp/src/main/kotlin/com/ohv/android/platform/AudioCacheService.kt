package com.ohv.android.platform

import com.ohv.shared.platform.getCacheDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

/**
 * 音频文件本地缓存（LRU，500MB 上限），支持断点续传。
 *
 * 文件状态：
 *   <postId>.<ext>      — 下载完成的正式缓存
 *   <postId>.<ext>.tmp  — 下载中/中断的临时文件，用于断点续传
 *
 * 断点续传逻辑：
 *   1. 发起下载前检查是否存在 .tmp 文件
 *   2. 若存在，用 Range: bytes=N- 续传；服务器返回 206 → 追加写入
 *   3. 若服务器不支持 Range（返回 200）→ 清空 .tmp 重新下载
 *   4. 写完后重命名为正式文件
 *
 * LRU 淘汰：.tmp 文件优先被淘汰（按 lastModified 升序，.tmp 排在同 postId 正式文件之前）
 */
class AudioCacheService private constructor() {

    companion object {
        val shared = AudioCacheService()
        private const val MAX_CACHE_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    private val cacheDir = File(getCacheDir(), "AudioCache").also { it.mkdirs() }

    // ─── 读取 ──────────────────────────────────────────────────────────────────

    /** 返回已完成下载的缓存文件，.tmp 不返回 */
    fun cachedFile(postId: String): File? {
        val files = cacheDir.listFiles() ?: return null
        val match = files.firstOrNull {
            it.isFile && it.name.startsWith("$postId.") && !it.name.endsWith(".tmp")
        }
        if (match != null) {
            touch(match)
            return match
        }
        // 旧格式（无扩展名）：ExoPlayer 无法识别，直接删除
        files.firstOrNull { it.name == postId }?.delete()
        return null
    }

    // ─── 下载（断点续传）──────────────────────────────────────────────────────

    suspend fun cacheAudio(remoteUrl: String, postId: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(remoteUrl)
            val ext = url.path.substringAfterLast('.', "").substringBefore('?').take(8)
            val filename = if (ext.isNotEmpty()) "$postId.$ext" else postId
            val dest = File(cacheDir, filename)
            if (dest.exists()) return@withContext

            val temp = File(cacheDir, "$filename.tmp")
            val offset = if (temp.exists()) temp.length() else 0L

            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            conn.connect()

            when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // 206：服务器支持续传，追加写入
                    conn.inputStream.use { input ->
                        temp.outputStream().also { it.channel.position(offset) }.use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                HttpURLConnection.HTTP_OK -> {
                    // 200：服务器不支持 Range，重新下载
                    temp.delete()
                    conn.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> return@withContext // 其他响应码放弃缓存
            }

            temp.renameTo(dest)
            evictIfNeeded()
        } catch (_: Exception) {
            // 缓存失败不影响播放，.tmp 保留供下次续传
        }
    }

    // ─── 管理 ──────────────────────────────────────────────────────────────────

    /** 删除指定 postId 的所有缓存文件（含 .tmp） */
    fun removeCache(postId: String) {
        cacheDir.listFiles()
            ?.filter { it.name == postId || it.name.startsWith("$postId.") }
            ?.forEach { it.delete() }
    }

    fun totalCacheSize(): Long =
        cacheDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ─── 内部工具 ──────────────────────────────────────────────────────────────

    private fun touch(file: File) = file.setLastModified(System.currentTimeMillis())

    /**
     * LRU 淘汰：按 lastModified 升序排，优先删 .tmp（未完成的下载先淘汰）。
     */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_CACHE_BYTES) return

        // .tmp 排在前面（优先淘汰），同 lastModified 时正式文件排后
        val sorted = files.sortedWith(compareBy({ !it.name.endsWith(".tmp") }, { it.lastModified() }))
        for (file in sorted) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            totalBytes -= file.length()
            file.delete()
        }
    }
}
