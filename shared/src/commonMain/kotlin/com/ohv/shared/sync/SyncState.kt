package com.ohv.shared.sync

/**
 * 同步状态（顶层 sealed class，便于 KMP cinterop 暴露给 Swift）
 *
 * v1.7 Phase C.3：从 SyncService 内嵌套提到顶层
 *  - 原 nested sealed class 在 KMP 暴露给 Swift 时遇到嵌套 sealed 解析问题
 *  - 提到顶层后，Swift 可直接 typealias SharedSyncState = Shared.SyncState
 */
sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val message: String, val progress: Double) : SyncState()
    object Success : SyncState()
    data class Failed(val error: Exception) : SyncState()
}