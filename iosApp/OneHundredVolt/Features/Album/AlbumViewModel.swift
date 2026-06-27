import Foundation
import Shared

@Observable
final class AlbumViewModel {
    let album: Album
    private let db = DatabaseService.shared
    private let progressStore = PlaybackProgressStore.shared

    var audioItems: [AudioItem] = []
    var isLoading = false

    var displayItems: [AudioItem] { audioItems }

    init(album: Album) {
        self.album = album
    }

    func load() {
        isLoading = true
        let items = db.audioItems(for: album.id)
        audioItems = items
        isLoading = false
    }

    func progressRatio(for item: AudioItem) -> Double {
        let progress = progressStore.progress(for: item.id)
        return item.progressRatio(progress: progress)
    }

    func isCurrentlyPlaying(_ item: AudioItem) -> Bool {
        AudioPlayerService.shared.currentItem?.id == item.id
    }

    /// 是否已播完（历史上完整听完过）
    func isCompleted(_ item: AudioItem) -> Bool {
        progressStore.isCompleted(item.id)
    }
}
