import SwiftUI

struct AllCreatorsView: View {
    let creators: [Creator]
    private let db = DatabaseService.shared

    var body: some View {
        ZStack {
            Theme.Colors.background.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: Theme.Spacing.sm) {
                    ForEach(creators) { creator in
                        NavigationLink {
                            CreatorView(creator: creator)
                        } label: {
                            creatorRow(creator)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.vertical, Theme.Spacing.md)
            }
        }
        .navigationTitle("全部创作者")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func creatorRow(_ creator: Creator) -> some View {
        let albumCount = db.albums(for: creator.id).count

        return HStack(spacing: Theme.Spacing.md) {
            CachedImage(urlString: creator.avatarUrl) {
                Circle().fill(Theme.Colors.cardBackground)
                    .overlay(Image(systemName: "person.fill").foregroundColor(Theme.Colors.textSecondary))
            }
            .frame(width: 52, height: 52)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: Theme.Spacing.xs) {
                Text(creator.name)
                    .font(Theme.Typography.subheadline)
                    .foregroundColor(Theme.Colors.textPrimary)

                if let doing = creator.doing, !doing.isEmpty {
                    Text(doing)
                        .font(Theme.Typography.caption)
                        .foregroundColor(Theme.Colors.textSecondary)
                        .lineLimit(1)
                } else {
                    Text("\(albumCount) 个专辑")
                        .font(Theme.Typography.caption)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
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
