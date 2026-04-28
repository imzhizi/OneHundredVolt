import Foundation

/// 音频文件本地缓存（LRU，500MB 上限）
final class AudioCacheService {

    static let shared = AudioCacheService()
    private init() {
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    private let cacheDir: URL = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return caches.appendingPathComponent("AudioCache", isDirectory: true)
    }()

    private let maxCacheBytes: Int64 = 500 * 1024 * 1024  // 500 MB
    private let session = URLSession(configuration: .default)

    // MARK: - Public API

    func cachedURL(for postId: String) -> URL? {
        let url = cacheDir.appendingPathComponent(postId)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        touch(url)
        return url
    }

    func cacheAudio(from remoteURL: URL, postId: String) async {
        let dest = cacheDir.appendingPathComponent(postId)
        guard !FileManager.default.fileExists(atPath: dest.path) else { return }
        do {
            let (tempURL, _) = try await session.download(from: remoteURL)
            try FileManager.default.moveItem(at: tempURL, to: dest)
            evictIfNeeded()
        } catch {
            // 缓存失败不影响播放，静默忽略
        }
    }

    func removeCache(for postId: String) {
        let url = cacheDir.appendingPathComponent(postId)
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - LRU 淘汰

    private func touch(_ url: URL) {
        try? (url as NSURL).setResourceValue(Date(), forKey: .contentAccessDateKey)
    }

    private func evictIfNeeded() {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: cacheDir, includingPropertiesForKeys: [.fileSizeKey, .contentAccessDateKey]
        ) else { return }

        var totalBytes: Int64 = 0
        var files: [(url: URL, accessDate: Date, size: Int64)] = []

        for item in items {
            let rv = try? item.resourceValues(forKeys: [.fileSizeKey, .contentAccessDateKey])
            let size = Int64(rv?.fileSize ?? 0)
            let date = rv?.contentAccessDate ?? .distantPast
            totalBytes += size
            files.append((item, date, size))
        }

        guard totalBytes > maxCacheBytes else { return }

        // 按访问时间升序（最旧的先删）
        let sorted = files.sorted { $0.accessDate < $1.accessDate }
        var remaining = totalBytes
        for file in sorted {
            guard remaining > maxCacheBytes else { break }
            try? fm.removeItem(at: file.url)
            remaining -= file.size
        }
    }
}
