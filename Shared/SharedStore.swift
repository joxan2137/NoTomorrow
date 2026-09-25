import Foundation

/// The App Group the app and the widget extension share (`docs/widgets.md`, "Data flow"). Everything the widgets
/// show goes through here: the `WidgetSnapshot` file, the quick-log queue and the rest timer's state.
///
/// Without a usable group (a build signed without the entitlement) `container` and `defaults` are nil: the app keeps
/// working on `UserDefaults.standard` and the widgets show `widget.setup`.
enum SharedStore {
    static let defaultGroupID = "group.app.notomorrow.ios"

    /// A sideloading tool that re-signs the app under another team (AltStore, SideStore) renames its App Groups and
    /// lists the real names under `ALTAppGroups` in the app's Info.plist; the extension reads its host app's.
    static let groupID: String = {
        for bundle in [Bundle.main, hostAppBundle].compactMap({ $0 }) {
            if let groups = bundle.object(forInfoDictionaryKey: "ALTAppGroups") as? [String], !groups.isEmpty {
                return groups.first(where: { $0.hasPrefix(defaultGroupID) }) ?? groups[0]
            }
        }
        return defaultGroupID
    }()

    /// The containing app when running in the extension (`…/NoTomorrow.app/PlugIns/X.appex`), else nil.
    private static var hostAppBundle: Bundle? {
        let url = Bundle.main.bundleURL
        guard url.pathExtension == "appex" else { return nil }
        return Bundle(url: url.deletingLastPathComponent().deletingLastPathComponent())
    }

    static let container: URL? = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID)

    /// Group defaults, only when the group really exists: `UserDefaults(suiteName:)` also succeeds for a group the
    /// process is not entitled to, and then silently stores into a private plist nobody else reads.
    static let defaults: UserDefaults? = container == nil ? nil : UserDefaults(suiteName: groupID)

    static var isAvailable: Bool { defaults != nil }

    static func fileURL(_ name: String) -> URL? {
        container?.appendingPathComponent(name, isDirectory: false)
    }
}

/// Widget kinds, shared so the app can reload exactly the timelines it changed.
enum WidgetKind {
    static let fuel = "nt.widget.fuel"
    static let history = "nt.widget.history"
    static let week = "nt.widget.week"
    static let rest = "nt.widget.rest"

    /// Everything that reads the snapshot.
    static let snapshotKinds = [fuel, history, week, rest]
}
