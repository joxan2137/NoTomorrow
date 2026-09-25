import XCTest
import SwiftData
@testable import NoTomorrow

/// The widgets' pure rules (`docs/widgets.md`) and the snapshot the app publishes for them. Android's
/// `QuickFoodsTest` / `BreakPresetsTest` assert the same cases.
@MainActor
final class WidgetTests: XCTestCase {

    private func candidate(_ foodID: String?, _ name: String, kcal: Double = 100, minutesAgo: Double,
                           now: Date) -> QuickFood.Candidate {
        QuickFood.Candidate(foodID: foodID, name: name, grams: 100, kcal: kcal, protein: 1, carbs: 2, fat: 3,
                            isAIEstimate: false, loggedAt: now.addingTimeInterval(-minutesAgo * 60))
    }

    // MARK: - Quick foods

    func testQuickFoodsRankByCountThenRecency() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let picked = QuickFood.pick([
            candidate("off:1", "Skyr", minutesAgo: 300, now: now),
            candidate("off:1", "Skyr", kcal: 186, minutesAgo: 10, now: now),
            candidate("off:2", "Oats", minutesAgo: 5, now: now),
            candidate(nil, "Owsianka ", minutesAgo: 400, now: now),
            candidate(nil, "owsianka", minutesAgo: 200, now: now),
            candidate(nil, "Kurczak", minutesAgo: 1, now: now),
        ])
        XCTAssertEqual(picked.map(\.key), ["food:off:1", "name:owsianka", "name:kurczak", "food:off:2"])
        XCTAssertEqual(picked[0].kcal, 186, "the newest entry's figures")
        XCTAssertEqual(picked[1].name, "owsianka", "the newest entry's name, trimmed")
        XCTAssertNil(picked[1].foodID)
    }

    func testQuickFoodsFoldAndLimit() {
        XCTAssertEqual(QuickFood.fold("  Łosoś   WĘDZONY "), "losos wedzony")
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let many = (0..<6).map { candidate("id\($0)", "F\($0)", minutesAgo: Double($0), now: now) }
        XCTAssertEqual(QuickFood.pick(many).map(\.key), ["food:id0", "food:id1", "food:id2", "food:id3"])
        XCTAssertTrue(QuickFood.pick([candidate(nil, "   ", minutesAgo: 1, now: now)]).isEmpty,
                      "a nameless custom entry is nothing to log again")
    }

    // MARK: - Break presets

    func testBreakPresets() {
        XCTAssertEqual(BreakPresets.lengths(default: 90), [60, 90, 120])
        XCTAssertEqual(BreakPresets.lengths(default: 60), [60, 90, 120])
        XCTAssertEqual(BreakPresets.lengths(default: 120), [60, 90, 120])
        XCTAssertEqual(BreakPresets.lengths(default: 75), [60, 75, 120])
        XCTAssertEqual(BreakPresets.lengths(default: 150), [60, 120, 150])
    }

    // MARK: - Heat

    func testFuelHeatIsTheCalendarScale() {
        for goal in TrainingGoal.allCases {
            let bands = FuelCalendar.tolerance(for: goal)
            for kcal in stride(from: 0.0, through: 4000, by: 37) {
                for isToday in [false, true] {
                    XCTAssertEqual(
                        FuelHeat.level(kcalEaten: kcal, kcalGoal: 2600, under: bands.under, over: bands.over,
                                       hasEntries: true, isToday: isToday),
                        FuelCalendar.level(kcalEaten: kcal, kcalGoal: 2600, goal: goal, hasEntries: true, isToday: isToday))
                }
            }
        }
    }

    // MARK: - Snapshot

    func testSnapshotFromStore() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        let now = Date.now
        let cal = Calendar.current
        context.insert(UserProfile(name: "Kuba", goal: .maintain, calorieGoal: 2400))
        context.insert(GymSchedule(weekdays: [1, 3, 5], defaultMinuteOfDay: 18 * 60))
        let food = FoodItem(id: "off:590", name: "Skyr", source: .openFoodFacts,
                            kcalPer100: 62, proteinPer100: 11, carbsPer100: 4, fatPer100: 0.2)
        context.insert(food)
        for _ in 0..<2 {
            context.insert(MealEntry(day: now, slot: .breakfast, food: food, grams: 300,
                                     kcal: 186, proteinG: 33, carbsG: 12, fatG: 0.6))
        }
        let workout = Workout(name: "Push A", startedAt: now.addingTimeInterval(-3600))
        workout.endedAt = now
        context.insert(workout)
        let bench = Exercise(id: "bench", name: "Bench", primaryMuscles: ["chest"])
        context.insert(bench)
        let entry = WorkoutExercise(order: 0, exercise: bench)
        context.insert(entry)
        entry.workout = workout
        let set = SetEntry(order: 0, weightKg: 80, reps: 8)
        context.insert(set)
        set.completedAt = now
        set.workoutExercise = entry
        try context.save()

        let snapshot = WidgetSync.makeSnapshot(from: context, now: now)
        XCTAssertEqual(snapshot.fuel.kcal, 372, accuracy: 0.001)
        XCTAssertEqual(snapshot.fuel.kcalGoal, 2400)
        XCTAssertEqual(snapshot.quickFoods.map(\.key), ["food:off:590"])
        XCTAssertEqual(snapshot.calendar.days.last?.date, cal.startOfDay(for: now))
        XCTAssertEqual(snapshot.calendar.days.last?.kcal ?? 0, 372, accuracy: 0.001)
        XCTAssertEqual(snapshot.calendar.days.last?.trained, true)
        XCTAssertEqual(snapshot.calendar.sessions30, 1)
        XCTAssertEqual(snapshot.calendar.under, FuelCalendar.tolerance(for: .maintain).under)
        XCTAssertEqual(snapshot.week.days.count, 7)
        XCTAssertEqual(snapshot.week.gymMinutes, [1: 1080, 3: 1080, 5: 1080])
    }
}
