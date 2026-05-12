package com.ohv.shared.platform

/**
 * 平台文件路径（expect/actual）
 * iOS actual → NSSearchPathForDirectoriesInDomains
 * Android actual → context.filesDir / context.cacheDir
 */
expect fun getDocumentsDir(): String
expect fun getCacheDir(): String
