import Foundation
@testable import OneHundredVolt

final class MockPlaybackProgressStore: PlaybackProgressStoring {
    var progressMap: [String: TimeInterval] = [:]
    var completedIds: Set<String> = []
    var setProgressCallCount = 0
    var markCompletedCallCount = 0
    var lastPlayedPostId: String?

    func progress(for postId: String) -> TimeInterval {
        progressMap[postId] ?? 0
    }

    func setProgress(_ seconds: TimeInterval, for postId: String) {
        setProgressCallCount += 1
        progressMap[postId] = seconds
    }

    func setLastPlayed(postId: String, creatorId: String?) {
        lastPlayedPostId = postId
    }

    func markCompleted(for postId: String) {
        markCompletedCallCount += 1
        progressMap.removeValue(forKey: postId)
        completedIds.insert(postId)
    }
}
