package com.ohv.shared.sync

import com.ohv.shared.models.AudioItem

data class AlbumUpdateDiff(
    val added: List<AudioItem>,
    val changed: List<AudioItem>
) {
    val totalChanged: Int get() = added.size + changed.size
}

/** 只比较可持久化的 AudioItem 字段，忽略每次播放前获取的临时 audioUrl。 */
object AlbumUpdateDiffCalculator {
    fun compare(existing: List<AudioItem>, remote: List<AudioItem>): AlbumUpdateDiff {
        val localById = existing.associateBy { it.id }
        val added = remote.filter { it.id !in localById }
        val changed = remote.filter { item ->
            localById[item.id]?.let { local -> local != item } == true
        }
        return AlbumUpdateDiff(added = added, changed = changed)
    }
}
