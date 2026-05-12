package com.ohv.android.platform

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.models.AudioItem
import com.ohv.shared.platform.KeyValueStore
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
    val currentItem: AudioItem? = null,
    val playlist: List<AudioItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackRate: Float = 1.0f,
    val sleepRemainingSeconds: Int = 0,
    val loudnessBoostEnabled: Boolean = false
) {
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
        private const val LOUDNESS_BOOST_KEY = "loudness_boost_enabled"

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playlist = mutableListOf<AudioItem>()
    private val _state = MutableStateFlow(
        PlayerState(
            playbackRate = kvStore.getFloat(PLAYBACK_RATE_KEY, 1.0f).takeIf { it > 0f } ?: 1.0f,
            loudnessBoostEnabled = kvStore.getBoolean(LOUDNESS_BOOST_KEY, false)
        )
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _scrollToPlaylist = MutableStateFlow(false)
    val scrollToPlaylist: StateFlow<Boolean> = _scrollToPlaylist.asStateFlow()

    val playbackRate: Float get() = _state.value.playbackRate
    val loudnessBoostEnabled: Boolean get() = _state.value.loudnessBoostEnabled

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var sleepEndMs: Long = 0L

    init {
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
                startProgressPolling()
            } catch (_: Exception) {
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) startProgressPolling() else stopProgressPolling()
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            _state.value = _state.value.copy(isLoading = isLoading)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    val finished = _state.value.currentItem
                    if (finished != null) {
                        progressStore.markCompleted(finished.id)
                    }
                    advanceToNext()
                }
                Player.STATE_READY -> {
                    val duration = controller?.duration?.takeIf { it > 0 } ?: 0L
                    _state.value = _state.value.copy(durationMs = duration, isLoading = false)
                }
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(isLoading = true)
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
            while (isActive) {
                val ctrl = controller ?: break
                val position = ctrl.currentPosition.coerceAtLeast(0L)
                val duration = ctrl.duration.takeIf { it > 0 } ?: _state.value.durationMs
                _state.value = _state.value.copy(currentTimeMs = position, durationMs = duration)

                val item = _state.value.currentItem
                if (item != null && position > 0) {
                    progressStore.setProgress(position / 1000.0, item.id)
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
            currentItem = item,
            playlist = _playlist.toList(),
            isPlaying = ctrl.isPlaying,
            durationMs = duration
        )
    }

    private fun advanceToNext() {
        if (_playlist.isNotEmpty()) _playlist.removeAt(0)
        if (_playlist.isEmpty()) {
            _state.value = PlayerState(
                playbackRate = playbackRate,
                loudnessBoostEnabled = loudnessBoostEnabled
            )
            stopProgressPolling()
            return
        }
        scope.launch { loadAndPlay(_playlist[0]) }
    }

    private suspend fun loadAndPlay(item: AudioItem) {
        _state.value = _state.value.copy(isLoading = true, currentItem = item)
        try {
            val url = item.audioUrl ?: run {
                val fetched = api.fetchAudioUrl(item.id)
                item.audioUrl = fetched
                fetched
            }

            val ctrl = controller ?: run {
                connectToService()
                delay(800)
                controller
            } ?: return

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(item.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtworkUri(item.coverUrl?.let(android.net.Uri::parse))
                        .build()
                )
                .build()

            ctrl.setMediaItem(mediaItem)
            ctrl.prepare()
            ctrl.setPlaybackSpeed(playbackRate)

            val savedProgress = progressStore.progress(item.id)
            if (savedProgress > 5.0) {
                ctrl.seekTo((savedProgress * 1000).toLong())
            }

            ctrl.play()
            progressStore.setLastPlayed(item.id, item.creatorId)

            _state.value = _state.value.copy(
                currentItem = item,
                playlist = _playlist.toList(),
                isLoading = false
            )
        } catch (_: Exception) {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    fun playImmediately(item: AudioItem) {
        _playlist.clear()
        _playlist.add(item)
        scope.launch { loadAndPlay(item) }
    }

    fun appendAndPlay(items: List<AudioItem>) {
        _playlist.clear()
        _playlist.addAll(items)
        if (_playlist.isNotEmpty()) {
            scope.launch { loadAndPlay(_playlist[0]) }
        }
    }

    fun appendToPlaylist(item: AudioItem) {
        if (_playlist.none { it.id == item.id }) {
            _playlist.add(item)
            _state.value = _state.value.copy(playlist = _playlist.toList())
        }
    }

    fun playFromPlaylist(index: Int) {
        if (index !in _playlist.indices) return
        val item = _playlist.removeAt(index)
        _playlist.add(0, item)
        scope.launch { loadAndPlay(item) }
    }

    fun requestScrollToPlaylist() {
        _scrollToPlaylist.value = true
    }

    fun consumeScrollToPlaylist() {
        _scrollToPlaylist.value = false
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
        controller?.seekTo(0)
    }

    fun skipForward(seconds: Int = 30) {
        val ctrl = controller ?: return
        val target = (ctrl.currentPosition + seconds * 1000L).coerceAtMost(
            ctrl.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        )
        ctrl.seekTo(target)
    }

    fun skipBackward(seconds: Int = 15) {
        val ctrl = controller ?: return
        ctrl.seekTo((ctrl.currentPosition - seconds * 1000L).coerceAtLeast(0))
    }

    fun seekTo(seconds: Double) {
        controller?.seekTo((seconds * 1000).toLong())
    }

    fun setPlaybackRate(rate: Float) {
        kvStore.putFloat(PLAYBACK_RATE_KEY, rate)
        controller?.setPlaybackSpeed(rate)
        _state.value = _state.value.copy(playbackRate = rate)
    }

    fun setLoudnessBoostEnabled(enabled: Boolean) {
        kvStore.putBoolean(LOUDNESS_BOOST_KEY, enabled)
        _state.value = _state.value.copy(loudnessBoostEnabled = enabled)
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepEndMs = 0L
            _state.value = _state.value.copy(sleepRemainingSeconds = 0)
            return
        }
        sleepEndMs = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepRemainingSeconds = minutes * 60)
    }

    fun clearAll() {
        controller?.stop()
        controller?.clearMediaItems()
        _playlist.clear()
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
