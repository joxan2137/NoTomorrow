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
        XCTAssertEqual(records.count, 900)
        XCTAssertEqual(Set(records.map(\.id)).count, records.count)
        let represented = Set(ExerciseMedia.regions.map(\.muscle))
        for record in records {
            XCTAssertTrue(Set(record.primaryMuscles + record.secondaryMuscles).isSubset(of: represented), record.id)
        }
    }
}
