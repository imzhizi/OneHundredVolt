package com.ohv.shared.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 音频条目（对应爱发电的帖子 post，has_audio == 1）
 * 移植自 iOS AudioItem.swift
 */
@Serializable
data class AudioItem(
    val id: String,                     // post_id
    var albumId: String,                // 所属专辑 album_id
    var creatorId: String,              // 所属创作者 user_id
    var title: String,
    var coverUrl: String? = null,
    var duration: Double,               // 音频时长（秒），替代 Swift TimeInterval
    var sortOrder: Long,                // 在专辑内的排序（rank）
    var publishTime: Long               // epoch milliseconds，替代 Swift Date
) {
    // audioUrl 带签名、有时效性，每次播放前重新获取，不持久化
    @Transient
    var audioUrl: String? = null

    /** 进度百分比 0.0 ~ 1.0 */
    fun progressRatio(progress: Double): Double {
        if (duration <= 0) return 0.0
        return minOf(1.0, progress / duration)
    }
}
