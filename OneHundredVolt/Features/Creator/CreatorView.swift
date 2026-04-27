import SwiftUI

/// 创作者详情页：本地专辑列表 + 跳转爱发电主页
struct CreatorView: View {
    let creator: Creator
    @State private var viewModel: CreatorViewModel

    init(creator: Creator) {
        self.creator = creator
        _viewModel = State(initialValue: CreatorViewModel(creator: creator))
    }

    var body: some View {
        ZStack {
            Theme.Colors.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    creatorHeader

                    Divider().background(Theme.Colors.divider)
                        .padding(.vertical, Theme.Spacing.md)

                    LazyVStack(spacing: Theme.Spacing.sm) {
                        ForEach(viewModel.albums) { album in
                            NavigationLink {
                                AlbumDetailView(album: album)
                            } label: {
                                AlbumRowView(album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                    .padding(.bottom, Theme.Spacing.xl)
                }
            }
        }
        .navigationTitle(creator.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let url = creator.afdianPageURL {
                    NavigationLink {
                        CreatorWebView(creator: creator, url: url)
                    } label: {
                        HStack(spacing: 4) {
                            Text("爱发电主页")
                                .font(Theme.Typography.caption)
                            Image(systemName: "arrow.up.right.square")
                        }
                        .foregroundColor(Theme.Colors.accent)
                    }
                }
            }
        }
        .onAppear { viewModel.load() }
    }

    // MARK: - 创作者头部

    private var creatorHeader: some View {
        HStack(spacing: Theme.Spacing.md) {
            // 头像（统一使用 CachedImage 以享受内存缓存）
            CachedImage(urlString: creator.avatarUrl) {
                Circle().fill(Theme.Colors.cardBackground)
                    .overlay(Image(systemName: "person.fill").foregroundColor(Theme.Colors.textSecondary))
            }
            .frame(width: 64, height: 64)
            .clipShape(Circle())

            // 信息
            VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                Text(creator.name)
                    .font(Theme.Typography.title)
                    .foregroundColor(Theme.Colors.textPrimary)

                if let doing = creator.doing {
                    Text(doing)
                        .font(Theme.Typography.caption)
                        .foregroundColor(Theme.Colors.textSecondary)
                }

                Text("\(viewModel.albums.count) 个专辑 · \(viewModel.totalAudioCount) 期音频")
                    .font(Theme.Typography.caption)
                    .foregroundColor(Theme.Colors.textSecondary)
            }

            Spacer()
        }
        .padding(Theme.Spacing.md)
    }
}

// MARK: - 专辑行

private struct AlbumRowView: View {
    let album: Album

    var body: some View {
        HStack(spacing: Theme.Spacing.md) {
            CachedImage(urlString: album.coverUrl) {
                RoundedRectangle(cornerRadius: Theme.CornerRadius.cover)
                    .fill(Theme.Colors.cardBackground)
            }
            .frame(width: 60, height: 60)
            .clipShape(RoundedRectangle(cornerRadius: Theme.CornerRadius.cover))

            VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                Text(album.title)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textPrimary)
                    .lineLimit(2)

                HStack(spacing: Theme.Spacing.xs) {
                    Text("\(album.audioCount) 期")
                    if album.totalDuration > 0 {
                        Text("·")
                        Text(album.totalDuration.humanReadable)
                    }
                }
                .font(Theme.Typography.caption)
                .foregroundColor(Theme.Colors.textSecondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 12))
                .foregroundColor(Theme.Colors.textSecondary)
        }
        .padding(Theme.Spacing.md)
        .cardStyle()
    }
}

// MARK: - ViewModel

@Observable
final class CreatorViewModel {
    let creator: Creator
    private let db = DatabaseService.shared

    var albums: [Album] = []

    var totalAudioCount: Int {
        albums.reduce(0) { $0 + $1.audioCount }
    }

    init(creator: Creator) {
        self.creator = creator
    }

    func load() {
        albums = db.albums(for: creator.id)
    }
}
