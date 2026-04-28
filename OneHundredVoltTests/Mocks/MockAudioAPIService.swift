@testable import OneHundredVolt

final class MockAudioAPIService: AudioAPIService {
    var urlToReturn = "https://example.com/audio.mp3"
    var errorToThrow: Error? = nil
    var fetchCallCount = 0

    func fetchAudioURL(postId: String) async throws -> String {
        fetchCallCount += 1
        if let error = errorToThrow { throw error }
        return urlToReturn
    }
}
