package com.ohv.shared.progress

import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.util.currentTimeMillis
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PlaybackProgressStore(private val kvStore: KeyValueStore) {

    companion object {
        val shared = PlaybackProgressStore(KeyValueStore())

        private const val PROGRESS_KEY = "playback_progress_v1"
        private const val LAST_PLAYED_KEY = "last_played_post_id"
        private const val COMPLETED_KEY = "playback_completed_v1"
        private const val CREATOR_LAST_PLAYED_KEY = "creator_last_played_v1"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private var cache: MutableMap<String, Double> = mutableMapOf()
    private val completedIds: MutableSet<String> = mutableSetOf()

    private var persistJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        kvStore.getString(PROGRESS_KEY)?.let { raw ->
            try { cache = json.decodeFromString<Map<String, Double>>(raw).toMutableMap() } catch (_: Exception) {}
        }
        kvStore.getString(COMPLETED_KEY)?.let { raw ->
            try { completedIds.addAll(json.decodeFromString<List<String>>(raw)) } catch (_: Exception) {}
        }
    }

    // ─── 读取 ─────────────────────────────────────────────────────────────────

    fun progress(postId: String): Double = cache[postId] ?: 0.0

    fun isCompleted(postId: String): Boolean = completedIds.contains(postId)

    val lastPlayedPostId: String?
        get() = kvStore.getString(LAST_PLAYED_KEY)

    fun lastPlayedDate(creatorId: String): Long? {
        val raw = kvStore.getString(CREATOR_LAST_PLAYED_KEY) ?: return null
        return try { json.decodeFromString<Map<String, Long>>(raw)[creatorId] } catch (_: Exception) { null }
    }

    // ─── 写入 ─────────────────────────────────────────────────────────────────

    fun setProgress(seconds: Double, postId: String) {
        cache[postId] = maxOf(0.0, seconds)
        schedulePersist()
    }

    /** 立即同步写磁盘，用于暂停、seek、杀进程等关键事件 */
    fun flushToDisk() {
        persistJob?.cancel()
        kvStore.putString(PROGRESS_KEY, json.encodeToString(cache.toMap()))
    }

    fun setLastPlayed(postId: String, creatorId: String? = null) {
        kvStore.putString(LAST_PLAYED_KEY, postId)
        if (!creatorId.isNullOrEmpty()) {
            val raw = kvStore.getString(CREATOR_LAST_PLAYED_KEY)
            val dict = try {
                raw?.let { json.decodeFromString<Map<String, Long>>(it) }?.toMutableMap() ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }
            dict[creatorId] = currentTimeMillis()
            kvStore.putString(CREATOR_LAST_PLAYED_KEY, json.encodeToString(dict))
        }
    }

    fun markCompleted(postId: String) {
        cache.remove(postId)
        completedIds.add(postId)
        flushToDisk()
        kvStore.putString(COMPLETED_KEY, json.encodeToString(completedIds.toList()))
    }

    fun clearProgress(postId: String) {
        cache.remove(postId)
        schedulePersist()
    }

    fun clearAll() {
        cache.clear()
        completedIds.clear()
        kvStore.remove(PROGRESS_KEY)
        kvStore.remove(LAST_PLAYED_KEY)
        kvStore.remove(COMPLETED_KEY)
        kvStore.remove(CREATOR_LAST_PLAYED_KEY)
    }

    // ─── 定时落盘（15 秒），关键事件调 flushToDisk() 立即写 ──────────────────
    // 连续播放时每 15 秒保底写一次，避免 kill 进程丢进度；
    // 暂停/seek/onTaskRemoved 时通过 flushToDisk() 立即写。

    private fun schedulePersist() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch {
            delay(15_000)
            kvStore.putString(PROGRESS_KEY, json.encodeToString(cache.toMap()))
            persistJob = null
        }
    }
}
