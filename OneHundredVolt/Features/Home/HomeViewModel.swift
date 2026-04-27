import Foundation

@Observable
final class HomeViewModel {
    private let db = DatabaseService.shared
    private let progressStore = PlaybackProgressStore.shared
    private let player = AudioPlayerService.shared

    // MARK: - Data

    var creators: [Creator] = []
    var albumsByCreator: [String: [Album]] = [:]  // creatorId → albums

    /// 继续收听：上次播放的音频
    var continueListeningItem: AudioItem?
    var continueListeningAlbum: Album?

    // MARK: - Load

    /// 首次加载（数据为空时）
    func loadIfNeeded() {
        guard creators.isEmpty else { return }
        load()
    }

    func load() {
        creators = db.selectedCreators()
        for creator in creators {
            albumsByCreator[creator.id] = db.albums(for: creator.id)
        }
        loadContinueListening()
    }

    private func loadContinueListening() {
        guard let lastPostId = progressStore.lastPlayedPostId,
              let item = db.audioItem(id: lastPostId),
              progressStore.progress(for: lastPostId) > 0
        else {
            continueListeningItem = nil
            continueListeningAlbum = nil
            return
        }
        continueListeningItem = item
        continueListeningAlbum = db.albums.first { $0.id == item.albumId }
    }

    func albums(for creator: Creator) -> [Album] {
        albumsByCreator[creator.id] ?? []
    }

    func progressRatio(for item: AudioItem) -> Double {
        item.progressRatio(from: progressStore)
    }

    func progressTime(for item: AudioItem) -> TimeInterval {
        progressStore.progress(for: item.id)
    }
}
