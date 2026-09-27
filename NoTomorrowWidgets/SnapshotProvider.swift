import SwiftUI
import WidgetKit

/// Timeline for the widgets that render the app's snapshot: one entry now, one per moment the widget must change on
/// its own (`extraDates`), and a refresh at midnight, when "today" moves. The app reloads the timelines whenever the
/// snapshot changes (`WidgetSync.publish`).
struct SnapshotProvider: TimelineProvider {
    var extraDates: (WidgetSnapshot?, Date) -> [Date] = { _, _ in [] }

    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: .preview(now: .now))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let entry = SnapshotEntry.load()
        // The gallery shows real data once there is some, a sample before.
        completion(context.isPreview && !entry.isReady ? SnapshotEntry(date: .now, snapshot: .preview(now: .now)) : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let now = Date.now
        let first = SnapshotEntry.load(at: now)
        let midnight = Calendar.current.nextMidnight(after: now)
        let later = Set(extraDates(first.snapshot, now).filter { $0 > now && $0 < midnight } + [midnight]).sorted()
        let entries = [first] + later.map { SnapshotEntry(date: $0, snapshot: first.snapshot) }
        completion(Timeline(entries: entries, policy: .after(midnight)))
    }
}

extension WidgetSnapshot {
    /// Sample data for the widget gallery and placeholders: a believable half-day, a filled calendar, a mid-week.
    static func preview(now: Date) -> WidgetSnapshot {
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        let goal = 2600.0
        var days: [CalendarGrid.Day] = []
        for offset in stride(from: 181, through: 0, by: -1) {
            guard let date = cal.date(byAdding: .day, value: -offset, to: today) else { continue }
            // Deterministic "noise": most days near the goal, some off, a few not logged.
            let seed = (offset * 7919) % 97
            let kcal: Double? = seed < 9 ? nil : goal * (0.72 + Double(seed % 40) / 100)
            let trained = [1, 3, 5].contains(cal.widgetISOWeekday(for: date)) && seed % 5 != 0
            days.append(.init(date: date, kcal: offset == 0 ? 1240 : kcal, trained: trained))
        }
        let monday = cal.date(byAdding: .day, value: -(cal.widgetISOWeekday(for: today) - 1), to: today) ?? today
        let weekDays: [Week.Day] = (0..<7).map { i in
            let date = cal.date(byAdding: .day, value: i, to: monday) ?? monday
            let gym = [0, 2, 4].contains(i)
            let past = date < today
            return .init(date: date, isGymDay: gym,
                         me: !gym ? .rest : past ? .attended : .planned,
                         partner: !gym ? .rest : past ? (i == 2 ? .missed : .attended) : .planned)
        }
        return WidgetSnapshot(
            generatedAt: now, languageOverride: nil, hasProfile: true,
            fuel: .init(day: today, kcal: 1240, protein: 96, carbs: 128, fat: 38, kcalGoal: goal),
            quickFoods: [
                .init(key: "p1", foodID: nil, name: "Skyr, natural", grams: 300, kcal: 186, protein: 33, carbs: 12, fat: 0.6, isAIEstimate: false),
                .init(key: "p2", foodID: nil, name: "Oats with banana", grams: 320, kcal: 412, protein: 13, carbs: 72, fat: 7, isAIEstimate: false),
                .init(key: "p3", foodID: nil, name: "Chicken, rice, peppers", grams: 480, kcal: 655, protein: 52, carbs: 78, fat: 12, isAIEstimate: true),
            ],
            calendar: .init(days: days, under: [30, 80, 150], over: [100, 200, 350], kcalGoal: goal, sessions30: 12),
            week: .init(days: weekDays, isPaired: true, gymMinutes: [1: 1080, 3: 1080, 5: 1080], routineName: "Push A"),
            defaultRestSeconds: 90,
            lastLogged: nil
        )
    }
}
