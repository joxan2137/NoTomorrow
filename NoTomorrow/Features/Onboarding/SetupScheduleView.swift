import SwiftUI

/// Step "Schedule": 7 day toggles (Mon-first), usual time wheel, two reminder toggles.
struct SetupScheduleView: View {
    @Bindable var model: OnboardingModel
    @State private var overrideDay: OBDaySelection?

    var body: some View {
        OBStepScaffold(index: model.stepIndex ?? 1, count: model.stepCount, onBack: { model.back() }) {
            VStack(alignment: .leading, spacing: 0) {
                OBStepTitle(title: "onboarding.schedule.title", subtitle: "onboarding.schedule.subtitle")

                dayPicker
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 28)

                splitLine
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 12)

                timeSection
                    .padding(.horizontal, NT.Spacing.screenH)
                    .padding(.top, 32)

                VStack(spacing: 0) {
                    OBToggleRow(title: "onboarding.schedule.remindHourBefore", isOn: $model.remindHourBefore)
                    Hairline()
                    OBToggleRow(title: "onboarding.schedule.askIfSkipped",
                                detail: "onboarding.schedule.askIfSkipped.detail",
                                isOn: $model.askIfSkippedAt21)
                }
                .padding(.horizontal, NT.Spacing.screenH)
                .padding(.top, 24)
            }
        } footer: {
            PrimaryButton(title: "common.continue", isEnabled: !model.weekdays.isEmpty) { model.next() }
        }
        .sheet(item: $overrideDay) { selection in
            let day = selection.day
            OBDayTimeSheet(day: day, minute: model.overrides[day], usualMinute: model.usualMinuteOfDay) { minute in
                model.overrides[day] = minute
            }
        }
    }

    // MARK: Days

    private var dayPicker: some View {
        HStack(spacing: 4) {
            ForEach(1...7, id: \.self) { day in
                OBDayToggle(day: day, isOn: model.weekdays.contains(day), overrideMinute: model.overrides[day]) {
                    withAnimation(.easeOut(duration: 0.15)) { model.toggle(day: day) }
                } onHold: {
                    overrideDay = OBDaySelection(day: day)
                }
            }
        }
    }

    private var splitLine: some View {
        HStack(spacing: 4) {
            let count = model.weekdays.count
            if count == 0 {
                Text("onboarding.schedule.pickDays")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
            } else {
                (Text("onboarding.schedule.daysPerWeek \(count)")
                 + Text(verbatim: " · ")
                 + Text("onboarding.schedule.useSplit \(Text(Self.splitKey(days: count)))"))
                    .font(NT.Fonts.footnoteBold)
                    .foregroundStyle(NT.Colors.ember)
                    .tabular()
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(NT.Colors.ember)
            }
        }
        .frame(minHeight: 18)
    }

    static func splitKey(days: Int) -> LocalizedStringKey {
        switch days {
        case ...2: "split.fullBody"
        case 3...4: "split.pushPull"
        default: "split.pushPullLegs"
        }
    }

    // MARK: Time

    private var timeSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("onboarding.schedule.usualTime").font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
                Spacer(minLength: 12)
                Text("onboarding.schedule.holdHint")
                    .font(NT.Fonts.footnote)
                    .foregroundStyle(NT.Colors.ink2)
                    .multilineTextAlignment(.trailing)
            }
            OBTimeWheel(selection: $model.usualTime)
        }
    }
}

/// 44 pt circle with the day letter, short weekday name (or the override time in ember) below.
struct OBDayToggle: View {
    var day: Int
    var isOn: Bool
    var overrideMinute: Int?
    var onTap: () -> Void
    var onHold: () -> Void

    static let keys = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
    private var suffix: String { Self.keys[max(0, min(6, day - 1))] }

    var body: some View {
        VStack(spacing: 8) {
            Text(LocalizedStringKey(String("weekday.\(suffix)")))
                .font(NT.Fonts.headline)
                .foregroundStyle(isOn ? NT.Colors.onPrimary : NT.Colors.ink2)
                .frame(width: NT.Size.control, height: NT.Size.control)
                .background(isOn ? NT.Colors.ink : NT.Colors.surface, in: Circle())
            if let overrideMinute {
                Text(Fmt.time(minuteOfDay: overrideMinute))
                    .font(NT.Fonts.caption)
                    .foregroundStyle(NT.Colors.ember)
                    .tabular()
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            } else {
                Text(LocalizedStringKey(String("weekday.\(suffix).short")))
                    .font(NT.Fonts.caption)
                    .foregroundStyle(NT.Colors.ink2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onLongPressGesture(minimumDuration: 0.4, perform: onHold)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// Inline wheel for hour and minute inside a surface card.
struct OBTimeWheel: View {
    @Binding var selection: Date

    var body: some View {
        DatePicker("", selection: $selection, displayedComponents: .hourAndMinute)
            .datePickerStyle(.wheel)
            .labelsHidden()
            .colorScheme(.dark)
            .frame(maxWidth: .infinity)
            .frame(height: 164)
            .clipped()
            .padding(.vertical, 6)
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.card, style: .continuous))
    }
}

/// Sheet item for the per-day override (avoids conforming `Int` to `Identifiable` module-wide).
struct OBDaySelection: Identifiable, Equatable {
    var day: Int
    var id: Int { day }
}
