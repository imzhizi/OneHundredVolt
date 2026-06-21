package com.ohv.shared.util

/**
 * Android/JVM 实现：使用 java.lang.System
 */
actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()