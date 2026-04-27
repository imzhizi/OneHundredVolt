import SwiftUI

struct WelcomeView: View {
    @Binding var hasCompletedOnboarding: Bool
    @State private var showLogin = false

    var body: some View {
        ZStack {
            Theme.Colors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // MARK: 品牌区
                VStack(spacing: Theme.Spacing.lg) {
                    // ⚡ 闪电图标
                    ZStack {
                        Circle()
                            .fill(Theme.Colors.accent.opacity(0.15))
                            .frame(width: 100, height: 100)
                        Text("⚡")
                            .font(.system(size: 52))
                    }

                    VStack(spacing: Theme.Spacing.sm) {
                        Text("一百伏特")
                            .font(Theme.Typography.largeTitle)
                            .foregroundColor(Theme.Colors.textPrimary)

                        Text("专注播客的第三方爱发电 APP")
                            .font(Theme.Typography.body)
                            .foregroundColor(Theme.Colors.textSecondary)
                    }
                }

                Spacer()

                // MARK: 登录按钮区
                VStack(spacing: Theme.Spacing.md) {
                    Button {
                        showLogin = true
                    } label: {
                        Text("登录爱发电账户")
                            .primaryButtonStyle()
                    }

                    Text("登录即表示同意 爱发电服务条款")
                        .font(Theme.Typography.caption)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
                .padding(.horizontal, Theme.Spacing.xl)
                .padding(.bottom, 48)
            }
        }
        .onAppear {
            // 提前预热登录页 WebView，消除点击按钮后的白屏延迟
            LoginWebPreloader.shared.preload()
        }
        .fullScreenCover(isPresented: $showLogin) {
            LoginWebView(hasCompletedOnboarding: $hasCompletedOnboarding)
        }
    }
}
