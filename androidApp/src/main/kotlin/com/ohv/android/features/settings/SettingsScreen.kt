package com.ohv.android.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohv.android.platform.AudioCacheService
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.android.platform.AppUpdater
import com.ohv.android.BuildConfig
import com.ohv.android.components.UpdateDialog
import com.ohv.android.theme.OhvColors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.diagnostics.DebugDiagnostics
import com.ohv.shared.sync.DebugCatalogFixtures
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.platform.SecureStorage
import com.ohv.shared.progress.PlaybackProgressStore
import com.ohv.shared.sync.IncrementalUpdateService
import com.ohv.shared.sync.SyncService
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

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
    val audioCache = remember { AudioCacheService.shared }
    val syncService = remember { SyncService(api, db, kvStore) }
    val incrementalUpdate = remember {
        IncrementalUpdateService(api, db)
    }
    val coroutineScope = rememberCoroutineScope()

    // 实时读取状态
    val isLoggedIn by remember { derivedStateOf { api.isLoggedIn } }
    val creators by db.creators.collectAsState()
    val albums by db.albums.collectAsState()
    val audioItems by db.audioItems.collectAsState()
    val syncState by syncService.state.collectAsState()

    var showLogoutAlert by remember { mutableStateOf(false) }
    var showClearDataAlert by remember { mutableStateOf(false) }
    var showClearCacheAlert by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var debugRefreshToken by remember { mutableIntStateOf(0) }
    var incrementalStatus by remember { mutableStateOf<String?>(null) }
    var deleteEpisodeId by remember { mutableStateOf("") }
    var fixtureAlbumId by remember { mutableStateOf("") }
    var fixtureJson by remember { mutableStateOf("") }
    var cacheSizeBytes by remember { mutableStateOf(0L) }

    // ── OTA 更新状态 ──────────────────────────────────────────────
    var showUpdateDialog by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
    var updateCheckStatus by remember { mutableStateOf<String?>(null) } // "checking" | "up-to-date" | error msg
    var currentVersionCode by remember { mutableIntStateOf(0) }
    var currentVersionName by remember { mutableStateOf("") }

    // 在 composable 上下文中获取 context（用于版本信息读取）
    val appContext = LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        cacheSizeBytes = calculateCacheSize()
        // 获取当前版本信息
        currentVersionCode = readVersionCode(appContext)
        currentVersionName = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }

    // ── OTA 更新检查逻辑 ────────────────────────────────────────────
    // 用独立 trigger 触发检查，避免与 currentVersionCode 初始化产生竞态
    var checkTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(checkTrigger) {
        if (checkTrigger == 0) return@LaunchedEffect
        updateCheckStatus = "checking"
        try {
            // 实时读取 versionCode，确保不拿到初始值 0
            val versionCode = readVersionCode(appContext)
            val result = AppUpdater.checkForUpdate(versionCode)
            if (result != null) {
                showUpdateDialog = result
                updateCheckStatus = null
            } else {
                updateCheckStatus = "up-to-date"
            }
        } catch (e: Exception) {
            val msg = (e.toString()).take(80)
            updateCheckStatus = "error: $msg"
        }
    }

    // ── 更新弹窗 ─────────────────────────────────────────────────────
    val info = showUpdateDialog
    if (info != null) {
        UpdateDialog(
            updateInfo = info,
            onDismiss = { showUpdateDialog = null },
            onInstallReady = { showUpdateDialog = null }
        )
    }

    Scaffold(
        containerColor = OhvColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        color = OhvColors.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
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
                        icon = Icons.AutoMirrored.Filled.Login,
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
            if (BuildConfig.DEBUG) {
                SettingsSection(title = "调试诊断") {
                    SettingsButton(
                        icon = Icons.Default.BugReport,
                        label = "打开诊断面板",
                        iconTint = OhvColors.Accent,
                        labelColor = OhvColors.Accent,
                        onClick = {
                            debugRefreshToken++
                            showDebugDialog = true
                        }
                    )
                }
            }

            // ── 关于 ──────────────────────────────────────────────────────────
            SettingsSection(title = "关于") {
                SettingsRow(
                    icon = Icons.Default.Info,
                    label = "版本",
                    trailing = {
                        val context = LocalContext.current
                        val version = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                        } catch (_: Exception) { "—" }
                        Text(version, color = OhvColors.SecondaryText, fontSize = 13.sp)
                    }
                )
                HorizontalDivider(color = OhvColors.Separator)

                // 更新状态 / 检查按钮
                when (updateCheckStatus) {
                    "checking" -> {
                        SettingsRow(
                            icon = Icons.Default.Sync,
                            label = "检查更新",
                            trailing = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = OhvColors.Accent,
                                    strokeWidth = 2.dp
                                )
                            }
                        )
                    }
                    "up-to-date" -> {
                        SettingsRow(
                            icon = Icons.Default.CheckCircle,
                            label = "已是最新版",
                            trailing = { Text("v$currentVersionName", color = Color(0xFF34C759), fontSize = 13.sp) }
                        )
                    }
                    else -> {
                        if (updateCheckStatus != null && updateCheckStatus != "up-to-date") {
                            SettingsButton(
                                icon = Icons.Default.Warning,
                                label = "检查失败，点击重试",
                                labelColor = Color(0xFFFF9500),
                                iconTint = Color(0xFFFF9500),
                                onClick = { updateCheckStatus = null; checkTrigger++ }
                            )
                        } else {
                    SettingsButton(
                        icon = Icons.Default.SystemUpdate,
                        label = "检查更新",
                        onClick = { checkTrigger++ }
                    )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showDebugDialog) {
        // The token is intentionally read when the dialog recomposes, so the
        // panel can refresh without introducing a second logging state store.
        @Suppress("UNUSED_VARIABLE")
        val refresh = debugRefreshToken
        fun applyFixtureTemplate(scenario: String) {
            val albumId = fixtureAlbumId.trim()
            if (albumId.isBlank()) {
                incrementalStatus = "请先填写专辑 ID"
                return
            }
            try {
                fixtureJson = DebugCatalogFixtures.templateJson(
                    albumId = albumId,
                    scenario = scenario,
                    existingItems = db.audioItemsForAlbum(albumId)
                )
                incrementalStatus = DebugCatalogFixtures.setJson(albumId, fixtureJson)
                debugRefreshToken++
            } catch (e: Exception) {
                incrementalStatus = "fixture 生成失败：${e.message ?: "未知错误"}"
            }
        }
        AlertDialog(
            onDismissRequest = { showDebugDialog = false },
            title = { Text("调试诊断", color = OhvColors.White) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "日志 ${DebugDiagnostics.count()} 条",
                        color = OhvColors.SecondaryText,
                        fontSize = 12.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .verticalScroll(rememberScrollState())
                            .background(OhvColors.Background, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            DebugDiagnostics.exportText(120).ifBlank { "暂无诊断日志" },
                            color = OhvColors.SecondaryText,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { DebugDiagnostics.clear(); debugRefreshToken++ }) {
                            Text("清除日志", color = Color(0xFFFF9500))
                        }
                        TextButton(onClick = { debugRefreshToken++ }) {
                            Text("刷新", color = OhvColors.Accent)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { progressStore.clearAll(); debugRefreshToken++ }) {
                            Text("清除进度", color = OhvColors.SecondaryText)
                        }
                        TextButton(onClick = { clearAudioCache(); cacheSizeBytes = 0L; debugRefreshToken++ }) {
                            Text("清除缓存", color = OhvColors.SecondaryText)
                        }
                    }
                    Text("目录 fixture", color = OhvColors.White, fontSize = 12.sp)
                    OutlinedTextField(
                        value = fixtureAlbumId,
                        onValueChange = { fixtureAlbumId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("专辑 ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OhvColors.Accent,
                            unfocusedBorderColor = OhvColors.Separator,
                            focusedLabelColor = OhvColors.Accent,
                            unfocusedLabelColor = OhvColors.SecondaryText,
                            focusedTextColor = OhvColors.White,
                            unfocusedTextColor = OhvColors.White
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            fixtureAlbumId = albums.firstOrNull()?.id.orEmpty()
                            incrementalStatus = if (fixtureAlbumId.isBlank()) "没有可用专辑" else "已填入首条专辑 ID"
                        }) {
                            Text("填首条专辑", color = OhvColors.SecondaryText)
                        }
                        TextButton(
                            enabled = fixtureAlbumId.isNotBlank() && fixtureJson.isNotBlank(),
                            onClick = {
                                try {
                                    incrementalStatus = DebugCatalogFixtures.setJson(
                                        fixtureAlbumId.trim(),
                                        fixtureJson
                                    )
                                    debugRefreshToken++
                                } catch (e: Exception) {
                                    incrementalStatus = "fixture 应用失败：${e.message ?: "未知错误"}"
                                }
                            }
                        ) {
                            Text("应用 JSON", color = OhvColors.Accent)
                        }
                        TextButton(onClick = {
                            val albumId = fixtureAlbumId.trim()
                            if (albumId.isBlank()) {
                                incrementalStatus = "请先填写专辑 ID"
                            } else {
                                DebugCatalogFixtures.clearFixture(albumId)
                                fixtureJson = ""
                                incrementalStatus = "已清除该专辑 fixture"
                                debugRefreshToken++
                            }
                        }) {
                            Text("清除 fixture", color = OhvColors.SecondaryText)
                        }
                    }
                    OutlinedTextField(
                        value = fixtureJson,
                        onValueChange = { fixtureJson = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 120.dp),
                        label = { Text("fixture JSON") },
                        maxLines = 5,
                        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OhvColors.Accent,
                            unfocusedBorderColor = OhvColors.Separator,
                            focusedLabelColor = OhvColors.Accent,
                            unfocusedLabelColor = OhvColors.SecondaryText,
                            focusedTextColor = OhvColors.White,
                            unfocusedTextColor = OhvColors.White
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_NEW) }) {
                            Text("新增", color = OhvColors.Accent)
                        }
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_CHANGED) }) {
                            Text("变更", color = OhvColors.Accent)
                        }
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_EMPTY) }) {
                            Text("空目录", color = Color(0xFFFF9500))
                        }
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_DUPLICATE) }) {
                            Text("重复 ID", color = Color(0xFFFF9500))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_OMIT_LAST) }) {
                            Text("缺最后一条", color = OhvColors.SecondaryText)
                        }
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_ERROR) }) {
                            Text("模拟错误", color = Color(0xFFFF3B30))
                        }
                        TextButton(onClick = { applyFixtureTemplate(DebugCatalogFixtures.SCENARIO_TIMEOUT) }) {
                            Text("模拟超时", color = Color(0xFFFF3B30))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            incrementalUpdate.markAllAlbumsDue()
                            incrementalStatus = "已将所有专辑标记为待检查"
                            debugRefreshToken++
                        }) {
                            Text("标记待检查", color = OhvColors.SecondaryText)
                        }
                        TextButton(
                            enabled = isLoggedIn,
                            onClick = {
                                incrementalStatus = "增量检查中..."
                                coroutineScope.launch {
                                    val result = incrementalUpdate.checkAlbums(
                                        albumIds = albums.map { it.id },
                                        force = true
                                    )
                                    incrementalStatus = if (result.failures.isEmpty()) {
                                        "完成：新增 ${result.addedCount}，变更 ${result.changedCount}"
                                    } else {
                                        "完成：新增 ${result.addedCount}，失败 ${result.failures.size}"
                                    }
                                    debugRefreshToken++
                                }
                            }
                        ) {
                            Text("立即检查", color = if (isLoggedIn) OhvColors.Accent else OhvColors.SecondaryText)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            db.markAllAlbumUpdatesRead()
                            incrementalStatus = "已清除所有更新提醒"
                            debugRefreshToken++
                        }) {
                            Text("清除提醒", color = OhvColors.SecondaryText)
                        }
                        TextButton(onClick = {
                            deleteEpisodeId = audioItems.firstOrNull()?.id.orEmpty()
                            incrementalStatus = if (deleteEpisodeId.isBlank()) {
                                "没有可填入的本地单集"
                            } else {
                                "已填入首条单集 ID"
                            }
                        }) {
                            Text("填首条 ID", color = OhvColors.SecondaryText)
                        }
                    }
                    OutlinedTextField(
                        value = deleteEpisodeId,
                        onValueChange = { deleteEpisodeId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("单集 ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OhvColors.Accent,
                            unfocusedBorderColor = OhvColors.Separator,
                            focusedLabelColor = OhvColors.Accent,
                            unfocusedLabelColor = OhvColors.SecondaryText,
                            focusedTextColor = OhvColors.White,
                            unfocusedTextColor = OhvColors.White
                        )
                    )
                    TextButton(
                        enabled = deleteEpisodeId.isNotBlank(),
                        onClick = {
                            val id = deleteEpisodeId.trim()
                            val item = audioItems.firstOrNull { it.id == id }
                            if (item == null) {
                                incrementalStatus = "未找到单集：$id"
                            } else {
                                audioCache.removeCache(id)
                                db.deleteAudioItem(id)
                                incrementalStatus = "已删除本地单集：${item.title.take(20)}"
                                deleteEpisodeId = ""
                            }
                            debugRefreshToken++
                        }
                    ) {
                        Text("删除指定单集", color = Color(0xFFFF9500))
                    }
                    incrementalStatus?.let { status ->
                        Text(status, color = OhvColors.SecondaryText, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        playerManager.clearAll()
                        DebugCatalogFixtures.clearAll()
                        db.clearAll()
                        progressStore.clearAll()
                        debugRefreshToken++
                    }) {
                        Text("清除数据库和播放状态", color = Color(0xFFFF3B30))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugDialog = false }) {
                    Text("关闭", color = OhvColors.Accent)
                }
            },
            containerColor = OhvColors.CardBackground
        )
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
                    playerManager.clearAll()
                    DebugCatalogFixtures.clearAll()
                    progressStore.clearAll()
                    audioCache.clearCache()
                    db.clearAll()
                    kvStore.clear()
                    api.logout()
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
                    DebugCatalogFixtures.clearAll()
                    playerManager.clearAll()
                    db.clearAll()
                    progressStore.clearAll()
                    audioCache.clearCache()
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

/**
 * 读取当前 App 的 versionCode（Int）。
 *
 * PackageInfo.versionCode 在 API 34+ 被 deprecated（应用可能 > 2^31-1），
 * 推荐改用 PackageInfoCompat.getLongVersionCode 返回 Long。
 * 本工程 versionCode 远小于 Int 上限，且 AppUpdater 接口签名固定为 Int，
 * 故用 @Suppress("DEPRECATION") 容忍警告，避免引入 core-ktx 直接依赖。
 */
@Suppress("DEPRECATION")
private fun readVersionCode(context: android.content.Context): Int = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
} catch (_: Exception) {
    0
}

private fun formatDate(epochMs: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

private fun calculateCacheSize(): Long {
    val cacheDir = java.io.File(com.ohv.shared.platform.getCacheDir())
    return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun clearAudioCache() {
    // v1.6：调用 AudioCacheService.clearCache() 统一管理（与 iOS 对齐）
    // 避免直接操作文件系统，丢失缓存大小统计等内部状态
    AudioCacheService.shared.clearCache()
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${(value * 10).roundToLong() / 10.0} ${units[digitGroups]}"
}
