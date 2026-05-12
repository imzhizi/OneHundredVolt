package com.ohv.android.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.models.Album
import com.ohv.shared.models.Creator
import com.ohv.shared.progress.PlaybackProgressStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val creators: List<Creator> = emptyList(),
    val albumsByCreator: Map<String, List<Album>> = emptyMap(),
    val hasCurrentItem: Boolean = false   // 是否有正在播放的单集（控制迷你播放器显示）
)

/**
 * 首页 ViewModel（对应 iOS HomeViewModel）
 * @Observable → StateFlow + collectAsStateWithLifecycle
 */
class HomeViewModel : ViewModel() {

    private val db = DatabaseService.shared
    private val progressStore = PlaybackProgressStore.shared
    private val player = AudioPlayerManager.shared

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loaded = false

    fun loadIfNeeded() {
        if (loaded) return
        load()
    }

    fun load() {
        loaded = true
        viewModelScope.launch {
            // 仅监听当前播放项是否存在，避免进度刷新导致首页高频重组
            val hasCurrentItemFlow = player.state
                .map { it.currentItem?.id }
                .distinctUntilChanged()

            combine(db.creators, db.albums, hasCurrentItemFlow) { creators, albums, currentItemId ->
                val selected = creators.filter { it.isSelected }
                val sorted = selected.sortedByDescending { creator ->
                    progressStore.lastPlayedDate(creator.id) ?: 0L
                }
                val byCreator = sorted.associate { creator ->
                    creator.id to albums.filter { it.creatorId == creator.id }
                        .sortedBy { it.sortOrder }
                }
                HomeUiState(
                    creators = sorted,
                    albumsByCreator = byCreator,
                    hasCurrentItem = currentItemId != null
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
