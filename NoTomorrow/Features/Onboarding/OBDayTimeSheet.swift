import SwiftUI

/// "Hold a day for a different time": per-weekday override of the usual time.
struct OBDayTimeSheet: View {
    var day: Int
    var minute: Int?
    var usualMinute: Int
    var onSave: (Int?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var time: Date = .now

    private var dayKey: LocalizedStringKey {
        LocalizedStringKey(String("weekday.\(OBDayToggle.keys[max(0, min(6, day - 1))]).short"))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Grabber().frame(maxWidth: .infinity)

            Text("onboarding.schedule.dayTime.title \(Text(dayKey))")
                .font(NT.Fonts.title2)
                .foregroundStyle(NT.Colors.ink)
                .padding(.top, 18)

            OBTimeWheel(selection: $time)
                .padding(.top, 18)

            Spacer(minLength: 16)

            VStack(spacing: 10) {
                GhostButton(title: "onboarding.schedule.dayTime.useUsual") {
                    onSave(nil)
                    dismiss()
                }
                PrimaryButton(title: "common.save") {
                    let m = OnboardingModel.minuteOfDay(time)
                    onSave(m == usualMinute ? nil : m)
                    dismiss()
                }
            }
            .padding(.bottom, 8)
        }
        .padding(.horizontal, NT.Spacing.screenH)
        .ntScreenBackground()
        .presentationDetents([.medium])
        .presentationDragIndicator(.hidden)
        .presentationBackground(NT.Colors.ground)
        .onAppear { time = OnboardingModel.date(minuteOfDay: minute ?? usualMinute) }
    }
}
