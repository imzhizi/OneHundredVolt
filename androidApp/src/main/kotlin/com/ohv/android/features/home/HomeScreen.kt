package com.ohv.android.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ohv.android.features.player.MiniPlayerBar
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

private const val PLAYLIST_SECTION_KEY = "playlist_section"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAlbumClick: (String) -> Unit,
    onCreatorClick: (String) -> Unit,
    onAllCreatorsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPlayerClick: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val player = AudioPlayerManager.shared
    val playerState by player.state.collectAsStateWithLifecycle()
    val scrollToPlaylist by player.scrollToPlaylist.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // 播放列表头在 LazyColumn 中的索引
    val playlistHeaderIndex = run {
        var idx = uiState.creators.size
        if (uiState.creators.isEmpty()) idx += 1 // empty state item
        if (uiState.creators.size > 3) idx += 1   // "all creators" link
        idx
    }

    // 播放列表条目的起始索引（头之后）
    val playlistStartIndex = playlistHeaderIndex + 1

    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        // from.index / to.index 是 ReorderableItem 在 LazyColumn 中的全局索引，
        // 需要减去 playlistStartIndex 得到播放列表内的偏移
        val fromPlaylist = from.index - playlistStartIndex
        val toPlaylist = to.index - playlistStartIndex
        if (fromPlaylist != toPlaylist && fromPlaylist >= 0 && toPlaylist >= 0) {
            player.reorderPlaylist(fromPlaylist, toPlaylist)
        }
    }

    LaunchedEffect(Unit) { vm.loadIfNeeded() }
    LaunchedEffect(scrollToPlaylist, playerState.playlist.size) {
        if (scrollToPlaylist) {
            listState.animateScrollToItem(playlistHeaderIndex)
            player.consumeScrollToPlaylist()
        }
    }

    val completedIds = playerState.sessionCompletedIds

    // 拖拽结束检测：用库的 isAnyItemDragging (derivedStateOf) 作为唯一信号，
    // 比 per-item isDragging callback 更可靠，不受 LazyColumn 重组时序影响
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    val isAnyDragging = reorderableState.isAnyItemDragging
    LaunchedEffect(isAnyDragging) {
        if (!isAnyDragging && draggingItemId != null) {
            player.onReorderFinished()
            draggingItemId = null
        }
    }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "一百伏特",
                        color = OhvColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = OhvColors.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OhvColors.Background
                )
            )
        },
        bottomBar = {
            if (uiState.hasCurrentItem) {
                MiniPlayerBar(
                    onExpand = onPlayerClick,
                    onShowPlaylist = { player.requestScrollToPlaylist() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = if (uiState.hasCurrentItem) 88.dp else 16.dp)
            ) {
                // ── Creator Sections ────────────────────────────────────────────────
                items(uiState.creators, key = { it.id }) { creator ->
                    CreatorSection(
                        creator = creator,
                        albums = uiState.albumsByCreator[creator.id] ?: emptyList(),
                        onAlbumClick = onAlbumClick,
                        onCreatorClick = onCreatorClick
                    )
                }

                // ── Empty state (when no creators) ─────────────────────────────────
                if (uiState.creators.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无内容，请先同步", color = OhvColors.SecondaryText)
                        }
                    }
                }

                // ── All creators link ──────────────────────────────────────────────
                if (uiState.creators.size > 3) {
                    item(key = "all_creators_link") {
                        TextButton(
                            onClick = onAllCreatorsClick,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text("查看全部创作者", color = OhvColors.Accent, fontSize = 14.sp)
                        }
                    }
                }

                // ── Playlist Section ───────────────────────────────────────────────
                item(key = PLAYLIST_SECTION_KEY) {
                    PlaylistHeader(
                        count = playerState.playlist.size,
                        onClear = { player.clearAll() }
                    )
                }

                if (playerState.playlist.isEmpty()) {
                    item(key = "playlist_empty") {
                        PlaylistEmptyState()
                    }
                } else {
                    // 播放列表项 — 每个项都是一个独立的 ReorderableItem
                    // 注意：ReorderableItem 的 content lambda 以 ReorderableCollectionItemScope 为 receiver，
                    // 因此 Modifier.longPressDraggableHandle() 只能在此作用域内调用
                    items(playerState.playlist, key = { it.id }) { item ->
                        val idx = playerState.playlist.indexOf(item)

                        ReorderableItem(reorderableState, key = item.id) { isDragging ->
                            if (isDragging) {
                                draggingItemId = item.id
                            }

                            val handleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                }
                            )

                            val isCurrent = item.id == playerState.currentItem?.id
                            val isActivelyPlaying = isCurrent && (playerState.isPlaying || playerState.isLoading)

                            PlaylistRow(
                                item = item,
                                isCurrent = isCurrent,
                                isPlaying = playerState.isPlaying,
                                isCompleted = completedIds.contains(item.id),
                                isLoading = playerState.isLoading && isCurrent,
                                progressRatio = playerState.progressRatio,
                                isDragging = isDragging,
                                isLast = idx == playerState.playlist.lastIndex,
                                onClick = {
                                    if (isActivelyPlaying) {
                                        onPlayerClick()
                                    } else {
                                        player.playFromPlaylist(idx)
                                    }
                                },
                                onDismiss = { player.removeFromPlaylist(idx) },
                                dragHandleModifier = handleModifier
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PlaylistRow — 纯渲染组件，通过 dragHandleModifier 接入 Calvin-LL/Reorderable
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistRow(
    item: AudioItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isCompleted: Boolean,
    isLoading: Boolean,
    progressRatio: Float,
    isDragging: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    val dimmed = isCompleted && !isCurrent
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else {
                false
            }
        },
        positionalThreshold = { with(density) { 200.dp.toPx() } }
    )

    // 横滑跨越删除阈值时触发震动
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(OhvColors.DestructiveRed),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = OhvColors.White,
                        modifier = Modifier.padding(end = 24.dp)
                    )
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(OhvColors.CardBackground)
            ) {
                // Progress overlay for current item — spans full card width
                if (isCurrent) {
                    Row(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(progressRatio.coerceIn(0.001f, 0.999f))
                                .background(OhvColors.Accent.copy(alpha = 0.12f))
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight((1f - progressRatio).coerceIn(0.001f, 0.999f))
                                .background(OhvColors.Accent.copy(alpha = 0.06f))
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Content area (click → play / open player) ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onClick() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Accent bar
                            Box(
                                Modifier
                                    .width(3.dp).height(44.dp)
                                    .background(if (isCurrent) OhvColors.Accent else OhvColors.CardBackground)
                            )
                            Spacer(Modifier.width(12.dp))

                            // Cover
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(OhvColors.Background)
                                    .alpha(if (dimmed) 0.4f else 1f),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = item.coverUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isCurrent || dimmed) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when {
                                            isCurrent && isLoading -> CircularProgressIndicator(
                                                color = Color.White,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            isCurrent && isPlaying -> Icon(
                                                Icons.Default.GraphicEq,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            isCurrent -> Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            dimmed -> Icon(
                                                Icons.Default.Check,
                                                contentDescription = "已完成",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            // Title + duration
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                Text(
                                    text = item.title,
                                    color = when {
                                        isCurrent -> OhvColors.Accent
                                        dimmed -> OhvColors.SecondaryText
                                        else -> OhvColors.White
                                    },
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = if (isCurrent) {
                                        item.duration.toRemainingText(progressRatio)
                                    } else {
                                        item.duration.toMinutesText()
                                    },
                                    color = when {
                                        isCurrent -> OhvColors.Accent.copy(alpha = 0.75f)
                                        dimmed -> OhvColors.SecondaryText.copy(alpha = 0.6f)
                                        else -> OhvColors.SecondaryText
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // ── Drag handle (library handles long-press gesture) ──
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "拖拽排序",
                        tint = OhvColors.SecondaryText.copy(alpha = if (dimmed) 0.3f else 0.6f),
                        modifier = Modifier
                            .size(36.dp)
                            .then(dragHandleModifier)
                    )

                    Spacer(Modifier.width(12.dp))
                }
            }
        }

        if (!isLast) {
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Other composables (unchanged)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CreatorSection(
    creator: Creator,
    albums: List<com.ohv.shared.models.Album>,
    onAlbumClick: (String) -> Unit,
    onCreatorClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCreatorClick(creator.id) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncImage(
                model = creator.avatarUrl,
                contentDescription = creator.name,
                modifier = Modifier.size(36.dp).clip(CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(creator.name, color = OhvColors.White, fontWeight = FontWeight.SemiBold)
                creator.doing?.let {
                    Text(it, color = OhvColors.SecondaryText, fontSize = 12.sp)
                }
            }
        }

        if (albums.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album = album, onClick = { onAlbumClick(album.id) })
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = OhvColors.Separator
        )
    }
}

@Composable
private fun PlaylistHeader(count: Int, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "播放列表",
                color = OhvColors.SecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (count > 0) {
                TextButton(onClick = onClear) {
                    Text("清空", color = OhvColors.SecondaryText)
                }
            }
        }
    }
}

@Composable
private fun PlaylistEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(OhvColors.CardBackground)
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Waves,
                contentDescription = null,
                tint = OhvColors.SecondaryText.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Text("在专辑页点击播放后会出现在这里", color = OhvColors.SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AlbumCard(
    album: com.ohv.shared.models.Album,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            AsyncImage(
                model = album.coverUrl,
                contentDescription = album.title,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(OhvColors.CardBackground)
            )
            if (album.unreadUpdateCount > 0) {
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    containerColor = OhvColors.Accent,
                    contentColor = Color.Black
                ) {
                    Text(
                        text = album.unreadUpdateCount.coerceAtMost(99).toString(),
                        fontSize = 10.sp
                    )
                }
            }
        }
        Text(
            text = album.title,
            color = OhvColors.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Duration formatting ──────────────────────────────────────────────────────

private fun Double.toMinutesText(): String {
    val totalMinutes = (this / 60).roundToInt()
    return "${totalMinutes} 分钟"
}

private fun Double.toRemainingText(progressRatio: Float): String {
    val remainingSeconds = this * (1.0 - progressRatio)
    val remainingMinutes = (remainingSeconds / 60).roundToInt()
    return "还有 ${remainingMinutes} 分钟"
}
