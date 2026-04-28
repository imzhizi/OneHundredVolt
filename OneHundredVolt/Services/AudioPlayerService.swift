import Foundation
import AVFoundation

/// 全局音频播放器（单例）
///
/// ## 播放列表语义
/// - `playlist[0]` 始终是当前正在播放（或待播放）的单集
/// - 播放完成后，当前单集自动从列表移除，自动播放新的 `playlist[0]`
/// - 手动跳下一首：移除 `playlist[0]`，播放新的 `playlist[0]`
/// - 手动跳上一首：若进度 > 5s 则重播当前，否则无操作（已移除的不可恢复）
@Observable
final class AudioPlayerService {

    static let shared = AudioPlayerService()

    // MARK: - 依赖（可注入，便于单测）

    private let playerFactory: AudioPlayerFactory
    private let api: AudioAPIService
    private let progressStore: PlaybackProgressStoring
    private let audioCache: AudioCaching
    private let defaults: UserDefaults

    init(
        playerFactory: AudioPlayerFactory = LiveAudioPlayerFactory(),
        api: AudioAPIService = AfdianAPIService.shared,
        progressStore: PlaybackProgressStoring = PlaybackProgressStore.shared,
        audioCache: AudioCaching = AudioCacheService.shared,
        defaults: UserDefaults = .standard
    ) {
        self.playerFactory = playerFactory
        self.api = api
        self.progressStore = progressStore
        self.audioCache = audioCache
        self.defaults = defaults
        setupAudioSession()
    }

    // MARK: - AVPlayer 内部状态（不暴露给 UI）

    private var player: AVPlayerProtocol?
    private var playerItem: AVPlayerItem?
    private var timeObserver: Any?
    private var statusObserver: NSKeyValueObservation?

    // MARK: - 播放状态（UI 观察这些属性）

    private(set) var currentItem: AudioItem?
    var playlist: [AudioItem] = [] {
        didSet { persistPlaylist() }
    }

    private(set) var isPlaying: Bool = false
    private(set) var isLoading: Bool = false
    private(set) var currentTime: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    private(set) var loadError: String?

    // MARK: - 播放速度

    var playbackRate: Float = 1.0 {
        didSet {
            if isPlaying { player?.rate = playbackRate }
            defaults.set(playbackRate, forKey: "playback_rate")
        }
    }

    // MARK: - 睡眠定时

    private var sleepTimer: Timer?
    private(set) var sleepRemainingSeconds: Int = 0

    enum SleepDuration: Int, CaseIterable {
        case off = 0, min15 = 15, min30 = 30, min45 = 45, min60 = 60
        var label: String {
            switch self {
            case .off:   return "关闭"
            case .min15: return "15 分钟"
            case .min30: return "30 分钟"
            case .min45: return "45 分钟"
            case .min60: return "60 分钟"
            }
        }
    }

    // MARK: - 持久化

    private static let playlistKey = "saved_playlist_v1"

    private func persistPlaylist() {
        if playlist.isEmpty {
            defaults.removeObject(forKey: Self.playlistKey)
        } else if let data = try? JSONEncoder().encode(playlist) {
            defaults.set(data, forKey: Self.playlistKey)
        }
    }

    private func restorePlaylist() {
        guard let data = defaults.data(forKey: Self.playlistKey),
              let items = try? JSONDecoder().decode([AudioItem].self, from: data),
              !items.isEmpty else { return }
        playlist = items
        currentItem = items[0]
        duration = items[0].duration
        let saved = progressStore.progress(for: items[0].id)
        if saved > 0 { currentTime = saved }
    }

    // MARK: - 便利计算属性

    var hasNext: Bool { playlist.count > 1 }
    var hasPrevious: Bool { currentTime > 5 }

    var progressRatio: Double {
        guard duration > 0 else { return 0 }
        return min(1.0, currentTime / duration)
    }

    // MARK: - Audio Session

    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("[AudioPlayer] Session setup failed: \(error)")
        }
        let savedRate = defaults.float(forKey: "playback_rate")
        playbackRate = savedRate > 0 ? savedRate : 1.0
        restorePlaylist()
    }

    // MARK: - 播放控制（对外 API）

    func play(playlist: [AudioItem], startAt index: Int = 0) {
        guard index < playlist.count else { return }
        var reordered = playlist
        if index != 0 {
            let item = reordered.remove(at: index)
            reordered.insert(item, at: 0)
        }
        self.playlist = reordered
        loadAndPlay(item: reordered[0])
    }

    func play(item: AudioItem) {
        playlist.removeAll { $0.id == item.id }
        playlist.insert(item, at: 0)
        loadAndPlay(item: item)
    }

    func playImmediately(_ item: AudioItem) {
        playlist.removeAll { $0.id == item.id }
        playlist.insert(item, at: 0)
        loadAndPlay(item: item)
    }

    func appendToPlaylist(_ item: AudioItem) {
        guard !playlist.contains(where: { $0.id == item.id }) else { return }
        playlist.append(item)
    }

    func appendAndPlay(items: [AudioItem]) {
        guard !items.isEmpty else { return }
        let newItems = items.filter { item in !playlist.contains(where: { $0.id == item.id }) }
        playlist.append(contentsOf: newItems)
        playImmediately(items[0])
    }

    func playNext() {
        guard hasNext else { return }
        if let id = currentItem?.id {
            progressStore.markCompleted(for: id)
            playlist.removeAll { $0.id == id }
        }
        loadAndPlay(item: playlist[0])
    }

    func playPrevious() {
        if currentTime > 5 { seek(to: 0) }
    }

    func clearAll() {
        saveCurrentProgress()
        stopCurrentPlayer()
        currentItem = nil
        playlist = []
        isPlaying = false
        isLoading = false
        currentTime = 0
        duration = 0
        loadError = nil
        NowPlayingService.shared.updateNowPlaying(item: nil)
    }

    func didRemoveItems(deletingCurrent: Bool) {
        if playlist.isEmpty {
            clearAll()
        } else if deletingCurrent {
            loadAndPlay(item: playlist[0])
        }
    }

    func pause() {
        player?.pause()
        isPlaying = false
        saveCurrentProgress()
    }

    func resume() {
        guard player != nil else { return }
        player?.rate = playbackRate
        isPlaying = true
    }

    func togglePlayPause() {
        if isPlaying {
            pause()
        } else if player == nil, let item = currentItem {
            loadAndPlay(item: item)
        } else {
            resume()
        }
    }

    func seek(to time: TimeInterval) {
        let cmTime = CMTime(seconds: time, preferredTimescale: 600)
        player?.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        currentTime = time
    }

    func skipForward(seconds: Double = 30) {
        seek(to: min(currentTime + seconds, duration))
    }

    func skipBackward(seconds: Double = 15) {
        seek(to: max(currentTime - seconds, 0))
    }

    func syncAfterReorder() {
        guard let current = currentItem else { return }
        if let idx = playlist.firstIndex(where: { $0.id == current.id }), idx != 0 {
            let item = playlist.remove(at: idx)
            playlist.insert(item, at: 0)
        }
    }

    // MARK: - 睡眠定时

    func setSleepTimer(_ duration: SleepDuration) {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepRemainingSeconds = 0
        guard duration != .off else { return }
        sleepRemainingSeconds = duration.rawValue * 60
        sleepTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.sleepRemainingSeconds > 0 {
                self.sleepRemainingSeconds -= 1
            } else {
                self.pause()
                self.sleepTimer?.invalidate()
                self.sleepTimer = nil
            }
        }
    }

    func cancelSleepTimer() {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepRemainingSeconds = 0
    }

    // MARK: - 私有：加载并播放

    private func loadAndPlay(item: AudioItem) {
        saveCurrentProgress()
        stopCurrentPlayer()

        currentItem = item
        isLoading = true
        isPlaying = false
        loadError = nil
        duration = item.duration
        // currentTime 保留旧值，readyToPlay 后 seek 完成再更新，避免进度条闪烁

        progressStore.setLastPlayed(postId: item.id, creatorId: item.creatorId)

        Task {
            do {
                if let localURL = audioCache.cachedURL(for: item.id) {
                    await MainActor.run { self.setupPlayer(url: localURL, item: item) }
                    return
                }
                let urlString = try await api.fetchAudioURL(postId: item.id)
                guard let url = URL(string: urlString) else { throw APIError.noAudioURL }
                await MainActor.run { self.setupPlayer(url: url, item: item) }
                // 后台缓存，不阻塞播放 Task
                Task { await self.audioCache.cacheAudio(from: url, postId: item.id) }
            } catch {
                await MainActor.run {
                    self.isLoading = false
                    self.loadError = error.localizedDescription
                }
            }
        }
    }

    @MainActor
    private func setupPlayer(url: URL, item: AudioItem) {
        let avItem = playerFactory.makePlayerItem(url: url)
        playerItem = avItem
        player = playerFactory.makePlayer(playerItem: avItem)
        (player as? AVPlayer)?.automaticallyWaitsToMinimizeStalling = true

        statusObserver = avItem.observe(\.status, options: [.new]) { [weak self] avPlayerItem, _ in
            // 用 Task { @MainActor in } 而非 DispatchQueue.main.async，
            // 避免与 Swift Concurrency 的 actor 模型混用导致 race condition
            Task { @MainActor [weak self] in
                guard let self, self.currentItem?.id == item.id else { return }
                switch avPlayerItem.status {
                case .readyToPlay:
                    self.isLoading = false
                    let d = avPlayerItem.duration.seconds
                    self.duration = d.isNaN || d <= 0 ? item.duration : d
                    let saved = self.progressStore.progress(for: item.id)
                    if saved > 5 {
                        let target = CMTime(seconds: saved, preferredTimescale: 600)
                        // 捕获当前 player 实例，seek completion 里用捕获值而非 self.player，
                        // 避免切歌后旧 completion 意外启动新歌播放
                        let capturedPlayer = self.player
                        capturedPlayer?.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
                            Task { @MainActor [weak self] in
                                guard let self, self.currentItem?.id == item.id else { return }
                                if finished { self.currentTime = saved }
                                capturedPlayer?.rate = self.playbackRate
                                self.isPlaying = true
                            }
                        }
                    } else {
                        self.currentTime = 0
                        self.player?.rate = self.playbackRate
                        self.isPlaying = true
                    }
                    NowPlayingService.shared.updateNowPlaying(item: item)
                case .failed:
                    self.isLoading = false
                    self.loadError = avPlayerItem.error?.localizedDescription ?? "播放失败"
                default: break
                }
            }
        }

        timeObserver = player?.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self, self.isPlaying else { return }
            self.currentTime = time.seconds
            if Int(time.seconds) % 15 == 0 { self.saveCurrentProgress() }
            NowPlayingService.shared.updateProgress(current: time.seconds, duration: self.duration)
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidFinishPlaying),
            name: .AVPlayerItemDidPlayToEndTime,
            object: avItem
        )
    }

    @objc private func playerDidFinishPlaying() {
        Task { @MainActor [weak self] in
            self?.handlePlaybackFinished()
        }
    }

    @MainActor
    private func handlePlaybackFinished() {
        if let id = currentItem?.id {
            progressStore.markCompleted(for: id)
            playlist.removeAll { $0.id == id }
            audioCache.removeCache(for: id)
        }
        if !playlist.isEmpty {
            loadAndPlay(item: playlist[0])
        } else {
            stopCurrentPlayer()
            isPlaying = false
            currentItem = nil
            currentTime = 0
            duration = 0
            NowPlayingService.shared.updateNowPlaying(item: nil)
            NotificationCenter.default.post(name: .playbackDidFinishAll, object: nil)
        }
    }

    // MARK: - 私有：停止并清理 AVPlayer

    private func stopCurrentPlayer() {
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        statusObserver?.invalidate()
        statusObserver = nil
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: playerItem)
        player?.pause()
        player = nil
        playerItem = nil
    }

    private func saveCurrentProgress() {
        guard let id = currentItem?.id, currentTime > 0 else { return }
        progressStore.setProgress(currentTime, for: id)
    }
}
