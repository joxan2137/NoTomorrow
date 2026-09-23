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

    // MARK: - Moving to another day

    func testMoveNormalisesToStartOfDayAndKeepsLoggedAt() throws {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        let entry = MealEntry(day: today, slot: .lunch, customName: "Pierogi", grams: 250,
                              kcal: 612.4, proteinG: 21.3, carbsG: 80.2, fatG: 22.7, isAIEstimate: true, confidence: 0.8)
        context.insert(entry)
        try context.save()
        let loggedAt = entry.loggedAt
        let twoDaysAgoAfternoon = calendar.date(byAdding: .day, value: -2, to: today)!.addingTimeInterval(15 * 3600)

        entry.move(to: twoDaysAgoAfternoon)
        try context.save()

        XCTAssertEqual(entry.day, calendar.startOfDay(for: twoDaysAgoAfternoon))
        XCTAssertEqual(entry.loggedAt, loggedAt, "the clock time is not shown anywhere; the row keeps its order")
        XCTAssertEqual(entry.slot, .lunch)
        XCTAssertEqual(entry.kcal, 612.4)
        XCTAssertTrue(entry.isAIEstimate, "a move is not an edit of the figures")
        XCTAssertEqual(entry.confidence, 0.8)

        let model = FuelModel()
        model.refresh(in: context)
        XCTAssertTrue(model.entries.isEmpty, "gone from today")
        model.go(to: twoDaysAgoAfternoon)
        model.refresh(in: context)
        XCTAssertEqual(model.entries(for: .lunch).map(\.id), [entry.id])
    }

    func testMoveToTheSameListedDayKeepsAZoneShiftedMidnight() {
        let today = Calendar.current.startOfDay(for: .now)
        let entry = MealEntry(day: today, slot: .snack, customName: "Jabłko", grams: 0,
                              kcal: 80, proteinG: 0, carbsG: 20, fatG: 0)
        entry.day = today.addingTimeInterval(-3600)   // logged an hour further east

        entry.move(to: today.addingTimeInterval(9 * 3600))

        XCTAssertEqual(entry.day, today.addingTimeInterval(-3600))
    }

    // MARK: - Edit sheet prefills (quick-add / AI rows)

    /// The AI rounding bug: saving an AI row with nothing typed must keep its badge and exact numbers.
    func testUntouchedPrefillsKeepExactAIFigures() {
        let entry = MealEntry(day: .now, slot: .dinner, customName: "Kotlet schabowy", grams: 187.36,
                              kcal: 1234.4567, proteinG: 12.345, carbsG: 30.06, fatG: 7.77, isAIEstimate: true, confidence: 0.62)
        context.insert(entry)
        let texts = entry.editTexts(locale: Locale(identifier: "pl_PL"))
        XCTAssertEqual(texts, MealEntry.EditTexts(grams: "187,4", kcal: "1234,5", protein: "12,3", carbs: "30,1", fat: "7,8"))

        let figure = FuelText.editedFigure
        entry.overwrite(name: "Schabowy",
                        grams: figure(texts.grams, texts.grams, entry.grams)!,
                        kcal: figure(texts.kcal, texts.kcal, entry.kcal)!,
                        proteinG: figure(texts.protein, texts.protein, entry.proteinG)!,
                        carbsG: figure(texts.carbs, texts.carbs, entry.carbsG)!,
                        fatG: figure(texts.fat, texts.fat, entry.fatG)!)

        XCTAssertEqual(entry.displayName, "Schabowy")
        XCTAssertTrue(entry.isAIEstimate)
        XCTAssertEqual(entry.confidence, 0.62)
        XCTAssertEqual(entry.kcal, 1234.4567)
        XCTAssertEqual(entry.grams, 187.36)
        XCTAssertEqual(entry.fatG, 7.77)
    }

    func testTypedFigureReplacesTheOriginal() {
        XCTAssertEqual(FuelText.editedFigure("1250", prefill: "1234,5", original: 1234.4567), 1250)
        XCTAssertEqual(FuelText.editedFigure("12,5", prefill: "12,3", original: 12.345), 12.5)
        XCTAssertEqual(FuelText.editedFigure("1234.5", prefill: "1234,5", original: 1234.4567), 1234.5,
                       "retyping the same number with a dot is an edit")
        XCTAssertNil(FuelText.editedFigure("", prefill: "7,8", original: 7.77))
        XCTAssertNil(FuelText.editedFigure("-5", prefill: "7,8", original: 7.77))
    }

    func testQuickAddRowWithoutPortionPrefillsNoGrams() {
        let entry = MealEntry(day: .now, slot: .snack, customName: "Baton", grams: 0,
                              kcal: 230, proteinG: 4, carbsG: 30, fatG: 10)
        XCTAssertEqual(entry.editTexts(locale: Locale(identifier: "en_US")),
                       MealEntry.EditTexts(grams: "", kcal: "230", protein: "4", carbs: "30", fat: "10"))
    }

    // MARK: - Saving the quick-add edit sheet

    /// AI logging keeps 0 kcal rows that have a portion (a glass of water). Their edit sheet must still save a move.
    func testZeroKcalAIRowMovedToAnotherDaySaves() throws {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let water = MealEntry(day: today, slot: .snack, customName: "Woda", grams: 250, kcal: 0, proteinG: 0,
                              carbsG: 0, fatG: 0, isAIEstimate: true, confidence: 0.9)
        context.insert(water)
        try context.save()
        let prefill = water.editTexts(locale: Locale(identifier: "pl_PL"))

        XCTAssertTrue(water.canSaveEdit(name: "Woda", kcalText: prefill.kcal, prefill: prefill))
        XCTAssertTrue(water.applyEdit(name: "Woda", texts: prefill, prefill: prefill, figuresUntouched: true,
                                      slot: .breakfast, day: yesterday.addingTimeInterval(15 * 3600)))
        try context.save()

        XCTAssertEqual(water.day, yesterday)
        XCTAssertEqual(water.slot, .breakfast)
        XCTAssertEqual(water.kcal, 0)
        XCTAssertEqual(water.grams, 250)
        XCTAssertTrue(water.isAIEstimate, "a move is not an edit of the figures")
        let model = FuelModel()
        model.go(to: yesterday)
        model.refresh(in: context)
        XCTAssertEqual(model.entries(for: .breakfast).map(\.id), [water.id])
    }

    func testEditSaveNeedsANameAndAKcalFigure() {
        let entry = MealEntry(day: .now, slot: .lunch, customName: "Zupa", grams: 0, kcal: 180, proteinG: 6,
                              carbsG: 20, fatG: 8)
        let prefill = entry.editTexts(locale: Locale(identifier: "pl_PL"))

        XCTAssertTrue(entry.canSaveEdit(name: "Zupa", kcalText: "0", prefill: prefill), "0 kcal is a figure")
        XCTAssertFalse(entry.canSaveEdit(name: "   ", kcalText: prefill.kcal, prefill: prefill))
        XCTAssertFalse(entry.canSaveEdit(name: "Zupa", kcalText: "", prefill: prefill))
        XCTAssertFalse(entry.canSaveEdit(name: "Zupa", kcalText: "abc", prefill: prefill))

        var texts = prefill
        texts.kcal = ""
        XCTAssertFalse(entry.applyEdit(name: "Rosół", texts: texts, prefill: prefill, figuresUntouched: false,
                                       slot: .dinner, day: .now))
        XCTAssertEqual(entry.displayName, "Zupa", "nothing is written")
        XCTAssertEqual(entry.slot, .lunch)

        texts.kcal = "210"
        XCTAssertTrue(entry.applyEdit(name: " Rosół ", texts: texts, prefill: prefill, figuresUntouched: false,
                                      slot: .dinner, day: .now))
        XCTAssertEqual(entry.displayName, "Rosół")
        XCTAssertEqual(entry.kcal, 210)
        XCTAssertEqual(entry.proteinG, 6, "untouched macros keep their figures")
        XCTAssertEqual(entry.slot, .dinner)
    }

    // MARK: - Rescale when the grams of an AI / quick-add row change

    func testFiguresFollowTheGramsProportionally() throws {
        let entry = MealEntry(day: .now, slot: .dinner, customName: "Kotlet schabowy", grams: 150,
                              kcal: 390, proteinG: 33, carbsG: 15, fatG: 22.5, isAIEstimate: true, confidence: 0.6)
        let figures = try XCTUnwrap(entry.figures(atGrams: 120))
        XCTAssertEqual(figures.kcal, 312, accuracy: 1e-9)
        XCTAssertEqual(figures.protein, 26.4, accuracy: 1e-9)
        XCTAssertEqual(figures.carbs, 12, accuracy: 1e-9)
        XCTAssertEqual(figures.fat, 18, accuracy: 1e-9)
        XCTAssertNil(entry.figures(atGrams: 0))
        XCTAssertNil(MealEntry(day: .now, slot: .snack, customName: "Baton", grams: 0, kcal: 230, proteinG: 4,
                               carbsG: 30, fatG: 10).figures(atGrams: 50), "no portion, nothing to scale from")
    }

    func testRescaledTextsFollowTheGramsField() {
        let pl = Locale(identifier: "pl_PL")
        let entry = MealEntry(day: .now, slot: .dinner, customName: "Ryż", grams: 187.36,
                              kcal: 243.568, proteinG: 5.06, carbsG: 52.84, fatG: 0.56, isAIEstimate: true, confidence: 0.6)
        let prefill = entry.editTexts(locale: pl)
        XCTAssertEqual(entry.rescaledTexts(gramsText: "250", prefill: prefill, locale: pl),
                       MealEntry.EditTexts(grams: "250", kcal: "325", protein: "6,8", carbs: "70,5", fat: "0,7"))
        XCTAssertEqual(entry.rescaledTexts(gramsText: prefill.grams, prefill: prefill, locale: pl), prefill,
                       "back at the original grams: the original texts, so the exact figures are kept")
        XCTAssertNil(entry.rescaledTexts(gramsText: "", prefill: prefill, locale: pl))
        XCTAssertNil(entry.rescaledTexts(gramsText: "0", prefill: prefill, locale: pl))
    }

    func testTypedFiguresStopTheRescale() {
        let written = MealEntry.EditTexts(grams: "150", kcal: "390", protein: "33", carbs: "15", fat: "22,5")
        var typed = written
        typed.grams = "120"
        XCTAssertTrue(typed.sameFigures(as: written), "only the grams changed")
        typed.kcal = "350"
        XCTAssertFalse(typed.sameFigures(as: written))
    }

    // MARK: - Snapshot / copy

    func testSnapshotRestoresEveryStoredProperty() {
        let food = oats()
        let entry = MealEntry(day: .now, slot: .breakfast, food: food, grams: 60,
                              kcal: 228, proteinG: 7.8, carbsG: 39.6, fatG: 4.2, isAIEstimate: true, confidence: 0.4)
        entry.day = entry.day.addingTimeInterval(-3600)
        entry.loggedAt = Date(timeIntervalSince1970: 1_790_000_000)
        context.insert(entry)

        let restored = entry.snapshot.restore()

        XCTAssertEqual(restored.id, entry.id)
        XCTAssertEqual(restored.day, entry.day, "the stored midnight is kept exactly, not re-normalised")
        XCTAssertEqual(restored.loggedAt, entry.loggedAt)
        XCTAssertEqual(restored.slot, .breakfast)
        XCTAssertTrue(restored.food === food)
        XCTAssertNil(restored.customName)
        XCTAssertEqual(restored.grams, 60)
        XCTAssertEqual(restored.kcal, 228)
        XCTAssertEqual(restored.proteinG, 7.8)
        XCTAssertEqual(restored.carbsG, 39.6)
        XCTAssertEqual(restored.fatG, 4.2)
        XCTAssertTrue(restored.isAIEstimate)
        XCTAssertEqual(restored.confidence, 0.4)
    }

    func testCopyIsANewEntryWithTheSameFigures() {
        let entry = MealEntry(day: .now.addingTimeInterval(-3 * 86_400), slot: .dinner, customName: "Zupa pomidorowa",
                              grams: 350, kcal: 245.6, proteinG: 6.1, carbsG: 30.2, fatG: 10.3, isAIEstimate: true, confidence: 0.7)
        let today = Calendar.current.startOfDay(for: .now)
        let at = Date()

        let copy = entry.copy(to: today, loggedAt: at)

        XCTAssertNotEqual(copy.id, entry.id)
        XCTAssertEqual(copy.day, today)
        XCTAssertEqual(copy.loggedAt, at)
        XCTAssertEqual(copy.slot, .dinner)
        XCTAssertEqual(copy.displayName, "Zupa pomidorowa")
        XCTAssertEqual(copy.grams, 350)
        XCTAssertEqual(copy.kcal, 245.6)
        XCTAssertTrue(copy.isAIEstimate)
        XCTAssertEqual(copy.confidence, 0.7)
    }
}
