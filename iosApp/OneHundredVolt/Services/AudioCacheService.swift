import Foundation

/// 音频文件本地缓存（LRU，500MB 上限）
///
/// v1.6 改动：
///  - 增加 cacheLock (NSLock) 串行化所有文件系统操作
///  - 修复原 race condition（cachedURL + cacheAudio 并发可能导致目录读取错乱）
///  - cacheAudio 改为可取消（Batch 7.2 后续）
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

    /// 串行化所有文件系统操作（cachedURL / cacheAudio / removeCache / clearCache 等）
    private let cacheLock = NSLock()

    /// 跟踪正在进行的 cacheAudio 任务（postId -> Task）
    /// 用于 clearCache 时取消未完成的下载
    private var activeDownloads: [String: Task<Void, Never>] = [:]
    private let downloadsLock = NSLock()

    // MARK: - Public API

    func cachedURL(for postId: String) -> URL? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(atPath: cacheDir.path) else { return nil }

        if let match = files.first(where: { $0.hasPrefix(postId + ".") }) {
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
        let ext = remoteURL.pathExtension
        let filename = ext.isEmpty ? postId : "\(postId).\(ext)"
        let dest = cacheDir.appendingPathComponent(filename)

        // 预检查：先在锁内确认文件不存在，避免下载浪费
        cacheLock.lock()
        let exists = FileManager.default.fileExists(atPath: dest.path)
        cacheLock.unlock()
        guard !exists else { return }

        // 包装成 Task 并注册，便于 clearCache 取消
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }
            do {
                let (tempURL, _) = try await self.session.download(from: remoteURL)
                // 检查取消
                if Task.isCancelled {
                    try? FileManager.default.removeItem(at: tempURL)
                    return
                }
                // 移入缓存目录时持锁
                self.cacheLock.lock()
                defer { self.cacheLock.unlock() }
                // 二次检查：可能在我们下载期间已有其他协程完成缓存
                if FileManager.default.fileExists(atPath: dest.path) {
                    try? FileManager.default.removeItem(at: tempURL)
                    return
                }
                try FileManager.default.moveItem(at: tempURL, to: dest)
                self.evictIfNeededLocked()
            } catch {
                // 缓存失败不影响播放，静默忽略
            }
        }

        downloadsLock.lock()
        activeDownloads[postId] = task
        downloadsLock.unlock()

        await task.value

        downloadsLock.lock()
        activeDownloads.removeValue(forKey: postId)
        downloadsLock.unlock()
    }

    func removeCache(for postId: String) {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        let url = cacheDir.appendingPathComponent(postId)
        try? FileManager.default.removeItem(at: url)
    }

    func totalCacheSize() -> Int64 {
        cacheLock.lock()
        defer { cacheLock.unlock() }
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
        // 取消所有进行中的下载
        downloadsLock.lock()
        let tasks = Array(activeDownloads.values)
        activeDownloads.removeAll()
        downloadsLock.unlock()
        for task in tasks { task.cancel() }

        cacheLock.lock()
        defer { cacheLock.unlock() }
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

    /// 调用方必须持 cacheLock
    private func evictIfNeededLocked() {
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

        let sorted = files.sorted { $0.accessDate < $1.accessDate }
        var remaining = totalBytes
        for file in sorted {
            guard remaining > maxCacheBytes else { break }
            try? fm.removeItem(at: file.url)
            remaining -= file.size
        }
    }
}