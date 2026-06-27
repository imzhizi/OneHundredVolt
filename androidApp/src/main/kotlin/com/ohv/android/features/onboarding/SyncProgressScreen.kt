package com.ohv.android.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohv.android.theme.OhvColors
import com.ohv.shared.api.AfdianApiService
import com.ohv.shared.db.DatabaseService
import com.ohv.shared.platform.KeyValueStore
import com.ohv.shared.platform.SecureStorage
import com.ohv.shared.sync.SyncService
import com.ohv.shared.sync.SyncState
import kotlinx.coroutines.launch

/**
 * 同步进度页（对应 iOS SyncProgressView.swift）
 *
 * @param selectedCreatorIds 用户在 CreatorSelectScreen 选中的创作者 ID 列表
 * @param onComplete         同步成功后用户点击「进入应用」时调用
 */
@Composable
fun SyncProgressScreen(
    selectedCreatorIds: List<String>,
    onComplete: () -> Unit
) {
    val secureStorage = remember { SecureStorage() }
    val api = remember { AfdianApiService(secureStorage) }
    val db = remember { DatabaseService.shared }
    val kvStore = remember { KeyValueStore() }
    val syncService = remember { SyncService(api, db, kvStore) }

    val syncState by syncService.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 进入页面立即开始同步
    LaunchedEffect(Unit) {
        syncService.fullSync(selectedCreatorIds)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OhvColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 图标
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(OhvColors.Accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("⚡", fontSize = 44.sp)
            }

            // 状态文字
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (val state = syncState) {
                    is SyncState.Idle, is SyncState.Syncing -> {
                        Text(
                            "正在同步...",
                            color = OhvColors.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (state is SyncState.Syncing) {
                            Text(
                                state.message,
                                color = OhvColors.SecondaryText,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    is SyncState.Success -> {
                        Text(
                            "同步完成 ✓",
                            color = Color(0xFF34C759), // iOS success green
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    is SyncState.Failed -> {
                        Text(
                            "同步失败",
                            color = Color(0xFFFF9500), // iOS warning orange
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            state.error.message ?: "未知错误",
                            color = OhvColors.SecondaryText,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 进度条（仅 Syncing 状态显示）
            val state = syncState
            if (state is SyncState.Syncing) {
                LinearProgressIndicator(
                    progress = { state.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = OhvColors.Accent,
                    trackColor = OhvColors.Separator
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 底部按钮
            when (syncState) {
                is SyncState.Success -> {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OhvColors.Accent,
                            contentColor = Color.Black
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("进入应用 →", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }

                is SyncState.Failed -> {
                    Button(
                        onClick = {
                            scope.launch {
                                syncService.fullSync(selectedCreatorIds)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OhvColors.Accent,
                            contentColor = Color.Black
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("重试", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }

                else -> {
                    // 同步中：无按钮
                    Spacer(modifier = Modifier.height(52.dp))
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
