import SwiftUI
import Observation

enum AppTab: Int, CaseIterable, Identifiable {
    case today, train, fuel, progress, bro
    var id: Int { rawValue }

    var titleKey: LocalizedStringKey {
        switch self {
        case .today: "tab.today"
        case .train: "tab.train"
        case .fuel: "tab.fuel"
        case .progress: "tab.progress"
        case .bro: "tab.bro"
        }
    }

    var symbol: String {
        switch self {
        case .today: "house"
        case .train: "dumbbell"
        case .fuel: "fork.knife"
        case .progress: "chart.line.uptrend.xyaxis"
        case .bro: "person.2"
        }
    }
}

/// App-wide, non-persisted-in-SwiftData state: onboarding flag, selected tab, presented sheets.
@Observable
final class AppState {
    var selectedTab: AppTab = .today
    var hasOnboarded: Bool {
        didSet { UserDefaults.standard.set(hasOnboarded, forKey: Keys.hasOnboarded) }
    }
    var languageOverride: String? {
        didSet { UserDefaults.standard.set(languageOverride, forKey: Keys.language) }
    }

    /// Route requests from deep links / notifications, consumed (and cleared) by `MainTabView`.
    var pendingRoute: Route?

    /// Bumped by the Dashboard's Fuel row; the Fuel tab jumps to today whenever it changes.
    var fuelTodayRequests = 0

    /// Opens the Fuel tab on today, whatever day it was left on.
    func openFuelToday() {
        fuelTodayRequests += 1
        selectedTab = .fuel
    }

    enum Route: Equatable {
        case restTimer
        case activeWorkout
        case bro
        case settings
    }

    init() {
        hasOnboarded = UserDefaults.standard.bool(forKey: Keys.hasOnboarded)
        languageOverride = UserDefaults.standard.string(forKey: Keys.language)
    }

    private enum Keys {
        static let hasOnboarded = "nt.hasOnboarded"
        static let language = "nt.language"
    }
}

extension AppState.Route {
    static let urlScheme = "notomorrow"

    /// `notomorrow://workout` → the workout in progress, `notomorrow://workout/rest` → it plus the rest sheet
    /// (Live Activity taps), `notomorrow://bro`, `notomorrow://settings`. Anything else is ignored.
    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.urlScheme else { return nil }
        let parts = ([url.host ?? ""] + url.pathComponents)
            .map { $0.lowercased() }
            .filter { !$0.isEmpty && $0 != "/" }
        switch parts {
        case ["workout"]: self = .activeWorkout
        case ["workout", "rest"]: self = .restTimer
        case ["bro"]: self = .bro
        case ["settings"]: self = .settings
        default: return nil
        }
    }
}
