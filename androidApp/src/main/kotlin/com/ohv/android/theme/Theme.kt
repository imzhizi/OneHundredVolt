package com.ohv.android.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与 iOS Theme.swift 保持一致的颜色体系
object OhvColors {
    val Accent = Color(0xFFFFB800)          // 黄色主色（与 iOS #FFB800 对齐）
    val Background = Color(0xFF0A0A0A)      // 深黑背景
    val CardBackground = Color(0xFF1A1A1A)  // 卡片背景
    val SecondaryText = Color(0xFF888888)   // 次要文字
    val Separator = Color(0xFF2A2A2A)       // 分割线
    val White = Color(0xFFFFFFFF)
    val DestructiveRed = Color(0xFFFF3B30)
}

private val DarkColorScheme = darkColorScheme(
    primary = OhvColors.Accent,
    onPrimary = Color.Black,
    background = OhvColors.Background,
    surface = OhvColors.CardBackground,
    onBackground = OhvColors.White,
    onSurface = OhvColors.White,
    secondary = OhvColors.SecondaryText,
    error = OhvColors.DestructiveRed
)

@Composable
fun OhvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
