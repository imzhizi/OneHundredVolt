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
        Button {
            showPlayer = true
        } label: {
            HStack(spacing: Theme.Spacing.sm) {
                // 封面
                CachedImage(urlString: item.coverUrl) {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Theme.Colors.cardBackground)
                }
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 6))

                // 标题 + 进度
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textPrimary)
                        .lineLimit(1)

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.Colors.divider).frame(height: 2)
                            Capsule().fill(Theme.Colors.accent)
                                .frame(width: geo.size.width * player.progressRatio, height: 2)
                        }
                    }
                    .frame(height: 2)
                }

                Spacer()

                // 播放/暂停（缓冲中显示 loading 圆圈）
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

                // 下一首
                Button {
                    player.playNext()
                } label: {
                    Image(systemName: "forward.fill")
                        .font(.system(size: 18))
                        .foregroundColor(Theme.Colors.textSecondary)
                }
                .buttonStyle(.plain)
                .disabled(player.isLoading)

                // 播放队列（回首页并滚动到播放列表）
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
            .background(Theme.Colors.secondaryBackground)
        }
        .buttonStyle(.plain)
    }
}
