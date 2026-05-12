package com.ohv.shared.platform

actual fun getDocumentsDir(): String = AndroidContext.context.filesDir.absolutePath
actual fun getCacheDir(): String = AndroidContext.context.cacheDir.absolutePath
