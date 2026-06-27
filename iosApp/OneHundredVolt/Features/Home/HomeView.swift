import SwiftUI
import Shared

struct HomeView: View {
    @State private var viewModel = HomeViewModel()
    @State private var showSettings = false
    @State private var showPlayer = false
    @State private var scrollToPlaylist = false
    @State private var navigationPath = NavigationPath()
    @State private var scrollViewID = UUID()

    private let player = AudioPlayerService.shared

    private let maxVisibleCreators = 3

    var body: some View {
        NavigationStack(path: $navigationPath) {
            ZStack(alignment: .bottom) {
                Theme.Colors.background.ignoresSafeArea()

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            Color.clear.frame(height: 0).id("top")

                            // MARK: 空状态
                            if viewModel.creators.isEmpty {
                                emptyStateView
                            }

                            // MARK: 创作者 + 专辑（最多显示前 3 个）
                            Spacer().frame(height: Theme.Spacing.sm)
                            ForEach(viewModel.creators.prefix(maxVisibleCreators)) { creator in
                                creatorSection(creator: creator)
                            }

                            // MARK: 更多创作者入口
                            if viewModel.creators.count > maxVisibleCreators {
                                moreCreatorsRow
                            }

                            // MARK: 播放列表
                            playlistSection
                                .id("playlist")

                            Spacer().frame(height: player.currentItem != nil ? 80 : 0)
                        }
                    }
                    .id(scrollViewID)
                    .onChange(of: scrollToPlaylist) { _, val in
                        guard val else { return }
                        withAnimation { proxy.scrollTo("playlist", anchor: .top) }
                        scrollToPlaylist = false
                    }
                    .onAppear {
                        scrollViewID = UUID()
                        viewModel.loadIfNeeded()
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
                            .foregroundColor(Theme.Colors.accent)
                    }
                }
            }
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
            .fullScreenCover(isPresented: $showPlayer) { PlayerView() }
        }
    }

    // MARK: - 创作者专辑

    private func creatorSection(creator: Creator) -> some View {
        let albums = viewModel.albums(for: creator)
        guard !albums.isEmpty else { return AnyView(EmptyView()) }

        return AnyView(
            VStack(alignment: .leading, spacing: Theme.Spacing.sm) {
                NavigationLink {
                    CreatorView(creator: creator)
                } label: {
                    HStack(spacing: Theme.Spacing.sm) {
                        CachedImage(urlString: creator.avatarUrl) {
                            Circle().fill(Theme.Colors.cardBackground)
                                .overlay(Image(systemName: "person.fill").foregroundColor(Theme.Colors.textSecondary))
                        }
                        .frame(width: 36, height: 36)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Theme.Colors.divider, lineWidth: 1))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(creator.name)
                                .font(Theme.Typography.subheadline)
                                .foregroundColor(Theme.Colors.textPrimary)
                            if let doing = creator.doing {
                                Text(doing)
                                    .font(Theme.Typography.caption)
                                    .foregroundColor(Theme.Colors.textSecondary)
                                    .lineLimit(1)
                            }
                        }

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
                            .buttonStyle(ScaleButtonStyle(scale: 0.95))
                        }
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                }

                Divider().background(Theme.Colors.divider)
                    .padding(.top, Theme.Spacing.sm)
            }
            .padding(.top, Theme.Spacing.md)
            .padding(.bottom, Theme.Spacing.lg)
        )
    }

    // MARK: - 更多创作者入口

    private var moreCreatorsRow: some View {
        NavigationLink {
            AllCreatorsView(creators: viewModel.creators)
        } label: {
            sectionHeader("查看全部")
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.bottom, Theme.Spacing.lg)
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
                List {
                    ForEach(Array(player.playlist.enumerated()), id: \.element.id) { index, item in
                        PlaylistRowView(item: item, index: index, showPlayer: $showPlayer)
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowBackground(
                                (player.currentItem?.id == item.id
                                    ? Theme.Colors.accent.opacity(0.06)
                                    : Theme.Colors.cardBackground)
                            )
                            .listRowSeparatorTint(Theme.Colors.divider)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    player.removeItems(atOffsets: IndexSet(integer: index))
                                } label: {
                                    Image(systemName: "trash.fill")
                                }
                            }
                    }
                    .onMove { from, to in
                        player.moveItem(fromOffsets: from, toOffset: to)
                    }
                }
                .listStyle(.plain)
                .scrollDisabled(true)
                .frame(height: CGFloat(player.playlist.count) * 64)
                .background(Theme.Colors.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.card))
                .padding(.horizontal, Theme.Spacing.md)
            }
        }
        .padding(.bottom, Theme.Spacing.lg)
    }

    // MARK: - 空状态

    private var emptyStateView: some View {
        VStack(spacing: Theme.Spacing.lg) {
            Image(systemName: "bolt.slash")
                .font(.system(size: 48, weight: .thin))
                .foregroundColor(Theme.Colors.textSecondary.opacity(0.4))

            VStack(spacing: Theme.Spacing.xs) {
                Text("还没有内容")
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textPrimary)
                Text("前往设置同步你的爱发电内容")
                    .font(Theme.Typography.caption)
                    .foregroundColor(Theme.Colors.textSecondary)
            }

            Button {
                showSettings = true
            } label: {
                Text("去同步")
                    .primaryButtonStyle()
                    .frame(width: 160)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 80)
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
        let isCompleted = progressStore.isCompleted(item.id)
        let isActivelyPlaying = isCurrent && (player.isPlaying || player.isLoading)

        HStack(spacing: 12) {
            // 当前播放竖条
            Rectangle()
                .fill(isCurrent ? Theme.Colors.accent : Color.clear)
                .frame(width: 3)
                .clipShape(Capsule())

            // 封面 + 状态指示器叠加
            ZStack {
                CachedImage(urlString: item.coverUrl) {
                    RoundedRectangle(cornerRadius: 6).fill(Theme.Colors.background)
                }
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 6))

                if isCurrent || isCompleted {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Color.black.opacity(0.45))
                        .frame(width: 44, height: 44)

                    if player.isLoading && isCurrent {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.white)
                            .scaleEffect(0.7)
                    } else if player.isPlaying && isCurrent {
                        Image(systemName: "waveform")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.white)
                            .symbolEffect(.variableColor.iterative)
                    } else if isCurrent {
                        Image(systemName: "play.fill")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                    } else {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
            }

            // 标题（上行）+ 时长（下行）
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(Theme.Typography.caption)
                    .foregroundColor(isCurrent ? Theme.Colors.accent : Theme.Colors.textPrimary)
                    .lineLimit(1)

                Text(isCurrent ? "还有 \(max(1, Int(player.duration - player.currentTime) / 60)) 分钟" : item.duration.minutesOnly)
                    .font(Theme.Typography.mono)
                    .foregroundColor(isCurrent ? Theme.Colors.accent.opacity(0.7) : Theme.Colors.textSecondary)
            }

            Spacer(minLength: 0)

            // 拖拽手柄
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 12))
                .foregroundColor(Theme.Colors.textSecondary.opacity(0.4))
        }
        .opacity(isCompleted && !isCurrent ? 0.4 : 1)
        .padding(.vertical, 10)
        .padding(.leading, 0)
        .padding(.trailing, Theme.Spacing.sm)
        .background {
            if isCurrent {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Theme.Colors.accent.opacity(0.06)
                        Theme.Colors.accent.opacity(0.12)
                            .frame(width: geo.size.width * player.progressRatio)
                    }
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if isActivelyPlaying {
                showPlayer = true
                return
            }
            player.playItemInPlaylist(at: index)
        }
    }
}

// MARK: - 专辑卡片

private struct AlbumCardView: View {
    let album: Album

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
            ZStack(alignment: .bottomTrailing) {
                CachedImage(urlString: album.coverUrl) {
                    RoundedRectangle(cornerRadius: Theme.CornerRadius.cover)
                        .fill(Theme.Colors.cardBackground)
                }
                .frame(width: 90, height: 90)
                .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.cover))
                .opacity(album.isAccessible ? 1.0 : 0.5)

                // 付费未购买：右下角锁图标
                if !album.isAccessible {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(4)
                        .background(Color.black.opacity(0.55))
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                        .padding(4)
                }
            }

            Text(album.title)
                .font(Theme.Typography.caption)
                .foregroundColor(album.isAccessible ? Theme.Colors.textPrimary : Theme.Colors.textSecondary)
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
