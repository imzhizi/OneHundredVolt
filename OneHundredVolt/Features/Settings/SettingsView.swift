import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showLogoutAlert = false
    @State private var showClearDataAlert = false
    @State private var showResyncFlow = false
    @State private var showLoginFlow = false

    private let api = AfdianAPIService.shared
    private let sync = SyncService.shared
    private let db = DatabaseService.shared
    private let progressStore = PlaybackProgressStore.shared
    private let player = AudioPlayerService.shared

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Colors.background.ignoresSafeArea()

                List {
                    // MARK: 账户
                    Section {
                        HStack {
                            Label("登录状态", systemImage: "person.circle.fill")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text(api.isLoggedIn ? "已登录" : "未登录")
                                .foregroundColor(api.isLoggedIn ? Theme.Colors.success : Theme.Colors.warning)
                                .font(Theme.Typography.caption)
                        }

                        if api.isLoggedIn {
                            Button(role: .destructive) {
                                showLogoutAlert = true
                            } label: {
                                Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                                    .foregroundColor(Theme.Colors.warning)
                            }
                        } else {
                            Button {
                                showLoginFlow = true
                            } label: {
                                Label("重新登录", systemImage: "arrow.right.circle.fill")
                                    .foregroundColor(Theme.Colors.accent)
                            }
                        }
                    } header: {
                        sectionHeader("账户")
                    }
                    .listRowBackground(Theme.Colors.cardBackground)

                    // MARK: 同步
                    Section {
                        HStack {
                            Label("上次同步", systemImage: "arrow.clockwise")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text(sync.lastSyncDate?.displayString ?? "从未同步")
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }

                        // 立即同步：先弹出创作者选择，确认后开始同步
                        Button {
                            showResyncFlow = true
                        } label: {
                            HStack {
                                Label("立即同步", systemImage: "icloud.and.arrow.down")
                                    .foregroundColor(Theme.Colors.textPrimary)
                                Spacer()
                            }
                        }
                        .disabled(sync.isSyncing)
                    } header: {
                        sectionHeader("同步")
                    }
                    .listRowBackground(Theme.Colors.cardBackground)

                    // MARK: 数据
                    Section {
                        HStack {
                            Label("创作者", systemImage: "person.2.fill")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text("\(db.creators.count) 个")
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }
                        HStack {
                            Label("专辑", systemImage: "square.stack.fill")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text("\(db.albums.count) 个")
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }
                        HStack {
                            Label("音频", systemImage: "headphones")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text("\(db.audioItems.count) 条")
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }
                        Button(role: .destructive) {
                            showClearDataAlert = true
                        } label: {
                            Label("清除所有数据", systemImage: "trash.fill")
                                .foregroundColor(Theme.Colors.warning)
                        }
                    } header: {
                        sectionHeader("本地数据")
                    }
                    .listRowBackground(Theme.Colors.cardBackground)

                    // MARK: 关于
                    Section {
                        HStack {
                            Label("版本", systemImage: "info.circle.fill")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text(appVersion)
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }
                    } header: {
                        sectionHeader("关于")
                    }
                    .listRowBackground(Theme.Colors.cardBackground)
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                        .foregroundColor(Theme.Colors.accent)
                }
            }
            // 退出登录确认
            .alert("退出登录", isPresented: $showLogoutAlert) {
                Button("取消", role: .cancel) {}
                Button("退出", role: .destructive) { logout() }
            } message: {
                Text("退出后需要重新登录爱发电账户")
            }
            // 清除数据确认
            .alert("清除所有数据", isPresented: $showClearDataAlert) {
                Button("取消", role: .cancel) {}
                Button("清除", role: .destructive) { clearData() }
            } message: {
                Text("将删除所有本地缓存的创作者、专辑和音频数据，此操作不可撤销")
            }
            // 重新同步流程：先选择创作者，确认后同步
            .fullScreenCover(isPresented: $showResyncFlow) {
                CreatorSelectView(
                    hasCompletedOnboarding: .constant(true),
                    syncCompleteAction: {
                        showResyncFlow = false
                    }
                )
            }
            // 重新登录流程
            .fullScreenCover(isPresented: $showLoginFlow) {
                LoginWebView(hasCompletedOnboarding: .constant(false))
            }
        }
    }

    // MARK: - 操作

    private func logout() {
        // clearAll() 会停播、清空播放列表、让 MiniPlayer 消失
        player.clearAll()
        api.logout()
        db.clearAll()
        progressStore.clearAll()
        UserDefaults.standard.removeObject(forKey: "hasCompletedOnboarding")
        // 通知 App 根视图回到 Onboarding
        NotificationCenter.default.post(name: .didLogout, object: nil)
        dismiss()
    }

    private func clearData() {
        // clearAll() 会停播、清空播放列表、让 MiniPlayer 消失
        player.clearAll()
        db.clearAll()
        progressStore.clearAll()
        NotificationCenter.default.post(name: .didClearData, object: nil)
    }

    // MARK: - 辅助

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(Theme.Typography.caption)
            .foregroundColor(Theme.Colors.textSecondary)
            .textCase(nil)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
    }
}

// MARK: - 通知名

extension Notification.Name {
    static let didLogout    = Notification.Name("OneHundredVolt.didLogout")
    static let didClearData = Notification.Name("OneHundredVolt.didClearData")
    static let tokenExpired = Notification.Name("OneHundredVolt.tokenExpired")
}
