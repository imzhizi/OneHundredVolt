package com.ohv.shared.models

import kotlinx.serialization.Serializable

/**
 * 专辑（对应爱发电的合集）
 * 移植自 iOS Album.swift
 */
@Serializable
data class Album(
    val id: String,                         // album_id
    var creatorId: String,                  // 所属创作者 user_id
    var title: String,
    var coverUrl: String? = null,
    var description: String? = null,
    var audioCount: Int,                    // 有音频的帖子数量
    var totalDuration: Double,              // 总时长（秒），替代 Swift TimeInterval
    var sortOrder: Int,                     // 在创作者页面的显示顺序
    var lastSyncedAt: Long? = null,         // epoch milliseconds
    var lastCheckedAt: Long? = null,        // 最近一次完整检查目录的时间
    var lastContentChangeAt: Long? = null,  // 最近一次发现单集变化的时间
    var unreadUpdateCount: Int = 0,         // 尚未查看的新增或变更数量
    /**
     * 是否有播放权限：已购买付费专辑 或 免费专辑 均为 true；付费未购买为 false
     * 通过 bought=1 直接确认，或 bought=0 时探测 get-detail.has_right 确认
     */
    var isAccessible: Boolean
)
