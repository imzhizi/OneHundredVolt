package com.ohv.android.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.theme.OhvColors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.platform.SecureStorage
import com.ohv.shared.progress.PlaybackProgressStore
import com.ohv.shared.sync.SyncService
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToLong

/**
 * 设置页（对应 iOS SettingsView.swift）
 *
 * @param onBack          返回
 * @param onLogout        退出登录后回调（导航回 Welcome）
 * @param onResync        点击「立即同步」→ 导航到 CreatorSelectScreen（重新同步流程）
 * @param onRelogin       点击「重新登录」→ 导航到 LoginWebViewScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onResync: () -> Unit,
    onRelogin: () -> Unit
) {
    val secureStorage = remember { SecureStorage() }
    val api = remember { AfdianApiService(secureStorage) }
    val db = remember { DatabaseService.shared }
    val kvStore = remember { KeyValueStore() }
    val progressStore = remember { PlaybackProgressStore.shared }
    val playerManager = remember { AudioPlayerManager.shared }
    val syncService = remember { SyncService(api, db, kvStore) }

    // 实时读取状态
    val isLoggedIn by remember { derivedStateOf { api.isLoggedIn } }
    val creators by db.creators.collectAsState()
    val albums by db.albums.collectAsState()
    val audioItems by db.audioItems.collectAsState()
    val syncState by syncService.state.collectAsState()

    var showLogoutAlert by remember { mutableStateOf(false) }
    var showClearDataAlert by remember { mutableStateOf(false) }
    var showClearCacheAlert by remember { mutableStateOf(false) }
    var cacheSizeBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSizeBytes = calculateCacheSize()
    }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("设置", color = OhvColors.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = OhvColors.Accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OhvColors.Background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(OhvColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 账户 ──────────────────────────────────────────────────────────
            SettingsSection(title = "账户") {
                SettingsRow(
                    icon = Icons.Default.Person,
                    label = "登录状态",
                    trailing = {
                        Text(
                            if (isLoggedIn) "已登录" else "未登录",
                            color = if (isLoggedIn) Color(0xFF34C759) else Color(0xFFFF9500),
                            fontSize = 13.sp
                        )
                    }
                )
                HorizontalDivider(color = OhvColors.Separator)
                if (isLoggedIn) {
                    SettingsButton(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        label = "退出登录",
                        labelColor = Color(0xFFFF3B30),
                        iconTint = Color(0xFFFF3B30),
                        onClick = { showLogoutAlert = true }
                    )
                } else {
                    SettingsButton(
                        icon = Icons.Default.Login,
                        label = "重新登录",
                        labelColor = OhvColors.Accent,
                        iconTint = OhvColors.Accent,
                        onClick = onRelogin
                    )
                }
            }

            // ── 同步 ──────────────────────────────────────────────────────────
            SettingsSection(title = "同步") {
                SettingsRow(
                    icon = Icons.Default.Refresh,
                    label = "上次同步",
                    trailing = {
                        val lastSync = syncService.lastSyncDate
                        Text(
                            if (lastSync != null) formatDate(lastSync) else "从未同步",
                            color = OhvColors.SecondaryText,
                            fontSize = 13.sp
                        )
                    }
                )
                HorizontalDivider(color = OhvColors.Separator)
                SettingsButton(
                    icon = Icons.Default.CloudDownload,
                    label = "立即同步",
                    enabled = !syncService.isSyncing,
                    onClick = onResync
                )
            }

            // ── 本地数据 ──────────────────────────────────────────────────────
            SettingsSection(title = "本地数据") {
                SettingsRow(
                    icon = Icons.Default.People,
                    label = "创作者",
                    trailing = { Text("${creators.size} 个", color = OhvColors.SecondaryText, fontSize = 13.sp) }
                )
                HorizontalDivider(color = OhvColors.Separator)
                SettingsRow(
                    icon = Icons.Default.LibraryMusic,
                    label = "专辑",
                    trailing = { Text("${albums.size} 个", color = OhvColors.SecondaryText, fontSize = 13.sp) }
                )
                HorizontalDivider(color = OhvColors.Separator)
                SettingsRow(
                    icon = Icons.Default.Headphones,
                    label = "音频",
                    trailing = { Text("${audioItems.size} 条", color = OhvColors.SecondaryText, fontSize = 13.sp) }
                )
                HorizontalDivider(color = OhvColors.Separator)
                SettingsButton(
                    icon = Icons.Default.DeleteForever,
                    label = "清除所有数据",
                    labelColor = Color(0xFFFF3B30),
                    iconTint = Color(0xFFFF3B30),
                    onClick = { showClearDataAlert = true }
                )
            }

            // ── 缓存 ──────────────────────────────────────────────────────────
            SettingsSection(title = "缓存") {
                SettingsRow(
                    icon = Icons.Default.Storage,
                    label = "音频缓存",
                    trailing = { Text(formatCacheSize(cacheSizeBytes), color = OhvColors.SecondaryText, fontSize = 13.sp) }
                )
                HorizontalDivider(color = OhvColors.Separator)
                SettingsButton(
                    icon = Icons.Default.CleaningServices,
                    label = "清空缓存",
                    labelColor = Color(0xFFFF9500),
                    iconTint = Color(0xFFFF9500),
                    onClick = { showClearCacheAlert = true }
                )
            }

            // ── 关于 ──────────────────────────────────────────────────────────
            SettingsSection(title = "关于") {
                SettingsRow(
                    icon = Icons.Default.Info,
                    label = "版本",
                    trailing = { Text("1.0.0", color = OhvColors.SecondaryText, fontSize = 13.sp) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ── 退出登录确认弹窗 ──────────────────────────────────────────────────────
    if (showLogoutAlert) {
        AlertDialog(
            onDismissRequest = { showLogoutAlert = false },
            title = { Text("退出登录", color = OhvColors.White) },
            text = { Text("退出后需要重新登录爱发电账户", color = OhvColors.SecondaryText) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutAlert = false
                    api.logout()
                    db.clearAll()
                    progressStore.clearAll()
                    onLogout()
                }) {
                    Text("退出", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutAlert = false }) {
                    Text("取消", color = OhvColors.SecondaryText)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }

    // ── 清除数据确认弹窗 ──────────────────────────────────────────────────────
    if (showClearDataAlert) {
        AlertDialog(
            onDismissRequest = { showClearDataAlert = false },
            title = { Text("清除所有数据", color = OhvColors.White) },
            text = { Text("将删除所有本地缓存的创作者、专辑和音频数据，此操作不可撤销", color = OhvColors.SecondaryText) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDataAlert = false
                    db.clearAll()
                    progressStore.clearAll()
                }) {
                    Text("清除", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataAlert = false }) {
                    Text("取消", color = OhvColors.SecondaryText)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }

    // ── 清空缓存确认弹窗 ──────────────────────────────────────────────────────
    if (showClearCacheAlert) {
        AlertDialog(
            onDismissRequest = { showClearCacheAlert = false },
            title = { Text("清空缓存", color = OhvColors.White) },
            text = { Text("将删除所有已缓存的音频文件，不影响已同步的专辑数据", color = OhvColors.SecondaryText) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheAlert = false
                    clearAudioCache()
                    cacheSizeBytes = 0L
                }) {
                    Text("清空", color = Color(0xFFFF9500))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheAlert = false }) {
                    Text("取消", color = OhvColors.SecondaryText)
                }
            },
            containerColor = OhvColors.CardBackground
        )
    }
}

// ─── 通用组件 ─────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            title,
            color = OhvColors.SecondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OhvColors.CardBackground, RoundedCornerShape(12.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = OhvColors.White, modifier = Modifier.size(20.dp))
        Text(label, color = OhvColors.White, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SettingsButton(
    icon: ImageVector,
    label: String,
    labelColor: Color = OhvColors.White,
    iconTint: Color = OhvColors.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                color = if (enabled) labelColor else labelColor.copy(alpha = 0.4f),
                fontWeight = FontWeight.Normal
            )
        }
    }
}

// ─── 日期格式化 ───────────────────────────────────────────────────────────────

private fun formatDate(epochMs: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

private fun calculateCacheSize(): Long {
    val cacheDir = java.io.File(com.ohv.shared.platform.getCacheDir())
    return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun clearAudioCache() {
    val cacheDir = java.io.File(com.ohv.shared.platform.getCacheDir())
    cacheDir.walkTopDown().forEach { file ->
        if (file.isFile) file.delete()
    }
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${(value * 10).roundToLong() / 10.0} ${units[digitGroups]}"
}
