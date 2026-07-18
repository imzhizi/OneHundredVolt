import SwiftUI

@main
struct OneHundredVoltApp: App {
    // 只有完整同步成功后才完成入门；登录令牌本身不能代表本地内容已准备好。
    @State private var hasCompletedOnboarding = UserDefaults.standard.bool(forKey: "hasCompletedOnboarding")

    init() {
        _ = AudioPlayerService.shared
        _ = NowPlayingService.shared
        SyncService.shared.recoverIfNeeded()
        configureAppearance()
    }

    var body: some Scene {
        WindowGroup {
            RootView(hasCompletedOnboarding: $hasCompletedOnboarding)
                .onReceive(NotificationCenter.default.publisher(for: .didLogout)) { _ in
                    hasCompletedOnboarding = false
                }
                .onReceive(NotificationCenter.default.publisher(for: .didCompleteOnboarding)) { _ in
                    UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")
                    hasCompletedOnboarding = true
                }
        }
    }

    private func configureAppearance() {
        // 全局导航栏外观
        let navAppearance = UINavigationBarAppearance()
        navAppearance.configureWithOpaqueBackground()
        navAppearance.backgroundColor = UIColor(Theme.Colors.background)
        navAppearance.titleTextAttributes = [
            .foregroundColor: UIColor(Theme.Colors.accent)
        ]
        navAppearance.largeTitleTextAttributes = [
            .foregroundColor: UIColor(Theme.Colors.accent)
        ]
        UINavigationBar.appearance().standardAppearance  = navAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navAppearance
        UINavigationBar.appearance().compactAppearance   = navAppearance
        UINavigationBar.appearance().tintColor = UIColor(Theme.Colors.accent)

        // 全局 List 背景
        UITableView.appearance().backgroundColor = .clear

        // 状态栏
        UINavigationBar.appearance().overrideUserInterfaceStyle = .dark
    }
}

// MARK: - Root View

struct RootView: View {
    @Binding var hasCompletedOnboarding: Bool
    @State private var showRelogin = false

    var body: some View {
        Group {
            if hasCompletedOnboarding {
                HomeView()
                    .transition(.opacity)
            } else if AfdianAPIService.shared.isLoggedIn {
                CreatorSelectView(hasCompletedOnboarding: $hasCompletedOnboarding)
                    .transition(.opacity)
            } else {
                WelcomeView(hasCompletedOnboarding: $hasCompletedOnboarding)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: hasCompletedOnboarding)
        .preferredColorScheme(.dark)
        .task {
            _ = await IncrementalUpdateCoordinator.shared.checkDue()
        }
        .sheet(isPresented: $showRelogin) {
            LoginWebView(hasCompletedOnboarding: $hasCompletedOnboarding)
        }
        .onReceive(NotificationCenter.default.publisher(for: .tokenExpired)) { _ in
            guard hasCompletedOnboarding, !showRelogin else { return }
            showRelogin = true
        }
    }
}
