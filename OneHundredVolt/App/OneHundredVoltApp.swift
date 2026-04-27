import SwiftUI

@main
struct OneHundredVoltApp: App {
    // Keychain 有 token 即视为已登录，UserDefaults 只是缓存
    @State private var hasCompletedOnboarding: Bool = {
        if KeychainService.load(forKey: KeychainService.authTokenKey) != nil {
            UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")
            return true
        }
        return UserDefaults.standard.bool(forKey: "hasCompletedOnboarding")
    }()

    init() {
        // 预热单例服务
        _ = AudioPlayerService.shared
        _ = NowPlayingService.shared
        // 检测上次同步是否被中断，若是则清除不完整数据
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
            .foregroundColor: UIColor.white
        ]
        navAppearance.largeTitleTextAttributes = [
            .foregroundColor: UIColor.white
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

    var body: some View {
        Group {
            if hasCompletedOnboarding {
                HomeView()
                    .transition(.opacity)
            } else {
                WelcomeView(hasCompletedOnboarding: $hasCompletedOnboarding)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: hasCompletedOnboarding)
        .preferredColorScheme(.dark)
    }
}
