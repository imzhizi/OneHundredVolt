import Foundation

/// 本地数据库服务（基于 JSON 文件 + 内存缓存，无需第三方依赖）
/// 存储路径：Documents/ohv_db.json
@Observable
final class DatabaseService {

    static let shared = DatabaseService()

    // MARK: - 内存存储

    private(set) var creators: [Creator] = []
    private(set) var albums: [Album] = []
    private(set) var audioItems: [AudioItem] = []

    private let dbFileURL: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("ohv_db.json")
    }()

    /// 异步写磁盘专用队列（串行，避免并发写冲突）
    private let ioQueue = DispatchQueue(label: "com.ohv.db.io", qos: .utility)

    /// 防抖 save：当批量写入时合并成一次磁盘写
    private var pendingSave = false

    private init() {
        load()
    }

    // MARK: - Creator CRUD

    func upsertCreator(_ creator: Creator) {
        if let idx = creators.firstIndex(where: { $0.id == creator.id }) {
            creators[idx] = creator
        } else {
            creators.append(creator)
        }
        save()
    }

    /// 批量 upsert：内存全部更新完毕后只写一次磁盘
    func upsertCreators(_ list: [Creator]) {
        for c in list {
            if let idx = creators.firstIndex(where: { $0.id == c.id }) {
                creators[idx] = c
            } else {
                creators.append(c)
            }
        }
        save()
    }

    func deleteCreator(id: String) {
        creators.removeAll { $0.id == id }
        albums.removeAll { $0.creatorId == id }
        audioItems.removeAll { $0.creatorId == id }
        save()
    }

    func selectedCreators() -> [Creator] {
        creators.filter { $0.isSelected }
    }

    // MARK: - Album CRUD

    func upsertAlbum(_ album: Album) {
        if let idx = albums.firstIndex(where: { $0.id == album.id }) {
            albums[idx] = album
        } else {
            albums.append(album)
        }
        save()
    }

    /// 批量 upsert：内存全部更新完毕后只写一次磁盘
    func upsertAlbums(_ list: [Album]) {
        for a in list {
            if let idx = albums.firstIndex(where: { $0.id == a.id }) {
                albums[idx] = a
            } else {
                albums.append(a)
            }
        }
        save()
    }

    func albums(for creatorId: String) -> [Album] {
        albums.filter { $0.creatorId == creatorId }
              .sorted { $0.sortOrder < $1.sortOrder }
    }

    // MARK: - AudioItem CRUD

    func upsertAudioItem(_ item: AudioItem) {
        if let idx = audioItems.firstIndex(where: { $0.id == item.id }) {
            var updated = item
            updated.audioUrl = audioItems[idx].audioUrl   // 保留已缓存的 audioUrl
            audioItems[idx] = updated
        } else {
            audioItems.append(item)
        }
        save()
    }

    /// 批量 upsert：内存全部更新完毕后只写一次磁盘
    func upsertAudioItems(_ list: [AudioItem]) {
        for item in list {
            if let idx = audioItems.firstIndex(where: { $0.id == item.id }) {
                var updated = item
                updated.audioUrl = audioItems[idx].audioUrl
                audioItems[idx] = updated
            } else {
                audioItems.append(item)
            }
        }
        save()
    }

    func audioItems(for albumId: String) -> [AudioItem] {
        audioItems.filter { $0.albumId == albumId }
                  .sorted { $0.sortOrder < $1.sortOrder }
    }

    func audioItem(id: String) -> AudioItem? {
        audioItems.first { $0.id == id }
    }

    // MARK: - 清空

    func clearAll() {
        creators.removeAll()
        albums.removeAll()
        audioItems.removeAll()
        pendingSave = false
        ioQueue.async { [dbFileURL] in
            try? FileManager.default.removeItem(at: dbFileURL)
        }
    }

    // MARK: - 持久化（异步写磁盘，防抖合并）

    private struct DBSnapshot: Codable {
        var creators: [Creator]
        var albums: [Album]
        var audioItems: [AudioItem]
    }

    /// 标记需要保存，用 DispatchQueue 防抖——同一 run loop 内多次调用只写一次磁盘
    private func save() {
        guard !pendingSave else { return }
        pendingSave = true
        // 下一个 run loop tick 执行，让当前批量操作全部完成后再读取快照
        DispatchQueue.main.async { [weak self] in
            self?.flushToDisk()
        }
    }

    private func flushToDisk() {
        pendingSave = false
        let snapshot = DBSnapshot(creators: creators, albums: albums, audioItems: audioItems)
        // 序列化在主线程（访问 @Observable 属性安全），写文件在 IO 队列
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        ioQueue.async { [dbFileURL] in
            try? data.write(to: dbFileURL, options: .atomic)
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: dbFileURL),
              let snapshot = try? JSONDecoder().decode(DBSnapshot.self, from: data)
        else { return }
        creators   = snapshot.creators
        albums     = snapshot.albums
        audioItems = snapshot.audioItems
    }
}
