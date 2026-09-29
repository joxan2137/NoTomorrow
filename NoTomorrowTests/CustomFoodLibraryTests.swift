import XCTest
import SwiftData
@testable import NoTomorrow

/// Quick adds and AI estimates saved to the food library (`CustomFoodLibrary`).
@MainActor
final class CustomFoodLibraryTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                       configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    func testWeighedPortionIsStoredPer100Grams() throws {
        let item = try XCTUnwrap(CustomFoodLibrary.save(name: "  Owsianka ", grams: 250, kcal: 300, protein: 10,
                                                        carbs: 50, fat: 5, source: .quickAdd, in: context))
        XCTAssertTrue(item.id.hasPrefix("custom:"))
        XCTAssertEqual(item.name, "Owsianka")
        XCTAssertEqual(item.kcalPer100, 120, accuracy: 1e-9)
        XCTAssertEqual(item.proteinPer100, 4, accuracy: 1e-9)
        XCTAssertEqual(item.carbsPer100, 20, accuracy: 1e-9)
        XCTAssertEqual(item.fatPer100, 2, accuracy: 1e-9)
        XCTAssertEqual(item.servingSizeG, 250)
        XCTAssertEqual(item.useCount, 1)
        XCTAssertNotNil(item.lastUsedAt)
    }

    func testPortionWithoutWeightCountsAs100Grams() throws {
        let item = try XCTUnwrap(CustomFoodLibrary.save(name: "Kebab", grams: 0, kcal: 650, protein: 30, carbs: 60,
                                                        fat: 30, source: .quickAdd, in: context))
        XCTAssertEqual(item.kcalPer100, 650)
        XCTAssertNil(item.servingSizeG)
    }

    func testSameNameRefreshesInsteadOfDuplicating() throws {
        CustomFoodLibrary.save(name: "Żurek", grams: 0, kcal: 200, protein: 0, carbs: 0, fat: 0,
                               source: .quickAdd, in: context)
        let again = try XCTUnwrap(CustomFoodLibrary.save(name: "zurek", grams: 0, kcal: 250, protein: 0, carbs: 0,
                                                         fat: 0, source: .quickAdd, in: context))
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FoodItem>()), 1)
        XCTAssertEqual(again.kcalPer100, 250)
        XCTAssertEqual(again.useCount, 2)
    }

    func testEstimateKeepsFiguresTheUserTyped() throws {
        CustomFoodLibrary.save(name: "Pierogi", grams: 100, kcal: 190, protein: 6, carbs: 29, fat: 5,
                               source: .quickAdd, in: context)
        let item = try XCTUnwrap(CustomFoodLibrary.save(name: "Pierogi", grams: 210, kcal: 420, protein: 14,
                                                        carbs: 63, fat: 13, source: .aiEstimate, in: context))
        XCTAssertEqual(item.source, .quickAdd)
        XCTAssertEqual(item.kcalPer100, 190)
        XCTAssertEqual(item.useCount, 2)
    }

    func testProductsWithTheSameNameAreLeftAlone() throws {
        let product = FoodItem(id: "off:590", name: "Skyr", source: .openFoodFacts, barcode: "590",
                               kcalPer100: 60, proteinPer100: 11, carbsPer100: 4, fatPer100: 0)
        context.insert(product)
        CustomFoodLibrary.save(name: "Skyr", grams: 150, kcal: 100, protein: 16, carbs: 6, fat: 0,
                               source: .aiEstimate, in: context)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FoodItem>()), 2)
        XCTAssertEqual(product.kcalPer100, 60)
    }

    func testBackfillSavesPastQuickAddsAndEstimatesOnce() throws {
        let earlier = Date(timeIntervalSince1970: 1_000)
        let later = Date(timeIntervalSince1970: 2_000)
        let old = MealEntry(day: earlier, slot: .lunch, customName: "Kebab", grams: 0, kcal: 600, proteinG: 30,
                            carbsG: 50, fatG: 30)
        old.loggedAt = earlier
        let newer = MealEntry(day: later, slot: .dinner, customName: "kebab ", grams: 0, kcal: 650, proteinG: 30,
                              carbsG: 60, fatG: 30)
        newer.loggedAt = later
        let estimate = MealEntry(day: later, slot: .dinner, customName: "Pierogi", grams: 210, kcal: 420, proteinG: 14,
                                 carbsG: 63, fatG: 13, isAIEstimate: true, confidence: 0.6)
        let product = FoodItem(id: "off:590", name: "Skyr", source: .openFoodFacts, barcode: "590",
                               kcalPer100: 60, proteinPer100: 11, carbsPer100: 4, fatPer100: 0)
        let logged = MealEntry(day: later, slot: .snack, food: product, grams: 150, kcal: 90, proteinG: 16.5,
                               carbsG: 6, fatG: 0)
        [old, newer, estimate, logged].forEach { context.insert($0) }
        context.insert(product)

        XCTAssertEqual(CustomFoodLibrary.backfill(in: context), 2)
        let saved = try context.fetch(FetchDescriptor<FoodItem>()).filter { $0.id.hasPrefix("custom:") }
        let kebab = try XCTUnwrap(saved.first { $0.source == .quickAdd })
        XCTAssertEqual(kebab.name, "kebab")
        XCTAssertEqual(kebab.kcalPer100, 650, "the latest row's figures")
        XCTAssertEqual(kebab.useCount, 2)
        XCTAssertEqual(kebab.lastUsedAt, later)
        let pierogi = try XCTUnwrap(saved.first { $0.source == .aiEstimate })
        XCTAssertEqual(pierogi.kcalPer100, 200, accuracy: 1e-9)
        XCTAssertEqual(pierogi.servingSizeG, 210)

        XCTAssertEqual(CustomFoodLibrary.backfill(in: context), 0, "a second run adds nothing")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FoodItem>()), 3)
    }

    func testBlankNameSavesNothing() throws {
        XCTAssertNil(CustomFoodLibrary.save(name: "  ", grams: 0, kcal: 100, protein: 0, carbs: 0, fat: 0,
                                            source: .quickAdd, in: context))
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FoodItem>()), 0)
    }
}
