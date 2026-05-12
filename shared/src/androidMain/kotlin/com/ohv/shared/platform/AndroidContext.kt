package com.ohv.shared.platform

import android.content.Context

/**
 * Android Context 单例注入点
 * 在 Application.onCreate() 里调用 AndroidContext.init(this)
 */
object AndroidContext {
    private lateinit var _context: Context

    fun init(context: Context) {
        _context = context.applicationContext
    }

    val context: Context
        get() = _context
}
