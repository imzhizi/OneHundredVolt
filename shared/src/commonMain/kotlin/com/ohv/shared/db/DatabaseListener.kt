package com.ohv.shared.db

import com.ohv.shared.models.Album
import com.ohv.shared.models.AudioItem
import com.ohv.shared.models.Creator

/**
 * 数据库变更回调（Kotlin/Native cinterop 友好）
 *
 * 由于 Kotlin/Native 把 abstract/open 方法暴露为 final，Swift 无法 subclass
 * 抽象类。改用 3 个独立的函数类型属性，Swift 可直接传 closure。
 *
 * 使用方式（Swift）：
 * ```swift
 * let svc = Shared.DatabaseService.shared
 * svc.setOnCreatorsChangedCallback { creators in
 *     // 在这里更新 UI
 * }
 * ```
 *
 * 限制：仅支持单个回调（iOS 只需要一个 wrapper）。多 listener 场景
 * 应使用 Flow（Android 用 StateFlow，iOS 用 callback 即可）。
 */
typealias DatabaseChangeCallback = (List<Creator>) -> Unit
typealias AlbumsChangeCallback = (List<Album>) -> Unit
typealias AudioItemsChangeCallback = (List<AudioItem>) -> Unit