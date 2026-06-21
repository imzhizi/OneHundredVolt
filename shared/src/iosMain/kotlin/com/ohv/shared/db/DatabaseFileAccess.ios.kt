@file:OptIn(ExperimentalForeignApi::class)

package com.ohv.shared.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.gettimeofday
import platform.posix.timeval
import com.ohv.shared.util.currentTimeMillis

/**
 * iOS / Kotlin/Native 实现：使用 NSFileManager + Foundation API
 *
 * 原子写入策略：
 * 1. 写入临时文件（带 millis 后缀避免并发冲突）
 * 2. 用 NSFileManager.replaceItemAtURL 原子替换
 */
actual class DatabaseFileAccess actual constructor(private val dbFilePath: String) {

    actual fun readFile(path: String): String? {
        return try {
            val nsString = NSString.stringWithContentsOfFile(
                path = path,
                encoding = NSUTF8StringEncoding,
                error = null
            )
            nsString as String?
        } catch (e: Exception) {
            null
        }
    }

    actual fun writeAtomic(path: String, content: String) {
        val nsString = content as NSString
        val tmpPath = "${path}.tmp.${currentTimeMillis()}"
        val written = nsString.writeToFile(
            path = tmpPath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )
        if (!written) throw RuntimeException("Failed to write tmp file: $tmpPath")

        val fileManager = NSFileManager.defaultManager
        val targetURL = NSURL.fileURLWithPath(path)
        val tmpURL = NSURL.fileURLWithPath(tmpPath)
        val moved = fileManager.replaceItemAtURL(
            originalItemURL = targetURL,
            withItemAtURL = tmpURL,
            backupItemName = null,
            options = 0u,
            resultingItemURL = null,
            error = null
        )
        if (!moved) {
            fileManager.removeItemAtURL(targetURL, null)
            fileManager.moveItemAtURL(tmpURL, targetURL, null)
        }
    }

    actual fun deleteFile(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    actual fun renameAsCorrupt(path: String) {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(path)) return
        val corruptPath = "${path}.corrupt.${currentTimeMillis()}"
        fileManager.moveItemAtPath(path, corruptPath, null)
    }

    actual fun cleanupTempFiles(path: String) {
        val fileManager = NSFileManager.defaultManager
        val dir = NSURL.fileURLWithPath(path).URLByDeletingLastPathComponent ?: return
        val targetName = NSURL.fileURLWithPath(path).lastPathComponent ?: return
        val dirPath = dir.path ?: return
        val prefix = "$targetName.tmp."
        val names = fileManager.contentsOfDirectoryAtPath(dirPath, null) ?: return
        for (name in names) {
            if (name is String && name.startsWith(prefix)) {
                fileManager.removeItemAtPath("$dirPath/$name", null)
            }
        }
    }
}