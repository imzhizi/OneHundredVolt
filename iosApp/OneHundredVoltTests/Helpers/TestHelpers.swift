import Foundation
@testable import OneHundredVolt

struct TestDeps {
    let factory: MockAudioPlayerFactory
    let api: MockAudioAPIService
    let store: MockPlaybackProgressStore
    let cache: MockAudioCacheService
    let defaults: UserDefaults
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

        if let items = playlistItems, let data = try? JSONEncoder().encode(items) {
            defaults.set(data, forKey: "saved_playlist_v1")
        }
        defaults.removeObject(forKey: "playback_rate")

        service = AudioPlayerService(
            playerFactory: factory,
            api: api,
            progressStore: store,
            audioCache: cache,
            defaults: defaults
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
        publishTime: Date()
    )
}
