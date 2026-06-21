package com.ohv.shared.db

import java.io.File
import java.io.FileOutputStream

/**
 * JVM/Android 实现：使用 java.io.File + FileOutputStream
 *
 * 原子写入：
 * 1. 写入临时文件 + fsync 确保落盘
 * 2. rename 原子（旧文件保持完整）
 * 3. Windows fallback：renameTo 失败时 copyTo + delete
 *
 * 临时文件名后缀：当前纳秒时间戳（足够区分并发写入）
 */
actual class DatabaseFileAccess actual constructor(private val dbFilePath: String) {

    actual fun readFile(path: String): String? {
        val file = File(path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    actual fun writeAtomic(path: String, content: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File("${path}.tmp.${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            // POSIX rename 原子
            if (!tmp.renameTo(target)) {
                // Windows fallback
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    actual fun deleteFile(path: String) {
        File(path).delete()
    }

    actual fun renameAsCorrupt(path: String) {
        val target = File(path)
        if (!target.exists()) return
        val corrupt = File("${path}.corrupt.${System.currentTimeMillis()}")
        target.renameTo(corrupt)
    }

    actual fun cleanupTempFiles(path: String) {
        val target = File(path)
        val dir = target.parentFile ?: return
        val prefix = "${target.name}.tmp."
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
    }
}