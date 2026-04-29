import AVFoundation
import Foundation

/// AudioPlayerService 内部使用的播放器接口，方便单测替换
protocol AVPlayerProtocol: AnyObject {
    var rate: Float { get set }
    func pause()
    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime)
    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime, completionHandler: @escaping @Sendable (Bool) -> Void)
    func addPeriodicTimeObserver(forInterval interval: CMTime, queue: DispatchQueue?, using block: @escaping @Sendable (CMTime) -> Void) -> Any
    func removeTimeObserver(_ observer: Any)
}

extension AVPlayer: AVPlayerProtocol {}

protocol AudioPlayerFactory {
    func makePlayerItem(url: URL) -> AVPlayerItem
    func makePlayerItem(asset: AVURLAsset) -> AVPlayerItem
    func makePlayer(playerItem: AVPlayerItem) -> AVPlayerProtocol
}

struct LiveAudioPlayerFactory: AudioPlayerFactory {
    func makePlayerItem(url: URL) -> AVPlayerItem { AVPlayerItem(url: url) }
    func makePlayerItem(asset: AVURLAsset) -> AVPlayerItem { AVPlayerItem(asset: asset) }
    func makePlayer(playerItem: AVPlayerItem) -> AVPlayerProtocol { AVPlayer(playerItem: playerItem) }
}
