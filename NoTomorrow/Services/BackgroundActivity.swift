import UIKit

/// Keeps a short network job alive when the user switches apps while it runs (an AI estimate takes 5–60 s). iOS grants
/// about 30 s of background time; without it the request dies on suspension and the result, plus a unit of the daily
/// AI quota, is lost. `end()` is idempotent and also runs when the time is up.
@MainActor
final class BackgroundActivity {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
