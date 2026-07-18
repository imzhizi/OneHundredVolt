package com.ohv.android.platform

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.diagnostics.DebugDiagnostics
import com.ohv.shared.models.AudioItem
import com.ohv.shared.platform.KeyValueStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ohv.shared.platform.SecureStorage
import com.ohv.shared.progress.PlaybackProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放器状态（对应 iOS AudioPlayerService 的 @Observable 属性）
 */
data class PlayerState(
    // 正在加载/切换中的目标单集（loadAndPlay 开始时设置，STATE_READY 后清空）
    val loadingItem: AudioItem? = null,
    // 已就绪、真正在播放的单集（仅在 STATE_READY 时更新）
    val playingItem: AudioItem? = null,
    val playlist: List<AudioItem> = emptyList(),
    val isPlaying: Boolean = false,
    // 反映 ExoPlayer 缓冲状态（onIsLoadingChanged），不用于切歌判断
    val isBuffering: Boolean = false,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackRate: Float = 1.0f,
    val sleepRemainingSeconds: Int = 0,
    val loudnessBoostEnabled: Boolean = false,
    val sessionCompletedIds: Set<String> = emptySet()
) {
    // 对外暴露的"当前单集"：加载中用 loadingItem，就绪后用 playingItem
    val currentItem: AudioItem? get() = loadingItem ?: playingItem

    val isLoading: Boolean get() = loadingItem != null

    val progressRatio: Float
        get() = if (durationMs > 0) currentTimeMs.toFloat() / durationMs else 0f

    val currentTimeSec: Double get() = currentTimeMs / 1000.0
    val durationSec: Double get() = durationMs / 1000.0
}

class AudioPlayerManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AudioPlayerManager? = null

        private const val PLAYBACK_RATE_KEY = "playback_rate"
        // v1.6：响度增强默认开启（改善有声内容普遍音量偏低的问题）
//  - 旧值 false：保持向后兼容已升级用户的偏好
//  - 新装用户获得 true：首启即开启响度增强
//  - 当前无 UI 开关（v1.6 plan：先做能力不做 UI），setLoudnessBoostEnabled API 保留
private const val LOUDNESS_BOOST_KEY = "loudness_boost_enabled"
        private const val PLAYLIST_KEY = "saved_playlist_v1"
        private val json = Json { ignoreUnknownKeys = true }

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AudioPlayerManager(context.applicationContext)
                    }
                }
            }
        }

        val shared: AudioPlayerManager
            get() = instance ?: error("AudioPlayerManager not initialized. Call init() in Application.onCreate()")
    }

    private val secureStorage = SecureStorage()
    private val api = AfdianApiService(secureStorage)
    private val progressStore = PlaybackProgressStore.shared
    private val kvStore = KeyValueStore()
    private val audioCache = AudioCacheService.shared
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private sealed class PendingSeek {
        data class PlayAfterSeek(val ms: Long) : PendingSeek()
        data class RestoreSeek(val ms: Long) : PendingSeek()
    }

    private val _playlist = mutableListOf<AudioItem>()
    private var pendingSeek: PendingSeek? = null
    private val _state = MutableStateFlow(
        PlayerState(
            playbackRate = kvStore.getFloat(PLAYBACK_RATE_KEY, 1.0f).takeIf { it > 0f } ?: 1.0f,
            // v1.6：响度增强默认开启
            //  - 多数有声内容音量偏低，Tanh 压缩能显著改善听感
            //  - 用户可在 v1.7+ 通过设置开关关闭
            //  - 当前没有 UI 开关，保留 API 以便未来扩展
            loudnessBoostEnabled = kvStore.getBoolean(LOUDNESS_BOOST_KEY, true)
        )
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private fun persistPlaylist() {
        if (_playlist.isEmpty()) {
            kvStore.putString(PLAYLIST_KEY, "")
        } else {
            kvStore.putString(PLAYLIST_KEY, json.encodeToString(_playlist.toList()))
        }
    }

    private fun restorePlaylist() {
        val data = kvStore.getString(PLAYLIST_KEY) ?: return
        if (data.isBlank()) return
        try {
            val items = json.decodeFromString<List<AudioItem>>(data)
            if (items.isNotEmpty()) {
                _playlist.addAll(items)
                _state.value = _state.value.copy(
                    playingItem = items[0],
                    playlist = items
                )
            }
        } catch (_: Exception) {}
    }

    private val _scrollToPlaylist = MutableStateFlow(false)
    val scrollToPlaylist: StateFlow<Boolean> = _scrollToPlaylist.asStateFlow()

    private val _queueFinished = MutableStateFlow(false)
    val queueFinished: StateFlow<Boolean> = _queueFinished.asStateFlow()

    val playbackRate: Float get() = _state.value.playbackRate
    val loudnessBoostEnabled: Boolean get() = _state.value.loudnessBoostEnabled

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var sleepEndMs: Long = 0L

    init {
        restorePlaylist()
        connectToService()
    }

    private fun connectToService() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudioPlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(playerListener)
                controller?.setPlaybackSpeed(playbackRate)
                syncStateFromController()
                // 恢复播放列表后，如果 ExoPlayer 没有媒体项，加载第一集但不自动播放
                if (_playlist.isNotEmpty() && controller?.currentMediaItem == null) {
                    scope.launch { loadMediaForRestore(_playlist[0]) }
                }
                startProgressPolling()
            } catch (_: Exception) {
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) {
                startProgressPolling()
            } else {
                stopProgressPolling()
                progressStore.flushToDisk()
            }
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            _state.value = _state.value.copy(isBuffering = isLoading)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    // durationMs == 0 说明从未经过 STATE_READY，忽略异常结束
                    if (_state.value.durationMs > 0) {
                        val finished = _state.value.currentItem
                        if (finished != null) {
                            progressStore.markCompleted(finished.id)
                        }
                        advanceToNext()
                    }
                }
                Player.STATE_READY -> {
                    val duration = controller?.duration?.takeIf { it > 0 } ?: 0L
                    // loadingItem 就绪，晋升为 playingItem，清空 loadingItem
                    val readyItem = _state.value.loadingItem ?: _state.value.playingItem
                    _state.value = _state.value.copy(
                        loadingItem = null,
                        playingItem = readyItem,
                        durationMs = duration
                    )
                    when (val seek = pendingSeek) {
                        is PendingSeek.PlayAfterSeek -> {
                            controller?.seekTo(seek.ms)
                            controller?.playWhenReady = true
                            if (readyItem != null) progressStore.setLastPlayed(readyItem.id, readyItem.creatorId)
                        }
                        is PendingSeek.RestoreSeek -> {
                            controller?.seekTo(seek.ms)
                        }
                        null -> Unit
                    }
                    pendingSeek = null
                }
                Player.STATE_BUFFERING -> {
                    // 缓冲状态由 onIsLoadingChanged 处理，此处无需操作
                }
                else -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromController()
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            // UI 更新频率：200ms（5Hz，保证进度条平滑）
            // 磁盘持久化频率：1Hz（通过 lastPersistMs 跟踪，避免每次轮询都写）
            var lastPersistMs = 0L
            while (isActive) {
                val ctrl = controller ?: break
                val position = ctrl.currentPosition.coerceAtLeast(0L)
                val duration = ctrl.duration.takeIf { it > 0 } ?: _state.value.durationMs
                _state.value = _state.value.copy(currentTimeMs = position, durationMs = duration)

                // 磁盘写入：每秒最多一次（Shared.PlaybackProgressStore 内部还有 15s 防抖）
                val item = _state.value.currentItem
                val now = System.currentTimeMillis()
                if (item != null && position > 0 && now - lastPersistMs >= 1000) {
                    progressStore.setProgress(position / 1000.0, item.id)
                    lastPersistMs = now
                }

                if (sleepEndMs > 0) {
                    val remaining = ((sleepEndMs - System.currentTimeMillis()) / 1000).toInt()
                    if (remaining <= 0) {
                        pause()
                        sleepEndMs = 0L
                        _state.value = _state.value.copy(sleepRemainingSeconds = 0)
                    } else {
                        _state.value = _state.value.copy(sleepRemainingSeconds = remaining)
                    }
                }

                delay(200)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun syncStateFromController() {
        val ctrl = controller ?: return
        val mediaId = ctrl.currentMediaItem?.mediaId ?: return
        val item = _playlist.firstOrNull { it.id == mediaId }
        val duration = ctrl.duration.takeIf { it > 0 } ?: 0L
        _state.value = _state.value.copy(
            playingItem = item,
            playlist = _playlist.toList(),
            isPlaying = ctrl.isPlaying,
            durationMs = duration
        )
    }

    private fun advanceToNext() {
        if (_playlist.isNotEmpty()) {
            val finished = _playlist.first()
            // v1.6 改动：不再立即删除缓存
            //  - LRU 策略自动管理 500MB 上限
            //  - 用户可能重听已完结单集，立即删除体验不佳
            _playlist.removeAt(0)
            _state.value = _state.value.copy(
                sessionCompletedIds = _state.value.sessionCompletedIds + finished.id
            )
        }
        if (_playlist.isEmpty()) {
            persistPlaylist()
            _state.value = PlayerState(
                playbackRate = playbackRate,
                loudnessBoostEnabled = loudnessBoostEnabled
            )
            stopProgressPolling()
            _queueFinished.value = true
            return
        }
        persistPlaylist()
        scope.launch { loadAndPlay(_playlist[0]) }
    }

    private suspend fun loadAndPlay(item: AudioItem) {
        progressStore.flushToDisk()
        _state.value = _state.value.copy(loadingItem = item)
        DebugDiagnostics.log("player", "load requested", details = mapOf("postId" to item.id, "title" to item.title))
        try {
            // 优先使用本地缓存
            val cachedFile = audioCache.cachedFile(item.id)
            val playUrl: String
            if (cachedFile != null) {
                playUrl = cachedFile.toURI().toString()
                DebugDiagnostics.log("player", "using cached audio", details = mapOf("postId" to item.id))
            } else {
                val remoteUrl = item.audioUrl ?: run {
                    val fetched = api.fetchAudioUrl(item.id)
                    item.audioUrl = fetched
                    fetched
                }
                playUrl = remoteUrl
                DebugDiagnostics.log("player", "using remote audio and caching", details = mapOf("postId" to item.id))
                // 后台缓存，不阻塞播放
                scope.launch(Dispatchers.IO) {
                    audioCache.cacheAudio(remoteUrl, item.id)
                }
            }

            val ctrl = controller ?: run {
                connectToService()
                delay(800)
                controller
            } ?: return

            val mediaItem = MediaItem.Builder()
                .setUri(playUrl)
                .setMediaId(item.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtworkUri(item.coverUrl?.let(android.net.Uri::parse))
                        .build()
                )
                .build()

            ctrl.setMediaItem(mediaItem)

            val savedProgress = progressStore.progress(item.id)
            if (savedProgress > 5.0) {
                pendingSeek = PendingSeek.PlayAfterSeek((savedProgress * 1000).toLong())
                ctrl.playWhenReady = false
            } else {
                pendingSeek = null
                ctrl.playWhenReady = true
            }
            ctrl.prepare()
            ctrl.setPlaybackSpeed(playbackRate)

            if (savedProgress <= 5.0) {
                progressStore.setLastPlayed(item.id, item.creatorId)
            }

            _state.value = _state.value.copy(playlist = _playlist.toList())
            persistPlaylist()
        } catch (e: Exception) {
            _state.value = _state.value.copy(loadingItem = null)
            DebugDiagnostics.log("player", "load failed", "ERROR", mapOf(
                "postId" to item.id,
                "errorType" to e::class.simpleName.orEmpty(),
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    /**
     * 恢复播放列表时加载媒体到 ExoPlayer，但不自动播放。
     * 与 loadAndPlay 的区别：始终 playWhenReady = false，等待用户主动点击播放。
     */
    private suspend fun loadMediaForRestore(item: AudioItem) {
        _state.value = _state.value.copy(loadingItem = item)
        try {
            val cachedFile = audioCache.cachedFile(item.id)
            val playUrl: String
            if (cachedFile != null) {
                playUrl = cachedFile.toURI().toString()
            } else {
                val remoteUrl = item.audioUrl ?: run {
                    val fetched = api.fetchAudioUrl(item.id)
                    item.audioUrl = fetched
                    fetched
                }
                playUrl = remoteUrl
                scope.launch(Dispatchers.IO) {
                    audioCache.cacheAudio(remoteUrl, item.id)
                }
            }

            val ctrl = controller ?: return

            val mediaItem = MediaItem.Builder()
                .setUri(playUrl)
                .setMediaId(item.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtworkUri(item.coverUrl?.let(android.net.Uri::parse))
                        .build()
                )
                .build()

            ctrl.setMediaItem(mediaItem)

            val savedProgress = progressStore.progress(item.id)
            pendingSeek = if (savedProgress > 5.0) PendingSeek.RestoreSeek((savedProgress * 1000).toLong()) else null
            ctrl.playWhenReady = false
            ctrl.prepare()
            ctrl.setPlaybackSpeed(playbackRate)

            _state.value = _state.value.copy(playlist = _playlist.toList())
        } catch (e: Exception) {
            _state.value = _state.value.copy(loadingItem = null)
            DebugDiagnostics.log("player", "restore failed", "ERROR", mapOf(
                "postId" to item.id,
                "errorType" to e::class.simpleName.orEmpty(),
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    fun playImmediately(item: AudioItem) {
        DebugDiagnostics.log("player", "play requested", details = mapOf("postId" to item.id, "title" to item.title))
        _playlist.removeAll { it.id == item.id }
        _playlist.add(0, item)
        _state.value = _state.value.copy(playlist = _playlist.toList())
        persistPlaylist()
        scope.launch { loadAndPlay(item) }
    }

    fun appendAndPlay(items: List<AudioItem>) {
        if (items.isEmpty()) return
        val newItems = items.filter { item -> _playlist.none { it.id == item.id } }
        _playlist.addAll(newItems)
        persistPlaylist()
        playImmediately(items[0])
    }

    fun appendToPlaylist(item: AudioItem) {
        if (_playlist.none { it.id == item.id }) {
            _playlist.add(item)
            _state.value = _state.value.copy(playlist = _playlist.toList())
            persistPlaylist()
        }
    }

    fun reorderPlaylist(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _playlist.indices || toIndex !in _playlist.indices) return
        if (fromIndex == toIndex) return
        val item = _playlist.removeAt(fromIndex)
        _playlist.add(toIndex, item)
        _state.value = _state.value.copy(playlist = _playlist.toList())
        persistPlaylist()
        // 拖拽过程中仅做视觉重排，不触发播放切换。
        // 播放逻辑统一在拖拽结束后由 onReorderFinished() 处理。
    }

    /**
     * 拖拽排序结束后调用，根据最终位置决定是否需要切换播放。
     * - 当前项仍在新队首 → syncAfterReorder（确保 playlist 与 state 一致）
     * - 当前项被移走 → 播放新的 playlist[0]
     */
    fun onReorderFinished() {
        val current = _state.value.currentItem ?: return
        val idx = _playlist.indexOfFirst { it.id == current.id }
        if (idx == 0) {
            syncAfterReorder()
        } else if (idx > 0) {
            scope.launch { loadAndPlay(_playlist[0]) }
        }
    }

    fun removeFromPlaylist(index: Int) {
        if (index !in _playlist.indices) return
        val deletingCurrent = _playlist[index].id == _state.value.currentItem?.id
        // v1.6 改动：不再立即删除缓存（同上，依赖 LRU 策略）
        _playlist.removeAt(index)
        if (_playlist.isEmpty()) {
            clearAll()
        } else if (deletingCurrent) {
            persistPlaylist()
            scope.launch { loadAndPlay(_playlist[0]) }
        } else {
            _state.value = _state.value.copy(playlist = _playlist.toList())
            persistPlaylist()
        }
    }

    private fun syncAfterReorder() {
        val current = _state.value.currentItem ?: return
        val idx = _playlist.indexOfFirst { it.id == current.id }
        if (idx > 0) {
            val item = _playlist.removeAt(idx)
            _playlist.add(0, item)
        }
        _state.value = _state.value.copy(playlist = _playlist.toList())
    }

    fun playFromPlaylist(index: Int) {
        if (index !in _playlist.indices) return
        val item = _playlist.removeAt(index)
        _playlist.add(0, item)
        persistPlaylist()
        scope.launch { loadAndPlay(item) }
    }

    fun requestScrollToPlaylist() {
        _scrollToPlaylist.value = true
    }

    fun consumeScrollToPlaylist() {
        _scrollToPlaylist.value = false
    }

    fun consumeQueueFinished() {
        _queueFinished.value = false
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun play() {
        controller?.play()
    }

    fun playNext() {
        advanceToNext()
    }

    fun playPrevious() {
        if (_state.value.currentTimeSec > 5.0) {
            seekAndPersist(0L)
        }
    }

    fun skipForward(seconds: Int = 30) {
        val ctrl = controller ?: return
        val target = (ctrl.currentPosition + seconds * 1000L).coerceAtMost(
            ctrl.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        )
        seekAndPersist(target)
    }

    fun skipBackward(seconds: Int = 15) {
        val ctrl = controller ?: return
        seekAndPersist((ctrl.currentPosition - seconds * 1000L).coerceAtLeast(0L))
    }

    fun seekTo(seconds: Double) {
        val ctrl = controller ?: return
        val target = (seconds * 1000).toLong().coerceIn(0L, ctrl.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekAndPersist(target)
    }

    /**
     * Media3 stops the polling loop while paused. Update the observable state
     * synchronously after a seek so the progress bar and time labels do not
     * remain at the pre-seek position.
     */
    private fun seekAndPersist(positionMs: Long) {
        val ctrl = controller ?: return
        val target = positionMs.coerceAtLeast(0L)
        ctrl.seekTo(target)
        val duration = ctrl.duration.takeIf { it > 0 } ?: _state.value.durationMs
        _state.value = _state.value.copy(
            currentTimeMs = target,
            durationMs = duration
        )
        _state.value.currentItem?.let { item ->
            progressStore.setProgress(target / 1000.0, item.id)
        }
        progressStore.flushToDisk()
        DebugDiagnostics.log("player", "seek persisted", details = mapOf(
            "postId" to (_state.value.currentItem?.id ?: "unknown"),
            "positionMs" to target.toString()
        ))
    }

    fun setPlaybackRate(rate: Float) {
        kvStore.putFloat(PLAYBACK_RATE_KEY, rate)
        controller?.setPlaybackSpeed(rate)
        _state.value = _state.value.copy(playbackRate = rate)
        DebugDiagnostics.log("player", "playback rate changed", details = mapOf("rate" to rate.toString()))
    }

    fun setLoudnessBoostEnabled(enabled: Boolean) {
        kvStore.putBoolean(LOUDNESS_BOOST_KEY, enabled)
        _state.value = _state.value.copy(loudnessBoostEnabled = enabled)
        // 同步到正在运行的 AudioPlaybackService 中的 LoudnessEnhancer
        controller?.sendCustomCommand(
            AudioPlaybackService.COMMAND_SET_LOUDNESS,
            bundleOf("enabled" to enabled)
        )
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepEndMs = 0L
            _state.value = _state.value.copy(sleepRemainingSeconds = 0)
            DebugDiagnostics.log("player", "sleep timer cleared")
            return
        }
        sleepEndMs = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepRemainingSeconds = minutes * 60)
        DebugDiagnostics.log("player", "sleep timer set", details = mapOf("minutes" to minutes.toString()))
    }

    fun clearAll() {
        val currentItem = _state.value.currentItem
        if (currentItem != null && _state.value.currentTimeMs > 0) {
            progressStore.setProgress(_state.value.currentTimeSec, currentItem.id)
        }
        controller?.stop()
        controller?.clearMediaItems()
        _playlist.clear()
        persistPlaylist()
        sleepEndMs = 0L
        stopProgressPolling()
        _state.value = PlayerState(
            playbackRate = playbackRate,
            loudnessBoostEnabled = loudnessBoostEnabled
        )
    }

    fun release() {
        stopProgressPolling()
        controller?.removeListener(playerListener)
        controllerFuture?.let(MediaController::releaseFuture)
        scope.cancel()
    }
}
