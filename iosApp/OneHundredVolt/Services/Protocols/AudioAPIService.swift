import Foundation

protocol AudioAPIService {
    func fetchAudioURL(postId: String) async throws -> String
}

extension AfdianAPIService: AudioAPIService {}
