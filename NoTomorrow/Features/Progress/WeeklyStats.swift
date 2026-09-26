import Foundation

/// Pure half of the Progress "Weekly stats" card (`WeeklyStatsCard`): finished workouts bucketed into the last eight
/// weeks, each week carrying every metric the card can switch between.
///
/// Weeks start on the calendar's `firstWeekday`; the card passes a Monday-first calendar, like the rest of the app. A workout counts once it is
/// finished and has a completed set, as on the training calendar; its volume leaves warm-ups out
/// (`Workout.totalVolumeKg`), its sets count every completed set (`Workout.completedSetCount`).
enum WeeklyStats {

    enum Metric: String, CaseIterable, Identifiable, Hashable {
        case workouts, volume, duration, sets
        var id: String { rawValue }
    }

    /// One finished workout, copied out of SwiftData.
    struct Session: Equatable {
        var startedAt: Date
        var duration: TimeInterval
        var volumeKg: Double
        var sets: Int
    }

    struct Week: Identifiable, Equatable {
        let weekStart: Date
        let isCurrent: Bool
        var workouts = 0
        var volumeKg = 0.0
        var duration: TimeInterval = 0
        var sets = 0
        var id: Date { weekStart }

        /// The number the bars plot for `metric`: a count, kilograms or seconds.
        func value(_ metric: Metric) -> Double {
            switch metric {
            case .workouts: Double(workouts)
            case .volume: volumeKg
            case .duration: duration
            case .sets: Double(sets)
            }
        }
    }

    static let weekCount = 8

    /// Start of the week containing `date`, on `calendar.firstWeekday`.
    static func startOfWeek(for date: Date, calendar: Calendar) -> Date {
        calendar.dateInterval(of: .weekOfYear, for: date)?.start ?? calendar.startOfDay(for: date)
    }

    /// Start of the oldest of the last `count` weeks: sessions before it never show, so the card need not read them.
    static func firstWeekStart(now: Date, calendar: Calendar = .current, count: Int = weekCount) -> Date {
        weeks(sessions: [], now: now, calendar: calendar, count: count).first?.weekStart
            ?? startOfWeek(for: now, calendar: calendar)
    }

    /// The last `count` weeks, oldest first, ending with the week of `now`. Sessions outside them are ignored.
    static func weeks(sessions: [Session], now: Date, calendar: Calendar = .current, count: Int = weekCount) -> [Week] {
        let thisWeek = startOfWeek(for: now, calendar: calendar)
        var weeks: [Week] = []
        for offset in (0..<max(count, 0)).reversed() {
            // A day inside the week `offset` weeks back, snapped to its start (DST-safe).
            guard let day = calendar.date(byAdding: .day, value: -7 * offset + 3, to: thisWeek) else { continue }
            weeks.append(Week(weekStart: startOfWeek(for: day, calendar: calendar), isCurrent: offset == 0))
        }
        var index: [Date: Int] = [:]
        for (i, week) in weeks.enumerated() { index[week.weekStart] = i }
        for session in sessions {
            guard let i = index[startOfWeek(for: session.startedAt, calendar: calendar)] else { continue }
            weeks[i].workouts += 1
            weeks[i].volumeKg += session.volumeKg
            weeks[i].duration += max(0, session.duration)
            weeks[i].sets += session.sets
        }
        return weeks
    }

    /// This week against last week as a fraction (0.25 = +25 %); nil when last week is zero.
    static func change(_ weeks: [Week], metric: Metric) -> Double? {
        guard weeks.count >= 2 else { return nil }
        let last = weeks[weeks.count - 2].value(metric)
        guard last > 0 else { return nil }
        return (weeks[weeks.count - 1].value(metric) - last) / last
    }
}
