import SwiftUI
import Shared

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showLogoutAlert = false
    @State private var showClearDataAlert = false
    @State private var showClearCacheAlert = false
    @State private var showResyncFlow = false
    @State private var showLoginFlow = false
    #if DEBUG
    @State private var showDebugPanel = false
    #endif
    @State private var cacheSize: Int64 = 0

    private let api = AfdianAPIService.shared
    private let sync = SyncService.shared
    private let db = DatabaseService.shared
    private let progressStore = PlaybackProgressStore.shared
    private let player = AudioPlayerService.shared
    private let audioCache = AudioCacheService.shared
    private let incremental = IncrementalUpdateCoordinator.shared

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

                    // MARK: 缓存
                    Section {
                        HStack {
                            Label("音频缓存", systemImage: "externaldrive.fill")
                                .foregroundColor(Theme.Colors.textPrimary)
                            Spacer()
                            Text(ByteCountFormatter.string(fromByteCount: cacheSize, countStyle: .file))
                                .foregroundColor(Theme.Colors.textSecondary)
                                .font(Theme.Typography.caption)
                        }
                        Button(role: .destructive) {
                            showClearCacheAlert = true
                        } label: {
                            Label("清空缓存", systemImage: "trash.fill")
                                .foregroundColor(Theme.Colors.warning)
                        }
                    } header: {
                        sectionHeader("缓存")
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

                    #if DEBUG
                    Section {
                        Button {
                            showDebugPanel = true
                        } label: {
                            Label("打开诊断面板", systemImage: "ladybug.fill")
                                .foregroundColor(Theme.Colors.accent)
                        }
                    } header: {
                        sectionHeader("调试诊断")
                    }
                    .listRowBackground(Theme.Colors.cardBackground)
                    #endif
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
                .onAppear { cacheSize = audioCache.totalCacheSize() }
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
            // 清空缓存确认
            .alert("清空缓存", isPresented: $showClearCacheAlert) {
                Button("取消", role: .cancel) {}
                Button("清空", role: .destructive) {
                    audioCache.clearCache()
                    cacheSize = 0
                }
            } message: {
                Text("将删除所有已缓存的音频文件，不影响已同步的专辑数据")
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
            #if DEBUG
            .sheet(isPresented: $showDebugPanel) {
                DebugDiagnosticsView(
                    onClearData: clearData,
                    onClearProgress: { progressStore.clearAll() },
                    onClearCache: {
                        audioCache.clearCache()
                        cacheSize = 0
                    },
                    onMarkAllDue: { incremental.markAllDue() },
                    onCheckAll: { await incremental.checkAll() },
                    onClearUpdateReminders: { db.markAllAlbumUpdatesRead() },
                    onDeleteAudio: { id in
                        guard let item = db.audioItems.first(where: { $0.id == id }) else {
                            return "未找到单集：\(id)"
                        }
                        audioCache.removeCache(for: item.id)
                        db.deleteAudioItem(id: item.id)
                        return "已删除本地单集：\(item.title.prefix(20))"
                    }
                )
            }
            #endif
        }
    }

    // MARK: - 操作

    private func logout() {
        player.clearAll()
        #if DEBUG
        Shared.DebugCatalogFixtures.shared.clearAll()
        #endif
        api.logout()
        db.clearAll()
        progressStore.clearAll()
        audioCache.clearCache()
        UserDefaults.standard.removeObject(forKey: "hasCompletedOnboarding")
        NotificationCenter.default.post(name: .didLogout, object: nil)
        dismiss()
    }

    private func clearData() {
        // clearAll() 会停播、清空播放列表、让 MiniPlayer 消失
        player.clearAll()
        #if DEBUG
        Shared.DebugCatalogFixtures.shared.clearAll()
        #endif
        db.clearAll()
        progressStore.clearAll()
        // v1.6 同步清空音频缓存（与 Android SettingsScreen clearCache 对齐）
        AudioCacheService.shared.clearCache()
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

#if DEBUG
private struct DebugDiagnosticsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var refreshToken = 0

    let onClearData: () -> Void
    let onClearProgress: () -> Void
    let onClearCache: () -> Void
    let onMarkAllDue: () -> Void
    let onCheckAll: () async -> String
    let onClearUpdateReminders: () -> Void
    let onDeleteAudio: (String) -> String
    @State private var deleteEpisodeId = ""
    @State private var fixtureAlbumId = ""
    @State private var fixtureJson = ""
    @State private var incrementalStatus: String?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("日志 \(Shared.DebugDiagnostics.shared.count()) 条")
                    .font(.caption)
                    .foregroundColor(Theme.Colors.textSecondary)
                ScrollView {
                    Text(Shared.DebugDiagnostics.shared.exportText(maxEntries: 120).isEmpty ? "暂无诊断日志" : Shared.DebugDiagnostics.shared.exportText(maxEntries: 120))
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(Theme.Colors.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .id(refreshToken)
                .frame(maxHeight: 360)
                .padding(8)
                .background(Theme.Colors.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: 8))

                HStack {
                    Button("清除日志") {
                        Shared.DebugDiagnostics.shared.clear()
                        refreshToken += 1
                    }
                    Button("刷新") { refreshToken += 1 }
                }
                .buttonStyle(.bordered)

                HStack {
                    Button("清除进度") { onClearProgress() }
                    Button("清除缓存") { onClearCache() }
                    Button("清除数据") { onClearData() }
                }
                .buttonStyle(.bordered)
                .foregroundColor(Theme.Colors.warning)

                HStack {
                    Button("标记待检查") {
                        onMarkAllDue()
                        incrementalStatus = "已将所有专辑标记为待检查"
                    }
                    Button("立即检查") {
                        incrementalStatus = "增量检查中..."
                        Task { incrementalStatus = await onCheckAll() }
                    }
                }
                .buttonStyle(.bordered)
                HStack {
                    Button("清除提醒") {
                        onClearUpdateReminders()
                        incrementalStatus = "已清除所有更新提醒"
                    }
                    Button("填首条 ID") {
                        deleteEpisodeId = DatabaseService.shared.audioItems.first?.id ?? ""
                        incrementalStatus = deleteEpisodeId.isEmpty ? "没有可填入的本地单集" : "已填入首条单集 ID"
                    }
                }
                .buttonStyle(.bordered)
                Text("目录 fixture")
                    .font(.caption)
                    .foregroundColor(Theme.Colors.textPrimary)
                TextField("专辑 ID", text: $fixtureAlbumId)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("填首条专辑") {
                        fixtureAlbumId = DatabaseService.shared.albums.first?.id ?? ""
                        incrementalStatus = fixtureAlbumId.isEmpty ? "没有可用专辑" : "已填入首条专辑 ID"
                    }
                    Button("应用 JSON") {
                        applyFixture()
                    }
                    .disabled(fixtureAlbumId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || fixtureJson.isEmpty)
                    Button("清除 fixture") {
                        clearFixture()
                    }
                }
                .buttonStyle(.bordered)
                TextEditor(text: $fixtureJson)
                    .font(.system(size: 10, design: .monospaced))
                    .frame(minHeight: 72, maxHeight: 120)
                    .padding(4)
                    .background(Theme.Colors.cardBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                HStack {
                    Button("新增") { applyFixtureTemplate("new") }
                    Button("变更") { applyFixtureTemplate("changed") }
                    Button("空目录") { applyFixtureTemplate("empty") }
                    Button("重复 ID") { applyFixtureTemplate("duplicate") }
                }
                .buttonStyle(.bordered)
                HStack {
                    Button("缺最后一条") { applyFixtureTemplate("omit_last") }
                    Button("模拟错误") { applyFixtureTemplate("error") }
                    Button("模拟超时") { applyFixtureTemplate("timeout") }
                }
                .buttonStyle(.bordered)
                TextField("单集 ID", text: $deleteEpisodeId)
                    .textFieldStyle(.roundedBorder)
                Button("删除指定单集") {
                    incrementalStatus = onDeleteAudio(deleteEpisodeId.trimmingCharacters(in: .whitespacesAndNewlines))
                    if incrementalStatus?.hasPrefix("已删除") == true {
                        deleteEpisodeId = ""
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(deleteEpisodeId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let incrementalStatus {
                    Text(incrementalStatus)
                        .font(.caption2)
                        .foregroundColor(Theme.Colors.textSecondary)
                }
                Spacer()
            }
            .padding()
            .background(Theme.Colors.background.ignoresSafeArea())
            .navigationTitle("调试诊断")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func applyFixtureTemplate(_ scenario: String) {
        let albumId = fixtureAlbumId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !albumId.isEmpty else {
            incrementalStatus = "请先填写专辑 ID"
            return
        }
        let items = DatabaseService.shared.audioItems(for: albumId)
        fixtureJson = Shared.DebugCatalogFixtures.shared.templateJson(
            albumId: albumId,
            scenario: scenario,
            existingItems: items
        )
        applyFixture()
    }

    private func applyFixture() {
        let albumId = fixtureAlbumId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !albumId.isEmpty else {
            incrementalStatus = "请先填写专辑 ID"
            return
        }
        incrementalStatus = Shared.DebugCatalogFixtures.shared.setJsonSafely(
            albumId: albumId,
            fixtureJson: fixtureJson
        )
        refreshToken += 1
    }

    private func clearFixture() {
        let albumId = fixtureAlbumId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !albumId.isEmpty else {
            incrementalStatus = "请先填写专辑 ID"
            return
        }
        Shared.DebugCatalogFixtures.shared.clearFixture(albumId: albumId)
        fixtureJson = ""
        incrementalStatus = "已清除该专辑 fixture"
        refreshToken += 1
    }
}
#endif

// MARK: - 通知名

extension Notification.Name {
    static let didLogout    = Notification.Name("OneHundredVolt.didLogout")
    static let didClearData = Notification.Name("OneHundredVolt.didClearData")
    static let tokenExpired = Notification.Name("OneHundredVolt.tokenExpired")
}
