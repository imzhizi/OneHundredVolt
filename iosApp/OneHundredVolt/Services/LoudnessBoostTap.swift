import AVFoundation
import MediaToolbox

final class LoudnessBoostContext: @unchecked Sendable {
    let gain: Float
    var processingFormat: AudioStreamBasicDescription?

    init(gain: Float) {
        self.gain = gain
    }
}

enum LoudnessBoostTap {
    private static let gainInDecibels: Float = 6
    private static let linearGain = pow(10, gainInDecibels / 20)

    static func makeAudioMix(for playerItem: AVPlayerItem) async -> AVAudioMix? {
        let tracks: [AVAssetTrack]
        do {
            tracks = try await playerItem.asset.loadTracks(withMediaType: .audio)
        } catch {
            return nil
        }

        guard !tracks.isEmpty else { return nil }

        let context = LoudnessBoostContext(gain: linearGain)
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: Unmanaged.passRetained(context).toOpaque(),
            init: { _, clientInfo, tapStorageOut in
                tapStorageOut.pointee = clientInfo
            },
            finalize: { tap in
                let storage = MTAudioProcessingTapGetStorage(tap)
                Unmanaged<LoudnessBoostContext>.fromOpaque(storage).release()
            },
            prepare: { tap, _, processingFormat in
                let storage = MTAudioProcessingTapGetStorage(tap)
                let context = Unmanaged<LoudnessBoostContext>.fromOpaque(storage).takeUnretainedValue()
                context.processingFormat = processingFormat.pointee
            },
            unprepare: nil,
            process: { tap, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut in
                let status = MTAudioProcessingTapGetSourceAudio(
                    tap,
                    numberFrames,
                    bufferListInOut,
                    flagsOut,
                    nil,
                    numberFramesOut
                )
                guard status == noErr else { return }

                let storage = MTAudioProcessingTapGetStorage(tap)
                let context = Unmanaged<LoudnessBoostContext>.fromOpaque(storage).takeUnretainedValue()
                guard let format = context.processingFormat else { return }

                let buffers = UnsafeMutableAudioBufferListPointer(bufferListInOut)
                let isFloat = (format.mFormatFlags & kAudioFormatFlagIsFloat) != 0
                let isSignedInteger = (format.mFormatFlags & kAudioFormatFlagIsSignedInteger) != 0

                for buffer in buffers {
                    guard let data = buffer.mData else { continue }

                    if isFloat && format.mBitsPerChannel == 32 {
                        let sampleCount = Int(buffer.mDataByteSize) / MemoryLayout<Float>.stride
                        let samples = data.assumingMemoryBound(to: Float.self)
                        for index in 0..<sampleCount {
                            samples[index] = tanhf(samples[index] * context.gain)
                        }
                    } else if isSignedInteger && format.mBitsPerChannel == 16 {
                        let sampleCount = Int(buffer.mDataByteSize) / MemoryLayout<Int16>.stride
                        let samples = data.assumingMemoryBound(to: Int16.self)
                        for index in 0..<sampleCount {
                            let boosted = tanhf(Float(samples[index]) * context.gain / Float(Int16.max))
                            samples[index] = Int16(boosted * Float(Int16.max))
                        }
                    }
                }

                flagsOut.pointee = flags
            }
        )

        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tap
        )
        guard status == noErr, let tap else { return nil }

        let parameters = tracks.map { track in
            let inputParameters = AVMutableAudioMixInputParameters(track: track)
            inputParameters.audioTapProcessor = tap
            return inputParameters
        }

        let mix = AVMutableAudioMix()
        mix.inputParameters = parameters
        return mix
    }
}
