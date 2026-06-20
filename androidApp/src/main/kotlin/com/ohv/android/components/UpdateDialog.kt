package com.ohv.android.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ohv.android.platform.AppUpdater
import com.ohv.android.theme.OhvColors
import kotlinx.coroutines.launch

/**
 * 应用更新弹窗（三态：确认 -> 下载中 -> 下载完成/安装引导）
 */
@Composable
fun UpdateDialog(
    updateInfo: AppUpdater.UpdateInfo,
    onDismiss: () -> Unit,
    onInstallReady: () -> Unit
) {
    var phase by remember { mutableIntStateOf(0) } // 0=确认, 1=下载中, 2=完成
    var progress by remember { mutableStateOf(AppUpdater.DownloadProgress(0L, 0L)) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = { if (phase != 1) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = phase != 1, dismissOnClickOutside = phase != 1)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(OhvColors.CardBackground)
                .padding(24.dp)
        ) {
            // 用 when 而非 AnimatedVisibility，避免多 phase 同时渲染导致文字叠加
            when (phase) {
                0 -> ConfirmContent(
                    info = updateInfo,
                    onUpdate = {
                        phase = 1
                        downloadError = null
                        scope.launch {
                            try {
                                AppUpdater.downloadApk(context, updateInfo.downloadUrl)
                                    .collect { p ->
                                        progress = p
                                        if (p.done && p.bytesDownloaded > 100_000) {
                                            phase = 2
                                        } else if (p.done && p.bytesDownloaded <= 100_000) {
                                            downloadError = "下载失败，文件不完整"
                                            phase = 0
                                        }
                                    }
                            } catch (e: Exception) {
                                downloadError = e.message ?: e.javaClass.simpleName
                                phase = 0
                            }
                        }
                    },
                    onDismiss = onDismiss,
                    error = downloadError
                )
                1 -> DownloadingContent(
                    progress = progress,
                    onCancel = {
                        AppUpdater.cancelDownload(context)
                        onDismiss()
                    }
                )
                2 -> DownloadCompleteContent(
                    onInstall = {
                        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.packageManager.canRequestPackageInstalls()
                        } else true

                        if (canInstall) {
                            val apkUri = AppUpdater.getDownloadedApk(context)
                            if (apkUri != null) {
                                AppUpdater.installApk(context, apkUri)
                                onInstallReady()
                            }
                        } else {
                            // 跳转到系统设置引导用户开启「允许安装未知来源」
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        }
                    },
                    onDismiss = onDismiss
                )
            }
        }
    }
}

// ─── Phase 0: 确认更新 ──────────────────────────────────────────────────

@Composable
private fun ConfirmContent(
    info: AppUpdater.UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "发现新版本",
            color = OhvColors.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "v${info.versionName}",
            color = OhvColors.Accent,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        if (info.changelog.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                info.changelog.replace("\\n", "\n").take(200),
                color = OhvColors.SecondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "下载失败：$error",
                color = Color(0xFFFF3B30),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OhvColors.SecondaryText)
            ) {
                Text("稍后", fontSize = 14.sp)
            }
            Button(
                onClick = onUpdate,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OhvColors.Accent)
            ) {
                Text(
                    if (error != null) "重试" else "立即更新",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─── Phase 1: 下载中 ────────────────────────────────────────────────────

@Composable
private fun DownloadingContent(
    progress: AppUpdater.DownloadProgress,
    onCancel: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "正在下载",
            color = OhvColors.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = OhvColors.Accent,
            trackColor = OhvColors.SecondaryText.copy(alpha = 0.2f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatSize(progress.bytesDownloaded), color = OhvColors.SecondaryText, fontSize = 13.sp)
            Text("%.1f%%".format(progress.percent), color = OhvColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (progress.contentLength > 0) {
                Text(formatSize(progress.contentLength), color = OhvColors.SecondaryText, fontSize = 13.sp)
            } else {
                Spacer(modifier = Modifier.width(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OhvColors.SecondaryText)
        ) {
            Text("取消", fontSize = 14.sp)
        }
    }
}

// ─── Phase 2: 下载完成 ──────────────────────────────────────────────────

@Composable
private fun DownloadCompleteContent(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "下载完成 ✓",
            color = Color(0xFF34C759),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "点击安装，系统会询问是否允许安装未知来源应用；\n如果不想授权，也可以打开系统文件管理器，在「下载」文件夹里找到安装包手动安装。",
            color = OhvColors.SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onInstall,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OhvColors.Accent)
        ) {
            Text("立即安装", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(onClick = onDismiss) {
            Text("稍后安装", color = OhvColors.SecondaryText, fontSize = 13.sp)
        }
    }
}

// ─── 工具方法 ────────────────────────────────────────────────────────────

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}
