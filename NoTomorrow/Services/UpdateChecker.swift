import Foundation
import Observation

/// A release version such as `v1.2.3`, `0.4` or `1.0.0-beta.2`, compared by semantic-versioning precedence: numeric
/// parts left to right (missing parts count as 0, so `1.2` == `1.2.0`), then a pre-release sorts before its release.
/// Build metadata (`+…`) is ignored.
struct ReleaseVersion: Comparable, CustomStringConvertible {
    let numbers: [Int]
    let preRelease: [String]

    init?(_ string: String) {
        var text = string.trimmingCharacters(in: .whitespaces)
        if text.first == "v" || text.first == "V" { text.removeFirst() }
        if let plus = text.firstIndex(of: "+") { text = String(text[..<plus]) }
        var core = text
        var pre: [String] = []
        if let dash = text.firstIndex(of: "-") {
            core = String(text[..<dash])
            pre = text[text.index(after: dash)...].split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            guard !pre.isEmpty, !pre.contains(where: \.isEmpty) else { return nil }
        }
        let parts = core.split(separator: ".", omittingEmptySubsequences: false)
        let numbers = parts.compactMap { $0.allSatisfy(\.isNumber) ? Int($0) : nil }
        guard numbers.count == parts.count else { return nil }
        self.numbers = numbers
        self.preRelease = pre
    }

    var description: String {
        numbers.map(String.init).joined(separator: ".") + (preRelease.isEmpty ? "" : "-" + preRelease.joined(separator: "."))
    }

    static func == (lhs: ReleaseVersion, rhs: ReleaseVersion) -> Bool {
        !(lhs < rhs) && !(rhs < lhs)
    }

    static func < (lhs: ReleaseVersion, rhs: ReleaseVersion) -> Bool {
        for i in 0..<max(lhs.numbers.count, rhs.numbers.count) {
            let l = i < lhs.numbers.count ? lhs.numbers[i] : 0
            let r = i < rhs.numbers.count ? rhs.numbers[i] : 0
            if l != r { return l < r }
        }
        // Same numbers: a pre-release comes before the release itself.
        switch (lhs.preRelease.isEmpty, rhs.preRelease.isEmpty) {
        case (true, true), (true, false): return false
        case (false, true): return true
        case (false, false): break
        }
        for (l, r) in zip(lhs.preRelease, rhs.preRelease) where l != r {
            let (ln, rn) = (Int(l), Int(r))
            if let ln, let rn { return ln < rn }
            if ln != nil || rn != nil { return ln != nil }   // numeric identifiers sort before alphanumeric ones
            return l < r
        }
        return lhs.preRelease.count < rhs.preRelease.count
    }
}

/// Looks up the latest GitHub release once per launch and, when it is newer than this build, offers it in a banner
/// (`UpdateBanner`). The app is sideloaded, so this is the only way people hear of a new version.
/// Every failure (offline, timeout, GitHub's 60-an-hour anonymous rate limit, an unexpected body) is silent: the
/// banner simply does not show, and the next launch tries again.
@MainActor
@Observable
final class UpdateChecker {
    static let shared = UpdateChecker()

    nonisolated static let latestReleaseAPI = URL(string: "https://api.github.com/repos/joxan2137/NoTomorrow/releases/latest")!
    nonisolated static let releasesPage = URL(string: "https://github.com/joxan2137/NoTomorrow/releases")!

    /// The newer release to offer, e.g. `v0.5.0`, or nil when there is none (or it was dismissed).
    private(set) var availableTag: String?

    typealias Fetch = (URLRequest) async throws -> (Data, URLResponse)

    private let fetch: Fetch
    private let currentVersion: () -> String?
    private let defaults: UserDefaults
    private var hasChecked = false

    init(fetch: @escaping Fetch = { try await URLSession.shared.data(for: $0) },
         currentVersion: @escaping () -> String? = UpdateChecker.bundleVersion,
         defaults: UserDefaults = .standard) {
        self.fetch = fetch
        self.currentVersion = currentVersion
        self.defaults = defaults
    }

    /// This build's `CFBundleShortVersionString`. In Debug builds the `-NTSimulatedAppVersion 0.1.0` launch argument
    /// stands in for it, to see the banner without shipping an old build.
    nonisolated static func bundleVersion() -> String? {
        #if DEBUG
        if let simulated = UserDefaults.standard.string(forKey: "NTSimulatedAppVersion") { return simulated }
        #endif
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    /// Runs the check the first time it is called in this process; later calls do nothing.
    func checkOnce() async {
        guard !hasChecked else { return }
        hasChecked = true
        await check()
    }

    func check() async {
        guard let tag = await latestTag(),
              let latest = ReleaseVersion(tag),
              let current = currentVersion().flatMap(ReleaseVersion.init),
              current < latest,
              defaults.string(forKey: Keys.dismissedTag) != tag
        else { return }
        availableTag = tag
    }

    /// Hides the banner until a release newer than this one comes out.
    func dismiss() {
        guard let availableTag else { return }
        defaults.set(availableTag, forKey: Keys.dismissedTag)
        self.availableTag = nil
    }

    private func latestTag() async -> String? {
        var request = URLRequest(url: Self.latestReleaseAPI, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        guard let (data, response) = try? await fetch(request) else { return nil }
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let release = try? JSONDecoder().decode(Release.self, from: data),
              release.draft != true, release.prerelease != true
        else { return nil }
        return release.tag_name
    }

    private struct Release: Decodable {
        let tag_name: String
        let draft: Bool?
        let prerelease: Bool?
    }

    private enum Keys {
        static let dismissedTag = "update.dismissedTag"
    }
}
