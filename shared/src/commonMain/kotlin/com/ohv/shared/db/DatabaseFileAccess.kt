package com.ohv.shared.db

/**
 * 文件访问抽象层（expect/actual）
 *
 * v1.6.5 新增：原本 DatabaseService 在 commonMain 中直接使用 java.io.File，
 * 但 Kotlin/Native (iOS) 不支持 java.io.File。改为 expect/actual 后，
 * 平台各自使用 native 的文件 API：
 * - JVM/Android：java.io.File
 * - Native (iOS/macOS/Linux)：基于 platform.posix
 *
 * 同时为单元测试提供 mock 实现。
 */
expect class DatabaseFileAccess(dbFilePath: String) {
    /**
     * 读取文件全部内容为 UTF-8 字符串。文件不存在返回 null。
     */
    fun readFile(path: String): String?

    /**
     * 原子写入文件：先写到临时文件，再 rename 到目标路径。
     * - POSIX 系统（Linux / macOS / iOS / Android）：rename 原子
     * - Windows：renameTo 失败时 fallback 到 copyTo + delete
     */
    fun writeAtomic(path: String, content: String)

    /**
     * 删除单个文件（不存在不抛异常）。
     */
    fun deleteFile(path: String)

    /**
     * 把损坏文件重命名为 .corrupt.<ts>，保留供用户恢复。
     */
    fun renameAsCorrupt(path: String)

    /**
     * 清理 path.tmp.* 后缀的临时文件。
     */
    fun cleanupTempFiles(path: String)
}