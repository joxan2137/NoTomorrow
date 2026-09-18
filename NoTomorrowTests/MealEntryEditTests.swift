import XCTest
import SwiftData
@testable import NoTomorrow

/// Editing an already-logged entry: food-backed rows re-derive macros from the food, custom rows take the user's figures.
@MainActor
final class MealEntryEditTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private func oats() -> FoodItem {
        let food = FoodItem(id: "custom:oats", name: "Oats", source: .custom,
                            kcalPer100: 380, proteinPer100: 13, carbsPer100: 66, fatPer100: 7)
        context.insert(food)
        return food
    }

    func testResizeFollowsFoodPer100() throws {
        let food = oats()
        let entry = MealEntry(day: .now, slot: .breakfast, food: food, grams: 50,
                              kcal: 190, proteinG: 6.5, carbsG: 33, fatG: 3.5)
        context.insert(entry)
        let loggedAt = entry.loggedAt

        entry.resize(to: 80)

        XCTAssertEqual(entry.grams, 80)
        XCTAssertEqual(entry.kcal, 304, accuracy: 0.001)
        XCTAssertEqual(entry.proteinG, 10.4, accuracy: 0.001)
        XCTAssertEqual(entry.carbsG, 52.8, accuracy: 0.001)
        XCTAssertEqual(entry.fatG, 5.6, accuracy: 0.001)
        XCTAssertEqual(entry.loggedAt, loggedAt, "row order must survive an edit")
    }

    func testResizeIgnoresCustomEntries() {
        let entry = MealEntry(day: .now, slot: .lunch, customName: "Soup", grams: 0,
                              kcal: 120, proteinG: 4, carbsG: 10, fatG: 6)
        context.insert(entry)

        entry.resize(to: 300)

        XCTAssertEqual(entry.grams, 0)
        XCTAssertEqual(entry.kcal, 120)
    }

    func testOverwriteDropsAIFlagWhenFiguresChange() {
        let entry = MealEntry(day: .now, slot: .dinner, customName: "Rice", grams: 150,
                              kcal: 195, proteinG: 4, carbsG: 42, fatG: 0.5, isAIEstimate: true, confidence: 0.7)
        context.insert(entry)

        entry.overwrite(name: "Rice", grams: 150, kcal: 210, proteinG: 4, carbsG: 45, fatG: 0.5)

        XCTAssertFalse(entry.isAIEstimate)
        XCTAssertNil(entry.confidence)
        XCTAssertEqual(entry.kcal, 210)
        XCTAssertEqual(entry.carbsG, 45)
    }

    func testOverwriteKeepsAIFlagOnRenameOnly() {
        let entry = MealEntry(day: .now, slot: .snack, customName: "Aple", grams: 120,
                              kcal: 62, proteinG: 0.3, carbsG: 15, fatG: 0.2, isAIEstimate: true, confidence: 0.9)
        context.insert(entry)

        entry.overwrite(name: "Apple", grams: 120, kcal: 62, proteinG: 0.3, carbsG: 15, fatG: 0.2)

        XCTAssertEqual(entry.displayName, "Apple")
        XCTAssertTrue(entry.isAIEstimate)
        XCTAssertEqual(entry.confidence, 0.9)
    }

    func testMovingSlotKeepsDayTotals() throws {
        let entry = MealEntry(day: .now, slot: .breakfast, customName: "Toast", grams: 0,
                              kcal: 150, proteinG: 5, carbsG: 25, fatG: 3)
        context.insert(entry)
        try context.save()
        let model = FuelModel()
        model.refresh(in: context)
        XCTAssertEqual(model.entries(for: .breakfast).count, 1)

        entry.slot = .lunch
        try context.save()
        model.refresh(in: context)

        XCTAssertTrue(model.entries(for: .breakfast).isEmpty)
        XCTAssertEqual(model.entries(for: .lunch).count, 1)
        XCTAssertEqual(model.kcalEaten, 150, accuracy: 0.001)
    }
}
