import Foundation
import Shared

/// 同步服务：拉取创作者、专辑、音频目录
@Observable
final class SyncService {

    static let shared = SyncService()
    private init() {}

    private let api = AfdianAPIService.shared
    private let db  = DatabaseService.shared
    private let kvStore = Shared.KeyValueStore()

    // Shared KeyValueStore key：标记上次同步尚未完成（用于中断检测）
    private let syncInProgressKey = "sync_in_progress"
    private let lastSyncDateKey = "last_sync_date"

    // MARK: - 状态

    enum SyncState {
        case idle
        case syncing(message: String, progress: Double)
        case success
        case failed(Error)
    }

    var state: SyncState = .idle
    var isSyncing: Bool {
        if case .syncing = state { return true }
        return false
    }

    // MARK: - 启动时检测：若上次同步被中断，重置标志位

    /// 应用启动时调用：检测到上次同步未完成则重置标志位，保留已有数据
    func recoverIfNeeded() {
        guard kvStore.getBoolean(key: syncInProgressKey, default: false) else { return }
        kvStore.remove(key: syncInProgressKey)
    }

    // MARK: - 同步入口

    /// 完整同步（Onboarding 及设置页均调用此方法）
    func fullSync(selectedCreatorIds: [String]) async {
        await sync(creatorIds: selectedCreatorIds)
    }

    // MARK: - 核心同步逻辑

    private func sync(creatorIds: [String]) async {
        guard !creatorIds.isEmpty else {
            await MainActor.run { state = .success }
            return
        }

        kvStore.putBoolean(key: syncInProgressKey, value: true)

        do {
            // Step 1：拉取所有支持的创作者（确保数据最新）
            await setProgress("正在获取创作者列表...", 0.05)
            let allCreators = try await api.fetchSponsoringCreators()

            var selectedCreators = allCreators.filter { creatorIds.contains($0.id) }
            selectedCreators = selectedCreators.map {
                var c = $0; c.isSelected = true; return c
            }
            db.upsertCreators(selectedCreators)

            let total = selectedCreators.count
            for (i, creator) in selectedCreators.enumerated() {
                let baseProgress = 0.1 + Double(i) / Double(total) * 0.85
                await setProgress("同步 \(creator.name)...", baseProgress)

                await setProgress("获取 \(creator.name) 专辑列表...", baseProgress)
                var albums = try await api.fetchAlbums(creatorId: creator.id)
                db.upsertAlbums(albums)

                let unknownAlbums = albums.filter { !$0.isAccessible }
                for (j, album) in unknownAlbums.enumerated() {
                    let probeProgress = baseProgress + Double(j) / Double(max(unknownAlbums.count, 1)) * (0.85 / Double(total) * 0.3)
                    await setProgress("检测权限 \(creator.name) — \(album.title)...", probeProgress)
                    // v1.6 修复：sleep 移到 probe 之后
                    //  原实现先 sleep 500ms 再 probe，第一次无意义等待
                    //  改为：先 probe，结束后再 sleep 500ms（rate-limit QPS ≤ 2/s）
                    let accessible = await api.probeAlbumAccessibility(albumId: album.id)
                    if j < unknownAlbums.count - 1 {
                        try await Task.sleep(nanoseconds: 500_000_000)
                    }
                    if accessible {
                        if let idx = albums.firstIndex(where: { $0.id == album.id }) {
                            albums[idx].isAccessible = true
                        }
                        var updated = album
                        updated.isAccessible = true
                        db.upsertAlbum(updated)
                    }
                }

                let accessibleAlbums = albums.filter { $0.isAccessible }
                for (j, album) in accessibleAlbums.enumerated() {
                    let perCreator = 0.85 / Double(total)
                    let albumFraction = Double(j) / Double(max(accessibleAlbums.count, 1))
                    let albumProgress = baseProgress + perCreator * 0.3 + albumFraction * (perCreator * 0.7)
                    await setProgress("同步 \(creator.name) — \(album.title)...", albumProgress)

                    if j > 0 { try await Task.sleep(nanoseconds: 300_000_000) }
                    let items = try await api.fetchAlbumCatalog(albumId: album.id)
                    db.upsertAudioItems(items)

                    var updatedAlbum = album
                    updatedAlbum.audioCount = Int32(items.count)
                    updatedAlbum.totalDuration = items.reduce(0) { $0 + $1.duration }
                    updatedAlbum.lastSyncedAtDate = Date()
                    db.upsertAlbum(updatedAlbum)
                }

                var updatedCreator = creator
                updatedCreator.lastSyncedAtDate = Date()
                db.upsertCreator(updatedCreator)
            }

            await MainActor.run {
                kvStore.putLong(key: lastSyncDateKey, value: Int64(Date().timeIntervalSince1970 * 1000.0))
                kvStore.remove(key: syncInProgressKey)
            }

            await setProgress("同步完成", 1.0)
            await MainActor.run {
                state = .success
                NotificationCenter.default.post(name: .didSyncComplete, object: nil)
            }

        } catch {
            await MainActor.run {
                kvStore.remove(key: syncInProgressKey)
                state = .failed(error)
            }
        }
    }

    // MARK: - 辅助

    private func setProgress(_ message: String, _ progress: Double) async {
        await MainActor.run {
            state = .syncing(message: message, progress: progress)
        }
    }

    var lastSyncDate: Date? {
        let ms = kvStore.getLong(key: lastSyncDateKey, default: 0)
        guard ms > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
    }
}

// MARK: - 通知名

extension Notification.Name {
    static let didSyncComplete = Notification.Name("OneHundredVolt.didSyncComplete")
}
