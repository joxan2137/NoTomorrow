import XCTest
import SwiftData
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import NoTomorrow

/// Correcting an AI estimate before logging: rescaling from per-100 g values, the count stepper, remove / rename /
/// replace / add, the corrections line a refine sends, and a refine that keeps the user's fixes.
@MainActor
final class AIScanCorrectionTests: XCTestCase {
    private func pierogi() -> AIFood {
        .mock("Pierogi ruskie", AIPer100(kcal: 200, protein: 6.5, carbs: 30, fat: 6), count: 6, unit: "szt.", perUnit: 35,
              confidence: 0.65)
    }

    private func oil() -> AIFood {
        .mock("Olej", AIPer100(kcal: 884, protein: 0, carbs: 0, fat: 100), count: 1, unit: "łyżka", perUnit: 10,
              confidence: 0.3, isGuess: true)
    }

    // MARK: Rescaling

    func testScaledRecomputesFromPer100() {
        let food = pierogi().scaled(toGrams: 262.46)
        XCTAssertEqual(food.grams, 262.5)
        XCTAssertEqual(food.kcal, 525)
        XCTAssertEqual(food.protein, 17.1)
        XCTAssertEqual(food.carbs, 78.8)
        XCTAssertEqual(food.fat, 15.8)
        XCTAssertEqual(food.portionCount, 6, "the count stays")
        XCTAssertEqual(food.gramsPerUnit, 43.8, "the unit weight follows")
    }

    func testScaledWithoutPer100IsProportional() {
        let old = AIFood(name: "Rice", grams: 200, kcal: 260, protein: 5, carbs: 56, fat: 1, confidence: 0.7)
        let food = old.scaled(toGrams: 300)
        XCTAssertEqual(food.kcal, 390, accuracy: 1e-9)
        XCTAssertEqual(food.carbs, 84, accuracy: 1e-9)
        XCTAssertEqual(old.withCount(2).grams, 400, accuracy: 1e-9, "a v1 item counts as one unit of its grams")
    }

    func testWithCountKeepsTheUnitWeight() {
        let food = pierogi().withCount(8)
        XCTAssertEqual(food.portionCount, 8)
        XCTAssertEqual(food.gramsPerUnit, 35)
        XCTAssertEqual(food.grams, 280)
        XCTAssertEqual(food.kcal, 560)
        XCTAssertEqual(pierogi().withGramsPerUnit(40).grams, 240)
    }

    func testCountSteps() {
        XCTAssertEqual(AIScanCorrections.steppedCount(6, up: true), 7)
        XCTAssertEqual(AIScanCorrections.steppedCount(1, up: false), 0.5)
        XCTAssertEqual(AIScanCorrections.steppedCount(0.5, up: false), 0.5)
        XCTAssertEqual(AIScanCorrections.steppedCount(0.5, up: true), 1)
        XCTAssertEqual(AIScanCorrections.steppedCount(2.5, up: false), 1.5)
        XCTAssertEqual(AIScanCorrections.steppedCount(99, up: true), 99)
    }

    func testPortionBasis() {
        XCTAssertEqual(AIScanFormat.portionBasis(pierogi(), locale: Locale(identifier: "pl_PL")), "6 szt. × 35 g")
        XCTAssertNil(AIScanFormat.portionBasis(oil()), "a single unit needs no basis line")
    }

    // MARK: Corrections line and notes

    func testCorrectionsLine() {
        let line = AIScanCorrections.line(kept: [pierogi().withCount(8), AIFood.mock("Kompot", AIPer100(kcal: 60, protein: 0, carbs: 15, fat: 0), count: 1, unit: "", perUnit: 250.5, confidence: 1)],
                                          removed: ["Olej"])
        XCTAssertEqual(line, "User corrections (authoritative): Pierogi ruskie = 280 g (8 szt.); Kompot = 250.5 g; removed: Olej")
        XCTAssertNil(AIScanCorrections.line(kept: [], removed: []))
    }

    func testNotesStayWithinTheBackendLimitAndCutTheTypedPartFirst() {
        let line = "User corrections (authoritative): Pierogi = 280 g"
        XCTAssertEqual(AIScanCorrections.notes(typed: "  z okrasą ", corrections: line), "z okrasą\n" + line)
        XCTAssertEqual(AIScanCorrections.notes(typed: "", corrections: line), line)
        XCTAssertEqual(AIScanCorrections.notes(typed: " 150 g ", corrections: nil), "150 g")

        let long = String(repeating: "🍕", count: 1000)   // 2000 UTF-16 units
        let notes = AIScanCorrections.notes(typed: long, corrections: line)
        XCTAssertLessThanOrEqual(notes.utf16.count, 1500)
        XCTAssertTrue(notes.hasSuffix(line), "the corrections line is kept whole")
        XCTAssertLessThanOrEqual(AIScanCorrections.notes(typed: long, corrections: nil).utf16.count, 1500)
    }

    func testMergeKeepsCorrectionsAndRemovals() {
        var fixed = pierogi().withCount(8)
        fixed.name = "Pierogi z mięsem"
        let refined = [
            AIFood.mock("pierogi RUSKIE", AIPer100(kcal: 210, protein: 7, carbs: 30, fat: 7), count: 6, unit: "szt.", perUnit: 36, confidence: 0.6),
            AIFood.mock("Olej", AIPer100(kcal: 884, protein: 0, carbs: 0, fat: 100), count: 1, unit: "łyżka", perUnit: 10, confidence: 0.3),
            AIFood.mock("Surówka", AIPer100(kcal: 50, protein: 1, carbs: 10, fat: 0.5), count: 1, unit: "porcja", perUnit: 120, confidence: 0.6),
        ]
        let kompot = AIFood.mock("Kompot", AIPer100(kcal: 60, protein: 0, carbs: 15, fat: 0), count: 1, unit: "", perUnit: 250, confidence: 1)
        let merged = AIScanCorrections.merge(refined: refined,
                                             kept: [.init(food: fixed, names: ["Pierogi ruskie", "Pierogi z mięsem"]),
                                                    .init(food: kompot, names: ["Kompot"])],
                                             removed: ["olej"])
        XCTAssertEqual(merged.map(\.name), ["Pierogi z mięsem", "Surówka", "Kompot"])
        XCTAssertEqual(merged[0], fixed, "the user's item replaces the model's, figures included")
    }

    private func food(_ name: String, kcal: Double = 150, grams: Double = 100, key: String = "none") -> AIFood {
        var item = AIFood.mock(name, AIPer100(kcal: kcal, protein: 5, carbs: 20, fat: 5), count: 1, unit: "porcja",
                               perUnit: grams, confidence: 0.6)
        item.genericKey = key
        return item
    }

    func testMergeFindsARenamedItem() {
        let fixed = pierogi().withCount(8)
        let refined = [food("Pierogi ruskie z cebulką", kcal: 210, grams: 240), food("Surówka")]

        let merged = AIScanCorrections.merge(refined: refined, kept: [.init(food: fixed, names: ["Pierogi ruskie"])],
                                             removed: [])

        XCTAssertEqual(merged, [fixed, refined[1]], "the renamed item is replaced, not counted next to the user's")
    }

    func testMergeDropsTheModelsSplitOfAKeptItem() {
        let fixed = food("Ziemniaki z masłem", kcal: 120, grams: 250)
        let refined = [food("Ziemniaki", grams: 220), food("Masło", kcal: 740, grams: 10), food("Kotlet schabowy")]

        let merged = AIScanCorrections.merge(refined: refined, kept: [.init(food: fixed, names: ["Ziemniaki z masłem"])],
                                             removed: [])

        XCTAssertEqual(merged.map(\.name), ["Ziemniaki z masłem", "Kotlet schabowy"])
        XCTAssertEqual(merged.reduce(0) { $0 + $1.kcal }, fixed.kcal + refined[2].kcal, accuracy: 0.001)
    }

    func testMergeMatchesByGenericKey() {
        let fixed = food("Schabowy", grams: 180, key: "pork_chop_breaded")
        let refined = [food("Kotlet panierowany", grams: 150, key: "pork_chop_breaded"), food("Kapusta zasmażana")]

        let merged = AIScanCorrections.merge(refined: refined, kept: [.init(food: fixed, names: ["Schabowy"])],
                                             removed: [])

        XCTAssertEqual(merged.map(\.name), ["Schabowy", "Kapusta zasmażana"])
    }

    func testRenamedRemovedItemStaysRemoved() {
        let refined = [food("Pierogi ruskie"), food("Olej rzepakowy", kcal: 884, grams: 10), food("Sałatka z olejem"),
                       food("Oliwa z oliwek", kcal: 884, grams: 10, key: "olive_oil")]

        let merged = AIScanCorrections.merge(refined: refined, kept: [], removed: ["Olej", "Oliwa"],
                                             removedKeys: ["olive_oil"])

        XCTAssertEqual(merged.map(\.name), ["Pierogi ruskie", "Sałatka z olejem"],
                       "a dish that only mentions the removed food is a different item")
    }

    func testMergeKeepsDifferentFoodsApart() {
        let fixed = food("Kurczak", grams: 150)
        let refined = [food("Kurczak", grams: 120), food("Sos do kurczaka"), food("Pierogi z mięsem"), food("Ser żółty")]

        let merged = AIScanCorrections.merge(refined: refined,
                                             kept: [.init(food: fixed, names: ["Kurczak"]),
                                                    .init(food: food("Serek wiejski"), names: ["Serek wiejski"])],
                                             removed: ["Pierogi ruskie"])

        XCTAssertEqual(merged.map(\.name), ["Kurczak", "Sos do kurczaka", "Pierogi z mięsem", "Ser żółty", "Serek wiejski"])
    }

    func testNameMatchingRules() {
        XCTAssertTrue(AIScanCorrections.sameFood("Pierogi ruskie", "pierogi RUSKIE z cebulką"))
        XCTAssertTrue(AIScanCorrections.sameFood("Olej", "Olej rzepakowy"))
        XCTAssertFalse(AIScanCorrections.sameFood("Olej", "Sałatka z olejem"))
        XCTAssertFalse(AIScanCorrections.sameFood("Pierogi ruskie", "Pierogi z mięsem"))
        XCTAssertTrue(AIScanCorrections.isPart("Masło", of: "Ziemniaki z masłem"))
        XCTAssertFalse(AIScanCorrections.isPart("Sos do kurczaka", of: "Kurczak"))
        XCTAssertTrue(AIScanCorrections.sameWord("ziemniaki", "ziemniakami"))
        XCTAssertFalse(AIScanCorrections.sameWord("serek", "sernik"))
        XCTAssertFalse(AIScanCorrections.sameWord("ser", "sery"))
        XCTAssertEqual(AIScanCorrections.words("Ziemniaki z masłem, 2 łyżki"), ["ziemniaki", "maslem", "lyzki"])
    }

    func testRemovingAModelItemRemembersItsGenericKey() {
        let o = food("Oliwa", kcal: 884, grams: 10, key: "olive_oil")
        let model = model(with: [pierogi(), o])
        model.remove(o.id)
        XCTAssertEqual(model.removedNames, ["Oliwa"])
        XCTAssertEqual(model.removedKeys, ["olive_oil"])
    }

    // MARK: Model

    private func model(with foods: [AIFood], service: (any AIEstimateService)? = nil) -> AIScanModel {
        let model = AIScanModel(meal: .dinner, defaults: UserDefaults(suiteName: "AIScanCorrectionTests")!,
                                service: service ?? StubEstimateService(answers: []))
        model.foods = foods
        model.phase = .result
        return model
    }

    func testRemoveAndEditsAreReportedOnRefine() {
        let p = pierogi(), o = oil()
        let model = model(with: [p, o])
        model.stepCount(up: true, for: p.id)
        XCTAssertEqual(model.foods[0].grams, 245)
        model.remove(o.id)
        XCTAssertEqual(model.foods.count, 1)
        XCTAssertEqual(model.correctionsLine, "User corrections (authoritative): Pierogi ruskie = 245 g (7 szt.); removed: Olej")
        model.notes = "z okrasą"
        XCTAssertEqual(model.outgoingNotes(refining: true), "z okrasą\n" + model.correctionsLine!)
        XCTAssertEqual(model.outgoingNotes(refining: false), "z okrasą")
    }

    func testOpeningTheEditorWithoutChangesIsNoCorrection() {
        let p = pierogi()
        let model = model(with: [p])
        model.update(p)
        XCTAssertNil(model.correctionsLine)
    }

    func testRemovingAnAddedItemTellsTheModelNothing() {
        let model = model(with: [pierogi()])
        let added = AIFood.mock("Kompot", AIPer100(kcal: 60, protein: 0, carbs: 15, fat: 0), count: 1, unit: "", perUnit: 250, confidence: 1)
        model.append(added)
        XCTAssertEqual(model.correctionsLine, "User corrections (authoritative): Kompot = 250 g")
        model.remove(added.id)
        XCTAssertNil(model.correctionsLine)
    }

    func testRenameKeepsTheModelsNameForMatching() {
        let p = pierogi()
        let model = model(with: [p])
        var renamed = p
        renamed.name = "Pierogi z mięsem"
        model.update(renamed)
        XCTAssertEqual(model.kept[p.id], ["Pierogi ruskie", "Pierogi z mięsem"])
    }

    /// A model that went through the real intake (photo → downscale → first answer) and shows the result.
    private func analysed(_ answers: [Result<AIEstimate, Error>]) async throws -> (AIScanModel, StubEstimateService) {
        let service = StubEstimateService(answers: answers)
        let model = AIScanModel(meal: .dinner, defaults: UserDefaults(suiteName: "AIScanCorrectionTests")!, service: service)
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 30)).image { context in
            UIColor.gray.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 30))
        }
        model.handlePicked(.image(photo))
        try await waitUntil { service.notes.count == 1 && model.phase != .analyzing }
        return (model, service)
    }

    func testRefineKeepsTheUsersCorrections() async throws {
        let refinedAnswer = AIEstimate(foods: [
            .mock("Pierogi ruskie", AIPer100(kcal: 210, protein: 7, carbs: 30, fat: 7), count: 6, unit: "szt.", perUnit: 36, confidence: 0.6),
            .mock("Olej", AIPer100(kcal: 884, protein: 0, carbs: 0, fat: 100), count: 1, unit: "łyżka", perUnit: 10, confidence: 0.3),
            .mock("Śmietana", AIPer100(kcal: 185, protein: 2.5, carbs: 3.6, fat: 18), count: 1, unit: "łyżka", perUnit: 15, confidence: 0.4),
        ], overallConfidence: 0.6)
        let (model, service) = try await analysed([.success(AIEstimate(foods: [pierogi(), oil()], overallConfidence: 0.6)),
                                                   .success(refinedAnswer)])
        XCTAssertEqual(model.phase, .result)
        XCTAssertNotNil(model.image)
        let p = model.foods[0], o = model.foods[1]
        model.setGrams(280, for: p.id)
        model.remove(o.id)

        model.analyze()
        try await waitUntil { service.notes.count == 2 && model.phase == .result }
        XCTAssertEqual(model.foods.map(\.name), ["Pierogi ruskie", "Śmietana"])
        XCTAssertEqual(model.foods[0].grams, 280, "the corrected grams survive the refine")
        XCTAssertEqual(model.foods[0].id, p.id)
        XCTAssertEqual(service.notes, ["", "User corrections (authoritative): Pierogi ruskie = 280 g (6 szt.); removed: Olej"])
    }

    func testRemovingEveryItemLeavesNothingToLogOrRefine() async throws {
        let (model, service) = try await analysed([.success(AIEstimate(foods: [pierogi(), oil()], overallConfidence: 0.6))])
        XCTAssertTrue(model.hasItems)
        for food in model.foods { model.remove(food.id) }
        XCTAssertFalse(model.hasItems, "Log and Recalculate are disabled")

        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        XCTAssertEqual(model.log(into: container.mainContext), 0)
        XCTAssertTrue(try container.mainContext.fetch(FetchDescriptor<MealEntry>()).isEmpty)

        model.analyze()
        XCTAssertEqual(model.phase, .result, "a refine with nothing left is not sent")
        XCTAssertEqual(service.notes.count, 1)
    }

    func testFailedRefineKeepsTheResult() async throws {
        let (model, service) = try await analysed([.success(AIEstimate(foods: [pierogi()], overallConfidence: 0.6)),
                                                   .failure(AIEstimateError.busy)])
        let before = model.foods
        model.analyze()
        try await waitUntil { service.notes.count == 2 && model.phase == .result && model.toast != nil }
        XCTAssertEqual(model.foods, before)
        XCTAssertEqual(model.toast, AIEstimateError.busy.errorDescription)
    }

    func testFirstAnalysisErrorsAreDistinct() async throws {
        let (model, _) = try await analysed([.failure(AIEstimateError.timeout)])
        XCTAssertEqual(model.phase, .failed(String(localized: "fuel.ai.error.timeout")))
    }

    func testNotAllowedShowsItsScreen() async throws {
        let (model, _) = try await analysed([.failure(AIEstimateError.notAllowed)])
        XCTAssertEqual(model.phase, .notAllowed)
    }

    func testEmptyAnswerIsNoFood() async throws {
        let (model, _) = try await analysed([.success(AIEstimate(foods: [], overallConfidence: 0))])
        XCTAssertEqual(model.phase, .failed(String(localized: "fuel.ai.failed")))
    }

    // MARK: Database picks

    func testReplaceTakesTheProductAndKeepsTheAmount() {
        let candidate = FoodCandidate(id: "off:5900000000001", code: "5900000000001", name: "Pierogi ruskie Biedronka",
                                      brand: "Biedronka", quantity: nil, servingSizeG: nil, servingLabel: nil,
                                      kcalPer100: 190, proteinPer100: 6, carbsPer100: 29, fatPer100: 5.5, fiberPer100: nil,
                                      imageURL: nil)
        let original = pierogi()
        let replaced = original.replaced(by: .candidate(candidate))
        XCTAssertEqual(replaced.id, original.id)
        XCTAssertEqual(replaced.name, "Pierogi ruskie Biedronka")
        XCTAssertEqual(replaced.grams, 210)
        XCTAssertEqual(replaced.portionCount, 6)
        XCTAssertEqual(replaced.kcal, 399)
        XCTAssertEqual(replaced.confidence, 1)
        XCTAssertEqual(replaced.nutritionSource, "database")
        XCTAssertEqual(replaced.databaseFood, .candidate(candidate))
    }

    func testLoggingWritesDatabasePicksAsFoodEntries() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        let saved = FoodItem(id: "custom:kompot", name: "Kompot", source: .custom,
                             kcalPer100: 60, proteinPer100: 0, carbsPer100: 15, fatPer100: 0)
        context.insert(saved)
        let model = model(with: [pierogi()])
        model.append(AIFood.fromDatabase(.item(saved), grams: 250))

        XCTAssertEqual(model.log(into: context), 2)
        let entries = try context.fetch(FetchDescriptor<MealEntry>())
        let ai = try XCTUnwrap(entries.first { $0.food == nil })
        XCTAssertTrue(ai.isAIEstimate)
        XCTAssertEqual(ai.kcal, 420)
        let picked = try XCTUnwrap(entries.first { $0.food != nil })
        XCTAssertFalse(picked.isAIEstimate)
        XCTAssertEqual(picked.food?.id, "custom:kompot")
        XCTAssertEqual(picked.kcal, 150, accuracy: 1e-9)
        XCTAssertEqual(saved.useCount, 1)
    }

    // MARK: Label fill

    func testLabelFillFillsFiguresAndOnlyAnEmptyName() {
        let reading = LabelReading(legible: true, energyFrom: "kcal", name: "Baton zbożowy", brand: "Sante",
                                   per100: .init(kcal: 400, protein: 10, carbs: 60, fat: 13, fiber: 5, sugar: 20, salt: 0.5),
                                   servingSizeG: 30, confidence: 0.85)
        let pl = Locale(identifier: "pl_PL")
        let fill = LabelFill.from(reading, currentName: "", locale: pl)
        XCTAssertEqual(fill, LabelFill(name: "Baton zbożowy", brand: "Sante", kcal: "400", protein: "10", carbs: "60",
                                       fat: "13", fiber: "5", serving: "30"))
        XCTAssertNil(LabelFill.from(reading, currentName: "Mój baton", locale: pl).name)

        var decimals = reading
        decimals.per100 = .init(kcal: 97.5, protein: 11, carbs: 2.5, fat: 5, fiber: nil, sugar: nil, salt: nil)
        XCTAssertEqual(LabelFill.from(decimals, currentName: "x", locale: pl).kcal, "97,5")
        XCTAssertNil(LabelFill.from(decimals, currentName: "x", locale: pl).fiber, "not printed: the field is left alone")
    }

    func testIllegibleLabelOnlyFillsTheName() {
        let reading = LabelReading(legible: false, unreadableReason: "illegible", name: "Serek", brand: "")
        XCTAssertEqual(LabelFill.from(reading, currentName: ""), LabelFill(name: "Serek"))
    }

    // MARK: Downscaling

    func testDataDownscaleShrinksAndStripsLocation() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 3000, height: 2000),
                                            format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { context in
                UIColor.orange.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 3000, height: 2000))
            }
        let out = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil))
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 52.23, kCGImagePropertyGPSLongitude: 21.01]
        CGImageDestinationAddImage(destination, try XCTUnwrap(image.cgImage), [kCGImagePropertyGPSDictionary: gps] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))

        let small = try XCTUnwrap(ImageDownscaler.jpegData(from: out as Data))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(small as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 1024)
        XCTAssertTrue((682...683).contains(properties[kCGImagePropertyPixelHeight] as? Int ?? 0))
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary])

        let label = try XCTUnwrap(ImageDownscaler.jpegData(from: out as Data, maxLongEdge: ImageDownscaler.labelLongEdge))
        XCTAssertEqual(UIImage(data: label)?.size.width, 1600)
        XCTAssertNil(ImageDownscaler.jpegData(from: Data("not an image".utf8)))
    }

    // MARK: Helpers

    private func waitUntil(timeout: TimeInterval = 5, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else { return XCTFail("timed out") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

/// Answers from a queue and records the notes each request sent.
private final class StubEstimateService: AIEstimateService {
    private var answers: [Result<AIEstimate, Error>]
    private(set) var notes: [String] = []

    init(answers: [Result<AIEstimate, Error>]) { self.answers = answers }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        self.notes.append(notes)
        guard !answers.isEmpty else { throw AIEstimateError.busy }
        return try answers.removeFirst().get()
    }

    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        throw AIEstimateError.busy
    }
}
