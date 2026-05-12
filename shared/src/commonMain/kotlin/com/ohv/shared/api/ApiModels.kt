package com.ohv.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── 支持的创作者列表 ───────────────────────────────────────────────────────────

@Serializable
internal data class SponsoringResponse(
    val ec: Int,
    val em: String? = null,
    val data: SponsoringData
) {
    @Serializable
    data class SponsoringData(val sponsoring: List<SponsoringItem>)

    @Serializable
    data class SponsoringItem(val user: SponsoringUser)

    @Serializable
    data class SponsoringUser(
        @SerialName("user_id") val userId: String,
        val name: String,
        val avatar: String? = null,
        @SerialName("url_slug") val urlSlug: String,
        val creator: SponsoringCreator? = null
    )

    @Serializable
    data class SponsoringCreator(val doing: String? = null)
}

// ─── 专辑列表 ──────────────────────────────────────────────────────────────────

@Serializable
internal data class AlbumListResponse(
    val ec: Int,
    val em: String? = null,
    val data: AlbumListData
) {
    @Serializable
    data class AlbumListData(
        val list: List<AlbumItem>,
        @SerialName("has_more") val hasMore: Int
    )

    @Serializable
    data class AlbumItem(
        @SerialName("album_id") val albumId: String,
        val title: String,
        val cover: String? = null,
        val content: String? = null,
        @SerialName("post_count") val postCount: Int,
        val bought: Int
    )
}

// ─── 专辑目录 ──────────────────────────────────────────────────────────────────

@Serializable
internal data class AlbumCatalogResponse(
    val ec: Int,
    val em: String? = null,
    val data: CatalogData
) {
    @Serializable
    data class CatalogData(
        val list: List<CatalogPost>,
        @SerialName("has_more") val hasMore: Int
    )

    @Serializable
    data class CatalogPost(
        @SerialName("post_id") val postId: String,
        @SerialName("user_id") val userId: String,
        val title: String,
        val cover: String? = null,
        @SerialName("has_audio") val hasAudio: Int,
        val rank: Long? = null,
        @SerialName("publish_time") val publishTime: Long? = null,
        val ext: PostExt? = null
    )

    @Serializable
    data class PostExt(
        @SerialName("audio_duration") val audioDuration: Int? = null
    )
}

// ─── 帖子详情 ──────────────────────────────────────────────────────────────────

@Serializable
internal data class PostDetailResponse(
    val ec: Int,
    val em: String? = null,
    val data: PostDetailData? = null
) {
    @Serializable
    data class PostDetailData(val post: PostDetail)

    @Serializable
    data class PostDetail(
        @SerialName("post_id") val postId: String,
        val title: String,
        val audio: String? = null,
        @SerialName("has_right") val hasRight: Int? = null
    )
}
