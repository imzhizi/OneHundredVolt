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
                        if viewModel.isLoading {
                            // 骨架屏：加载中显示占位行
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
                                        .padding(.leading, 16 + 44 + 12)
                                }
                            }
                        }
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
        .padding(.horizontal, Theme.Spacing.md)
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

            // 全部播放按钮
            Button {
                if !viewModel.displayItems.isEmpty {
                    player.play(playlist: viewModel.displayItems, startAt: 0)
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
            // 正在播放指示 / 缓冲中 / 已完成勾
            ZStack {
                if isItemLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .scaleEffect(0.65)
                        .tint(Theme.Colors.accent)
                } else if isCurrent && player.isPlaying {
                    Image(systemName: "waveform")
                        .font(.system(size: 14))
                        .foregroundColor(Theme.Colors.accent)
                        .symbolEffect(.variableColor.iterative)
                } else if isCurrent && !player.isPlaying {
                    // 暂停中：显示暂停图标
                    Image(systemName: "pause.fill")
                        .font(.system(size: 11))
                        .foregroundColor(Theme.Colors.accent)
                } else if isCompleted && !isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(Theme.Colors.textSecondary.opacity(0.5))
                }
            }
            .frame(width: 20, alignment: .center)

            // 封面（小）
            CachedImage(urlString: item.coverUrl) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Theme.Colors.cardBackground)
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .opacity(dimmed ? 0.4 : 1.0)

            // 标题 + 进度
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(
                        isCurrent ? Theme.Colors.accent
                        : dimmed  ? Theme.Colors.textSecondary
                        : Theme.Colors.textPrimary
                    )
                    .lineLimit(2)

                // 进度条（听过但未完成才显示）
                let ratio = viewModel.progressRatio(for: item)
                if ratio > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.Colors.divider).frame(height: 2)
                            Capsule().fill(Theme.Colors.accent.opacity(0.6))
                                .frame(width: geo.size.width * ratio, height: 2)
                        }
                    }
                    .frame(height: 2)
                }
            }

            Spacer()

            // 时长
            Text(item.duration.formatted)
                .font(Theme.Typography.mono)
                .foregroundColor(dimmed ? Theme.Colors.textSecondary.opacity(0.4) : Theme.Colors.textSecondary)
                .fixedSize()

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
            .buttonStyle(.plain)

            // 立即播放（插入当前曲目后并立刻播放）
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
            .buttonStyle(.plain)
        }
        .padding(.horizontal, Theme.Spacing.md)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}
