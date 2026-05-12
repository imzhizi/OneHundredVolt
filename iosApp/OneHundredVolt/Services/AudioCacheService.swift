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
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(atPath: cacheDir.path) else { return nil }

        if let match = files.first(where: { $0.hasPrefix(postId + ".") }) {
            // 带扩展名的新格式缓存
            let url = cacheDir.appendingPathComponent(match)
            touch(url)
            return url
        }

        // 旧格式（无扩展名）：AVPlayer 无法识别，直接删除让其重新缓存
        if files.contains(postId) {
            try? fm.removeItem(at: cacheDir.appendingPathComponent(postId))
        }
        return nil
    }

    func cacheAudio(from remoteURL: URL, postId: String) async {
        let ext = remoteURL.pathExtension  // 如 "mp3"、"m4a"，query string 不影响
        let filename = ext.isEmpty ? postId : "\(postId).\(ext)"
        let dest = cacheDir.appendingPathComponent(filename)
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

    func totalCacheSize() -> Int64 {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(
            at: cacheDir, includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        return items.reduce(Int64(0)) { sum, item in
            let size = (try? item.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return sum + Int64(size)
        }
    }

    func clearCache() {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(atPath: cacheDir.path) else { return }
        for item in items {
            try? fm.removeItem(at: cacheDir.appendingPathComponent(item))
        }
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
