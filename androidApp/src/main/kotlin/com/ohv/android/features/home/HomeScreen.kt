package com.ohv.android.features.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import com.ohv.android.features.player.MiniPlayerBar
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator

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

    LaunchedEffect(Unit) { vm.loadIfNeeded() }
    LaunchedEffect(scrollToPlaylist, playerState.playlist.size) {
        if (scrollToPlaylist) {
            listState.animateScrollToItem(playlistSectionIndex(uiState.creators.size))
            player.consumeScrollToPlaylist()
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
                        fontSize = 20.sp
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
                MiniPlayerBar(onExpand = onPlayerClick)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.creators.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无内容，请先同步", color = OhvColors.SecondaryText)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = if (uiState.hasCurrentItem) 88.dp else 16.dp)
                ) {
                    items(uiState.creators, key = { it.id }) { creator ->
                        CreatorSection(
                            creator = creator,
                            albums = uiState.albumsByCreator[creator.id] ?: emptyList(),
                            onAlbumClick = onAlbumClick,
                            onCreatorClick = onCreatorClick
                        )
                    }

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

                    item(key = PLAYLIST_SECTION_KEY) {
                        PlaylistSection(
                            playlist = playerState.playlist,
                            currentItemId = playerState.currentItem?.id,
                            isLoading = playerState.isLoading,
                            progressRatio = playerState.progressRatio,
                            onPlayItem = { index ->
                                val item = playerState.playlist.getOrNull(index)
                                if (item?.id == playerState.currentItem?.id) {
                                    onPlayerClick()
                                } else {
                                    player.playFromPlaylist(index)
                                }
                            },
                            onReorder = { from, to -> player.reorderPlaylist(from, to) },
                            onRemove = { index -> player.removeFromPlaylist(index) },
                            onClear = { player.clearAll() }
                        )
                    }
                }
            }
        }
    }
}

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

// ─── Playlist Section with drag-reorder + swipe-delete ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSection(
    playlist: List<AudioItem>,
    currentItemId: String?,
    isLoading: Boolean,
    progressRatio: Float,
    onPlayItem: (Int) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onClear: () -> Unit
) {
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "播放列表",
                color = OhvColors.SecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (playlist.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空", color = OhvColors.SecondaryText)
                }
            }
        }

        if (playlist.isEmpty()) {
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
            return
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = OhvColors.CardBackground,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                playlist.forEachIndexed { index, item ->
                    val isCurrent = item.id == currentItemId
                    val isDragged = index == draggedIndex
                    val elevation by animateDpAsState(
                        targetValue = if (isDragged) 4.dp else 0.dp,
                        label = "dragElevation"
                    )

                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                onRemove(index)
                                true
                            } else false
                        },
                        positionalThreshold = { totalDistance -> totalDistance * 0.4f }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            val color = when (dismissState.targetValue) {
                                SwipeToDismissBoxValue.EndToStart -> OhvColors.DestructiveRed
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color, RoundedCornerShape(16.dp))
                                    .padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = Color.White
                                )
                            }
                        }
                    ) {
                        PlaylistRow(
                            item = item,
                            index = index,
                            isCurrent = isCurrent,
                            isLoading = isLoading && isCurrent,
                            progressRatio = if (isCurrent) progressRatio else 0f,
                            isDragged = isDragged,
                            onClick = { onPlayItem(index) },
                            onDragStart = { draggedIndex = index },
                            onDrag = { offset ->
                                dragOffsetY = offset
                                val targetIndex = computeTargetIndex(index, offset, 64f)
                                if (targetIndex != index && targetIndex in playlist.indices) {
                                    onReorder(index, targetIndex)
                                    draggedIndex = targetIndex
                                    dragOffsetY = 0f
                                }
                            },
                            onDragEnd = {
                                draggedIndex = -1
                                dragOffsetY = 0f
                            }
                        )
                    }

                    if (index < playlist.lastIndex) {
                        HorizontalDivider(
                            color = OhvColors.Separator,
                            modifier = Modifier.padding(start = 59.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun computeTargetIndex(currentIndex: Int, offsetY: Float, itemHeight: Float): Int {
    val shift = (offsetY / itemHeight).toInt()
    return currentIndex + shift
}

@Composable
private fun PlaylistRow(
    item: AudioItem,
    index: Int,
    isCurrent: Boolean,
    isLoading: Boolean,
    progressRatio: Float,
    isDragged: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isCurrent) OhvColors.Accent.copy(alpha = 0.08f) else if (isDragged) OhvColors.Separator else OhvColors.CardBackground)
            .padding(horizontal = 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(44.dp)
                .background(if (isCurrent) OhvColors.Accent else OhvColors.CardBackground)
        )
        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(OhvColors.Background),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize()
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                color = if (isCurrent) OhvColors.Accent else OhvColors.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = item.duration.toMinutesOnly(),
                color = if (isCurrent) OhvColors.Accent.copy(alpha = 0.75f) else OhvColors.SecondaryText,
                fontSize = 12.sp
            )
            if (isCurrent) {
                LinearProgressIndicator(
                    progress = { progressRatio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .height(2.dp),
                    color = OhvColors.Accent,
                    trackColor = OhvColors.Separator
                )
            }
        }

        // Drag handle
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "拖拽排序",
            tint = OhvColors.SecondaryText.copy(alpha = 0.6f),
            modifier = Modifier
                .size(24.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                }
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = if (index == 0) "当前" else "${index + 1}",
            color = if (isCurrent) OhvColors.Accent else OhvColors.SecondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
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
        AsyncImage(
            model = album.coverUrl,
            contentDescription = album.title,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OhvColors.CardBackground)
        )
        Text(
            text = album.title,
            color = OhvColors.White,
            fontSize = 13.sp,
            maxLines = 2,
            lineHeight = 18.sp
        )
        Text(
            text = "${album.audioCount} 集",
            color = OhvColors.SecondaryText,
            fontSize = 11.sp
        )
    }
}

private fun playlistSectionIndex(creatorCount: Int): Int = creatorCount

private fun Double.toMinutesOnly(): String {
    val total = this.toFloat().roundToInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
