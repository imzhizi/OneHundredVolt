package com.ohv.shared.sync

import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.util.currentTimeMillis
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步服务：拉取创作者、专辑、音频目录
 * 移植自 iOS SyncService.swift
 * @Observable var state → StateFlow<SyncState>
 * async/await → suspend fun + coroutines
 */
class SyncService(
    private val api: AfdianApiService,
    private val db: DatabaseService,
    private val kvStore: KeyValueStore
) {

    companion object {
        private const val SYNC_IN_PROGRESS_KEY = "sync_in_progress"
        private const val LAST_SYNC_DATE_KEY = "last_sync_date"
    }

    // ─── 同步状态（详见 SyncState.kt，顶层 sealed class）────────────────────

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val isSyncing: Boolean get() = _state.value is SyncState.Syncing

    val lastSyncDate: Long?
        get() = kvStore.getLong(LAST_SYNC_DATE_KEY, 0L).takeIf { it > 0L }

    // ─── Callback API（v1.7 Phase C.3）──────────────────────────────────────

    // 同 DatabaseService 设计：closure 替代 listener interface
    var stateCallback: ((SyncState) -> Unit)? = null
        private set

    fun setOnStateChangedCallback(callback: ((SyncState) -> Unit)?) {
        stateCallback = callback
        callback?.invoke(_state.value)
    }

    private fun notifyStateChanged(state: SyncState) {
        stateCallback?.invoke(state)
    }

    // ─── 启动时检测中断 ───────────────────────────────────────────────────────

    fun recoverIfNeeded() {
        if (kvStore.getBoolean(SYNC_IN_PROGRESS_KEY)) {
            kvStore.remove(SYNC_IN_PROGRESS_KEY)
        }
    }

    // ─── 同步入口 ─────────────────────────────────────────────────────────────

    suspend fun fullSync(selectedCreatorIds: List<String>) {
        sync(selectedCreatorIds)
    }

    // ─── 核心同步逻辑 ─────────────────────────────────────────────────────────

    private suspend fun sync(creatorIds: List<String>) {
        if (creatorIds.isEmpty()) {
            _state.value = SyncState.Success
            notifyStateChanged(_state.value)
            return
        }

        kvStore.putBoolean(SYNC_IN_PROGRESS_KEY, true)

        try {
            // Step 1：拉取所有支持的创作者
            setProgress("正在获取创作者列表...", 0.05)
            val allCreators = api.fetchSponsoringCreators()

            var selectedCreators = allCreators
                .filter { it.id in creatorIds }
                .map { it.copy(isSelected = true) }
            db.upsertCreators(selectedCreators)

            // Step 2：逐创作者拉取专辑和音频
            val total = selectedCreators.size
            for ((i, creator) in selectedCreators.withIndex()) {
                val baseProgress = 0.1 + i.toDouble() / total * 0.85
                setProgress("同步 ${creator.name}...", baseProgress)

                // Step A：拉专辑列表
                var albums = api.fetchAlbums(creatorId = creator.id)
                db.upsertAlbums(albums)

                // Step B：对 bought=0 的专辑逐一探测权限（500ms 间隔，QPS ≤ 2/s）
                val unknownAlbums = albums.filter { !it.isAccessible }
                for ((j, album) in unknownAlbums.withIndex()) {
                    val probeProgress = baseProgress +
                            j.toDouble() / maxOf(unknownAlbums.size, 1) * (0.85 / total * 0.3)
                    setProgress("检测权限 ${creator.name} — ${album.title}...", probeProgress)
                    delay(500)
                    val accessible = api.probeAlbumAccessibility(album.id)
                    if (accessible) {
                        albums = albums.map { if (it.id == album.id) it.copy(isAccessible = true) else it }
                        db.upsertAlbum(album.copy(isAccessible = true))
                    }
                }

                // Step C：只对有权限的专辑拉取音频目录（0.3s 间隔）
                val accessibleAlbums = albums.filter { it.isAccessible }
                for ((j, album) in accessibleAlbums.withIndex()) {
                    val perCreator = 0.85 / total
                    val albumProgress = baseProgress + perCreator * 0.3 +
                            j.toDouble() / maxOf(accessibleAlbums.size, 1) * (perCreator * 0.7)
                    setProgress("同步 ${creator.name} — ${album.title}...", albumProgress)

                    if (j > 0) delay(300)
                    val items = api.fetchAlbumCatalog(album.id)
                    db.upsertAudioItems(items)

                    db.upsertAlbum(
                        album.copy(
                            audioCount = items.size,
                            totalDuration = items.sumOf { it.duration },
                            lastSyncedAt = currentTimeMillis()
                        )
                    )
                }

                db.upsertCreator(creator.copy(lastSyncedAt = currentTimeMillis()))
            }

            kvStore.putLong(LAST_SYNC_DATE_KEY, currentTimeMillis())
            kvStore.remove(SYNC_IN_PROGRESS_KEY)

            setProgress("同步完成", 1.0)
            _state.value = SyncState.Success
            notifyStateChanged(_state.value)

        } catch (e: Exception) {
            kvStore.remove(SYNC_IN_PROGRESS_KEY)
            _state.value = SyncState.Failed(e)
            notifyStateChanged(_state.value)
        }
    }

    private fun setProgress(message: String, progress: Double) {
        _state.value = SyncState.Syncing(message, progress)
        notifyStateChanged(_state.value)
    }
}
