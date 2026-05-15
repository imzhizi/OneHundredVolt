package com.ohv.android.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors

/**
 * 迷你播放器底部悬浮条（对应 iOS MiniPlayerView）
 *
 * 仅在有正在播放的内容时显示（currentItem != null）。
 * 点击整体区域 → 打开全屏 PlayerScreen。
 * 播放/暂停按钮和下一首按钮直接控制 AudioPlayerManager。
 *
 * @param onExpand 点击展开全屏播放器
 * @param onShowPlaylist 点击队列按钮 → 回首页并滚动到播放列表
 */
@Composable
fun MiniPlayerBar(
    onExpand: () -> Unit,
    onShowPlaylist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val player = AudioPlayerManager.shared
    val state by player.state.collectAsState()

    // playingItem 仅在 STATE_READY 时更新，保证迷你播放条与实际播放内容始终对齐
    val currentItem = state.playingItem ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OhvColors.CardBackground)
            .navigationBarsPadding()
    ) {
        // 进度条（细线，不可交互，Capsule 样式对齐 iOS）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(OhvColors.Separator)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.progressRatio.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(OhvColors.Accent)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 封面缩略图
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OhvColors.Separator),
                contentAlignment = Alignment.Center
            ) {
                val coverUrl = currentItem.coverUrl
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = currentItem.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = OhvColors.SecondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 标题（占满剩余空间）
            Text(
                text = currentItem.title,
                color = OhvColors.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 播放/暂停
            IconButton(
                onClick = { player.togglePlayPause() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = OhvColors.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 下一首
            IconButton(
                onClick = { player.playNext() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = OhvColors.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 播放队列 → 回首页并滚动到播放列表
            IconButton(
                onClick = onShowPlaylist,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = "播放队列",
                    tint = OhvColors.SecondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
