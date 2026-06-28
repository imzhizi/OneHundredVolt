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
    func replaceCurrentItem(with item: AVPlayerItem)
}

extension AVPlayer: AVPlayerProtocol {
    func replaceCurrentItem(with item: AVPlayerItem) {
        // iOS 16+ 提供 replaceCurrentItem API
        // AVPlayer 本质上继承 AVQueuePlayer（自 iOS 16），可直接调用
        if #available(iOS 16.0, *) {
            self.replaceCurrentItem(with: item)
        } else {
            // iOS 15 fallback：直接 setItem
            self.replaceCurrentItem(with: item)
        }
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
