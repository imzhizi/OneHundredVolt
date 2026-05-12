import Foundation

/// 同步服务：拉取创作者、专辑、音频目录
@Observable
final class SyncService {

    static let shared = SyncService()
    private init() {}

    private let api = AfdianAPIService.shared
    private let db  = DatabaseService.shared

    // UserDefaults key：标记上次同步尚未完成（用于中断检测）
    private let syncInProgressKey = "sync_in_progress"

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
        guard UserDefaults.standard.bool(forKey: syncInProgressKey) else { return }
        // 上次同步被中断（如用户强杀 App），重置标志位即可
        // 已同步的数据保留，用户可在设置页手动重新同步
        UserDefaults.standard.removeObject(forKey: syncInProgressKey)
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

        // 标记同步开始（未完成）
        await MainActor.run {
            UserDefaults.standard.set(true, forKey: syncInProgressKey)
        }

        do {
            // Step 1：拉取所有支持的创作者（确保数据最新）
            await setProgress("正在获取创作者列表...", 0.05)
            let allCreators = try await api.fetchSponsoringCreators()

            // 只同步用户选择的
            var selectedCreators = allCreators.filter { creatorIds.contains($0.id) }
            // 标记为已选择
            selectedCreators = selectedCreators.map {
                var c = $0; c.isSelected = true; return c
            }
            db.upsertCreators(selectedCreators)

            // Step 2：逐创作者拉取专辑和音频
            let total = selectedCreators.count
            for (i, creator) in selectedCreators.enumerated() {
                let baseProgress = 0.1 + Double(i) / Double(total) * 0.85
                await setProgress("同步 \(creator.name)...", baseProgress)

                // Step A：拉专辑列表（快，无 sleep）
                await setProgress("获取 \(creator.name) 专辑列表...", baseProgress)
                var albums = try await api.fetchAlbums(creatorId: creator.id)
                db.upsertAlbums(albums)

                // Step B：对 bought=0 的专辑逐一探测权限（免费 or 付费未购）
                // 每个专辑探测前 sleep 500ms，QPS ≤ 2/s，同时在进度条实时显示
                let unknownAlbums = albums.filter { !$0.isAccessible }
                for (j, album) in unknownAlbums.enumerated() {
                    let probeProgress = baseProgress + Double(j) / Double(max(unknownAlbums.count, 1)) * (0.85 / Double(total) * 0.3)
                    await setProgress("检测权限 \(creator.name) — \(album.title)...", probeProgress)
                    try await Task.sleep(nanoseconds: 500_000_000) // 500ms，控制 QPS
                    let accessible = await api.probeAlbumAccessibility(albumId: album.id)
                    if accessible {
                        // 更新内存中的 album
                        if let idx = albums.firstIndex(where: { $0.id == album.id }) {
                            albums[idx].isAccessible = true
                        }
                        // 更新数据库
                        var updated = album
                        updated.isAccessible = true
                        db.upsertAlbum(updated)
                    }
                }

                // Step C：只对有权限的专辑拉取音频目录
                // 每次请求间隔 0.3s
                let accessibleAlbums = albums.filter { $0.isAccessible }
                for (j, album) in accessibleAlbums.enumerated() {
                    let perCreator = 0.85 / Double(total)
                    let albumFraction = Double(j) / Double(max(accessibleAlbums.count, 1))
                    let albumProgress = baseProgress + perCreator * 0.3 + albumFraction * (perCreator * 0.7)
                    await setProgress("同步 \(creator.name) — \(album.title)...", albumProgress)

                    if j > 0 { try await Task.sleep(nanoseconds: 300_000_000) } // 0.3s
                    let items = try await api.fetchAlbumCatalog(albumId: album.id)
                    db.upsertAudioItems(items)

                    // 更新专辑总时长和音频数量
                    var updatedAlbum = album
                    updatedAlbum.audioCount = items.count
                    updatedAlbum.totalDuration = items.reduce(0) { $0 + $1.duration }
                    updatedAlbum.lastSyncedAt = Date()
                    db.upsertAlbum(updatedAlbum)
                }

                // 更新创作者同步时间
                var updatedCreator = creator
                updatedCreator.lastSyncedAt = Date()
                db.upsertCreator(updatedCreator)
            }

            // 保存最后同步时间，清除进行中标记
            await MainActor.run {
                UserDefaults.standard.set(Date(), forKey: "last_sync_date")
                UserDefaults.standard.removeObject(forKey: syncInProgressKey)
            }

            await setProgress("同步完成", 1.0)
            await MainActor.run {
                state = .success
                NotificationCenter.default.post(name: .didSyncComplete, object: nil)
            }

        } catch {
            // 同步失败：清除进行中标记（失败不算中断，由用户决定是否重试）
            await MainActor.run {
                UserDefaults.standard.removeObject(forKey: syncInProgressKey)
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
        UserDefaults.standard.object(forKey: "last_sync_date") as? Date
    }
}

// MARK: - 通知名

extension Notification.Name {
    static let didSyncComplete = Notification.Name("OneHundredVolt.didSyncComplete")
}
