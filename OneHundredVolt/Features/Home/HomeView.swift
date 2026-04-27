import SwiftUI

struct HomeView: View {
    @State private var viewModel = HomeViewModel()
    @State private var selectedCreator: Creator?
    @State private var showSettings = false
    @State private var showPlayer = false
    @State private var scrollToPlaylist = false
    @State private var navigationPath = NavigationPath()

    private let player = AudioPlayerService.shared
    private let progressStore = PlaybackProgressStore.shared

    var body: some View {
        NavigationStack(path: $navigationPath) {
            ZStack(alignment: .bottom) {
                Theme.Colors.background.ignoresSafeArea()

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {

                            // MARK: 上次播放
                            if let item = viewModel.continueListeningItem {
                                continueListeningSection(item: item)
                            }

                            // MARK: 创作者 + 专辑
                            ForEach(viewModel.creators) { creator in
                                creatorSection(creator: creator)
                            }

                            // MARK: 播放列表
                            playlistSection
                                .id("playlist")

                            Spacer().frame(height: player.currentItem != nil ? 80 : 0)
                        }
                    }
                    .onChange(of: scrollToPlaylist) { _, val in
                        guard val else { return }
                        withAnimation { proxy.scrollTo("playlist", anchor: .top) }
                        scrollToPlaylist = false
                    }
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
            .navigationTitle("一百伏特")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape.fill")
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }
            }
            .onAppear { viewModel.loadIfNeeded() }
            .onReceive(NotificationCenter.default.publisher(for: .didClearData)) { _ in
                viewModel.load()
            }
            .onReceive(NotificationCenter.default.publisher(for: .didSyncComplete)) { _ in
                viewModel.load()
            }
            .onReceive(NotificationCenter.default.publisher(for: .showPlaylistSheet)) { _ in
                scrollToPlaylist = true
            }
            .onReceive(NotificationCenter.default.publisher(for: .navigateToHomePlaylist)) { _ in
                // 弹回首页根路径（pop AlbumDetailView），等动画完成后再滚动
                navigationPath = NavigationPath()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    scrollToPlaylist = true
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .openPlayer)) { _ in
                showPlayer = true
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(item: $selectedCreator) { creator in CreatorView(creator: creator) }
            .fullScreenCover(isPresented: $showPlayer) { PlayerView() }
        }
    }

    // MARK: - 继续收听

    private func continueListeningSection(item: AudioItem) -> some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
            sectionHeader("▶ 继续收听")

            Button {
                player.play(item: item)
                showPlayer = true
            } label: {
                HStack(spacing: Theme.Spacing.md) {
                    CachedImage(urlString: item.coverUrl) {
                        RoundedRectangle(cornerRadius: Theme.CornerRadius.cover)
                            .fill(Theme.Colors.cardBackground)
                            .overlay(Image(systemName: "music.note").foregroundColor(Theme.Colors.textSecondary))
                    }
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.cover))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title)
                            .font(Theme.Typography.subheadline)
                            .foregroundColor(Theme.Colors.textPrimary)
                            .lineLimit(2)

                        let ratio = viewModel.progressRatio(for: item)
                        let elapsed = viewModel.progressTime(for: item)
                        HStack(spacing: Theme.Spacing.xs) {
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Theme.Colors.divider).frame(height: 3)
                                    Capsule().fill(Theme.Colors.accent)
                                        .frame(width: geo.size.width * ratio, height: 3)
                                }
                            }
                            .frame(height: 3)
                            Text("\(elapsed.formatted) / \(item.duration.formatted)")
                                .font(Theme.Typography.mono)
                                .foregroundColor(Theme.Colors.textSecondary)
                                .fixedSize()
                        }
                    }
                }
                .padding(Theme.Spacing.md)
                .cardStyle()
            }
            .buttonStyle(.plain)
            .padding(.horizontal, Theme.Spacing.md)
        }
        .padding(.top, Theme.Spacing.md)
        .padding(.bottom, Theme.Spacing.lg)
    }

    // MARK: - 创作者专辑

    private func creatorSection(creator: Creator) -> some View {
        let albums = viewModel.albums(for: creator)
        guard !albums.isEmpty else { return AnyView(EmptyView()) }

        return AnyView(
            VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
                Button {
                    selectedCreator = creator
                } label: {
                    HStack {
                        sectionHeader(creator.name)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 12))
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                }
                .buttonStyle(.plain)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Theme.Spacing.sm) {
                        ForEach(albums) { album in
                            NavigationLink {
                                AlbumDetailView(album: album)
                            } label: {
                                AlbumCardView(album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                }
            }
            .padding(.bottom, Theme.Spacing.lg)
        )
    }

    // MARK: - 播放列表

    private var playlistSection: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
            // 标题行
            HStack {
                sectionHeader("播放列表")
                Spacer()
                if !player.playlist.isEmpty {
                    Button {
                        withAnimation { player.clearAll() }
                    } label: {
                        Text("清空")
                            .font(Theme.Typography.caption)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .padding(.trailing, Theme.Spacing.md)
                }
            }

            if player.playlist.isEmpty {
                HStack {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "list.bullet")
                            .font(.system(size: 28))
                            .foregroundColor(Theme.Colors.textSecondary.opacity(0.3))
                        Text("在专辑页点击 + 添加单集")
                            .font(Theme.Typography.caption)
                            .foregroundColor(Theme.Colors.textSecondary.opacity(0.5))
                    }
                    Spacer()
                }
                .padding(.vertical, Theme.Spacing.xl)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(player.playlist.enumerated()), id: \.element.id) { index, item in
                        // 用独立子视图，让每行自己订阅 player，避免整个列表因 currentTime 变化全量重绘
                        PlaylistRowView(item: item, index: index, showPlayer: $showPlayer)

                        if index < player.playlist.count - 1 {
                            Divider()
                                .background(Theme.Colors.divider)
                                .padding(.leading, 16 + 44 + 12)
                        }
                    }
                    .onMove { from, to in
                        player.playlist.move(fromOffsets: from, toOffset: to)
                        player.syncAfterReorder()
                    }
                    .onDelete { indices in
                        let deletingCurrent = indices.contains(where: {
                            player.playlist[$0].id == player.currentItem?.id
                        })
                        player.playlist.remove(atOffsets: indices)
                        player.didRemoveItems(deletingCurrent: deletingCurrent)
                    }
                }
                .background(Theme.Colors.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.card))
                .padding(.horizontal, Theme.Spacing.md)
            }
        }
        .padding(.bottom, Theme.Spacing.lg)
    }

    // MARK: - 辅助

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(Theme.Typography.subheadline)
            .foregroundColor(Theme.Colors.textSecondary)
            .padding(.horizontal, Theme.Spacing.md)
    }
}

// MARK: - 播放列表行（独立子视图，隔离 player 订阅范围）

private struct PlaylistRowView: View {
    let item: AudioItem
    let index: Int
    @Binding var showPlayer: Bool

    private let player = AudioPlayerService.shared
    private let progressStore = PlaybackProgressStore.shared

    var body: some View {
        let isCurrent = player.currentItem?.id == item.id
        let progress = progressStore.progress(for: item.id)
        let ratio = item.duration > 0 ? min(1.0, progress / item.duration) : 0.0

        HStack(spacing: 12) {
            // 当前播放竖条
            Rectangle()
                .fill(isCurrent ? Theme.Colors.accent : Color.clear)
                .frame(width: 3)
                .clipShape(Capsule())

            // 封面
            CachedImage(urlString: item.coverUrl) {
                RoundedRectangle(cornerRadius: 6).fill(Theme.Colors.background)
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            // 标题 + 进度条
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 5) {
                    if isCurrent {
                        if player.isLoading {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .scaleEffect(0.6)
                                .tint(Theme.Colors.accent)
                                .frame(width: 12, height: 12)
                        } else {
                            Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundColor(Theme.Colors.accent)
                                .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                        }
                    }
                    Text(item.title)
                        .font(Theme.Typography.caption)
                        .foregroundColor(isCurrent ? Theme.Colors.accent : Theme.Colors.textPrimary)
                        .lineLimit(2)
                }

                if ratio > 0.01 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.Colors.divider).frame(height: 2)
                            Capsule()
                                .fill(Theme.Colors.accent.opacity(isCurrent ? 1.0 : 0.45))
                                .frame(width: geo.size.width * ratio, height: 2)
                        }
                    }
                    .frame(height: 2)
                }
            }

            Spacer(minLength: 0)

            // 时长
            Text(item.duration.formatted)
                .font(Theme.Typography.mono)
                .foregroundColor(isCurrent ? Theme.Colors.accent : Theme.Colors.textSecondary)
                .fixedSize()
        }
        .padding(.vertical, 10)
        .padding(.trailing, Theme.Spacing.sm)
        .background(isCurrent ? Theme.Colors.accent.opacity(0.06) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture {
            if index != 0 {
                player.playlist.move(fromOffsets: IndexSet(integer: index), toOffset: 0)
            }
            player.play(playlist: player.playlist, startAt: 0)
            showPlayer = true
        }
    }
}

// MARK: - 专辑卡片

private struct AlbumCardView: View {
    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            CachedImage(urlString: album.coverUrl) {
                RoundedRectangle(cornerRadius: Theme.CornerRadius.cover)
                    .fill(Theme.Colors.cardBackground)
            }
            .frame(width: 90, height: 90)
            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.cover))

            Text(album.title)
                .font(Theme.Typography.caption)
                .foregroundColor(Theme.Colors.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(width: 90, alignment: .leading)
        }
    }
}

// MARK: - Notifications

extension Notification.Name {
    static let showPlaylistSheet      = Notification.Name("OneHundredVolt.showPlaylistSheet")
    static let openPlayer             = Notification.Name("OneHundredVolt.openPlayer")
    /// 从任意页面跳回首页并滚动到播放列表
    static let navigateToHomePlaylist = Notification.Name("OneHundredVolt.navigateToHomePlaylist")
    /// 播放列表全部播完
    static let playbackDidFinishAll   = Notification.Name("OneHundredVolt.playbackDidFinishAll")
    /// Onboarding 完成，跳转首页
    static let didCompleteOnboarding  = Notification.Name("OneHundredVolt.didCompleteOnboarding")
}
