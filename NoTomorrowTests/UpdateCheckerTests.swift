import XCTest
@testable import NoTomorrow

/// The launch-time update check: semantic version order, and a banner only for a newer, undismissed release, with
/// every failure (offline, rate limit, odd body) silent.
@MainActor
final class UpdateCheckerTests: XCTestCase {
    /// A fresh suite per test (XCTest makes one instance per test method), so a dismissal never leaks between tests.
    private let defaults = UserDefaults(suiteName: "UpdateCheckerTests.\(UUID().uuidString)")!

    // MARK: - Versions

    private func v(_ string: String) -> ReleaseVersion { ReleaseVersion(string)! }

    func testVersionOrder() {
        XCTAssertEqual(v("v0.4.1"), v("0.4.1"))
        XCTAssertEqual(v("1.2"), v("1.2.0"))
        XCTAssertEqual(v("1.0.0+build.5"), v("1.0.0"))
        XCTAssertLessThan(v("0.4.1"), v("0.5.0"))
        XCTAssertLessThan(v("0.9.0"), v("0.10.0"))   // numeric, not lexical
        XCTAssertFalse(v("0.4.1") < v("0.4.1"))
    }

    func testPreReleaseOrder() {
        XCTAssertLessThan(v("1.0.0-beta.2"), v("1.0.0"))
        XCTAssertLessThan(v("1.0.0-alpha"), v("1.0.0-alpha.1"))
        XCTAssertLessThan(v("1.0.0-alpha.1"), v("1.0.0-alpha.beta"))
        XCTAssertLessThan(v("1.0.0-beta.2"), v("1.0.0-beta.11"))
    }

    func testInvalidVersions() {
        for invalid in ["", "abc", "1..2", "1.0-", "1.x"] {
            XCTAssertNil(ReleaseVersion(invalid), invalid)
        }
    }

    // MARK: - Check

    private func checker(current: String, status: Int = 200, tag: String = "v0.5.0") -> UpdateChecker {
        let body = Data(#"{"tag_name":"\#(tag)","draft":false,"prerelease":false}"#.utf8)
        return UpdateChecker(fetch: { request in
            (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
        }, currentVersion: { current }, defaults: defaults)
    }

    func testNewerReleaseShowsUntilDismissed() async {
        let checker = checker(current: "0.4.1")
        await checker.checkOnce()
        XCTAssertEqual(checker.availableTag, "v0.5.0")
        checker.dismiss()
        XCTAssertNil(checker.availableTag)

        let nextLaunch = self.checker(current: "0.4.1")
        await nextLaunch.check()
        XCTAssertNil(nextLaunch.availableTag, "a dismissed release stays hidden")

        let newerRelease = self.checker(current: "0.4.1", tag: "v0.6.0")
        await newerRelease.check()
        XCTAssertEqual(newerRelease.availableTag, "v0.6.0")
    }

    func testSameOrOlderReleaseShowsNothing() async {
        for current in ["0.5.0", "0.5", "0.6.0"] {
            let checker = checker(current: current)
            await checker.check()
            XCTAssertNil(checker.availableTag, current)
        }
    }

    func testFailuresAreSilent() async {
        let rateLimited = checker(current: "0.1.0", status: 403)
        await rateLimited.check()
        XCTAssertNil(rateLimited.availableTag)

        let offline = UpdateChecker(fetch: { _ in throw URLError(.notConnectedToInternet) },
                                    currentVersion: { "0.1.0" }, defaults: defaults)
        await offline.check()
        XCTAssertNil(offline.availableTag)

        let url = UpdateChecker.latestReleaseAPI
        let garbage = UpdateChecker(fetch: { _ in
            (Data("<html>".utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }, currentVersion: { "0.1.0" }, defaults: defaults)
        await garbage.check()
        XCTAssertNil(garbage.availableTag)
    }
}
