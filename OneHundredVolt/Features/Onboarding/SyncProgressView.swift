import SwiftUI

/// 同步进度页
/// - onComplete：同步成功后用户点击按钮时调用（Onboarding 传入设置 hasCompletedOnboarding 的闭包；设置页传入 dismiss 闭包）
struct SyncProgressView: View {
    let selectedCreatorIds: Set<String>
    /// 同步完成后执行（由调用方决定如何处理）
    let onComplete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var syncService = SyncService.shared

    var body: some View {
        ZStack {
            Theme.Colors.background.ignoresSafeArea()

            VStack(spacing: Theme.Spacing.xl) {
                Spacer()

                // 图标
                ZStack {
                    Circle()
                        .fill(Theme.Colors.accent.opacity(0.12))
                        .frame(width: 90, height: 90)
                    Text("⚡")
                        .font(.system(size: 44))
                }

                // 状态文字
                VStack(spacing: Theme.Spacing.sm) {
                    switch syncService.state {
                    case .idle, .syncing:
                        Text("正在同步...")
                            .font(Theme.Typography.title)
                            .foregroundColor(Theme.Colors.textPrimary)
                        if case .syncing(let msg, _) = syncService.state {
                            Text(msg)
                                .font(Theme.Typography.caption)
                                .foregroundColor(Theme.Colors.textSecondary)
                                .multilineTextAlignment(.center)
                                .animation(.easeInOut, value: msg)
                        }

                    case .success:
                        Text("同步完成 ✓")
                            .font(Theme.Typography.title)
                            .foregroundColor(Theme.Colors.success)

                    case .failed(let err):
                        Text("同步失败")
                            .font(Theme.Typography.title)
                            .foregroundColor(Theme.Colors.warning)
                        Text(err.localizedDescription)
                            .font(Theme.Typography.caption)
                            .foregroundColor(Theme.Colors.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                }

                // 进度条
                if case .syncing(_, let progress) = syncService.state {
                    ProgressView(value: progress)
                        .tint(Theme.Colors.accent)
                        .padding(.horizontal, Theme.Spacing.xl)
                }

                Spacer()

                // 底部按钮
                VStack(spacing: Theme.Spacing.sm) {
                    switch syncService.state {
                    case .success:
                        Button("进入应用 →") {
                            dismiss()
                            // 等本层 dismiss 动画结束后再执行上层回调
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                onComplete()
                            }
                        }
                        .primaryButtonStyle()
                        .padding(.horizontal, Theme.Spacing.xl)

                    case .failed:
                        Button("重试") {
                            Task { await syncService.fullSync(selectedCreatorIds: Array(selectedCreatorIds)) }
                        }
                        .primaryButtonStyle()
                        .padding(.horizontal, Theme.Spacing.xl)

                    case .idle, .syncing:
                        EmptyView()
                    }
                }
                .padding(.bottom, 48)
            }
        }
        .task {
            await syncService.fullSync(selectedCreatorIds: Array(selectedCreatorIds))
        }
    }
}
