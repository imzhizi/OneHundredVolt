import AVFoundation
import Foundation

/// AudioPlayerService 内部使用的播放器接口，方便单测替换
protocol AVPlayerProtocol: AnyObject {
    var rate: Float { get set }
    func play()
    func pause()
    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime)
    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime, completionHandler: @escaping @Sendable (Bool) -> Void)
    func addPeriodicTimeObserver(forInterval interval: CMTime, queue: DispatchQueue?, using block: @escaping @Sendable (CMTime) -> Void) -> Any
    func removeTimeObserver(_ observer: Any)
    /// v1.7 Defer 1：替换当前播放项（iOS 16+），保留 player 实例 + observers + audio session
    func replaceCurrentItemForOhv(with item: AVPlayerItem)
}

extension AVPlayer: AVPlayerProtocol {
    func replaceCurrentItemForOhv(with item: AVPlayerItem) {
        // iOS 16+ 提供 replaceCurrentItem API
        // Keep the protocol name distinct so this call dispatches to AVPlayer's
        // framework implementation instead of recursing into the adapter.
        self.replaceCurrentItem(with: item)
    }
}

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
