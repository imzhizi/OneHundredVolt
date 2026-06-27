import Foundation
import MediaPlayer

/// 锁屏 / 控制中心 Now Playing 集成
final class NowPlayingService {

    static let shared = NowPlayingService()
    private init() { setupRemoteCommands() }

    private let player = AudioPlayerService.shared
    private let center = MPNowPlayingInfoCenter.default()
    private let commandCenter = MPRemoteCommandCenter.shared()

    /// 当前正在显示的 item id（用于 race 检测）
    /// 封面异步加载完成时，只有当 currentItemId 未变才应用 artwork
    private var currentItemId: String?

    /// 当前正在进行的封面加载任务（用于切换 track 时取消上一个）
    private var artworkTask: Task<Void, Never>?

    // MARK: - Remote Commands

    private func setupRemoteCommands() {
        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.player.resume()
            return .success
        }
        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.player.pause()
            return .success
        }
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.player.togglePlayPause()
            return .success
        }
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.player.playNext()
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.player.playPrevious()
            return .success
        }
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.player.seek(to: e.positionTime)
            return .success
        }
        commandCenter.skipForwardCommand.preferredIntervals = [30]
        commandCenter.skipForwardCommand.addTarget { [weak self] _ in
            self?.player.skipForward()
            return .success
        }
        commandCenter.skipBackwardCommand.preferredIntervals = [15]
        commandCenter.skipBackwardCommand.addTarget { [weak self] _ in
            self?.player.skipBackward()
            return .success
        }
    }

    // MARK: - Now Playing Info

    func updateNowPlaying(item: AudioItem?) {
        guard let item else {
            currentItemId = nil
            artworkTask?.cancel()
            center.nowPlayingInfo = nil
            return
        }

        // 切换 track 时取消上一个未完成的封面加载
        if currentItemId != item.id {
            artworkTask?.cancel()
            currentItemId = item.id
        }

        var info: [String: Any] = [
            MPMediaItemPropertyTitle:            item.title,
            MPMediaItemPropertyMediaType:        MPMediaType.podcast.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: AudioPlayerService.shared.playbackRate,
            MPNowPlayingInfoPropertyIsLiveStream: false
        ]

        if item.duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = item.duration
        }

        // 先设置初始 info（无封面），让锁屏立刻有信息显示
        center.nowPlayingInfo = info

        // 异步加载封面：用 currentItemId race 检测，避免把旧 cover 应用到新 track
        if let coverUrl = item.coverUrl, let url = URL(string: coverUrl) {
            let targetId = item.id
            artworkTask = Task { [weak self] in
                guard let self else { return }
                guard let (data, _) = try? await URLSession.shared.data(from: url),
                      let uiImage = UIImage(data: data) else { return }
                // race check：track 未切换才应用
                guard self.currentItemId == targetId else { return }
                let artwork = MPMediaItemArtwork(boundsSize: uiImage.size) { _ in uiImage }
                await MainActor.run {
                    var updatedInfo = self.center.nowPlayingInfo ?? info
                    updatedInfo[MPMediaItemPropertyArtwork] = artwork
                    self.center.nowPlayingInfo = updatedInfo
                }
            }
        }
    }

    func updateProgress(current: TimeInterval, duration: TimeInterval) {
        guard var info = center.nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = current
        info[MPMediaItemPropertyPlaybackDuration] = duration
        info[MPNowPlayingInfoPropertyPlaybackRate] = AudioPlayerService.shared.playbackRate
        center.nowPlayingInfo = info
    }
}