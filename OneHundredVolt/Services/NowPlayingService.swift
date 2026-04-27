import Foundation
import MediaPlayer

/// 锁屏 / 控制中心 Now Playing 集成
final class NowPlayingService {

    static let shared = NowPlayingService()
    private init() { setupRemoteCommands() }

    private let player = AudioPlayerService.shared
    private let center = MPNowPlayingInfoCenter.default()
    private let commandCenter = MPRemoteCommandCenter.shared()

    // MARK: - Remote Commands

    private func setupRemoteCommands() {
        // 播放 / 暂停
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
        // 下一首 / 上一首
        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.player.playNext()
            return .success
        }
        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.player.playPrevious()
            return .success
        }
        // 进度拖动
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.player.seek(to: e.positionTime)
            return .success
        }
        // 快进 / 快退
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
            center.nowPlayingInfo = nil
            return
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

        // 异步加载封面图（用 URLSession 避免阻塞主线程）
        if let coverUrl = item.coverUrl, let url = URL(string: coverUrl) {
            Task {
                if let (data, _) = try? await URLSession.shared.data(from: url),
                   let uiImage = UIImage(data: data) {
                    let artwork = MPMediaItemArtwork(boundsSize: uiImage.size) { _ in uiImage }
                    var updatedInfo = self.center.nowPlayingInfo ?? info
                    updatedInfo[MPMediaItemPropertyArtwork] = artwork
                    await MainActor.run {
                        self.center.nowPlayingInfo = updatedInfo
                    }
                }
            }
        }

        center.nowPlayingInfo = info
    }

    func updateProgress(current: TimeInterval, duration: TimeInterval) {
        guard var info = center.nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = current
        info[MPMediaItemPropertyPlaybackDuration] = duration
        info[MPNowPlayingInfoPropertyPlaybackRate] = AudioPlayerService.shared.playbackRate
        center.nowPlayingInfo = info
    }
}
