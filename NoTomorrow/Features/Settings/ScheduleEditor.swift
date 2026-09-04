import SwiftUI
import SwiftData

/// Gym days · time: seven Mon-first day toggles and a wheel time picker, written straight to `GymSchedule`
/// and pushed to the bro when paired.
struct ScheduleEditor: View {
    @Bindable var schedule: GymSchedule
    var bro: BroService

    @State private var selected: Set<Int> = []
    @State private var time: Date = .now
    @State private var dirty = false

    var body: some View {
        STEditorScreen(title: "settings.gymDays") {
            dayToggles
                .stLabeled("settings.gymDays")
                .stFootnote(selected.isEmpty ? "onboarding.schedule.pickDays" : "settings.schedule.footnote")

            timePicker.stLabeled("onboarding.schedule.usualTime")
        }
        .onAppear {
            selected = Set(schedule.weekdays)
            time = ScheduleEditor.date(minuteOfDay: schedule.defaultMinuteOfDay)
        }
        .onChange(of: selected) { _, _ in dirty = true; apply() }
        .onChange(of: time) { _, _ in dirty = true; apply() }
        .onDisappear { if dirty { push() } }
    }

    // MARK: Days

    private var dayToggles: some View {
        HStack(spacing: 6) {
            ForEach(1...7, id: \.self) { day in
                let isOn = selected.contains(day)
                Button {
                    withAnimation(.easeOut(duration: 0.15)) {
                        if isOn { selected.remove(day) } else { selected.insert(day) }
                    }
                } label: {
                    Text(LocalizedStringKey(SettingsModel.letterKey(isoWeekday: day)))
                        .font(NT.Fonts.subheadlineBold)
                        .foregroundStyle(isOn ? NT.Colors.onPrimary : NT.Colors.ink)
                        .frame(maxWidth: .infinity)
                        .frame(height: NT.Size.control)
                        .background(isOn ? NT.Colors.ink : NT.Colors.surface,
                                    in: RoundedRectangle(cornerRadius: NT.Radius.field, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(LocalizedStringKey(SettingsModel.shortKey(isoWeekday: day))))
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
    }

    // MARK: Time

    private var timePicker: some View {
        DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
            .datePickerStyle(.wheel)
            .labelsHidden()
            .colorScheme(.dark)
            .frame(maxWidth: .infinity)
            .frame(height: 180)
            .clipped()
            .background(NT.Colors.surface, in: RoundedRectangle(cornerRadius: NT.Radius.tile, style: .continuous))
    }

    // MARK: Persist

    private func apply() {
        guard !selected.isEmpty else { return }
        schedule.weekdays = selected.sorted()
        schedule.defaultMinuteOfDay = Self.minuteOfDay(time)
        schedule.overrides = schedule.overrides.filter { selected.contains($0.key) }
        schedule.updatedAt = .now
    }

    private func push() {
        guard bro.isPaired else { return }
        let dto = ScheduleDTO(schedule)
        let client = bro.client
        Task { try? await client.pushSchedule(dto) }
    }

    static func minuteOfDay(_ date: Date) -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    static func date(minuteOfDay: Int) -> Date {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: .now)
        comps.hour = minuteOfDay / 60
        comps.minute = minuteOfDay % 60
        return Calendar.current.date(from: comps) ?? .now
    }
}
