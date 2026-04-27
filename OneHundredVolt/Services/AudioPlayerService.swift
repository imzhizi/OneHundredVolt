import Foundation
import AVFoundation
    
/// 全局音频播放器（单例）
///
/// ## 播放列表语义
/// - `playlist[0]` 始终是当前正在播放（或待播放）的单集
/// - 播放完成后，当前单集自动从列表移除，自动播放新的 `playlist[0]`
/// - 手动跳下一首：移除 `playlist[0]`，播放新的 `playlist[0]`
/// - 手动跳上一首：若进度 > 5s 则重播当前，否则无操作（已移除的不可恢复）
/// - `currentIndex` 已废弃，统一用 `playlist[0]` 表示当前
@Observable
final class AudioPlayerService {

    static let shared = AudioPlayerService()
    private init() {
        setupAudioSession()
    }

    // MARK: - AVPlayer 内部状态（不暴露给 UI）

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var timeObserver: Any?
    private var statusObserver: NSKeyValueObservation?

    // MARK: - 播放状态（UI 观察这些属性）

    private(set) var currentItem: AudioItem?    // 当前播放的单集，nil 时 MiniPlayer 消失
    var playlist: [AudioItem] = []              // playlist[0] == currentItem（保持一致）

    private(set) var isPlaying: Bool = false
    private(set) var isLoading: Bool = false
    private(set) var currentTime: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    private(set) var loadError: String?

    // MARK: - 播放速度

    var playbackRate: Float = 1.0 {
        didSet {
            if isPlaying { player?.rate = playbackRate }
            UserDefaults.standard.set(playbackRate, forKey: "playback_rate")
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

    private let progressStore = PlaybackProgressStore.shared
    private let api = AfdianAPIService.shared

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
        let savedRate = UserDefaults.standard.float(forKey: "playback_rate")
        playbackRate = savedRate > 0 ? savedRate : 1.0
    }

    // MARK: - 播放控制（对外 API）

    /// 设置新播放列表并从第 0 位开始播放
    func play(playlist: [AudioItem], startAt index: Int = 0) {
        guard index < playlist.count else { return }
        // 将 startAt 位置的单集移到最前，保证 playlist[0] == 当前播放
        var reordered = playlist
        if index != 0 {
            let item = reordered.remove(at: index)
            reordered.insert(item, at: 0)
        }
        self.playlist = reordered
        loadAndPlay(item: reordered[0])
    }

    /// 播放单个音频：若已在列表中则移到队首，否则插入队首
    func play(item: AudioItem) {
        playlist.removeAll { $0.id == item.id }
        playlist.insert(item, at: 0)
        loadAndPlay(item: item)
    }

    /// 立即播放：将单集移到队首并立刻播放（点击 ▶ 按钮）
    func playImmediately(_ item: AudioItem) {
        playlist.removeAll { $0.id == item.id }
        playlist.insert(item, at: 0)
        loadAndPlay(item: item)
    }

    /// 追加到播放列表末尾（点击 + 按钮，不改变当前播放）
    func appendToPlaylist(_ item: AudioItem) {
        guard !playlist.contains(where: { $0.id == item.id }) else { return }
        playlist.append(item)
    }

    /// 跳到下一首（标记当前为已完成并移除，播放列表中新的第一项）
    func playNext() {
        guard hasNext else { return }
        if let id = currentItem?.id {
            progressStore.markCompleted(for: id)
            playlist.removeAll { $0.id == id }
        }
        loadAndPlay(item: playlist[0])
    }

    /// 上一首：若进度 > 5s 则重播当前；否则无操作
    func playPrevious() {
        if currentTime > 5 {
            seek(to: 0)
        }
        // 已移除的单集无法恢复，不做跳转
    }

    /// 清空播放列表并停止播放（MiniPlayer 自动消失）
    func clearAll() {
        saveCurrentProgress()
        stopCurrentPlayer()
        currentItem = nil
        playlist = []
        isPlaying = false
        currentTime = 0
        duration = 0
        loadError = nil
        NowPlayingService.shared.updateNowPlaying(item: nil)
    }

    /// 从播放列表删除某一项后调用（HomeView onDelete）
    /// - 若删的是当前播放项且列表还有内容，自动播新的 playlist[0]
    /// - 若列表已空，调用 clearAll()
    func didRemoveItems(deletingCurrent: Bool) {
        if playlist.isEmpty {
            clearAll()
        } else if deletingCurrent {
            loadAndPlay(item: playlist[0])
        }
        // 删的不是当前播放项：不需要任何操作
    }

    func pause() {
        player?.pause()
        isPlaying = false
        saveCurrentProgress()
    }

    func resume() {
        guard player != nil else { return }  // 加载中时 player 为 nil，不强制设 isPlaying
        player?.rate = playbackRate
        isPlaying = true
    }

    func togglePlayPause() {
        isPlaying ? pause() : resume()
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

    // MARK: - 拖拽排序回调（playlist[0] 不变，只是重排后面的）

    /// 拖拽排序后确保 currentItem 和 playlist[0] 保持一致
    func syncAfterReorder() {
        guard let current = currentItem else { return }
        // 把当前正在播的移回 index 0（不依赖 SwiftUI.move，使用纯 Swift 数组操作）
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
        currentTime = 0
        duration = item.duration

        progressStore.setLastPlayed(postId: item.id)

        Task {
            do {
                let urlString = try await api.fetchAudioURL(postId: item.id)
                guard let url = URL(string: urlString) else { throw APIError.noAudioURL }
                await MainActor.run { self.setupPlayer(url: url, item: item) }
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
        let avItem = AVPlayerItem(url: url)
        playerItem = avItem
        player = AVPlayer(playerItem: avItem)
        player?.automaticallyWaitsToMinimizeStalling = true

        statusObserver = avItem.observe(\.status, options: [.new]) { [weak self] avPlayerItem, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                // 守卫：切歌后旧回调到来，忽略以避免状态覆盖
                guard self.currentItem?.id == item.id else { return }
                switch avPlayerItem.status {
                case .readyToPlay:
                    self.isLoading = false
                    let d = avPlayerItem.duration.seconds
                    self.duration = d.isNaN || d <= 0 ? item.duration : d
                    let saved = self.progressStore.progress(for: item.id)
                    if saved > 5 { self.seek(to: saved) }
                    self.player?.rate = self.playbackRate
                    self.isPlaying = true
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
        DispatchQueue.main.async { [weak self] in
            self?.handlePlaybackFinished()
        }
    }

    @MainActor
    private func handlePlaybackFinished() {
        // 播完：标记为已完成（同时清除进度记录），从列表移除
        if let id = currentItem?.id {
            progressStore.markCompleted(for: id)
            playlist.removeAll { $0.id == id }
        }

        if !playlist.isEmpty {
            // 还有待播内容，播 playlist[0]
            loadAndPlay(item: playlist[0])
        } else {
            // 全部播完：清理并通知 UI
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
