package com.ohv.shared.util

/**
 * 跨平台时间工具
 *
 * 替代 java.lang.System.currentTimeMillis()（在 Kotlin/Native 不可用）。
 * 包装 kotlin.time API 保证 KMP 兼容。
 */
expect fun currentTimeMillis(): Long