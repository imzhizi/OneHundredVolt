import Foundation
import Shared

@Observable
final class HomeViewModel {
    private let db = DatabaseService.shared
    private let progressStore = PlaybackProgressStore.shared

    var creators: [Creator] = []
    var albumsByCreator: [String: [Album]] = [:]  // creatorId → albums

    func loadIfNeeded() {
        guard creators.isEmpty else { return }
        load()
    }

    func load() {
        let all = db.selectedCreators()
        creators = all.sorted {
            let a = progressStore.lastPlayedDate(for: $0.id) ?? .distantPast
            let b = progressStore.lastPlayedDate(for: $1.id) ?? .distantPast
            return a > b
        }
        for creator in creators {
            albumsByCreator[creator.id] = db.albums(for: creator.id)
        }
    }

    func albums(for creator: Creator) -> [Album] {
        albumsByCreator[creator.id] ?? []
    }
}
