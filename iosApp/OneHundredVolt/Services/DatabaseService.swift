import Foundation
import Shared

/// 本地数据库服务（Shared.DatabaseService 的 iOS 适配层）
///
/// v1.7 Phase A.3：用 closure callback 替代 100ms 轮询
/// v1.7 Phase B：Models 改 typealias 后，方法直接接受 Creator/Album/AudioItem
///               （无需转换，已经是 Shared.Creator 等）
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
                self?.creators = list
            }
        }
        backend.setOnAlbumsChangedCallback { [weak self] list in
            Task { @MainActor in
                self?.albums = list
            }
        }
        backend.setOnAudioItemsChangedCallback { [weak self] list in
            Task { @MainActor in
                self?.audioItems = list
            }
        }
    }

    // MARK: - Creator CRUD

    func upsertCreator(_ creator: Creator) {
        backend.upsertCreator(creator: creator)
    }

    func upsertCreators(_ list: [Creator]) {
        backend.upsertCreators(list: list)
    }

    func deleteCreator(id: String) {
        backend.deleteCreator(id: id)
    }

    func selectedCreators() -> [Creator] {
        creators.filter { $0.isSelected }
    }

    // MARK: - Album CRUD

    func upsertAlbum(_ album: Album) {
        backend.upsertAlbum(album: album)
    }

    func upsertAlbums(_ list: [Album]) {
        backend.upsertAlbums(list: list)
    }

    func albums(for creatorId: String) -> [Album] {
        backend.albumsForCreator(creatorId: creatorId)
    }

    func markAllAlbumsDue() {
        backend.markAllAlbumsDue()
    }

    func markAlbumUpdatesRead(albumId: String) {
        backend.markAlbumUpdatesRead(albumId: albumId)
    }

    func markAllAlbumUpdatesRead() {
        backend.markAllAlbumUpdatesRead()
    }

    // MARK: - AudioItem CRUD

    func upsertAudioItem(_ item: AudioItem) {
        backend.upsertAudioItem(item: item)
    }

    func upsertAudioItems(_ list: [AudioItem]) {
        backend.upsertAudioItems(items: list)
    }

    func audioItems(for albumId: String) -> [AudioItem] {
        backend.audioItemsForAlbum(albumId: albumId)
    }

    func audioItem(id: String) -> AudioItem? {
        backend.audioItemById(id: id)
    }

    func deleteAudioItem(id: String) {
        backend.deleteAudioItem(id: id)
    }

    // MARK: - 清空

    func clearAll() {
        backend.clearAll()
    }
}
