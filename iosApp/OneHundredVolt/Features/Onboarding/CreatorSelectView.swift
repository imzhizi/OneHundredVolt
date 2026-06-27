import SwiftUI
import Shared

/// 选择要同步的创作者
/// - syncCompleteAction：同步全部完成后执行（Onboarding 传入设置 hasCompletedOnboarding；设置页传入 dismiss）
struct CreatorSelectView: View {
    /// Onboarding 场景使用；设置页场景传 nil（直接用 syncCompleteAction）
    @Binding var hasCompletedOnboarding: Bool
    /// 同步完成后的自定义回调（nil 时默认走 Onboarding 完成逻辑）
    var syncCompleteAction: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = CreatorSelectViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Colors.background.ignoresSafeArea()

                Group {
                    if viewModel.isLoading {
                        loadingView
                    } else if let error = viewModel.errorMessage {
                        errorView(error)
                    } else {
                        creatorListView
                    }
                }
            }
            .navigationTitle("选择要同步的项目")
            .navigationBarTitleDisplayMode(.inline)
            .task { await viewModel.loadCreators() }
        }
        .fullScreenCover(isPresented: $viewModel.showSyncProgress) {
            SyncProgressView(
                selectedCreatorIds: viewModel.selectedIds,
                onComplete: {
                    if let action = syncCompleteAction {
                        // 设置页场景：直接执行回调（如 dismiss）
                        action()
                    } else {
                        // Onboarding 场景：先 dismiss 自身，再切换根视图
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")
                            hasCompletedOnboarding = true
                        }
                    }
                }
            )
        }
    }

    // MARK: - 加载中

    private var loadingView: some View {
        VStack(spacing: Theme.Spacing.md) {
            ProgressView().tint(Theme.Colors.accent).scaleEffect(1.5)
            Text("正在获取项目列表...")
                .font(Theme.Typography.body)
                .foregroundColor(Theme.Colors.textSecondary)
        }
    }

    // MARK: - 错误

    private func errorView(_ msg: String) -> some View {
        VStack(spacing: Theme.Spacing.lg) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 44))
                .foregroundColor(Theme.Colors.textSecondary)
            Text(msg)
                .font(Theme.Typography.body)
                .foregroundColor(Theme.Colors.textSecondary)
                .multilineTextAlignment(.center)
            Button("重试") {
                Task { await viewModel.loadCreators() }
            }
            .foregroundColor(Theme.Colors.accent)
        }
        .padding()
    }

    // MARK: - 创作者列表

    private var creatorListView: some View {
        VStack(spacing: 0) {
            // 提示文字
            Text("你正在支持的项目（共 \(viewModel.creators.count) 个）")
                .font(Theme.Typography.caption)
                .foregroundColor(Theme.Colors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.vertical, Theme.Spacing.sm)

            // 列表
            ScrollView {
                LazyVStack(spacing: Theme.Spacing.sm) {
                    ForEach(viewModel.creators) { creator in
                        CreatorSelectRow(
                            creator: creator,
                            isSelected: viewModel.selectedIds.contains(creator.id)
                        ) {
                            viewModel.toggleSelection(creator.id)
                        }
                    }
                }
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.bottom, 120)
            }

            // 确认按钮（底部固定）
            VStack {
                Button {
                    viewModel.showSyncProgress = true
                } label: {
                    let count = viewModel.selectedIds.count
                    Text(count > 0 ? "确认同步（\(count) 个项目）" : "请至少选择一个项目")
                        .primaryButtonStyle()
                }
                .disabled(viewModel.selectedIds.isEmpty)
                .opacity(viewModel.selectedIds.isEmpty ? 0.5 : 1)
                .padding(.horizontal, Theme.Spacing.md)
                .padding(.vertical, Theme.Spacing.md)
            }
            .background(Theme.Colors.background)
        }
    }
}

// MARK: - 行视图

private struct CreatorSelectRow: View {
    let creator: Creator
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Theme.Spacing.md) {
                // 头像
                AsyncImage(url: URL(string: creator.avatarUrl ?? "")) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Circle().fill(Theme.Colors.cardBackground)
                }
                .frame(width: 48, height: 48)
                .clipShape(Circle())

                // 名称 + 类型
                VStack(alignment: .leading, spacing: 4) {
                    Text(creator.name)
                        .font(Theme.Typography.subheadline)
                        .foregroundColor(Theme.Colors.textPrimary)
                    if let doing = creator.doing {
                        Text(doing)
                            .font(Theme.Typography.caption)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }
                Spacer()

                // 勾选状态
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundColor(isSelected ? Theme.Colors.accent : Theme.Colors.textSecondary)
            }
            .padding(Theme.Spacing.md)
            .cardStyle()
        }
        .buttonStyle(.plain)
    }
}

// MARK: - ViewModel

@Observable
final class CreatorSelectViewModel {
    var creators: [Creator] = []
    var selectedIds: Set<String> = []
    var isLoading = false
    var errorMessage: String?
    var showSyncProgress = false

    func loadCreators() async {
        await MainActor.run { isLoading = true; errorMessage = nil }
        do {
            let list = try await AfdianAPIService.shared.fetchSponsoringCreators()
            await MainActor.run {
                self.creators = list
                // 默认全选
                self.selectedIds = Set(list.map { $0.id })
                self.isLoading = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    func toggleSelection(_ id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
    }
}
