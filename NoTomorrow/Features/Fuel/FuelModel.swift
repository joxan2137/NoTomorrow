import Foundation
import SwiftData
import Observation

/// Day-scoped totals for the Fuel home: goals from `UserProfile`, entries for the selected day, protein streak.
/// Refreshed from the model context on appear, on day change and after every sheet closes.
@Observable
final class FuelModel {
    struct Goals {
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        static let fallback = Goals(kcal: 2600, protein: 180, carbs: 300, fat: 80)
    }

    var day: Date = Calendar.current.startOfDay(for: .now)
    private(set) var entries: [MealEntry] = []
    private(set) var goals: Goals = .fallback
    private(set) var proteinStreak: Int = 0

    private let calendar = Calendar.current

    // MARK: - Day navigation

    var isToday: Bool { calendar.isDateInToday(day) }
    var canGoForward: Bool { day < calendar.startOfDay(for: .now) }

    func goPreviousDay() {
        day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
    }

    func goNextDay() {
        guard canGoForward else { return }
        day = calendar.date(byAdding: .day, value: 1, to: day) ?? day
    }

    // MARK: - Totals

    var kcalEaten: Double { entries.reduce(0) { $0 + $1.kcal } }
    var proteinEaten: Double { entries.reduce(0) { $0 + $1.proteinG } }
    var carbsEaten: Double { entries.reduce(0) { $0 + $1.carbsG } }
    var fatEaten: Double { entries.reduce(0) { $0 + $1.fatG } }

    var kcalLeft: Double { max(0, goals.kcal - kcalEaten) }
    var ringProgress: Double { goals.kcal > 0 ? min(1, kcalEaten / goals.kcal) : 0 }
    var proteinRemaining: Double { max(0, goals.protein - proteinEaten) }

    func entries(for slot: MealSlot) -> [MealEntry] {
        entries.filter { $0.slot == slot }.sorted { $0.loggedAt < $1.loggedAt }
    }

    func kcal(for slot: MealSlot) -> Double {
        entries(for: slot).reduce(0) { $0 + $1.kcal }
    }

    // MARK: - Persistence

    func refresh(in context: ModelContext) {
        if let profile = try? context.fetch(FetchDescriptor<UserProfile>()).first {
            goals = Goals(kcal: Double(profile.calorieGoal), protein: Double(profile.proteinGoalG),
                          carbs: Double(profile.carbsGoalG), fat: Double(profile.fatGoalG))
        }
        let selected = day
        let dayDescriptor = FetchDescriptor<MealEntry>(
            predicate: #Predicate { $0.day == selected },
            sortBy: [SortDescriptor(\.loggedAt)]
        )
        entries = (try? context.fetch(dayDescriptor)) ?? []
        proteinStreak = computeStreak(in: context)
    }

    func delete(_ entry: MealEntry, in context: ModelContext) {
        context.delete(entry)
        try? context.save()
        entries.removeAll { $0.id == entry.id }
    }

    /// Consecutive days (ending today, or yesterday if today is not there yet) with protein at or above goal.
    private func computeStreak(in context: ModelContext) -> Int {
        let today = calendar.startOfDay(for: .now)
        guard let from = calendar.date(byAdding: .day, value: -120, to: today) else { return 0 }
        let descriptor = FetchDescriptor<MealEntry>(predicate: #Predicate { $0.day >= from })
        let recent = (try? context.fetch(descriptor)) ?? []
        var proteinByDay: [Date: Double] = [:]
        for entry in recent { proteinByDay[entry.day, default: 0] += entry.proteinG }
        let goal = goals.protein
        guard goal > 0 else { return 0 }

        var cursor = today
        if (proteinByDay[cursor] ?? 0) < goal {
            cursor = calendar.date(byAdding: .day, value: -1, to: cursor) ?? cursor
        }
        var streak = 0
        while (proteinByDay[cursor] ?? 0) >= goal {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: cursor) else { break }
            cursor = previous
        }
        return streak
    }
}
