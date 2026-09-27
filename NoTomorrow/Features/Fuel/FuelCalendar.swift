import Foundation
import SwiftData

/// Pure half of the Fuel history grid (`FuelCalendarSheet`): day scoring, the 26-week layout, the stat tiles and the
/// one SwiftData read. Mirrored 1:1 by `FuelCalendar` in android/.../feature/fuel/FuelCalendar.kt; both test suites
/// assert the same vectors (FuelCalendarTests / FuelCalendarTest).
enum FuelCalendar {
    /// Columns in the grid, oldest first; the last one is the current week.
    static let weeks = 26
    /// Days (before today) behind the "30-day avg" and "on target" tiles.
    static let statsWindow = 30

    // MARK: - Scoring

    /// How far a day may miss its kcal goal, in permille of the goal, and still reach level 4, 3 and 2.
    /// `under` applies below the goal, `over` above it.
    struct Tolerance: Equatable {
        let under: [Int]
        let over: [Int]
    }

    /// Lose fat punishes going over hard and falling short gently; build muscle is the mirror; maintain is symmetric.
    /// Level 4 is 90–103 % / 97–110 % / 95–105 % of the goal.
    static func tolerance(for goal: TrainingGoal) -> Tolerance {
        switch goal {
        case .loseFat:     Tolerance(under: [100, 200, 350], over: [30, 80, 150])
        case .buildMuscle: Tolerance(under: [30, 80, 150], over: [100, 200, 350])
        case .maintain:    Tolerance(under: [50, 100, 200], over: [50, 100, 200])
        }
    }

    /// kcal eaten as whole permille of the goal, clamped to 0…10 000 (0 when the goal is not positive). Integer
    /// band edges keep both platforms equal: as Doubles, 2200 / 2000 − 1 is 0.10000000000000009, not 0.10.
    /// The scoring itself lives in `FuelHeat` (Shared), so the widgets score days exactly like this grid.
    static func permille(kcalEaten: Double, kcalGoal: Double) -> Int {
        FuelHeat.permille(kcalEaten: kcalEaten, kcalGoal: kcalGoal)
    }

    /// 0 = nothing logged, 1 = far off … 4 = on target. Today, while still below its best band, reads as progress
    /// instead (under 50 % → 1, under 75 % → 2, otherwise 3), so the cell brightens as the day fills up.
    static func level(kcalEaten: Double, kcalGoal: Double, goal: TrainingGoal, hasEntries: Bool, isToday: Bool) -> Int {
        let tolerance = tolerance(for: goal)
        return FuelHeat.level(kcalEaten: kcalEaten, kcalGoal: kcalGoal, under: tolerance.under, over: tolerance.over,
                              hasEntries: hasEntries, isToday: isToday)
    }

    // MARK: - Layout

    struct MonthLabel: Equatable {
        let column: Int
        /// First day of the month.
        let month: Date
    }

    struct Layout: Equatable {
        /// Monday of the oldest column.
        let start: Date
        let today: Date
        /// `columns[c][r]`: week `c` (0 = oldest), ISO weekday `r + 1`; nil after today.
        let columns: [[Date?]]
        let monthLabels: [MonthLabel]

        /// The column holding `day`, or nil outside `start…today`.
        func column(of day: Date, calendar: Calendar = .current) -> Int? {
            let d = calendar.startOfDay(for: day)
            guard d >= start, d <= today else { return nil }
            return (calendar.dateComponents([.day], from: start, to: d).day ?? 0) / 7
        }
    }

    /// Monday-first weeks (the app convention, and the Polish one) ending with the week that holds `today`.
    /// A column is labelled with the month whose 1st it contains; column 0 also gets `start`'s month when the first
    /// such label would come at column 2 or later.
    static func layout(today: Date, weeks: Int = weeks, calendar: Calendar = .current) -> Layout {
        let today = calendar.startOfDay(for: today)
        let firstMonday = calendar.date(byAdding: .day, value: -7 * (weeks - 1), to: calendar.startOfISOWeek(for: today)) ?? today
        let start = calendar.startOfDay(for: firstMonday)
        var columns: [[Date?]] = []
        var labels: [MonthLabel] = []
        for c in 0..<weeks {
            var column: [Date?] = []
            for r in 0..<7 {
                let date = calendar.startOfDay(for: calendar.date(byAdding: .day, value: c * 7 + r, to: start) ?? start)
                if calendar.component(.day, from: date) == 1 { labels.append(MonthLabel(column: c, month: date)) }
                column.append(date <= today ? date : nil)
            }
            columns.append(column)
        }
        if (labels.first?.column ?? .max) >= 2,
           let month = calendar.date(from: calendar.dateComponents([.year, .month], from: start)) {
            labels.insert(MonthLabel(column: 0, month: month), at: 0)
        }
        return Layout(start: start, today: today, columns: columns, monthLabels: labels)
    }

    /// Leading column to scroll to: the end (current week at the right edge) unless the selected day would be off
    /// screen there, in which case the selected day is centred.
    static func firstVisibleColumn(selected: Int?, visible: Int, count: Int) -> Int {
        let last = max(0, count - visible)
        guard let selected, selected < last else { return last }
        return min(max(0, selected - visible / 2), last)
    }

    // MARK: - Stats

    struct Stats: Equatable {
        var avg7: Double?
        var avg30: Double?
        var onTarget30: Int
    }

    /// Averages over the logged days among the 7 / 30 days before today (today is still in progress, so it is left
    /// out), and how many of those 30 days reached level 4. Days with nothing logged are not on target.
    static func stats(kcalByDay: [Date: Double], today: Date, kcalGoal: Double, goal: TrainingGoal,
                      calendar: Calendar = .current) -> Stats {
        let today = calendar.startOfDay(for: today)
        func window(_ n: Int) -> [Date] {
            (1...n).compactMap { offset in
                calendar.date(byAdding: .day, value: -offset, to: today).map { calendar.startOfDay(for: $0) }
            }
        }
        func average(_ n: Int) -> Double? {
            let values = window(n).compactMap { kcalByDay[$0] }
            return values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
        }
        let onTarget = window(statsWindow).filter { day in
            guard let kcal = kcalByDay[day] else { return false }
            return level(kcalEaten: kcal, kcalGoal: kcalGoal, goal: goal, hasEntries: true, isToday: false) == 4
        }.count
        return Stats(avg7: average(7), avg30: average(statsWindow), onTarget30: onTarget)
    }

    // MARK: - Day keys & data

    /// Local calendar day of a stored `MealEntry.day`. That value is local midnight at logging time, so reading it
    /// through noon keeps it on the same calendar day after a time-zone change of less than ±12 h.
    static func dayKey(_ storedDay: Date, calendar: Calendar = .current) -> Date {
        calendar.startOfDay(for: storedDay.addingTimeInterval(12 * 3600))
    }

    /// Stored `MealEntry.day` values whose `dayKey` is `day`: the half-open range for a day fetch.
    static func storedDayBounds(for day: Date, calendar: Calendar = .current) -> (lower: Date, upper: Date) {
        let start = calendar.startOfDay(for: day)
        let next = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(24 * 3600)
        return (start.addingTimeInterval(-12 * 3600), next.addingTimeInterval(-12 * 3600))
    }

    /// The entries listed under `day`: every stored `day` inside `storedDayBounds`. The Fuel day view and the Dashboard's
    /// Fuel row both fetch with it, so neither drops a meal logged before a time-zone change.
    static func entriesPredicate(for day: Date, calendar: Calendar = .current) -> Predicate<MealEntry> {
        let (lower, upper) = storedDayBounds(for: day, calendar: calendar)
        return #Predicate<MealEntry> { $0.day >= lower && $0.day < upper }
    }

    /// kcal per day for every entry on or after `from`, keyed by `dayKey`. Days with no entries are absent (level 0).
    /// One fetch of just `day` and `kcal`: about 2 000 small rows for half a year of normal logging.
    static func kcalByDay(from: Date, in context: ModelContext, calendar: Calendar = .current) -> [Date: Double] {
        let lower = storedDayBounds(for: from, calendar: calendar).lower
        var descriptor = FetchDescriptor<MealEntry>(predicate: #Predicate { $0.day >= lower })
        descriptor.propertiesToFetch = [\.day, \.kcal]
        var totals: [Date: Double] = [:]
        for entry in (try? context.fetch(descriptor)) ?? [] {
            totals[dayKey(entry.day, calendar: calendar), default: 0] += entry.kcal
        }
        return totals
    }
}
