import Foundation

/// Pure half of the Progress training calendar (`TrainingCalendarCard`): the month grid and how bright a day is.
enum TrainingCalendar {

    /// The weeks of `month` (any date in it), Monday first: `weeks[w][d]` is ISO weekday `d + 1`, nil outside the month.
    static func weeks(of month: Date, calendar: Calendar = .current) -> [[Date?]] {
        guard let interval = calendar.dateInterval(of: .month, for: month) else { return [] }
        let first = interval.start
        let days = calendar.range(of: .day, in: .month, for: first)?.count ?? 30
        // ISO: Monday = 0 … Sunday = 6.
        let lead = (calendar.component(.weekday, from: first) + 5) % 7
        var cells: [Date?] = Array(repeating: nil, count: lead)
        for offset in 0..<days {
            cells.append(calendar.date(byAdding: .day, value: offset, to: first))
        }
        while cells.count % 7 != 0 { cells.append(nil) }
        return stride(from: 0, to: cells.count, by: 7).map { Array(cells[$0..<($0 + 7)]) }
    }

    /// 0 = no workout, then 1…4 by completed sets that day (1–9, 10–17, 18–25, 26+).
    static func level(sets: Int) -> Int {
        switch sets {
        case ..<1: 0
        case 1..<10: 1
        case 10..<18: 2
        case 18..<26: 3
        default: 4
        }
    }

    /// Consecutive ISO weeks, ending with this one (or last week, when this week has none yet), with at least one
    /// workout.
    static func weekStreak(workoutDays: [Date], today: Date, calendar: Calendar = .current) -> Int {
        let weeks = Set(workoutDays.map { calendar.startOfISOWeek(for: $0) })
        var week = calendar.startOfISOWeek(for: today)
        if !weeks.contains(week) {
            guard let previous = calendar.date(byAdding: .day, value: -7, to: week) else { return 0 }
            week = previous
        }
        var streak = 0
        while weeks.contains(week) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -7, to: week) else { break }
            week = previous
        }
        return streak
    }
}
