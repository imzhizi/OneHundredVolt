import SwiftUI

/// 底部迷你播放条
struct MiniPlayerView: View {
    @Binding var showPlayer: Bool
    private let player = AudioPlayerService.shared

    var body: some View {
        if let item = player.currentItem {
            content(item: item)
        }
    }

    @ViewBuilder
    private func content(item: AudioItem) -> some View {
        VStack(spacing: 0) {
            // 顶部贯穿进度条
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.Colors.divider).frame(height: 2)
                    Capsule().fill(Theme.Colors.accent)
                        .frame(width: geo.size.width * player.progressRatio, height: 2)
                }
            }
            .frame(height: 2)
            .padding(.horizontal, 0)

            // 内容行
            Button {
                showPlayer = true
            } label: {
                HStack(spacing: Theme.Spacing.sm) {
                    CachedImage(urlString: item.coverUrl) {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(Theme.Colors.cardBackground)
                    }
                    .frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 6))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title)
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textPrimary)
                            .lineLimit(1)
                    }

                    Spacer()

                    Button {
                        if !player.isLoading {
                            player.togglePlayPause()
                        }
                    } label: {
                        ZStack {
                            if player.isLoading {
                                ProgressView()
                                    .progressViewStyle(.circular)
                                    .tint(Theme.Colors.textPrimary)
                                    .scaleEffect(0.85)
                                    .frame(width: 28, height: 28)
                            } else {
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 22))
                                    .foregroundColor(Theme.Colors.textPrimary)
                            }
                        }
                        .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)

                    Button {
                        player.playNext()
                    } label: {
                        Image(systemName: "forward.end.fill")
                            .font(.system(size: 18))
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .disabled(player.isLoading)

                    Button {
                        NotificationCenter.default.post(name: .navigateToHomePlaylist, object: nil)
                    } label: {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 18))
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.vertical, Theme.Spacing.sm)
            }
            .buttonStyle(ScaleButtonStyle(scale: 0.98))
        }
        .background(Theme.Colors.secondaryBackground)
    }
}
