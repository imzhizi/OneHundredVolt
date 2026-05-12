import Foundation
@testable import OneHundredVolt

final class MockAudioCacheService: AudioCaching {
    var cachedURLMap: [String: URL] = [:]
    var cacheCallCount = 0
    var removeCallCount = 0

    func cachedURL(for postId: String) -> URL? {
        cachedURLMap[postId]
    }

    func cacheAudio(from remoteURL: URL, postId: String) async {
        cacheCallCount += 1
        cachedURLMap[postId] = remoteURL
    }

    func removeCache(for postId: String) {
        removeCallCount += 1
        cachedURLMap.removeValue(forKey: postId)
    }
}
