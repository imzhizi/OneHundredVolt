import SwiftUI
import WebKit

/// 在 App 内打开创作者爱发电主页（带已有登录态 Cookie）
struct CreatorWebView: View {
    let creator: Creator
    let url: URL
    @State private var isLoading = true
    @State private var currentURL: String = ""

    var body: some View {
        ZStack(alignment: .top) {
            Theme.Colors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                // 加载指示
                if isLoading {
                    ProgressView()
                        .tint(Theme.Colors.accent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 2)
                        .background(Theme.Colors.accent.opacity(0.3))
                } else {
                    Color.clear.frame(height: 2)
                }

                CreatorWebViewRepresentable(
                    url: url,
                    isLoading: $isLoading
                )
            }
        }
        .navigationTitle(creator.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Representable

struct CreatorWebViewRepresentable: UIViewRepresentable {
    let url: URL
    @Binding var isLoading: Bool

    func makeUIView(context: Context) -> WKWebView {
        // 复用默认 WKWebsiteDataStore，自动携带已登录的 auth_token Cookie
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true

        // 注入 auth_token Cookie（确保 URLSession 手动请求也有效）
        if let token = KeychainService.load(forKey: KeychainService.authTokenKey) {
            let cookie = HTTPCookie(properties: [
                .name:    "auth_token",
                .value:   token,
                .domain:  "afdian.com",
                .path:    "/",
                .secure:  "TRUE"
            ])
            if let cookie {
                config.websiteDataStore.httpCookieStore.setCookie(cookie)
            }
        }

        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(isLoading: $isLoading) }

    class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool
        init(isLoading: Binding<Bool>) { _isLoading = isLoading }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            isLoading = false
        }
    }
}
