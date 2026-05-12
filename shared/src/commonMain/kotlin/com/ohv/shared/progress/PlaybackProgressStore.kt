package com.ohv.shared.progress

import com.ohv.shared.platform.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 本地播放进度存储
 * 移植自 iOS PlaybackProgressStore.swift
 * UserDefaults → KeyValueStore (expect/actual)
 * @Observable → 直接暴露方法，调用方用 StateFlow 包装
 */
class PlaybackProgressStore(private val kvStore: KeyValueStore) {

    companion object {
        val shared = PlaybackProgressStore(KeyValueStore())

        private const val PROGRESS_KEY = "playback_progress_v1"
        private const val LAST_PLAYED_KEY = "last_played_post_id"
        private const val COMPLETED_KEY = "playback_completed_v1"
        private const val CREATOR_LAST_PLAYED_KEY = "creator_last_played_v1"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // postId → 播放秒数
    private var cache: MutableMap<String, Double> = mutableMapOf()
    // 已播完的单集 id 集合
    private val completedIds: MutableSet<String> = mutableSetOf()

    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // 从持久化存储恢复
        kvStore.getString(PROGRESS_KEY)?.let { raw ->
            try {
                cache = json.decodeFromString<Map<String, Double>>(raw).toMutableMap()
            } catch (_: Exception) {}
        }
        kvStore.getString(COMPLETED_KEY)?.let { raw ->
            try {
                completedIds.addAll(json.decodeFromString<List<String>>(raw))
            } catch (_: Exception) {}
        }
    }

    // ─── 读取 ─────────────────────────────────────────────────────────────────

    fun progress(postId: String): Double = cache[postId] ?: 0.0

    fun isCompleted(postId: String): Boolean = completedIds.contains(postId)

    val lastPlayedPostId: String?
        get() = kvStore.getString(LAST_PLAYED_KEY)

    fun lastPlayedDate(creatorId: String): Long? {
        val raw = kvStore.getString(CREATOR_LAST_PLAYED_KEY) ?: return null
        return try {
            json.decodeFromString<Map<String, Long>>(raw)[creatorId]
        } catch (_: Exception) { null }
    }

    // ─── 写入 ─────────────────────────────────────────────────────────────────

    fun setProgress(seconds: Double, postId: String) {
        cache[postId] = maxOf(0.0, seconds)
        schedulePersist()
    }

    fun setLastPlayed(postId: String, creatorId: String? = null) {
        kvStore.putString(LAST_PLAYED_KEY, postId)
        if (!creatorId.isNullOrEmpty()) {
            val raw = kvStore.getString(CREATOR_LAST_PLAYED_KEY)
            val dict = try {
                raw?.let { json.decodeFromString<Map<String, Long>>(it) }?.toMutableMap()
                    ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }
            dict[creatorId] = System.currentTimeMillis()
            kvStore.putString(CREATOR_LAST_PLAYED_KEY, json.encodeToString(dict))
        }
    }

    /** 播放完成：清除进度，标记已完成 */
    fun markCompleted(postId: String) {
        cache.remove(postId)
        completedIds.add(postId)
        schedulePersist()
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

    // ─── 防抖持久化（100ms）──────────────────────────────────────────────────

    private fun schedulePersist() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(100)
            kvStore.putString(PROGRESS_KEY, json.encodeToString(cache.toMap()))
        }
    }
}
