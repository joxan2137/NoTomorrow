import SwiftUI
import SwiftData

/// Progress > Lifts: the last eight weeks of training as small bars, switchable between workouts, volume, time and
/// sets, with this week's value against last week's. The bucketing lives in `WeeklyStats`.
struct WeeklyStatsCard: View {
    var unit: WeightUnit

    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt, order: .reverse)
    private var workouts: [Workout]
    @State private var metric: WeeklyStats.Metric = .workouts

    var body: some View {
        let sessions: [WeeklyStats.Session] = workouts.compactMap { workout in
            let sets = workout.completedSetCount
            guard sets > 0 else { return nil }
            return WeeklyStats.Session(startedAt: workout.startedAt, duration: workout.duration,
                                       volumeKg: workout.totalVolumeKg, sets: sets)
        }
        // Monday-first like the rest of the app (calendar, muscles this week).
        var calendar = Calendar.current
        calendar.firstWeekday = 2
        let weeks = WeeklyStats.weeks(sessions: sessions, now: .now, calendar: calendar)
        let thisWeek = weeks.last
        let lastWeek = weeks.count >= 2 ? weeks[weeks.count - 2] : nil
        let ratio = WeeklyStats.change(weeks, metric: metric)

        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "stats.title")
            NTCard {
                VStack(alignment: .leading, spacing: 14) {
                    ProgressSegmented(options: WeeklyStats.Metric.allCases, label: Self.title, selection: $metric)
                    HStack(alignment: .bottom, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("progress.thisWeek").eyebrow()
                            Text(verbatim: format(thisWeek))
                                .font(NT.Fonts.title2).foregroundStyle(NT.Colors.ink).tabular()
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                        Spacer(minLength: 0)
                        VStack(alignment: .trailing, spacing: 4) {
                            Text("workout.history.lastWeek").eyebrow()
                            Text(verbatim: format(lastWeek))
                                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2).tabular()
                                .lineLimit(1).minimumScaleFactor(0.7)
                        }
                    }
                    if let ratio {
                        HStack(spacing: 0) {
                            Text(verbatim: Fmt.signedPercent(ratio) + " ")
                            Text("progress.vsLastWeek")
                        }
                        .font(NT.Fonts.footnote)
                        .foregroundStyle(ratio >= 0 ? NT.Colors.ember : NT.Colors.ink2)
                        .tabular()
                        .lineLimit(1)
                    }
                    // The volume chart's bars, fed the selected metric: its axis only labels the weeks.
                    WeeklyVolumeChart(weeks: weeks.map {
                        WeekVolume(weekStart: $0.weekStart, volumeKg: $0.value(metric), isCurrent: $0.isCurrent)
                    })
                    .frame(height: 86)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(Self.title(metric)))
                    .accessibilityValue(Text(verbatim: weeks.map { format($0) }.joined(separator: ", ")))
                }
            }
        }
    }

    static func title(_ metric: WeeklyStats.Metric) -> LocalizedStringKey {
        switch metric {
        case .workouts: "stats.workouts"
        case .volume: "workout.volume"
        case .duration: "workout.time"
        case .sets: "workout.sets"
        }
    }

    private func format(_ week: WeeklyStats.Week?) -> String {
        guard let week else { return "—" }
        switch metric {
        case .workouts: return WorkoutStrings.workouts(week.workouts)
        case .volume: return Fmt.volume(week.volumeKg, unit: unit)
        case .duration: return Fmt.duration(week.duration)
        case .sets: return WorkoutStrings.sets(week.sets)
        }
    }
}
