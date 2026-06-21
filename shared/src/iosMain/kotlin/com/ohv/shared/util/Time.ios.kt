@file:OptIn(ExperimentalForeignApi::class)

package com.ohv.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

/**
 * iOS / Kotlin/Native 实现：使用 platform.posix.gettimeofday
 *
 * 替代 NSDate.timeIntervalSince1970（Kotlin/Native 互操作不稳定）
 * 和 kotlin.system.getTimeMillis（Kotlin/Native 不可用）
 */
actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}