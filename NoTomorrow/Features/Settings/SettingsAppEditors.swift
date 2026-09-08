import SwiftUI
import SwiftData

// MARK: - Language

struct LanguageEditor: View {
    @Bindable var model: SettingsModel
    @Environment(AppState.self) private var appState

    var body: some View {
        STEditorScreen(title: "settings.language") {
            STGroup {
                STCheckRow(title: "settings.system", isSelected: appState.languageOverride == nil) { select(nil) }
                STCheckRow(title: "settings.english", isSelected: appState.languageOverride == "en") { select("en") }
                STCheckRow(title: "settings.polish", isSelected: appState.languageOverride == "pl") { select("pl") }
            }
            .stFootnote("settings.language.footnote")
        }
    }

    private func select(_ code: String?) {
        model.setLanguage(code, appState: appState)
    }
}

// MARK: - Notifications

struct NotificationsEditor: View {
    @Bindable var schedule: GymSchedule
    @Bindable var model: SettingsModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        STEditorScreen(title: "settings.notifications") {
            STGroup {
                STToggleRow(title: "onboarding.schedule.remindHourBefore", isOn: remindHourBefore)
                STToggleRow(title: "onboarding.schedule.askIfSkipped",
                            detail: "onboarding.schedule.askIfSkipped.detail",
                            isOn: askIfSkipped)
            }
            .stFootnote("settings.notifications.footnote")

            STGroup(title: "settings.notifications.permission") {
                STInfoRow(label: "settings.notifications.permission", value: model.notificationStatusValue,
                          dot: model.notificationStatus == .authorized ? NT.Colors.good : nil)
                if model.notificationStatus == .denied {
                    STActionRow(label: "settings.notifications.openSettings") { model.openSystemSettings() }
                } else {
                    STActionRow(label: "settings.notifications.request") {
                        Task { await model.requestNotificationPermission() }
                    }
                }
            }
        }
        .task { await model.refreshNotificationStatus() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshNotificationStatus() } }
        }
    }

    private var remindHourBefore: Binding<Bool> {
        Binding(get: { schedule.remindHourBefore },
                set: { schedule.remindHourBefore = $0; schedule.updatedAt = .now })
    }

    private var askIfSkipped: Binding<Bool> {
        Binding(get: { schedule.askIfSkippedAt21 },
                set: { schedule.askIfSkippedAt21 = $0; schedule.updatedAt = .now })
    }
}

// MARK: - Health

struct HealthEditor: View {
    @Bindable var model: SettingsModel

    var body: some View {
        STEditorScreen(title: "settings.health") {
            Text("settings.health.description")
                .font(NT.Fonts.subheadline)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)

            STGroup {
                STInfoRow(label: "settings.health.status", value: model.healthValue,
                          dot: model.health.isAuthorized ? NT.Colors.good : nil)
            }

            if model.health.isAvailable {
                PrimaryButton(title: "settings.health.connect") {
                    Task { await model.health.requestAuthorization() }
                }
            } else {
                Text("health.error.unavailable")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .padding(.horizontal, 16)
            }

            if let error = model.health.lastError {
                Text(error).font(NT.Fonts.footnote).foregroundStyle(NT.Colors.bad).padding(.horizontal, 16)
            }
        }
    }
}

// MARK: - AI estimates

/// Standard (Gemini through our backend), or Claude / Gemini with the user's own API key (Keychain).
struct AIProviderEditor: View {
    @Bindable var model: SettingsModel
    @State private var keyDraft = ""
    @State private var geminiKeyDraft = ""
    @FocusState private var keyFocused: Bool
    @FocusState private var geminiKeyFocused: Bool

    private var provider: Binding<AIProvider> {
        Binding(get: { model.aiProvider }, set: { model.aiProvider = $0 })
    }

    var body: some View {
        STEditorScreen(title: "settings.aiProvider") {
            STGroup {
                STCheckRow(title: "settings.ai.standard", detail: "settings.ai.standard.detail",
                           isSelected: model.aiProvider == .standard) { provider.wrappedValue = .standard }
                STCheckRow(title: "settings.ai.claude", detail: "settings.ai.claude.detail",
                           isSelected: model.aiProvider == .claudeBYOK) { provider.wrappedValue = .claudeBYOK }
                STCheckRow(title: "settings.ai.gemini", detail: "settings.ai.gemini.detail",
                           isSelected: model.aiProvider == .geminiBYOK) { provider.wrappedValue = .geminiBYOK }
            }

            if model.aiProvider == .claudeBYOK {
                keySection
            }

            if model.aiProvider == .geminiBYOK {
                geminiKeySection
            }

            STGroup(title: "settings.developer") {
                STToggleRow(title: "settings.demoData", detail: "settings.demoData.detail", isOn: demoData)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.aiProvider)
    }

    private var demoData: Binding<Bool> {
        Binding(get: { model.useDemoData }, set: { model.useDemoData = $0 })
    }

    private var keySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let masked = model.auth.maskedAnthropicKey {
                STGroup {
                    STInfoRow(label: "settings.ai.keyLabel", value: masked, dot: NT.Colors.good)
                    STActionRow(label: "settings.ai.removeKey", color: NT.Colors.bad) {
                        model.auth.removeAnthropicKey()
                        keyDraft = ""
                    }
                }
            } else {
                SecureField(String(localized: "settings.ai.keyPlaceholder"), text: $keyDraft)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .focused($keyFocused)
                    .onSubmit(saveKey)
                    .stField(isFocused: keyFocused)
                    .stLabeled("settings.ai.keyLabel")

                PrimaryButton(title: "settings.ai.saveKey", height: NT.Size.cardButton,
                              isEnabled: !keyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    saveKey()
                }
            }

            Text("settings.ai.footnote")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
        }
    }

    private var geminiKeySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let masked = model.auth.maskedGeminiKey {
                STGroup {
                    STInfoRow(label: "settings.ai.geminiKeyLabel", value: masked, dot: NT.Colors.good)
                    STActionRow(label: "settings.ai.removeKey", color: NT.Colors.bad) {
                        model.auth.removeGeminiKey()
                        geminiKeyDraft = ""
                    }
                }
            } else {
                SecureField(String(localized: "settings.ai.geminiKeyPlaceholder"), text: $geminiKeyDraft)
                    .font(NT.Fonts.body)
                    .foregroundStyle(NT.Colors.ink)
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.done)
                    .focused($geminiKeyFocused)
                    .onSubmit(saveGeminiKey)
                    .stField(isFocused: geminiKeyFocused)
                    .stLabeled("settings.ai.geminiKeyLabel")

                PrimaryButton(title: "settings.ai.saveKey", height: NT.Size.cardButton,
                              isEnabled: !geminiKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    saveGeminiKey()
                }
            }

            Text("settings.ai.geminiFootnote")
                .font(NT.Fonts.footnote)
                .foregroundStyle(NT.Colors.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
        }
    }

    private func saveKey() {
        let trimmed = keyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        model.auth.anthropicKey = trimmed
        keyDraft = ""
        keyFocused = false
    }

    private func saveGeminiKey() {
        let trimmed = geminiKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        model.auth.geminiKey = trimmed
        geminiKeyDraft = ""
        geminiKeyFocused = false
    }
}
