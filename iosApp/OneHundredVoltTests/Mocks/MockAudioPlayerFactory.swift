import AVFoundation
import Foundation
@testable import OneHundredVolt

/// AVPlayer 의 Mock — 서브클래싱 없이 프로토콜로 구현
final class MockAVPlayer: AVPlayerProtocol {
    var rate: Float = 0
    var pauseCallCount = 0
    var seekCallCount = 0
    var lastSeekTime: CMTime = .zero
    var pendingSeekCompletion: ((Bool) -> Void)?
    private var timeObserverBlock: ((CMTime) -> Void)?

    func play() {
        rate = 1
    }

    func pause() {
        pauseCallCount += 1
        rate = 0
    }

    func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime) {
        seekCallCount += 1
        lastSeekTime = time
    }

    func seek(
        to time: CMTime,
        toleranceBefore: CMTime,
        toleranceAfter: CMTime,
        completionHandler: @escaping @Sendable (Bool) -> Void
    ) {
        seekCallCount += 1
        lastSeekTime = time
        pendingSeekCompletion = completionHandler
    }

    func addPeriodicTimeObserver(
        forInterval interval: CMTime,
        queue: DispatchQueue?,
        using block: @escaping @Sendable (CMTime) -> Void
    ) -> Any {
        timeObserverBlock = block
        return NSObject() // opaque token
    }

    func removeTimeObserver(_ observer: Any) {
        timeObserverBlock = nil
    }

    func replaceCurrentItemForOhv(with item: AVPlayerItem) {}

    /// 测试中手动触发 seek completion
    func fireSeekCompletion(finished: Bool = true) {
        pendingSeekCompletion?(finished)
        pendingSeekCompletion = nil
    }

    /// 测试中手动推进时间
    func fireTimeUpdate(_ seconds: TimeInterval) {
        timeObserverBlock?(CMTime(seconds: seconds, preferredTimescale: 600))
    }
}

struct MockAudioPlayerFactory: AudioPlayerFactory {
    let item = AVPlayerItem(url: URL(string: "file:///tmp/mock-audio.mp3")!)
    let player = MockAVPlayer()

    func makePlayerItem(url: URL) -> AVPlayerItem { item }
    func makePlayerItem(asset: AVURLAsset) -> AVPlayerItem { item }
    func makePlayer(playerItem: AVPlayerItem) -> AVPlayerProtocol { player }
}
