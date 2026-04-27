import SwiftUI
import Foundation

// MARK: - TimeInterval 格式化

extension TimeInterval {
    /// mm:ss 或 h:mm:ss
    var formatted: String {
        let t = Int(max(0, self))
        let h = t / 3600, m = (t % 3600) / 60, s = t % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }

    /// "约 X 小时" / "X 分钟"
    var humanReadable: String {
        let mins = Int(self) / 60
        if mins >= 60 {
            let h = mins / 60, rem = mins % 60
            return rem > 0 ? "约 \(h) 小时 \(rem) 分钟" : "约 \(h) 小时"
        }
        return "\(mins) 分钟"
    }
}

// MARK: - Date 格式化

extension Date {
    var displayString: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        f.locale = Locale(identifier: "zh_CN")
        return f.string(from: self)
    }
}

// MARK: - Shimmer 骨架屏动画

private struct ShimmerModifier: ViewModifier {
    @State private var phase: CGFloat = -1.0

    func body(content: Content) -> some View {
        content
            .overlay(
                GeometryReader { geo in
                    let width = geo.size.width
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .white.opacity(0.18), location: 0.4),
                            .init(color: .white.opacity(0.28), location: 0.5),
                            .init(color: .white.opacity(0.18), location: 0.6),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: width * 2)
                    .offset(x: phase * width * 2)
                    .blendMode(.screen)
                }
                .clipped()
                .allowsHitTesting(false)
            )
            .onAppear {
                withAnimation(
                    .linear(duration: 1.2)
                    .repeatForever(autoreverses: false)
                ) {
                    phase = 1.0
                }
            }
    }
}

extension View {
    /// 骨架屏闪光效果
    func shimmer() -> some View {
        modifier(ShimmerModifier())
    }
}

// MARK: - View 修饰符

extension View {
    func cardStyle() -> some View {
        self.background(Theme.Colors.cardBackground)
            .cornerRadius(Theme.CornerRadius.card)
    }

    func primaryButtonStyle() -> some View {
        self.font(Theme.Typography.body.weight(.semibold))
            .foregroundColor(.black)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(Theme.Colors.accent)
            .cornerRadius(Theme.CornerRadius.button)
    }

    func secondaryButtonStyle() -> some View {
        self.font(Theme.Typography.body)
            .foregroundColor(Theme.Colors.accent)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .overlay(
                RoundedRectangle(cornerRadius: Theme.CornerRadius.button)
                    .stroke(Theme.Colors.accent, lineWidth: 1.5)
            )
    }
}

// MARK: - String

extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

// MARK: - URL Identifiable（用于 .sheet(item:)）

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

// MARK: - 内嵌网页浏览器（WKWebView，支持注入 Cookie）

import WebKit

/// 使用 WKWebView 展示网页，并将 Keychain 中的 auth_token 注入到 WKHTTPCookieStore，
/// 从而让爱发电网页保持登录态。
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> WebViewController {
        return WebViewController(url: url)
    }

    func updateUIViewController(_ vc: WebViewController, context: Context) {}
}

final class WebViewController: UIViewController, WKNavigationDelegate {
    private let url: URL
    private var webView: WKWebView!

    init(url: URL) {
        self.url = url
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(Theme.Colors.background)

        let config = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        // 注入 auth_token Cookie，再加载页面
        if let token = KeychainService.load(forKey: KeychainService.authTokenKey) {
            var props: [HTTPCookiePropertyKey: Any] = [
                .name:    "auth_token",
                .value:   token,
                .domain:  "afdian.com",
                .path:    "/",
                .secure:  "TRUE",
                .sameSitePolicy: "None",
            ]
            // 设置较长过期时间避免被清除
            props[.expires] = Date(timeIntervalSinceNow: 60 * 60 * 24 * 30)

            if let cookie = HTTPCookie(properties: props) {
                webView.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { [weak self] in
                    guard let self else { return }
                    self.loadPage()
                }
                return
            }
        }
        loadPage()
    }

    private func loadPage() {
        var request = URLRequest(url: url)
        request.setValue(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
            forHTTPHeaderField: "User-Agent"
        )
        webView.load(request)
    }
}
