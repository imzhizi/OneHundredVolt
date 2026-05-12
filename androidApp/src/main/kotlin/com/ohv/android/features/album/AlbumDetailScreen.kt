package com.ohv.android.features.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── ViewModel ────────────────────────────────────────────────────────────────

data class AlbumDetailUiState(
    val album: Album? = null,
    val items: List<AudioItem> = emptyList(),
    val isLoading: Boolean = true
)

class AlbumDetailViewModel(private val albumId: String) : ViewModel() {

    private val db = DatabaseService.shared

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val album = db.albums.value.firstOrNull { it.id == albumId }
            val items = db.audioItemsForAlbum(albumId)
            _uiState.value = AlbumDetailUiState(
                album = album,
                items = items,
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

/**
 * 专辑详情页（对应 iOS AlbumDetailView.swift）
 *
 * @param albumId       专辑 ID
 * @param onBack        返回
 * @param onPlayerClick 点击播放器（迷你播放器 / 播放按钮）→ 导航到 PlayerScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onPlayerClick: () -> Unit,
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
            // 迷你播放器：有正在播放内容时显示
            if (playerState.currentItem != null) {
                MiniPlayerBar(onExpand = onPlayerClick)
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
                    // 专辑头部
                    item {
                        AlbumHeader(
                            album = uiState.album,
                            onPlayAll = {
                                // 全部播放：从第一集开始，整个专辑入队
                                player.appendAndPlay(uiState.items)
                                onPlayerClick()
                            }
                        )
                    }

                    // 音频列表
                    itemsIndexed(uiState.items, key = { _, item -> item.id }) { index, item ->
                        val isCurrentlyPlaying = playerState.currentItem?.id == item.id

                        AudioRow(
                            item = item,
                            index = index,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            onPlay = {
                                player.playImmediately(item)
                                onPlayerClick()
                            },
                            onOpenWeb = {
                                uriHandler.openUri("https://afdian.com/p/${item.id}")
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

// ─── 专辑头部 ─────────────────────────────────────────────────────────────────

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

// ─── 音频行 ───────────────────────────────────────────────────────────────────

@Composable
private fun AudioRow(
    item: AudioItem,
    index: Int,
    isCurrentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onOpenWeb: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .background(
                if (isCurrentlyPlaying) OhvColors.Accent.copy(alpha = 0.08f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 封面
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(OhvColors.CardBackground),
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
        }

        // 标题 + 时长
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                color = if (isCurrentlyPlaying) OhvColors.Accent else OhvColors.White,
                fontSize = 14.sp,
                fontWeight = if (isCurrentlyPlaying) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.duration.toMinutesOnly(),
                color = OhvColors.SecondaryText,
                fontSize = 12.sp
            )
        }

        // 播放按钮 / 正在播放指示
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "播放",
                tint = if (isCurrentlyPlaying) OhvColors.Accent else OhvColors.SecondaryText,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ─── 时长格式化扩展 ───────────────────────────────────────────────────────────

private fun Double.toMinutesOnly(): String {
    val total = this.roundToInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private fun Double.toHumanReadable(): String {
    val total = this.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        else -> "${m}分钟"
    }
}
