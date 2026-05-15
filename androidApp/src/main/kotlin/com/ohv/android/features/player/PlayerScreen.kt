package com.ohv.android.features.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onDismiss: () -> Unit,
    onShowPlaylist: () -> Unit
) {
    val player = AudioPlayerManager.shared
    val playerState by player.state.collectAsState()
    val queueFinished by player.queueFinished.collectAsState()
    val density = LocalDensity.current

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    var isDraggingProgress by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    var showSpeedPicker by remember { mutableStateOf(false) }
    var showSleepPicker by remember { mutableStateOf(false) }

    // Animated offset for smooth snap-back after drag
    val animatedOffset = remember { Animatable(0f) }
    val animatedAlpha = remember { Animatable(1f) }

    // Auto-dismiss when queue finishes
    LaunchedEffect(queueFinished) {
        if (queueFinished) {
            player.consumeQueueFinished()
            onDismiss()
        }
    }

    // Animate offset when drag ends (snap-back)
    LaunchedEffect(dragOffsetPx) {
        if (dragOffsetPx <= 0f) {
            animatedOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f))
            animatedAlpha.snapTo(1f)
        }
    }

    val uriHandler = LocalUriHandler.current
    val displayProgress = if (isDraggingProgress) dragProgress else playerState.progressRatio
    val durationSec = playerState.durationSec.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OhvColors.Background)
            .offset { IntOffset(0, dragOffsetPx.coerceAtLeast(0f).roundToInt()) }
            .alpha(
                if (dragOffsetPx > 0f) (1f - dragOffsetPx / (density.density * 400)).coerceIn(0f, 1f)
                else 1f
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetPx > dismissThresholdPx) {
                            onDismiss()
                        } else {
                            dragOffsetPx = 0f
                        }
                    },
                    onDragCancel = { dragOffsetPx = 0f }
                ) { _, dragAmount ->
                    if (dragAmount > 0) dragOffsetPx += dragAmount
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "关闭",
                        tint = OhvColors.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    "正在播放",
                    color = OhvColors.SecondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(48.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(OhvColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    val coverUrl = playerState.currentItem?.coverUrl
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = playerState.currentItem?.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = OhvColors.SecondaryText,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                val currentItem = playerState.currentItem
                TextButton(
                    onClick = {
                        currentItem?.id?.let {
                            uriHandler.openUri("https://afdian.com/p/$it")
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            currentItem?.title ?: "（暂无播放内容）",
                            color = OhvColors.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (currentItem != null) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = OhvColors.SecondaryText,
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(top = 4.dp)
                            )
                        }
                    }
                }

                ProgressSection(
                    progress = displayProgress,
                    duration = durationSec,
                    isDragging = isDraggingProgress,
                    onDragStart = { isDraggingProgress = true },
                    onDragChange = { dragProgress = it },
                    onDragEnd = { ratio ->
                        isDraggingProgress = false
                        player.seekTo(durationSec * ratio.toDouble())
                    }
                )

                ControlsSection(
                    isPlaying = playerState.isPlaying,
                    isLoading = playerState.isLoading,
                    onSkipBack = { player.skipBackward(15) },
                    onPlayPause = { player.togglePlayPause() },
                    onSkipForward = { player.skipForward(30) }
                )

                SecondaryControls(
                    playbackRate = playerState.playbackRate,
                    sleepRemaining = playerState.sleepRemainingSeconds,
                    onSpeedClick = { showSpeedPicker = true },
                    onSleepClick = { showSleepPicker = true },
                    onQueueClick = {
                        onDismiss()
                        onShowPlaylist()
                    }
                )
            }
        }
    }

    if (showSpeedPicker) {
        AlertDialog(
            onDismissRequest = { showSpeedPicker = false },
            title = { Text("播放速度", color = OhvColors.White) },
            text = {
                Column {
                    listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                        TextButton(
                            onClick = {
                                player.setPlaybackRate(rate)
                                showSpeedPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (rate == 1.0f) "1.0x" else "%.2gx".format(rate),
                                color = if (rate == playerState.playbackRate) OhvColors.Accent else OhvColors.White
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedPicker = false }) {
                    Text("取消", color = OhvColors.SecondaryText)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }

    if (showSleepPicker) {
        AlertDialog(
            onDismissRequest = { showSleepPicker = false },
            title = { Text("睡眠定时", color = OhvColors.White) },
            text = {
                Column {
                    listOf(15 to "15分钟", 30 to "30分钟", 45 to "45分钟", 60 to "60分钟").forEach { (minutes, label) ->
                        TextButton(
                            onClick = {
                                player.setSleepTimer(minutes)
                                showSleepPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label, color = OhvColors.White)
                        }
                    }
                    if (playerState.sleepRemainingSeconds > 0) {
                        TextButton(
                            onClick = {
                                player.setSleepTimer(0)
                                showSleepPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("取消定时", color = OhvColors.SecondaryText)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepPicker = false }) {
                    Text("取消", color = OhvColors.SecondaryText)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }
}

@Composable
private fun ProgressSection(
    progress: Float,
    duration: Float,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDragChange: (Float) -> Unit,
    onDragEnd: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val trackWidth = maxWidth

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(Unit) {
                        var latestRatio = progress
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd(latestRatio) },
                            onDragCancel = { onDragEnd(progress) }
                        ) { change, _ ->
                            val width = size.width.toFloat().takeIf { it > 0f } ?: return@detectDragGestures
                            latestRatio = (change.position.x / width).coerceIn(0f, 1f)
                            onDragChange(latestRatio)
                            change.consume()
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(OhvColors.Separator)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(OhvColors.Accent)
                )
                Box(
                    modifier = Modifier
                        .offset(x = trackWidth * progress.coerceIn(0f, 1f) - 8.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(OhvColors.Accent)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                (duration * progress).toTimeFormatted(),
                color = OhvColors.SecondaryText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                duration.toTimeFormatted(),
                color = OhvColors.SecondaryText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ControlsSection(
    isPlaying: Boolean,
    isLoading: Boolean,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Replay, contentDescription = "快退15秒", tint = OhvColors.White, modifier = Modifier.size(34.dp))
                Text(
                    "15",
                    color = OhvColors.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset(y = 2.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(OhvColors.Accent)
                .clickableNoRipple(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.Forward30, contentDescription = "快进30秒", tint = OhvColors.White, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun SecondaryControls(
    playbackRate: Float,
    sleepRemaining: Int,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onQueueClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onSpeedClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OhvColors.Accent),
            border = BorderStroke(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.SolidColor(OhvColors.Accent.copy(alpha = 0.5f))
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                if (playbackRate == 1.0f) "1.0x" else "%.2gx".format(playbackRate),
                color = OhvColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = onSleepClick) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "睡眠定时",
                tint = if (sleepRemaining > 0) Color(0xFF5E5CE6) else OhvColors.SecondaryText,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (sleepRemaining > 0) sleepRemaining.toTimeFormatted() else "定时",
                color = if (sleepRemaining > 0) Color(0xFF5E5CE6) else OhvColors.SecondaryText,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onQueueClick) {
            Icon(
                Icons.Default.QueueMusic,
                contentDescription = "播放队列",
                tint = OhvColors.SecondaryText,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun Float.toTimeFormatted(): String {
    val total = this.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Int.toTimeFormatted(): String = this.toFloat().toTimeFormatted()

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            indication = null,
            interactionSource = interactionSource,
            onClick = onClick
        )
    )
}
