package com.ohv.shared.models

import kotlinx.serialization.Serializable

/**
 * 爱发电创作者（对应 /api/my/sponsoring 中的 user 对象）
 * 移植自 iOS Creator.swift
 */
@Serializable
data class Creator(
    val id: String,                     // user_id
    var name: String,
    var avatarUrl: String? = null,
    var urlSlug: String,                // 拼接 https://afdian.com/a/{urlSlug}
    var doing: String? = null,          // 创作类型，如"电影评论"
    var lastSyncedAt: Long? = null,     // epoch milliseconds（替代 Swift Date）
    var isSelected: Boolean = true
) {
    val afdianPageUrl: String
        get() = "https://afdian.com/a/$urlSlug"
}
