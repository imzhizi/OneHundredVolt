import Foundation
@testable import OneHundredVolt

struct TestDeps {
    let factory: MockAudioPlayerFactory
    let api: MockAudioAPIService
    let store: MockPlaybackProgressStore
    let cache: MockAudioCacheService
    let defaults: UserDefaults
    let playlistItemResolver: (String) -> AudioItem?
    let service: AudioPlayerService

    init(savedProgress: [String: TimeInterval] = [:], playlistItems: [AudioItem]? = nil) {
        factory = MockAudioPlayerFactory()
        api = MockAudioAPIService()
        store = MockPlaybackProgressStore()
        store.progressMap = savedProgress
        cache = MockAudioCacheService()

        // 独立 suite，避免污染 UserDefaults.standard
        let suiteName = "test-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!

        let persistedItems = playlistItems ?? []
        let itemMap = Dictionary(uniqueKeysWithValues: persistedItems.map { ($0.id, $0) })
        playlistItemResolver = { itemMap[$0] }
        if !persistedItems.isEmpty {
            defaults.set(persistedItems.map(\.id), forKey: "saved_playlist_v1")
        }
        defaults.removeObject(forKey: "playback_rate")

        service = AudioPlayerService(
            playerFactory: factory,
            api: api,
            progressStore: store,
            audioCache: cache,
            defaults: defaults,
            playlistItemResolver: playlistItemResolver
        )
    }
}

func makeItem(id: String = "post-1", duration: TimeInterval = 120) -> AudioItem {
    AudioItem(
        id: id,
        albumId: "album-1",
        creatorId: "creator-1",
        title: "Test Episode \(id)",
        coverUrl: nil,
        duration: duration,
        sortOrder: 0,
        publishTime: 0
    )
}
