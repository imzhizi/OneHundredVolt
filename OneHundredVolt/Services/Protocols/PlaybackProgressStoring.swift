import Foundation

protocol PlaybackProgressStoring {
    func progress(for postId: String) -> TimeInterval
    func setProgress(_ seconds: TimeInterval, for postId: String)
    func setLastPlayed(postId: String, creatorId: String?)
    func markCompleted(for postId: String)
}

extension PlaybackProgressStore: PlaybackProgressStoring {}
