import SwiftUI
import SwiftData

/// Editors reachable from the Settings root.
enum SettingsRoute: Hashable {
    case name, bodyWeight, dailyTarget
    case schedule, restTimer, units
    case language, notifications, health, export, importWorkouts, ai
    case partner
}

/// Settings sheet (from the Dashboard avatar): grouped rows per design/Settings.dc.html, each pushing a small editor.
struct SettingsView: View {
    @Environment(AppState.self) private var appState
    @Environment(WorkoutSessionController.self) private var session
    @Environment(RestTimerController.self) private var restTimer
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @Query private var profiles: [UserProfile]
    @Query private var schedules: [GymSchedule]
    @Query private var pairings: [BroPairing]

    @State private var model = SettingsModel()
    @State private var path: [SettingsRoute] = []
    @State private var showsSignOut = false
    @State private var showsSignIn = false
    @State private var showsDelete = false

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationTitle("settings.title")
                .navigationBarTitleDisplayMode(.large)
                .toolbarBackground(NT.Colors.ground, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button { dismiss() } label: {
                            Text("common.done").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                        }
                    }
                }
                .navigationDestination(for: SettingsRoute.self) { route in
                    destination(route)
                }
        }
        .tint(NT.Colors.ink)
        .preferredColorScheme(.dark)
        .task { await model.refreshNotificationStatus() }
        .task { await model.loadBro(in: modelContext) }
        .sheet(isPresented: $showsSignIn) {
            SignInView { Task { await model.loadBro(in: modelContext) } }
        }
        .confirmationDialog("settings.signOut.confirm", isPresented: $showsSignOut, titleVisibility: .visible) {
            Button("settings.signOut", role: .destructive) { Task { await model.signOut() } }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("settings.signOut.message")
        }
        .alert("settings.delete.confirm", isPresented: $showsDelete) {
            Button("common.delete", role: .destructive) {
                Task {
                    await model.deleteAccount(in: modelContext, appState: appState, session: session, restTimer: restTimer)
                    dismiss()
                }
            }
            Button("common.cancel", role: .cancel) {}
        } message: {
            Text("settings.delete.message")
        }
    }

    // MARK: Root

    /// Fetch-or-create, except once the account is deleted: the sheet's last renders get a detached placeholder,
    /// so the next onboarding does not find a stray profile or schedule next to its own.
    private var profile: UserProfile {
        profiles.first ?? (model.didDeleteAccount ? UserProfile(name: "") : SettingsModel.profile(in: modelContext))
    }
    private var schedule: GymSchedule {
        schedules.first ?? (model.didDeleteAccount ? GymSchedule() : SettingsModel.schedule(in: modelContext))
    }

    private var content: some View {
        ScrollView {
            VStack(spacing: 12) {
                youGroup
                trainingGroup
                appGroup
                broGroup
                accountGroup
                versionFooter
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .ntScreenBackground()
    }

    private var youGroup: some View {
        STGroup(title: "settings.you") {
            STLinkRow(label: "common.name", value: profile.name, destination: SettingsRoute.name)
            STLinkRow(label: "common.bodyWeight",
                      value: profile.bodyWeightKg.map { Fmt.weight($0, unit: profile.units) } ?? "—",
                      destination: SettingsRoute.bodyWeight)
            STLinkRow(label: "settings.dailyTarget", value: SettingsModel.targetValue(profile), destination: SettingsRoute.dailyTarget)
        }
    }

    private var trainingGroup: some View {
        STGroup(title: "settings.training") {
            STLinkRow(label: "settings.gymDays", value: SettingsModel.gymDaysValue(schedule), destination: SettingsRoute.schedule)
            STLinkRow(label: "settings.restTimer", value: model.restTimerValue(profile), destination: SettingsRoute.restTimer)
            STLinkRow(label: "settings.units", value: profile.units.rawValue, destination: SettingsRoute.units)
        }
    }

    private var appGroup: some View {
        STGroup(title: "settings.app") {
            STLinkRow(label: "settings.language", value: SettingsModel.languageValue(appState.languageOverride), destination: SettingsRoute.language)
            STLinkRow(label: "settings.notifications", value: model.notificationsValue(schedule), destination: SettingsRoute.notifications)
            STLinkRow(label: "settings.health", value: model.healthValue, destination: SettingsRoute.health,
                      dot: model.health.isAuthorized ? NT.Colors.good : nil)
            STLinkRow(label: "settings.export", value: "CSV", destination: SettingsRoute.export)
            STLinkRow(label: "import.title", value: "Strong · Hevy", destination: SettingsRoute.importWorkouts)
            STLinkRow(label: "settings.aiProvider", value: model.aiValue, destination: SettingsRoute.ai)
        }
    }

    private var broGroup: some View {
        STGroup(title: "settings.gymBro") {
            if let name = model.partnerName ?? pairings.first?.partnerName {
                let date = model.pairedAt ?? pairings.first?.pairedAt ?? .now
                STLinkRow(label: LocalizedStringKey(stringLiteral: name),
                          value: String(format: String(localized: "settings.paired"), Fmt.dayMonth(date)),
                          destination: SettingsRoute.partner)
            } else {
                STInfoRow(label: "settings.bro.none", value: String(localized: "settings.bro.pairInTab"))
            }
            SettingsCodeRow(code: model.bro.myCode ?? pairings.first?.myCode)
        }
    }

    private var accountGroup: some View {
        STGroup(title: "settings.account") {
            if model.auth.isSignedIn {
                STInfoRow(label: "settings.account.user", value: model.accountName, dot: NT.Colors.good)
                STActionRow(label: "settings.signOut", isBusy: model.isSigningOut) { showsSignOut = true }
            } else {
                STActionRow(label: "settings.signIn", value: String(localized: "settings.notSignedIn")) { showsSignIn = true }
                STInfoRow(label: "auth.google", value: "").opacity(0.4)
            }
            STActionRow(label: "settings.deleteAccount", color: NT.Colors.bad, isBusy: model.isDeleting) {
                showsDelete = true
            }
        }
        .stFootnote("settings.delete.footnote")
    }

    /// "Version 0.2.1 (57)": tells which build is installed.
    private var versionFooter: some View {
        Text("settings.version \(AppVersion.label())")
            .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink3).tabular()
            .frame(maxWidth: .infinity)
            .padding(.top, 12)
            .textSelection(.enabled)
    }

    // MARK: Destinations

    @ViewBuilder
    private func destination(_ route: SettingsRoute) -> some View {
        switch route {
        case .name: NameEditor(profile: profile)
        case .bodyWeight: BodyWeightEditor(profile: profile)
        case .dailyTarget: DailyTargetEditor(profile: profile)
        case .schedule: ScheduleEditor(schedule: schedule, bro: model.bro)
        case .restTimer: RestTimerEditor(profile: profile, model: model)
        case .units: UnitsEditor(profile: profile)
        case .language: LanguageEditor(model: model)
        case .notifications: NotificationsEditor(schedule: schedule, model: model)
        case .health: HealthEditor(model: model)
        case .export: ExportView()
        case .importWorkouts: ImportView()
        case .ai: AIProviderEditor(model: model)
        case .partner: PartnerEditor(model: model, onUnpaired: { path.removeAll() })
        }
    }
}

// MARK: - Version

/// The installed build as "0.2.1 (57)": marketing version, then the build number CI stamps from its run number.
enum AppVersion {
    static func label(info: [String: Any]? = Bundle.main.infoDictionary) -> String {
        let version = info?["CFBundleShortVersionString"] as? String ?? "–"
        guard let build = info?["CFBundleVersion"] as? String, !build.isEmpty else { return version }
        return "\(version) (\(build))"
    }
}

// MARK: - Code row

/// "Your code · NT-7K4Q" with a share icon instead of a chevron (the row is a ShareLink).
struct SettingsCodeRow: View {
    var code: String?

    var body: some View {
        if let code {
            ShareLink(item: String(format: String(localized: "bro.shareText"), code)) {
                rowLabel(code)
            }
            .buttonStyle(.plain)
        } else {
            rowLabel(String(localized: "bro.codePlaceholder")).opacity(0.5)
        }
    }

    private func rowLabel(_ value: String) -> some View {
        HStack(spacing: 12) {
            Text("settings.yourCode").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
            Spacer(minLength: 8)
            Text(value).font(NT.Fonts.body).foregroundStyle(NT.Colors.ink2).tabular().lineLimit(1)
            Image(systemName: "square.and.arrow.up").font(.system(size: 15, weight: .semibold)).foregroundStyle(NT.Colors.ink2)
        }
        .frame(minHeight: NT.Size.control)
        .contentShape(Rectangle())
    }
}
