package com.ohv.android

import android.app.Application
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.shared.platform.AndroidContext

class OhvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注入 Android Context 到 shared 层
        AndroidContext.init(this)
        // 初始化音频播放管理器（单例，连接 AudioPlaybackService）
        AudioPlayerManager.init(this)
    }
}
