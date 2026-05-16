package com.ohv.android

import android.app.Application
import com.ohv.android.platform.AudioPlayerManager
import com.ohv.shared.platform.AndroidContext

class OhvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
        AudioPlayerManager.init(this)
    }
}
