import SwiftUI
import SwiftData

// MARK: - Rest timer

/// Default rest length in 15 s steps + the auto-start flag (UserDefaults "nt.rest.autoStart").
struct RestTimerEditor: View {
    @Bindable var profile: UserProfile
    @Bindable var model: SettingsModel

    private let step = 15
    private let range = 15...600

    var body: some View {
        STEditorScreen(title: "settings.restTimer") {
            STGroup {
                HStack(spacing: 12) {
                    Text("settings.restTimer.length").font(NT.Fonts.body).foregroundStyle(NT.Colors.ink)
                    Spacer(minLength: 8)
                    stepper
                }
                .frame(minHeight: 56)
                .padding(.vertical, 4)
                STToggleRow(title: "settings.restTimer.autoStart",
                            detail: "settings.restTimer.autoStart.detail",
                            isOn: $model.restAutoStart)
            }
            .stFootnote("settings.restTimer.footnote")
        }
    }

    private var stepper: some View {
        HStack(spacing: 10) {
            stepButton("minus", enabled: profile.defaultRestSeconds - step >= range.lowerBound) {
                profile.defaultRestSeconds = max(range.lowerBound, profile.defaultRestSeconds - step)
            }
            Text(Fmt.clock(TimeInterval(profile.defaultRestSeconds)))
                .font(NT.Fonts.headline)
                .foregroundStyle(NT.Colors.ink)
                .tabular()
                .frame(minWidth: 52)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.15), value: profile.defaultRestSeconds)
            stepButton("plus", enabled: profile.defaultRestSeconds + step <= range.upperBound) {
                profile.defaultRestSeconds = min(range.upperBound, profile.defaultRestSeconds + step)
            }
        }
    }

    private func stepButton(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(NT.Colors.ink)
                .frame(width: 36, height: 36)
                .background(NT.Colors.surface2, in: Circle())
                .frame(width: NT.Size.control, height: NT.Size.control)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

// MARK: - Units

struct UnitsEditor: View {
    @Bindable var profile: UserProfile

    var body: some View {
        STEditorScreen(title: "settings.units") {
            STSegmented(options: [
                .init(value: WeightUnit.kg, title: "unit.kg"),
                .init(value: WeightUnit.lb, title: "unit.lb"),
            ], selection: $profile.units)
            .stLabeled("settings.units")
            .stFootnote("settings.units.footnote")
        }
    }
}
