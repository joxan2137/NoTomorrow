import XCTest
@testable import NoTomorrow

final class FoodSearchTests: XCTestCase {
    func testPolishKilojouleLabel() throws {
        let data = Data(#"{"code":"5901234123457","product_name":"Skyr","product_name_pl":"Skyr naturalny","nutriments":{"energy_100g":"418,4","proteins_100g":10,"carbohydrates_100g":15,"fat_100g":0}}"#.utf8)
        let product = try JSONDecoder().decode(OFFProduct.self, from: data)
        let food = try XCTUnwrap(FoodSearchService.candidate(from: product, locale: "pl"))
        XCTAssertEqual(food.name, "Skyr naturalny")
        XCTAssertEqual(food.kcalPer100, 100, accuracy: 0.001)
    }

    func testServingMassAndVolume() {
        XCTAssertEqual(FoodSearchService.grams(fromLabel: "2 x 15 g"), 30)
        XCTAssertEqual(FoodSearchService.grams(fromLabel: "1 porcja (30 g)"), 30)
        XCTAssertEqual(FoodSearchService.grams(fromLabel: "0,5 kg"), 500)
        XCTAssertNil(FoodSearchService.grams(fromLabel: "250 ml"))
    }

    func testBarcodeForms() {
        XCTAssertEqual(FoodSearchService.barcodeForms("05901234123457"), ["05901234123457", "5901234123457"])
        XCTAssertTrue(FoodSearchService.barcodeForms("123").isEmpty)
        XCTAssertEqual(FoodSearchService.barcodeForms("590 1234 123457"), ["5901234123457"])
    }

    func testMuscleModelCoversLibrary() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "exercises", withExtension: "json"))
        let records = try JSONDecoder().decode([ExerciseLibrary.Record].self, from: Data(contentsOf: url))
        XCTAssertEqual(records.count, 989, "free-exercise-db plus the app's nt_ additions (ExerciseLibrary)")
        XCTAssertEqual(Set(records.map(\.id)).count, records.count)
        let represented = Set(ExerciseMedia.regions.map(\.muscle))
        for record in records {
            XCTAssertTrue(Set(record.primaryMuscles + record.secondaryMuscles).isSubset(of: represented), record.id)
        }
    }
}

/// The Polish spelling a diacritic-free query also searches for, and the budget headroom extra requests keep.
final class PolishSpellingTests: XCTestCase {
    func testKnownWordsGetTheirPolishSpelling() {
        XCTAssertEqual(PolishSpelling.variant(of: "mieta"), "mięta")
        XCTAssertEqual(PolishSpelling.variant(of: "Zurek"), "żurek")
        XCTAssertEqual(PolishSpelling.variant(of: "ser zolty"), "ser żółty")
        XCTAssertEqual(PolishSpelling.variant(of: "losos wedzony"), "łosoś wędzony")
        XCTAssertEqual(PolishSpelling.variant(of: "smietana 18%"), "śmietana 18%")
        XCTAssertEqual(PolishSpelling.variant(of: "maslo"), "masło")
    }

    func testNothingToSpell() {
        XCTAssertNil(PolishSpelling.variant(of: "żurek"), "typed with Polish letters: the user's spelling stands")
        XCTAssertNil(PolishSpelling.variant(of: "zurek żytni"), "one Polish letter typed is enough")
        XCTAssertNil(PolishSpelling.variant(of: "serek wiejski"), "no word with Polish letters")
        XCTAssertNil(PolishSpelling.variant(of: "skyr"))
        XCTAssertNil(PolishSpelling.variant(of: "   "))
    }

    func testExtraRequestsKeepHeadroom() {
        var window = RequestWindow(limit: 4, window: 60)
        let now = Date.now
        XCTAssertTrue(window.reserveNow(at: now, keepingFree: 2))
        XCTAssertTrue(window.reserveNow(at: now, keepingFree: 2))
        XCTAssertFalse(window.reserveNow(at: now, keepingFree: 2), "the last two slots stay for typed queries")
        XCTAssertEqual(window.reserve(at: now, maxWait: 0), 0, "which a typed query still gets")
        XCTAssertTrue(window.reserveNow(at: now.addingTimeInterval(61), keepingFree: 2), "the window moved on")
    }
}

/// A remote hit that is a saved food already shows once, under the saved foods.
final class FoodSearchResultsTests: XCTestCase {
    private func hit(_ code: String, _ name: String) -> FoodCandidate {
        FoodCandidate(id: "off:\(code)", code: code, name: name, brand: nil, quantity: nil, servingSizeG: nil,
                      servingLabel: nil, kcalPer100: 43, proteinPer100: 1, carbsPer100: 5, fatPer100: 2,
                      fiberPer100: nil, imageURL: nil)
    }

    func testSavedFoodIsLeftOutOfTheRemoteHits() {
        let hits = [hit("5902003060560", "Zurek"), hit("5900397016613", "Żurek Krakus"), hit("5901", "Zurek soup")]

        let listed = FoodSearchModel.remoteHits(hits, excluding: ["off:5902003060560", "custom:abc"])

        XCTAssertEqual(listed.map(\.name), ["Żurek Krakus", "Zurek soup"])
        XCTAssertEqual(FoodSearchModel.remoteHits(hits, excluding: []).count, 3)
    }
}
