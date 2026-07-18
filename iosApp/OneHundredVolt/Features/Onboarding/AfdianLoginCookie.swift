import Foundation

enum AfdianLoginCookie {
    private static let tokenCookieNames = [
        "auth_token", "authtoken", "token", "auth", "session", "sid"
    ]

    static func authToken(from cookies: [HTTPCookie]) -> String? {
        let afdianCookies = cookies.filter { isAfdianDomain($0.domain) }

        for name in tokenCookieNames {
            if let cookie = afdianCookies.first(where: {
                $0.name.caseInsensitiveCompare(name) == .orderedSame && !$0.value.isEmpty
            }) {
                return cookie.value
            }
        }

        return nil
    }

    static func diagnosticCookieNames(from cookies: [HTTPCookie]) -> String {
        cookies
            .filter { isAfdianDomain($0.domain) }
            .map { "\($0.name) (\($0.domain))" }
            .joined(separator: "\n")
    }

    private static func isAfdianDomain(_ domain: String) -> Bool {
        let normalized = domain
            .lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return normalized == "afdian.com" || normalized.hasSuffix(".afdian.com")
    }
}
