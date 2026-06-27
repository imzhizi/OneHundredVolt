import Foundation
import Shared

/// 本地数据库服务（Shared.DatabaseService 的 iOS 适配层）
///
/// v1.7 Phase A.3：用 closure callback 替代 100ms 轮询
///  - Shared 暴露 setOnXxxChangedCallback（Kotlin function type properties）
///  - iOS 传 Swift closure 注册，Shared 在变更时调用
///  - 无轮询，UI 更新即时
///  - 实现细节：受 Kotlin/Native cinterop 限制，abstract class 无法 subclass
///    改用 function type callbacks（详见 DatabaseListener.kt）
@Observable
final class DatabaseService {

    static let shared = DatabaseService()

    private(set) var creators: [Creator] = []
    private(set) var albums: [Album] = []
    private(set) var audioItems: [AudioItem] = []

    private let backend = Shared.DatabaseService.companion.shared

    private init() {
        // 注册 callback：Shared 变更时调用
        backend.setOnCreatorsChangedCallback { [weak self] list in
            Task { @MainActor in
                self?.creators = list.map { Creator($0) }
            }
        }
        backend.setOnAlbumsChangedCallback { [weak self] list in
            Task { @MainActor in
                self?.albums = list.map { Album($0) }
            }
        }
        backend.setOnAudioItemsChangedCallback { [weak self] list in
            Task { @MainActor in
                self?.audioItems = list.map { AudioItem($0) }
            }
        }
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