package com.ohv.shared.sync

import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.diagnostics.DebugDiagnostics
import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.util.currentTimeMillis

data class AlbumUpdateSummary(
    val albumId: String,
    val addedCount: Int,
    val changedCount: Int,
    val unreadCount: Int,
    val checkedAt: Long,
    val skipped: Boolean = false
)

data class AlbumUpdateFailure(
    val albumId: String,
    val errorType: String,
    val message: String
)

data class IncrementalUpdateResult(
    val summaries: List<AlbumUpdateSummary>,
    val failures: List<AlbumUpdateFailure>
) {
    val addedCount: Int get() = summaries.sumOf { it.addedCount }
    val changedCount: Int get() = summaries.sumOf { it.changedCount }
}

/**
 * 对已同步专辑做低频目录检查。每个专辑独立提交，单个请求失败不会影响其他专辑。
 * fetchCatalog 由平台注入，便于 Android/iOS 复用并在单元测试中使用 fixture。
 */
class IncrementalUpdateService(
    private val db: DatabaseService,
    private val fetchCatalog: suspend (String) -> List<AudioItem>
) {
    constructor(api: AfdianApiService, db: DatabaseService) : this(db, { albumId ->
        DebugCatalogFixtures.resolve(albumId) ?: api.fetchAlbumCatalog(albumId)
    })

    companion object {
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }

    fun albumsDueForCheck(nowMs: Long = currentTimeMillis()): List<Album> =
        db.albums.value.filter { album ->
            val lastCheckedAt = album.lastCheckedAt
            lastCheckedAt == null || lastCheckedAt == 0L || nowMs - lastCheckedAt >= CHECK_INTERVAL_MS
        }

    fun markAllAlbumsDue() = db.markAllAlbumsDue()

    suspend fun checkDueAlbums(nowMs: Long = currentTimeMillis()): IncrementalUpdateResult =
        checkAlbums(albumsDueForCheck(nowMs).map { it.id }, force = true, nowMs = nowMs)

    suspend fun checkAllAlbums(nowMs: Long = currentTimeMillis()): IncrementalUpdateResult =
        checkAlbums(db.albums.value.map { it.id }, force = true, nowMs = nowMs)

    suspend fun checkAlbums(
        albumIds: List<String>,
        force: Boolean = false,
        nowMs: Long = currentTimeMillis()
    ): IncrementalUpdateResult {
        val summaries = mutableListOf<AlbumUpdateSummary>()
        val failures = mutableListOf<AlbumUpdateFailure>()

        for (albumId in albumIds.distinct()) {
            val album = db.albumById(albumId)
            if (album == null) {
                failures += AlbumUpdateFailure(albumId, "MissingAlbum", "本地找不到专辑")
                continue
            }

            if (!force && !isDue(album, nowMs)) {
                summaries += AlbumUpdateSummary(
                    albumId = album.id,
                    addedCount = 0,
                    changedCount = 0,
                    unreadCount = album.unreadUpdateCount,
                    checkedAt = album.lastCheckedAt ?: 0L,
                    skipped = true
                )
                continue
            }

            try {
                DebugDiagnostics.log("incremental", "catalog check started", details = mapOf("album" to album.id))
                val remoteItems = fetchCatalog(album.id)
                validateCatalog(album, remoteItems)

                val existingItems = db.audioItemsForAlbum(album.id)
                val diff = AlbumUpdateDiffCalculator.compare(existingItems, remoteItems)
                db.upsertAudioItems(diff.added + diff.changed)
                // 远端缺少旧 id 时保留本地单集；计数也基于保留后的本地目录，
                // 避免一次缺页响应让专辑元数据与实际可播放列表不一致。
                val mergedItems = (remoteItems + existingItems).distinctBy { it.id }
                val updatedAlbum = album.copy(
                    audioCount = mergedItems.size,
                    totalDuration = mergedItems.sumOf { it.duration },
                    lastSyncedAt = nowMs,
                    lastCheckedAt = nowMs,
                    lastContentChangeAt = if (diff.totalChanged > 0) nowMs else album.lastContentChangeAt,
                    unreadUpdateCount = album.unreadUpdateCount + diff.totalChanged
                )
                db.upsertAlbum(updatedAlbum)

                summaries += AlbumUpdateSummary(
                    albumId = album.id,
                    addedCount = diff.added.size,
                    changedCount = diff.changed.size,
                    unreadCount = updatedAlbum.unreadUpdateCount,
                    checkedAt = nowMs
                )
                DebugDiagnostics.log("incremental", "catalog check committed", details = mapOf(
                    "album" to album.id,
                    "added" to diff.added.size.toString(),
                    "changed" to diff.changed.size.toString()
                ))
            } catch (e: Exception) {
                val failure = AlbumUpdateFailure(
                    albumId = album.id,
                    errorType = e::class.simpleName.orEmpty(),
                    message = e.message ?: "检查失败"
                )
                failures += failure
                DebugDiagnostics.log("incremental", "catalog check failed", "ERROR", mapOf(
                    "album" to album.id,
                    "errorType" to failure.errorType,
                    "error" to failure.message
                ))
            }
        }

        return IncrementalUpdateResult(summaries = summaries, failures = failures)
    }

    private fun isDue(album: Album, nowMs: Long): Boolean {
        val lastCheckedAt = album.lastCheckedAt
        return lastCheckedAt == null || lastCheckedAt == 0L || nowMs - lastCheckedAt >= CHECK_INTERVAL_MS
    }

    private fun validateCatalog(album: Album, remoteItems: List<AudioItem>) {
        if (remoteItems.isEmpty() && db.audioItemsForAlbum(album.id).isNotEmpty()) {
            error("远端目录为空，疑似响应不完整")
        }
        val duplicateId = remoteItems.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicateId != null) {
            error("远端目录包含重复单集: $duplicateId")
        }
    }
}
