package com.ohv.android.platform

import com.ohv.shared.platform.getCacheDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

/**
 * 音频文件本地缓存（LRU，500MB 上限）
 * 对齐 iOS AudioCacheService
 */
class AudioCacheService private constructor() {

    companion object {
        val shared = AudioCacheService()
        private const val MAX_CACHE_BYTES = 500L * 1024 * 1024 // 500 MB
    }

    private val cacheDir = File(getCacheDir(), "AudioCache").also { it.mkdirs() }

    fun cachedFile(postId: String): File? {
        val files = cacheDir.listFiles() ?: return null
        // 带扩展名的新格式缓存
        val match = files.firstOrNull { it.name.startsWith("$postId.") && it.isFile }
        if (match != null) {
            touch(match)
            return match
        }
        // 旧格式（无扩展名）：ExoPlayer 无法识别，直接删除
        val old = files.firstOrNull { it.name == postId }
        if (old != null) {
            old.delete()
        }
        return null
    }

    suspend fun cacheAudio(remoteUrl: String, postId: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(remoteUrl)
                val ext = url.path.substringAfterLast('.', "").substringBefore('?')
                val filename = if (ext.isNotEmpty()) "$postId.$ext" else postId
                val dest = File(cacheDir, filename)
                if (dest.exists()) return@withContext

                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                val temp = File(cacheDir, "$filename.tmp")
                conn.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                temp.renameTo(dest)
                evictIfNeeded()
            } catch (_: Exception) {
                // 缓存失败不影响播放
            }
        }
    }

    fun removeCache(postId: String) {
        val files = cacheDir.listFiles() ?: return
        files.filter { it.name == postId || it.name.startsWith("$postId.") }.forEach { it.delete() }
    }

    fun totalCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_CACHE_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (totalBytes <= MAX_CACHE_BYTES) break
            totalBytes -= file.length()
            file.delete()
        }
    }
}
