import SwiftUI

struct AlbumDetailView: View {
    let album: Album
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: AlbumViewModel
    @State private var showPlayer = false
    @State private var postDetailURL: URL? = nil
    private let player = AudioPlayerService.shared
    private let progressStore = PlaybackProgressStore.shared

    init(album: Album) {
        self.album = album
        _viewModel = State(initialValue: AlbumViewModel(album: album))
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.Colors.background.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    // 专辑封面 + 信息
                    albumHeader

                    Section {
                        VStack(spacing: 0) {
                            if viewModel.isLoading {
                                ForEach(0..<6, id: \.self) { _ in
                                    skeletonRow
                                }
                            } else {
                                ForEach(Array(viewModel.displayItems.enumerated()), id: \.element.id) { index, item in
                                    audioRow(item: item, index: index)
                                        .onTapGesture {
                                            postDetailURL = URL(string: "https://afdian.com/p/\(item.id)")
                                        }

                                    if index < viewModel.displayItems.count - 1 {
                                        Divider()
                                            .background(Theme.Colors.divider)
                                            .padding(.leading, Theme.Spacing.sm + 20 + Theme.Spacing.sm + 44 + Theme.Spacing.sm)
                                    }
                                }
                            }
                        }
                        .background(Theme.Colors.cardBackground)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.card))
                        .padding(.horizontal, Theme.Spacing.md)
                    }
                }
                .padding(.bottom, player.currentItem != nil ? 80 : 0)
            }

            // 迷你播放器
            if player.currentItem != nil {
                VStack(spacing: 0) {
                    Divider().background(Theme.Colors.divider)
                    MiniPlayerView(showPlayer: $showPlayer)
                }
                .transition(.move(edge: .bottom))
            }
        }
        .navigationTitle(album.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    postDetailURL = URL(string: "https://afdian.com/album/\(album.id)")
                } label: {
                    Image(systemName: "safari")
                        .foregroundColor(Theme.Colors.accent)
                }
            }
        }
        .onAppear { viewModel.load() }
        .onReceive(NotificationCenter.default.publisher(for: .navigateToHomePlaylist)) { _ in
            // 先关闭全屏播放器（如有），再 pop 回首页
            showPlayer = false
            // 稍等 fullScreenCover 关闭动画，再 dismiss 自身（回到 HomeView）
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                dismiss()
            }
        }
        .fullScreenCover(isPresented: $showPlayer) {
            PlayerView()
        }
        .sheet(item: $postDetailURL) { url in
            SafariView(url: url)
                .ignoresSafeArea()
        }
    }

    // MARK: - 骨架屏占位行

    private var skeletonRow: some View {
        HStack(spacing: Theme.Spacing.sm) {
            // 序号占位
            RoundedRectangle(cornerRadius: 3)
                .fill(Theme.Colors.cardBackground)
                .frame(width: 20, height: 12)

            // 封面占位
            RoundedRectangle(cornerRadius: 4)
                .fill(Theme.Colors.cardBackground)
                .frame(width: 44, height: 44)

            // 标题占位
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Theme.Colors.cardBackground)
                    .frame(height: 13)
                RoundedRectangle(cornerRadius: 3)
                    .fill(Theme.Colors.cardBackground)
                    .frame(width: 80, height: 11)
            }

            Spacer()

            // 时长占位
            RoundedRectangle(cornerRadius: 3)
                .fill(Theme.Colors.cardBackground)
                .frame(width: 36, height: 12)
        }
        .padding(.horizontal, Theme.Spacing.sm)
        .padding(.vertical, 12)
        .shimmer()
    }

    // MARK: - 专辑头部

    private var albumHeader: some View {
        VStack(spacing: Theme.Spacing.md) {
            // 封面图
            CachedImage(urlString: album.coverUrl) {
                RoundedRectangle(cornerRadius: Theme.CornerRadius.card)
                    .fill(Theme.Colors.cardBackground)
                    .overlay(
                        Image(systemName: "music.note.list")
                            .font(.system(size: 40))
                            .foregroundColor(Theme.Colors.textSecondary)
                    )
            }
            .frame(width: 180, height: 180)
            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.card))
            .shadow(color: .black.opacity(0.3), radius: 12, y: 6)

            // 标题 + 元信息
            VStack(spacing: Theme.Spacing.xs) {
                Text(album.title)
                    .font(Theme.Typography.title)
                    .foregroundColor(Theme.Colors.textPrimary)
                    .multilineTextAlignment(.center)

                HStack(spacing: Theme.Spacing.xs) {
                    if album.audioCount > 0 {
                        Text("全 \(album.audioCount) 期")
                    }
                    if album.totalDuration > 0 {
                        Text("·")
                        Text(album.totalDuration.humanReadable)
                    }
                }
                .font(Theme.Typography.caption)
                .foregroundColor(Theme.Colors.textSecondary)
            }

            // 全部播放按钮（追加到现有队列末尾，立即播放第一集）
            Button {
                if !viewModel.displayItems.isEmpty {
                    player.appendAndPlay(items: viewModel.displayItems)
                    showPlayer = true
                }
            } label: {
                HStack(spacing: 6) {
                    if player.isLoading && player.currentItem.map({ item in
                        viewModel.displayItems.first?.id == item.id
                    }) == true {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.black)
                            .scaleEffect(0.8)
                    } else {
                        Image(systemName: "play.fill")
                    }
                    Text("全部播放")
                }
                .font(Theme.Typography.subheadline)
                .foregroundColor(.black)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Theme.Colors.accent)
                .cornerRadius(Theme.CornerRadius.button)
            }
            .padding(.horizontal, Theme.Spacing.md)
            .disabled(viewModel.isLoading)
            .buttonStyle(ScaleButtonStyle(scale: 0.97))
        }
        .padding(Theme.Spacing.lg)
    }

    // MARK: - 音频行

    private func audioRow(item: AudioItem, index: Int) -> some View {
        let isCurrent   = viewModel.isCurrentlyPlaying(item)
        // 直接读取 progressStore.completedIds 以建立 @Observable 响应追踪
        let isCompleted = progressStore.completedIds.contains(item.id)
        // 已播完且不是当前正在播的：整行置灰
        let dimmed = isCompleted && !isCurrent
        // 当前单集正在缓冲中
        let isItemLoading = isCurrent && player.isLoading

        return HStack(spacing: Theme.Spacing.sm) {
            // 封面 + 状态指示器叠加
            ZStack {
                CachedImage(urlString: item.coverUrl) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Theme.Colors.cardBackground)
                }
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .opacity(dimmed ? 0.4 : 1.0)

                if (isCurrent && (isItemLoading || player.isPlaying)) || dimmed {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.black.opacity(dimmed ? 0.25 : 0.45))
                        .frame(width: 44, height: 44)
                }

                if isItemLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(0.7)
                } else if isCurrent && player.isPlaying {
                    Image(systemName: "waveform")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(.white)
                        .symbolEffect(.variableColor.iterative)
                } else if dimmed {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.white.opacity(0.8))
                }
            }

            // 标题（上行）+ 时长（下行）
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(
                        isCurrent ? Theme.Colors.accent
                        : dimmed  ? Theme.Colors.textSecondary
                        : Theme.Colors.textPrimary
                    )
                    .lineLimit(1)

                Text(item.duration.minutesOnly)
                    .font(Theme.Typography.caption)
                    .foregroundColor(dimmed ? Theme.Colors.textSecondary.opacity(0.4) : Theme.Colors.textSecondary)
            }

            Spacer()

            // 追加到播放列表末尾
            Button {
                player.appendToPlaylist(item)
            } label: {
                Image(systemName: player.playlist.contains(where: { $0.id == item.id })
                      ? "checkmark.circle.fill" : "plus.circle")
                    .font(.system(size: 20))
                    .foregroundColor(
                        player.playlist.contains(where: { $0.id == item.id })
                        ? Theme.Colors.accent : Theme.Colors.textSecondary.opacity(dimmed ? 0.4 : 1.0)
                    )
            }
            .buttonStyle(ScaleButtonStyle())

            // 立即播放
            Button {
                player.playImmediately(item)
                showPlayer = true
            } label: {
                if isItemLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .scaleEffect(0.75)
                        .tint(Theme.Colors.accent)
                        .frame(width: 20, height: 20)
                } else {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 20))
                        .foregroundColor(Theme.Colors.accent.opacity(dimmed ? 0.4 : 1.0))
                }
            }
            .buttonStyle(ScaleButtonStyle())
        }
        .padding(.horizontal, Theme.Spacing.sm)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
