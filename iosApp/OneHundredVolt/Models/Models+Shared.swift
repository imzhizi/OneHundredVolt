import Foundation
import Shared

// MARK: - 类型别名：Shared Models（避免命名冲突）
//
// Kotlin Shared module 的 Creator/Album/AudioItem 在 Swift 中被去前缀为同名类，
// 直接使用会与本文件内的 Swift struct 冲突。用 Shared 前缀别名访问。
typealias SharedCreator = Shared.Creator
typealias SharedAlbum = Shared.Album
typealias SharedAudioItem = Shared.AudioItem

// MARK: - Creator 转换

extension Creator {
    /// 从 Shared Creator 构造 Swift Creator
    init(_ shared: SharedCreator) {
        self.init(
            id: shared.id,
            name: shared.name,
            avatarUrl: shared.avatarUrl,
            urlSlug: shared.urlSlug,
            doing: shared.doing,
            lastSyncedAt: shared.lastSyncedAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) },
            isSelected: shared.isSelected
        )
    }

    /// 转 Shared Creator（用于写入 Shared.DatabaseService）
    func toSharedCreator() -> SharedCreator {
        SharedCreator(
            id: id,
            name: name,
            avatarUrl: avatarUrl,
            urlSlug: urlSlug,
            doing: doing,
            lastSyncedAt: lastSyncedAt.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000.0)) },
            isSelected: isSelected
        )
    }
}

extension Array where Element == Creator {
    init(_ shared: [SharedCreator]) {
        self.init(shared.map(Creator.init))
    }

    func toSharedCreators() -> [SharedCreator] {
        map { $0.toSharedCreator() }
    }
}

// MARK: - Album 转换

extension Album {
    init(_ shared: SharedAlbum) {
        self.init(
            id: shared.id,
            creatorId: shared.creatorId,
            title: shared.title,
            coverUrl: shared.coverUrl,
            description: shared.description,
            audioCount: Int(shared.audioCount),
            totalDuration: shared.totalDuration,
            sortOrder: Int(shared.sortOrder),
            lastSyncedAt: shared.lastSyncedAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) },
            isAccessible: shared.isAccessible
        )
    }

    func toSharedAlbum() -> SharedAlbum {
        SharedAlbum(
            id: id,
            creatorId: creatorId,
            title: title,
            coverUrl: coverUrl,
            description: description,
            audioCount: Int32(audioCount),
            totalDuration: totalDuration,
            sortOrder: Int32(sortOrder),
            lastSyncedAt: lastSyncedAt.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000.0)) },
            isAccessible: isAccessible
        )
    }
}

extension Array where Element == Album {
    init(_ shared: [SharedAlbum]) {
        self.init(shared.map(Album.init))
    }

    func toSharedAlbums() -> [SharedAlbum] {
        map { $0.toSharedAlbum() }
    }
}

// MARK: - AudioItem 转换

extension AudioItem {
    init(_ shared: SharedAudioItem) {
        self.init(
            id: shared.id,
            albumId: shared.albumId,
            creatorId: shared.creatorId,
            title: shared.title,
            coverUrl: shared.coverUrl,
            duration: shared.duration,
            sortOrder: Int(shared.sortOrder),
            publishTime: Date(timeIntervalSince1970: TimeInterval(shared.publishTime) / 1000.0),
            audioUrl: shared.audioUrl
        )
    }

    func toSharedAudioItem() -> SharedAudioItem {
        let item = SharedAudioItem(
            id: id,
            albumId: albumId,
            creatorId: creatorId,
            title: title,
            coverUrl: coverUrl,
            duration: duration,
            sortOrder: Int64(sortOrder),
            publishTime: Int64(publishTime.timeIntervalSince1970 * 1000.0)
        )
        item.audioUrl = audioUrl
        return item
    }
}

extension Array where Element == AudioItem {
    init(_ shared: [SharedAudioItem]) {
        self.init(shared.map(AudioItem.init))
    }

    func toSharedAudioItems() -> [SharedAudioItem] {
        map { $0.toSharedAudioItem() }
    }
}