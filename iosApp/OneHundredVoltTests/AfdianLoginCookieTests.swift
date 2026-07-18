import Foundation
import Testing
@testable import OneHundredVolt

@Suite("AfdianLoginCookie")
struct AfdianLoginCookieTests {

    @Test("prefers an Afdian auth token over an unrelated session cookie")
    func ignoresThirdPartySessionCookie() {
        let analyticsSession = makeCookie(name: "session", value: "analytics-session", domain: "analytics.example.com")
        let afdianToken = makeCookie(name: "auth_token", value: "afdian-token", domain: ".afdian.com")

        #expect(AfdianLoginCookie.authToken(from: [analyticsSession, afdianToken]) == "afdian-token")
    }

    @Test("does not use a credential-like cookie from another domain")
    func rejectsOtherDomainCookies() {
        let otherDomainToken = makeCookie(name: "auth_token", value: "other-token", domain: "notafdian.com")

        #expect(AfdianLoginCookie.authToken(from: [otherDomainToken]) == nil)
    }

    @Test("accepts an Afdian subdomain cookie when no auth_token is present")
    func acceptsAfdianSubdomainCookie() {
        let session = makeCookie(name: "session", value: "afdian-session", domain: "accounts.afdian.com")

        #expect(AfdianLoginCookie.authToken(from: [session]) == "afdian-session")
    }

    private func makeCookie(name: String, value: String, domain: String) -> HTTPCookie {
        HTTPCookie(properties: [
            .name: name,
            .value: value,
            .domain: domain,
            .path: "/",
            .secure: "TRUE",
        ])!
    }
}
