package com.ohv.android.features.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ohv.android.features.player.MiniPlayerBar
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.progress.PlaybackProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class AlbumDetailUiState(
    val album: Album? = null,
    val items: List<AudioItem> = emptyList(),
    val completedIds: Set<String> = emptySet(),
    val isLoading: Boolean = true
)

class AlbumDetailViewModel(private val albumId: String) : ViewModel() {

    private val db = DatabaseService.shared
    private val progressStore = PlaybackProgressStore.shared

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val album = db.albums.value.firstOrNull { it.id == albumId }
            val items = db.audioItemsForAlbum(albumId)
            val completedIds = items.mapNotNull { item ->
                if (progressStore.isCompleted(item.id)) item.id else null
            }.toSet()
            _uiState.value = AlbumDetailUiState(
                album = album,
                items = items,
                completedIds = completedIds,
                isLoading = false
            )
        }
    }

    class Factory(private val albumId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AlbumDetailViewModel(albumId) as T
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPlayerClick: () -> Unit,
    onShowPlaylist: () -> Unit = {},
    vm: AlbumDetailViewModel = viewModel(factory = AlbumDetailViewModel.Factory(albumId))
) {
    val uiState by vm.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val player = AudioPlayerManager.shared
    val playerState by player.state.collectAsState()

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.album?.title ?: "",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = OhvColors.Accent
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        uiState.album?.let {
                            uriHandler.openUri("https://afdian.com/album/${it.id}")
                        }
                    }) {
                        Icon(Icons.Default.Language, contentDescription = "在浏览器中打开", tint = OhvColors.Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        },
        bottomBar = {
            if (playerState.currentItem != null) {
                MiniPlayerBar(
                    onExpand = onPlayerClick,
                    onShowPlaylist = onShowPlaylist
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(OhvColors.Background)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = OhvColors.Accent
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Album header
                    item {
                        AlbumHeader(
                            album = uiState.album,
                            onPlayAll = {
                                player.appendAndPlay(uiState.items)
                                onPlayerClick()
                            }
                        )
                    }

                    // Audio list
                    itemsIndexed(uiState.items, key = { _, item -> item.id }) { index, item ->
                        val isCurrentlyPlaying = playerState.currentItem?.id == item.id
                        val isCompleted = uiState.completedIds.contains(item.id)
                        val isInPlaylist = playerState.playlist.any { it.id == item.id }

                        AudioRow(
                            item = item,
                            index = index,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isCompleted = isCompleted,
                            isInPlaylist = isInPlaylist,
                            isLoading = playerState.isLoading && isCurrentlyPlaying,
                            onPlay = {
                                player.playImmediately(item)
                                onPlayerClick()
                            },
                            onAddToQueue = {
                                player.appendToPlaylist(item)
                            }
                        )
                        if (index < uiState.items.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = (16 + 8 + 44 + 8).dp),
                                color = OhvColors.Separator
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Album Header ─────────────────────────────────────────────────────────────

@Composable
private fun AlbumHeader(
    album: Album?,
    onPlayAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(OhvColors.CardBackground),
            contentAlignment = Alignment.Center
        ) {
            if (album?.coverUrl != null) {
                AsyncImage(
                    model = album.coverUrl,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = OhvColors.SecondaryText,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                album?.title ?: "",
                color = OhvColors.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            val meta = buildString {
                if ((album?.audioCount ?: 0) > 0) append("全 ${album?.audioCount} 期")
                val dur = album?.totalDuration ?: 0.0
                if (dur > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(dur.toHumanReadable())
                }
            }
            if (meta.isNotEmpty()) {
                Text(meta, color = OhvColors.SecondaryText, fontSize = 12.sp)
            }
        }

        Button(
            onClick = onPlayAll,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = OhvColors.Accent,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("全部播放", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Audio Row ────────────────────────────────────────────────────────────────

@Composable
private fun AudioRow(
    item: AudioItem,
    index: Int,
    isCurrentlyPlaying: Boolean,
    isCompleted: Boolean,
    isInPlaylist: Boolean,
    isLoading: Boolean,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    val dimmed = isCompleted && !isCurrentlyPlaying

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrentlyPlaying) OhvColors.Accent.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cover with state overlay
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(OhvColors.CardBackground)
                .alpha(if (dimmed) 0.4f else 1f),
            contentAlignment = Alignment.Center
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = OhvColors.SecondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }

            // State overlay
            if (isCurrentlyPlaying || dimmed) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isCurrentlyPlaying && isLoading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        isCurrentlyPlaying -> Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        dimmed -> Icon(Icons.Default.Check, contentDescription = "已完成", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Title + duration
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                color = when {
                    isCurrentlyPlaying -> OhvColors.Accent
                    dimmed -> OhvColors.SecondaryText
                    else -> OhvColors.White
                },
                fontSize = 14.sp,
                fontWeight = if (isCurrentlyPlaying) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.duration.toMinutesOnly(),
                color = OhvColors.SecondaryText.copy(alpha = if (dimmed) 0.6f else 1f),
                fontSize = 12.sp
            )
        }

        // Add to queue button
        IconButton(onClick = onAddToQueue, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (isInPlaylist) Icons.Default.CheckCircle else Icons.Default.AddCircle,
                contentDescription = if (isInPlaylist) "已在队列" else "加入队列",
                tint = when {
                    isInPlaylist -> OhvColors.Accent
                    dimmed -> OhvColors.SecondaryText.copy(alpha = 0.4f)
                    else -> OhvColors.SecondaryText
                },
                modifier = Modifier.size(22.dp)
            )
        }

        // Play now button
        IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
            if (isCurrentlyPlaying && isLoading) {
                CircularProgressIndicator(color = OhvColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    Icons.Default.PlayCircleFilled,
                    contentDescription = "播放",
                    tint = OhvColors.Accent.copy(alpha = if (dimmed) 0.4f else 1f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ─── Duration formatting ──────────────────────────────────────────────────────

private fun Double.toMinutesOnly(): String {
    val total = roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Double.toHumanReadable(): String {
    val total = roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        else -> "${m}分钟"
    }
}
