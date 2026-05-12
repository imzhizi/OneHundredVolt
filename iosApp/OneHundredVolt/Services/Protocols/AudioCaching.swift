import Foundation

protocol AudioCaching {
    func cachedURL(for postId: String) -> URL?
    func cacheAudio(from remoteURL: URL, postId: String) async
    func removeCache(for postId: String)
}

extension AudioCacheService: AudioCaching {}
