import Foundation
import Shared

/// 本地数据库服务（Shared.DatabaseService 的 iOS 适配层）
///
/// v1.6：基于 shared module 的 DatabaseService（KMP）作为存储后端
///  - 写入：直接调用 Shared.DatabaseService（原子写入、损坏恢复）
///  - 读取：轮询 Shared.DatabaseService 的 StateFlow 并同步到本地 @Observable 属性
///
/// @Observable 保留以维持 SwiftUI views 的观察语义
@Observable
final class DatabaseService {

    static let shared = DatabaseService()

    // MARK: - 内存存储（订阅 Shared.StateFlow 同步）

    private(set) var creators: [Creator] = []
    private(set) var albums: [Album] = []
    private(set) var audioItems: [AudioItem] = []

    /// Shared 模块的 KMP 数据库（用 Shared. 前缀避免与本类名冲突）
    private let backend: Shared.DatabaseService = Shared.DatabaseService.companion.shared

    /// 轮询 Shared.DatabaseService StateFlow 变化的 Task
    private var observerTask: Task<Void, Never>?

    private init() {
        // 同步初始值
        syncFromBackend()

        // 轮询后续变更（StateFlow 在 iOS KMP 不暴露 AsyncSequence，
        // 用 Task 周期 poll 替代，100ms 间隔足够 UI 流畅）
        observerTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard let self else { return }
                await MainActor.run {
                    self.syncFromBackend()
                }
            }
        }
    }

    /// 从 Shared.DatabaseService 同步 StateFlow 值到本地 @Observable 属性
    private func syncFromBackend() {
        let sharedCreators = (backend.creators.value as? [Shared.Creator]) ?? []
        let sharedAlbums = (backend.albums.value as? [Shared.Album]) ?? []
        let sharedItems = (backend.audioItems.value as? [Shared.AudioItem]) ?? []
        creators = sharedCreators.map { Creator($0) }
        albums = sharedAlbums.map { Album($0) }
        audioItems = sharedItems.map { AudioItem($0) }
    }

    deinit {
        observerTask?.cancel()
    }

    // MARK: - Creator CRUD

    func upsertCreator(_ creator: Creator) {
        backend.upsertCreator(creator: creator.toSharedCreator())
    }

    func upsertCreators(_ list: [Creator]) {
        backend.upsertCreators(list: list.toSharedCreators())
    }

    func deleteCreator(id: String) {
        backend.deleteCreator(id: id)
    }

    func selectedCreators() -> [Creator] {
        creators.filter { $0.isSelected }
    }

    // MARK: - Album CRUD

    func upsertAlbum(_ album: Album) {
        backend.upsertAlbum(album: album.toSharedAlbum())
    }

    func upsertAlbums(_ list: [Album]) {
        backend.upsertAlbums(list: list.toSharedAlbums())
    }

    func albums(for creatorId: String) -> [Album] {
        backend.albumsForCreator(creatorId: creatorId).map { Album($0) }
    }

    // MARK: - AudioItem CRUD

    func upsertAudioItem(_ item: AudioItem) {
        backend.upsertAudioItem(item: item.toSharedAudioItem())
    }

    func upsertAudioItems(_ list: [AudioItem]) {
        backend.upsertAudioItems(items: list.toSharedAudioItems())
    }

    func audioItems(for albumId: String) -> [AudioItem] {
        backend.audioItemsForAlbum(albumId: albumId).map { AudioItem($0) }
    }

    func audioItem(id: String) -> AudioItem? {
        backend.audioItemById(id: id).map { AudioItem($0) }
    }

    // MARK: - 清空

    func clearAll() {
        backend.clearAll()
    }
}