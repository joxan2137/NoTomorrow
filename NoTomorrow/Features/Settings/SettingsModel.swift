import SwiftUI
import SwiftData
import Observation
import UserNotifications

/// Everything Settings needs that is not a SwiftData object: Keychain-backed auth, HealthKit, the bro service,
/// notification permission, the rest-timer auto-start flag, language override, export and account deletion.
@Observable
@MainActor
final class SettingsModel {
    let auth = AuthStore.shared
    let health = HealthKitService()
    let bro = BroShared.service

    static let restAutoStartKey = "nt.rest.autoStart"

    var restAutoStart: Bool {
        didSet { UserDefaults.standard.set(restAutoStart, forKey: Self.restAutoStartKey) }
    }

    var aiProvider: AIProvider {
        get { AppConfig.shared.aiProvider }
        set { AppConfig.shared.aiProvider = newValue }
    }

    // Notifications
    var notificationStatus: UNAuthorizationStatus = .notDetermined

    // Account flows
    var isDeleting = false
    /// Set once the account is deleted: the Settings sheet is on its way out and must not re-create a profile or
    /// schedule for its last renders (`SettingsView.profile`).
    private(set) var didDeleteAccount = false
    var isUnpairing = false
    var isSigningOut = false

    /// "Use demo data (offline)": swaps the backend for the in-memory mock and forgets account-bound bro state.
    /// Queued attendance stays with the backend it was made on (`AttendanceOutbox.Target`).
    var useDemoData: Bool {
        get { AppConfig.shared.useMockBackend }
        set {
            guard newValue != AppConfig.shared.useMockBackend else { return }
            AppConfig.shared.useMockBackend = newValue
            bro.resetSession()
        }
    }

    init() {
        let defaults = UserDefaults.standard
        restAutoStart = defaults.object(forKey: Self.restAutoStartKey) == nil ? true : defaults.bool(forKey: Self.restAutoStartKey)
    }

    // MARK: Singletons

    /// Fetch-or-create so every editor has something to bind to.
    static func profile(in context: ModelContext) -> UserProfile {
        if let existing = try? context.fetch(FetchDescriptor<UserProfile>()).first { return existing }
        let profile = UserProfile(name: String(localized: "bro.you"))
        context.insert(profile)
        return profile
    }

    static func schedule(in context: ModelContext) -> GymSchedule {
        if let existing = try? context.fetch(FetchDescriptor<GymSchedule>()).first { return existing }
        let schedule = GymSchedule()
        context.insert(schedule)
        return schedule
    }

    // MARK: Language

    /// nil = follow the system. Writes the override for our own catalog lookup and `AppleLanguages` so iOS
    /// swaps the bundle on the next launch.
    func setLanguage(_ code: String?, appState: AppState) {
        appState.languageOverride = code
        let defaults = UserDefaults.standard
        if let code {
            defaults.set([code], forKey: "AppleLanguages")
        } else {
            defaults.removeObject(forKey: "AppleLanguages")
        }
    }

    // MARK: Notifications

    func refreshNotificationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationStatus = settings.authorizationStatus
    }

    func requestNotificationPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await refreshNotificationStatus()
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    // MARK: Bro

    var partnerName: String? { bro.partner?.name }
    var pairedAt: Date? { bro.partner?.pairedAt }

    /// Pulls who we are paired with (and our code) so the Gym bro group is current when the sheet opens.
    func loadBro(in context: ModelContext) async {
        await bro.refresh(in: context)
        if bro.myCode == nil { _ = await bro.createCode() }
    }

    func unpair(in context: ModelContext) async {
        isUnpairing = true
        await bro.unpair(in: context)
        isUnpairing = false
    }

    // MARK: Account

    /// Server-side revoke (best effort), Keychain cleared, local data kept.
    func signOut() async {
        isSigningOut = true
        await SignInModel.signOut()
        isSigningOut = false
    }

    /// Username, or the display name for accounts without one (Apple/Google).
    var accountName: String {
        if let me = bro.me {
            return me.username.isEmpty ? me.displayName : me.username
        }
        return String(localized: "settings.signedIn")
    }

    /// Backend first (best effort), then the session, the Keychain and the settings, then back to onboarding, and
    /// every user-owned SwiftData object goes once the screens showing them are gone (`LocalDataWipe`).
    /// The workout in progress is let go first (session, pending discards, rest timer, Live Activity), so nothing
    /// keeps pointing at a row the wipe removes and a relaunch has no workout to adopt.
    func deleteAccount(in context: ModelContext, appState: AppState,
                       session: WorkoutSessionController, restTimer: RestTimerController) async {
        isDeleting = true
        defer { isDeleting = false }
        // Read before the session goes: the queued attendance of this account goes with it.
        let account = bro.syncTarget
        try? await AppConfig.shared.makeBackendClient().deleteAccount()

        restTimer.reset()
        session.forgetAll()
        LocalDataWipe.markPending()
        didDeleteAccount = true

        auth.clear()
        auth.removeAnthropicKey()
        bro.resetSession()
        // Queued attendance of the account that is gone; another account's waits for it (`AttendanceOutbox.Target`).
        // Signed out (no account), everything goes, with the rest of the local data.
        bro.attendanceOutbox.clear(target: account)
        UserDefaults.standard.removeObject(forKey: Self.restAutoStartKey)
        // The next account starts its missed-day sweep from scratch, as on a fresh install.
        let sweep = AttendanceService.SweepCursor()
        sweep.sweptThrough = nil
        appState.hasOnboarded = false
    }
}

// MARK: - Display helpers

extension SettingsModel {
    static func gymDaysValue(_ schedule: GymSchedule) -> String {
        let days = schedule.weekdays.sorted().map { String(localized: String.LocalizationValue(Self.shortKey(isoWeekday: $0))) }
        var parts = days
        parts.append(Fmt.time(minuteOfDay: schedule.defaultMinuteOfDay))
        return parts.joined(separator: " · ")
    }

    static func shortKey(isoWeekday: Int) -> String {
        let names = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
        let index = min(max(isoWeekday - 1, 0), 6)
        return "weekday.\(names[index]).short"
    }

    static func letterKey(isoWeekday: Int) -> String {
        let names = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
        let index = min(max(isoWeekday - 1, 0), 6)
        return "weekday.\(names[index])"
    }

    func restTimerValue(_ profile: UserProfile) -> String {
        let clock = Fmt.clock(TimeInterval(profile.defaultRestSeconds))
        return restAutoStart ? "\(clock) · \(String(localized: "settings.autoStart"))" : clock
    }

    static func targetValue(_ profile: UserProfile) -> String {
        String(format: String(localized: "settings.dailyTarget.value %@ %@"),
               Fmt.kcal(Double(profile.calorieGoal)), Fmt.grams(Double(profile.proteinGoalG)))
    }

    static func languageValue(_ override: String?) -> String {
        switch override {
        case "en": String(localized: "settings.english")
        case "pl": String(localized: "settings.polish")
        default: String(localized: "settings.system")
        }
    }

    func notificationsValue(_ schedule: GymSchedule) -> String {
        let count = (schedule.remindHourBefore ? 1 : 0) + (schedule.askIfSkippedAt21 ? 1 : 0)
        return String(format: String(localized: "settings.notifications.on %lld"), count)
    }

    var healthValue: String {
        if !health.isAvailable { return String(localized: "settings.health.unavailable") }
        return String(localized: health.isAuthorized ? "settings.connected" : "settings.notConnected")
    }

    var aiValue: String {
        switch aiProvider {
        case .standard: String(localized: "settings.ai.standardShort")
        case .claudeBYOK: String(localized: "settings.ai.claudeShort")
        case .geminiBYOK: String(localized: "settings.ai.geminiShort")
        }
    }

    var notificationStatusValue: String {
        switch notificationStatus {
        case .authorized, .provisional, .ephemeral: String(localized: "settings.notifications.allowed")
        case .denied: String(localized: "settings.notifications.denied")
        default: String(localized: "settings.notifications.notAsked")
        }
    }
}
