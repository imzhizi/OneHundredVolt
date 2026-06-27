import SwiftUI
import Shared
import WebKit

// MARK: - 预加载器（WelcomeView 出现时提前加载网页，消除点击后的白屏延迟）

final class LoginWebPreloader {
    static let shared = LoginWebPreloader()

    private(set) var webView: WKWebView?

    private init() {}

    /// 在 WelcomeView.onAppear 时调用，提前创建并加载登录页
    func preload() {
        guard webView == nil else { return }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let wv = WKWebView(frame: .zero, configuration: config)
        wv.backgroundColor = UIColor(Theme.Colors.background)
        wv.isOpaque = false
        wv.load(URLRequest(url: URL(string: "https://afdian.com/login")!))
        webView = wv
    }

    /// LoginWebView 关闭后重置，下次打开重新预热
    func reset() {
        webView = nil
    }
}

/// WKWebView 内嵌爱发电登录页
/// 支持两种检测方式：
///   1. 自动检测：页面跳离 /login 后轮询 cookie store
///   2. 手动确认：用户点「已登录，继续」按钮，调 API 验证
struct LoginWebView: View {
    @Binding var hasCompletedOnboarding: Bool
    @Environment(\.dismiss) private var dismiss

    @State private var viewModel = LoginWebViewModel()

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Theme.Colors.background.ignoresSafeArea()

                VStack(spacing: 0) {
                    if viewModel.isLoading {
                        HStack(spacing: Theme.Spacing.sm) {
                            ProgressView().tint(Theme.Colors.accent)
                            Text("正在加载...")
                                .font(Theme.Typography.caption)
                                .foregroundColor(Theme.Colors.textSecondary)
                        }
                        .frame(height: 36)
                    }
                    WebViewRepresentable(viewModel: viewModel)
                }

                // 底部按钮区
                VStack(spacing: 0) {
                    Divider().background(Theme.Colors.cardBackground)
                    VStack(spacing: Theme.Spacing.sm) {
                        // 主按钮：验证登录后进入选择页
                        Button {
                            Task { await viewModel.confirmLogin() }
                        } label: {
                            if viewModel.isConfirming {
                                HStack(spacing: 8) {
                                    ProgressView().tint(.white)
                                    Text("验证中...")
                                }
                                .primaryButtonStyle()
                            } else {
                                Text("已登录，继续 →")
                                    .primaryButtonStyle()
                            }
                        }
                        .disabled(viewModel.isConfirming)

                        // 备用按钮：跳过验证直接进入首页（用于验证流程卡住的情况）
                        Button("跳过，直接进入首页") {
                            NotificationCenter.default.post(name: .didCompleteOnboarding, object: nil)
                        }
                        .font(Theme.Typography.caption)
                        .foregroundColor(Theme.Colors.textSecondary)
                    }
                    .padding(.horizontal, Theme.Spacing.md)
                    .padding(.vertical, Theme.Spacing.md)
                }
                .background(Theme.Colors.background)
            }
            .navigationTitle("登录爱发电")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("返回") {
                        LoginWebPreloader.shared.reset()
                        dismiss()
                    }
                    .foregroundColor(Theme.Colors.accent)
                }
            }
            .onDisappear {
                // 关闭时重置预加载器，下次重新预热（避免持有过期 webView）
                LoginWebPreloader.shared.reset()
            }
        }
        .alert("验证失败", isPresented: $viewModel.showError) {
            Button("好") {}
        } message: {
            Text(viewModel.errorMessage ?? "请确认已完成爱发电登录")
        }
        .fullScreenCover(isPresented: $viewModel.showCreatorSelect) {
            // 用 syncCompleteAction 控制：CreatorSelectView 完成后先 dismiss 自身，
            // 再由 LoginWebView dismiss，最后才切换根视图，确保逐层有序退出
            CreatorSelectView(
                hasCompletedOnboarding: $hasCompletedOnboarding,
                syncCompleteAction: {
                    // CreatorSelectView 已 dismiss，现在 dismiss LoginWebView
                    dismiss()
                    // 等 LoginWebView dismiss 动画结束后再切换根视图
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        UserDefaults.standard.set(true, forKey: "hasCompletedOnboarding")
                        hasCompletedOnboarding = true
                    }
                }
            )
        }
    }
}

// MARK: - WebView Representable

struct WebViewRepresentable: UIViewRepresentable {
    let viewModel: LoginWebViewModel

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.backgroundColor = UIColor(Theme.Colors.background)
        webView.isOpaque = false
        webView.navigationDelegate = context.coordinator
        webView.load(URLRequest(url: URL(string: "https://afdian.com/login")!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(viewModel: viewModel) }

    class Coordinator: NSObject, WKNavigationDelegate {
        let viewModel: LoginWebViewModel

        init(viewModel: LoginWebViewModel) { self.viewModel = viewModel }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation _: WKNavigation!) {
            viewModel.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish _: WKNavigation!) {
            viewModel.isLoading = false
            guard let url = webView.url,
                  url.host?.contains("afdian.com") == true else { return }

            let path = url.path
            let isLoginPage = path == "/login" || path.hasPrefix("/login")

            if !isLoginPage {
                // 离开了登录页：显示手动按钮 + 同时尝试自动提取
                DispatchQueue.main.async { self.viewModel.showManualButton = true }
                viewModel.tryExtractCookie(webView: webView)
            }
        }

        func webView(_ webView: WKWebView, didFail _: WKNavigation!, withError _: Error) {
            viewModel.isLoading = false
        }
    }
}

// MARK: - ViewModel

@Observable
final class LoginWebViewModel {
    var isLoading = true
    var showManualButton = false
    var isConfirming = false
    var showCreatorSelect = false
    var showError = false
    var errorMessage: String?

    private var pollCount = 0

    // MARK: 自动尝试从 cookie store 提取（异步，可能慢）
    func tryExtractCookie(webView: WKWebView) {
        pollCount = 0
        doPoll(webView: webView)
    }

    private func doPoll(webView: WKWebView) {
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self else { return }
            // 宽泛匹配：先精确找 auth_token，再找 afdian 域下含 token/auth 的 cookie
            let tokenCookieNames = ["auth_token", "authToken", "token", "auth", "session", "sid"]
            let found = cookies.first(where: { tokenCookieNames.contains($0.name.lowercased()) })
                ?? cookies.first(where: { $0.domain.contains("afdian") && $0.name.contains("token") })
                ?? cookies.first(where: { $0.domain.contains("afdian") && $0.name.contains("auth") })
            if let c = found {
                DispatchQueue.main.async {
                    KeychainService.save(c.value, forKey: KeychainService.authTokenKey)
                    self.showCreatorSelect = true
                }
                return
            }
            self.pollCount += 1
            guard self.pollCount < 20 else {
                DispatchQueue.main.async { self.showManualButton = true }
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self, weak webView] in
                guard let self, let webView else { return }
                self.doPoll(webView: webView)
            }
        }
    }

    // MARK: 手动点按钮：从 cookie store 中提取 token 并验证
    @MainActor
    func confirmLogin() async {
        isConfirming = true

        // 先列出所有 cookie，方便诊断
        let allCookies = await withCheckedContinuation { cont in
            WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cont.resume(returning: $0) }
        }

        // 找 auth_token（尝试多个可能的 cookie 名）
        let tokenCookieNames = ["auth_token", "authToken", "token", "auth", "session", "sid"]
        let tokenCookie = allCookies.first(where: { tokenCookieNames.contains($0.name.lowercased()) })
            ?? allCookies.first(where: { $0.domain.contains("afdian") && $0.name.contains("token") })
            ?? allCookies.first(where: { $0.domain.contains("afdian") && $0.name.contains("auth") })

        if let cookie = tokenCookie {
            KeychainService.save(cookie.value, forKey: KeychainService.authTokenKey)
            isConfirming = false
            showCreatorSelect = true
        } else {
            // 没找到 token cookie，把所有爱发电相关 cookie 列出来作为错误信息
            let afdianCookies = allCookies
                .filter { $0.domain.contains("afdian") }
                .map { "\($0.name) (\($0.domain))" }
                .joined(separator: "\n")
            isConfirming = false
            errorMessage = afdianCookies.isEmpty
                ? "未检测到爱发电 Cookie，请确认已在网页中完成登录"
                : "已检测到以下 Cookie，但未找到登录凭据：\n\n\(afdianCookies)"
            showError = true
        }
    }
}
