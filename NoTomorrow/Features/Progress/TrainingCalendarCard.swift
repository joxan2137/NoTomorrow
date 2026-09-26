import SwiftUI
import SwiftData

/// Progress > Lifts: a month of training at a glance. Days with a finished workout fill in the heat colours by how
/// many sets were done; tapping one opens that workout. Chevrons page through months; the footer counts the
/// month's workouts and the current weekly streak.
struct TrainingCalendarCard: View {
    var unit: WeightUnit

    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt, order: .reverse)
    private var workouts: [Workout]
    @State private var month = Date.now
    @State private var selected: Workout?

    private let calendar = Calendar.current

    var body: some View {
        // Only the shown month's workouts and the streak's own weeks open their sets: counting every workout's sets
        // on each render (a month page, a new workout) stalls the tab once years of history are imported.
        let shown = workouts.filter { calendar.isDate($0.startedAt, equalTo: month, toGranularity: .month) }
        let byDay = Dictionary(grouping: shown.filter { $0.completedSetCount > 0 }) { calendar.startOfDay(for: $0.startedAt) }
        let weeks = TrainingCalendar.weeks(of: month, calendar: calendar)
        let streakDays = TrainingCalendar.streakDays(newestFirst: workouts, date: { $0.startedAt },
                                                     counts: { $0.completedSetCount > 0 }, today: .now, calendar: calendar)
        let streak = TrainingCalendar.weekStreak(workoutDays: streakDays, today: .now, calendar: calendar)

        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "calendar.title")
            NTCard {
                VStack(spacing: 10) {
                    monthHeader
                    weekdayHeader
                    VStack(spacing: 6) {
                        ForEach(Array(weeks.enumerated()), id: \.offset) { _, week in
                            HStack(spacing: 6) {
                                ForEach(0..<7, id: \.self) { index in
                                    dayCell(week[index], byDay: byDay)
                                }
                            }
                        }
                    }
                    HStack {
                        Text(verbatim: WorkoutStrings.workouts(byDay.values.reduce(0) { $0 + $1.count }))
                        Spacer()
                        if streak > 0 {
                            Label {
                                Text(verbatim: WorkoutStrings.weekStreak(streak))
                            } icon: {
                                Image(systemName: "flame.fill").foregroundStyle(NT.Colors.ember)
                            }
                        }
                    }
                    .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
                    .padding(.top, 2)
                }
            }
        }
        .workoutDetailSheet($selected, unit: unit)
    }

    private var isCurrentMonth: Bool { calendar.isDate(month, equalTo: .now, toGranularity: .month) }

    private var monthHeader: some View {
        HStack {
            Text(month.formatted(.dateTime.month(.wide).year().locale(Fmt.locale)))
                .font(NT.Fonts.headline).foregroundStyle(NT.Colors.ink)
            Spacer()
            pageButton("chevron.left", label: "calendar.previous", isEnabled: true) { page(by: -1) }
            pageButton("chevron.right", label: "calendar.next", isEnabled: !isCurrentMonth) { page(by: 1) }
        }
    }

    private func pageButton(_ symbol: String, label: LocalizedStringKey, isEnabled: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(isEnabled ? NT.Colors.ink : NT.Colors.ink3)
                .frame(width: 36, height: 36)
                // 44 pt hit area around the 36 pt box, without changing the layout.
                .contentShape(Rectangle().inset(by: -4))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityLabel(Text(label))
    }

    private func page(by months: Int) {
        guard let next = calendar.date(byAdding: .month, value: months, to: month) else { return }
        withAnimation(.easeInOut(duration: 0.2)) { month = next }
    }

    /// M T W T F S S in the user's language, Monday first.
    private var weekdayHeader: some View {
        var cal = calendar
        cal.locale = Fmt.locale
        let symbols = cal.veryShortStandaloneWeekdaySymbols   // Sunday first
        let mondayFirst = Array(symbols[1...]) + [symbols[0]]
        return HStack(spacing: 6) {
            ForEach(Array(mondayFirst.enumerated()), id: \.offset) { _, symbol in
                Text(symbol).font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink3).frame(maxWidth: .infinity)
            }
        }
        // Single letters; each day cell reads its own date.
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func dayCell(_ day: Date?, byDay: [Date: [Workout]]) -> some View {
        if let day {
            let dayWorkouts = byDay[calendar.startOfDay(for: day)] ?? []
            let sets = dayWorkouts.reduce(0) { $0 + $1.completedSetCount }
            let level = TrainingCalendar.level(sets: sets)
            let isToday = calendar.isDateInToday(day)
            Button {
                selected = dayWorkouts.first
            } label: {
                // A flexible square carries the number, so every cell gets the same width (a Text sized to its
                // number made the row uneven and squeezed some days down to "…").
                Color.clear
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .overlay {
                        Text(verbatim: "\(calendar.component(.day, from: day))")
                            .font(level > 0 ? NT.Fonts.footnoteBold : NT.Fonts.footnote)
                            .foregroundStyle(level >= 3 ? NT.Colors.onPrimary : (day > .now ? NT.Colors.ink3 : NT.Colors.ink))
                            .tabular()
                            .lineLimit(1)
                            .fixedSize()
                    }
                    .contentShape(Rectangle())
                    .background(NT.Colors.heat[level], in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay {
                        if isToday {
                            RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(NT.Colors.ink, lineWidth: 1.5)
                        }
                    }
            }
            .buttonStyle(.plain)
            .disabled(dayWorkouts.isEmpty)
            .accessibilityLabel(Text(verbatim: Fmt.dayMonth(day)))
            .accessibilityValue(dayWorkouts.isEmpty ? Text("calendar.rest")
                                : Text(verbatim: WorkoutStrings.workouts(dayWorkouts.count) + ", " + WorkoutStrings.sets(sets)))
        } else {
            Color.clear.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity).accessibilityHidden(true)
        }
    }
}
