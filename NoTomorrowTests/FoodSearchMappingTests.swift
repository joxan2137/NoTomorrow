import XCTest
@testable import NoTomorrow

/// Open Food Facts → app mapping: name fallbacks, estimated nutrition, the found / partial / not-found split, the
/// search-a-licious decoder, and the search helpers (Lucene-safe terms, fallback rule, request window, local matching).
final class FoodSearchMappingTests: XCTestCase {
    private func outcome(_ data: Data, locale: String = "pl") throws -> BarcodeLookup {
        FoodSearchService.outcome(of: try JSONDecoder().decode(OFFProductResponse.self, from: data), locale: locale)
    }

    private func found(_ data: Data, locale: String = "pl", file: StaticString = #filePath, line: UInt = #line) throws -> FoodCandidate {
        guard case .found(let candidate) = try outcome(data, locale: locale) else {
            XCTFail("expected .found", file: file, line: line)
            throw XCTSkip()
        }
        return candidate
    }

    private func product(_ json: String) throws -> OFFProduct {
        try JSONDecoder().decode(OFFProduct.self, from: Data(json.utf8))
    }

    // MARK: - Fixtures

    func testEnglishOnlyNameIsRescued() throws {
        let food = try found(OFFFixtures.nameOnlyEnglish)
        XCTAssertEqual(food.id, "off:5900056007181")
        XCTAssertEqual(food.name, "Bagatelka")
        XCTAssertEqual(food.brand, "Kopernik Toruń")
        XCTAssertEqual(food.kcalPer100, 399)
        XCTAssertEqual(food.proteinPer100, 6.6)
        XCTAssertEqual(food.carbsPer100, 73)
        XCTAssertEqual(food.fatPer100, 8.4)
        XCTAssertEqual(food.servingSizeG, 100)
        XCTAssertFalse(food.isEstimated)
        XCTAssertEqual(try found(OFFFixtures.nameOnlyEnglish, locale: "en").name, "Bagatelka")
    }

    func testNamelessProductIsNamedBrandAndQuantity() throws {
        let food = try found(OFFFixtures.noNameBrandQuantity)
        XCTAssertEqual(food.name, "Pudliszki 200 g")
        XCTAssertEqual(food.brand, "Pudliszki")
        XCTAssertEqual(food.kcalPer100, 104)
        XCTAssertEqual(food.fiberPer100, 2.5)
        XCTAssertEqual(food.servingSizeG, 200)
        XCTAssertFalse(food.isEstimated, "an empty nutriments_estimated block changes nothing")
    }

    func testEstimatedNutritionIsUsedAndFlagged() throws {
        let food = try found(OFFFixtures.estimatedOnly)
        XCTAssertEqual(food.name, "Mini kiwi")
        XCTAssertEqual(food.brand, "Lidl")
        XCTAssertEqual(food.kcalPer100, 60.5)
        XCTAssertEqual(food.proteinPer100, 0.88)
        XCTAssertEqual(food.carbsPer100, 11)
        XCTAssertEqual(food.fatPer100, 0.6)
        XCTAssertEqual(food.fiberPer100, 2.4)
        XCTAssertTrue(food.isEstimated)
        XCTAssertTrue(PortionFood.candidate(food).isEstimated)
        XCTAssertFalse(PortionFood.item(food.makeFoodItem()).isEstimated, "the flag is not persisted")
    }

    func testNameWithoutNutritionIsPartial() throws {
        guard case .partial(let stub) = try outcome(OFFFixtures.nameNoNutrition) else { return XCTFail("expected .partial") }
        XCTAssertEqual(stub, ProductStub(code: "2050401935713", name: "Łosoś świeży", brand: "MOWI", quantity: "150 g",
                                         servingSizeG: 150,
                                         imageURL: "https://images.openfoodfacts.org/images/products/205/040/193/5713/front_pl.16.200.jpg"))
    }

    func testEmptyStubAndMissingProductAreNotFound() throws {
        XCTAssertEqual(try outcome(OFFFixtures.emptyStub), .notFound)
        XCTAssertEqual(try outcome(OFFFixtures.notFound), .notFound)
    }

    // MARK: - Names

    func testPolishNameChain() throws {
        // lc=pl does not localise product_name, so the Polish field wins over a main name in another language.
        let kefir = try product(#"{"code":"1","lang":"en","product_name":"Natural Kefir","product_name_pl":"Kefir naturalny Polski","product_name_en":"Kefir"}"#)
        XCTAssertEqual(FoodSearchService.displayName(kefir, locale: "pl"), "Kefir naturalny Polski")
        XCTAssertEqual(FoodSearchService.displayName(kefir, locale: "en"), "Kefir")

        // A Polish main name beats the English one; a Bulgarian main name does not.
        let polishMain = try product(#"{"code":"1","lang":"pl","product_name":"Mleko","product_name_en":"Milk"}"#)
        XCTAssertEqual(FoodSearchService.displayName(polishMain, locale: "pl"), "Mleko")
        let bulgarianMain = try product(#"{"code":"1","lang":"bg","product_name":"Сирене","product_name_en":"White cheese"}"#)
        XCTAssertEqual(FoodSearchService.displayName(bulgarianMain, locale: "pl"), "White cheese")
        XCTAssertEqual(FoodSearchService.displayName(try product(#"{"code":"1","lang":"bg","product_name":"Сирене"}"#), locale: "pl"), "Сирене")

        let generic = try product(#"{"code":"1","product_name":" ","generic_name_pl":"Ser żółty","generic_name":"Cheese"}"#)
        XCTAssertEqual(FoodSearchService.displayName(generic, locale: "pl"), "Ser żółty")
        let abbreviated = try product(#"{"code":"1","abbreviated_product_name":"Nutella t.400"}"#)
        XCTAssertEqual(FoodSearchService.displayName(abbreviated, locale: "pl"), "Nutella t.400")
        XCTAssertEqual(FoodSearchService.displayName(try product(#"{"code":"1","brands":"Kinga"}"#), locale: "pl"), "Kinga")
        XCTAssertNil(FoodSearchService.displayName(try product(#"{"code":"1","brands":" ","quantity":"700ml"}"#), locale: "pl"))
    }

    func testBrandsDecodeFromStringOrArray() throws {
        XCTAssertEqual(try product(#"{"brands":"Lidl, Nergi"}"#).brands, "Lidl, Nergi")
        let array = try product(#"{"brands":["Piątnica","Mlekpol"]}"#)
        XCTAssertEqual(array.brands, "Piątnica, Mlekpol")
        XCTAssertEqual(FoodSearchService.firstBrand(array), "Piątnica")
        XCTAssertNil(try product(#"{"brands":{"pl":"x"}}"#).brands)
    }

    // MARK: - Nutrition rules

    func testLabelValuesWinOverTheEstimate() throws {
        let p = try product(#"{"code":"1","product_name":"x","nutriments":{"energy-kj_100g":"418,4"},"nutriments_estimated":{"energy-kcal_100g":300}}"#)
        let n = try XCTUnwrap(FoodSearchService.per100(p))
        XCTAssertEqual(n.kcal, 100, accuracy: 0.001)
        XCTAssertFalse(n.isEstimated)
    }

    func testImplausibleLabelValuesAreNotReplacedByTheEstimate() throws {
        // The label block has energy, so it is judged on its own; bad crowd data becomes a label-form stub.
        let json = #"{"status":1,"product":{"code":"5","product_name":"Masło","nutriments":{"energy-kcal_100g":7440},"nutriments_estimated":{"energy-kcal_100g":744,"fat_100g":82}}}"#
        guard case .partial(let stub) = try outcome(Data(json.utf8)) else { return XCTFail("expected .partial") }
        XCTAssertEqual(stub.name, "Masło")

        let badMacro = try product(#"{"code":"1","product_name":"x","nutriments":{"energy-kcal_100g":100,"fat_100g":120}}"#)
        XCTAssertNil(FoodSearchService.per100(badMacro))
    }

    func testNoEnergyAnywhereIsNotACandidate() throws {
        let p = try product(#"{"code":"1","product_name":"x","nutriments":{"proteins_100g":10},"nutriments_estimated":{}}"#)
        XCTAssertNil(FoodSearchService.per100(p))
        XCTAssertNil(FoodSearchService.candidate(from: p, locale: "pl"))
    }

    func testCodeFallsBackToTheResponseCode() throws {
        let json = #"{"code":"5900000000001","status":1,"product":{"product_name":"x","nutriments":{"energy-kcal_100g":50}}}"#
        guard case .found(let food) = try outcome(Data(json.utf8)) else { return XCTFail("expected .found") }
        XCTAssertEqual(food.id, "off:5900000000001")
    }

    // MARK: - search-a-licious

    func testSearchALiciousDecoding() throws {
        let hits = try XCTUnwrap(JSONDecoder().decode(SearchALiciousResponse.self, from: OFFFixtures.searchALicious).hits)
        XCTAssertEqual(hits.count, 5)
        let foods = hits.compactMap { FoodSearchService.candidate(from: $0, locale: "pl") }
        XCTAssertEqual(foods.map(\.code), ["0444444143006", "5900531000935", "5900531050015", "5900512987378"],
                       "the hit without nutriments is dropped")
        XCTAssertEqual(foods[0].name, "Serek wiejski")
        XCTAssertEqual(foods[0].brand, "Piątnica")
        XCTAssertEqual(foods[0].kcalPer100, 97)
        XCTAssertEqual(foods[3].name, "Serek wiejski bez laktozy")
        XCTAssertNil(foods[3].brand)
    }

    func testSearchTermsAreLuceneSafe() {
        XCTAssertEqual(FoodSearchService.searchTerms("serek wiejski"), "serek wiejski")
        XCTAssertEqual(FoodSearchService.searchTerms("kawa: latte"), "kawa latte")
        XCTAssertEqual(FoodSearchService.searchTerms("Coca-Cola (0,5 l)"), "Coca Cola 0,5 l")
        XCTAssertEqual(FoodSearchService.searchTerms("\"jogurt\" +grecki*"), "jogurt grecki")
        XCTAssertEqual(FoodSearchService.searchTerms("!!"), "")
    }

    func testOnlyServerTroubleFallsBackToTheLegacySearch() {
        XCTAssertTrue(FoodSearchService.fallsBack(FoodSearchError.busy))
        XCTAssertTrue(FoodSearchService.fallsBack(FoodSearchError.unreachable))
        XCTAssertTrue(FoodSearchService.fallsBack(FoodSearchError.badResponse(400)))
        XCTAssertTrue(FoodSearchService.fallsBack(DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: ""))))
        XCTAssertFalse(FoodSearchService.fallsBack(FoodSearchError.rateLimited))
        XCTAssertFalse(FoodSearchService.fallsBack(FoodSearchError.offline))
        XCTAssertFalse(FoodSearchService.fallsBack(CancellationError()))
    }

    // MARK: - Errors

    func testErrorsSayWhatWentWrong() {
        XCTAssertEqual(FoodSearchError(status: 429), .rateLimited)
        XCTAssertEqual(FoodSearchError(status: 503), .busy)
        XCTAssertEqual(FoodSearchError(status: 502), .busy)
        XCTAssertEqual(FoodSearchError(status: 404), .badResponse(404))
        XCTAssertEqual(FoodSearchError(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(FoodSearchError(URLError(.dataNotAllowed)), .offline)
        XCTAssertEqual(FoodSearchError(URLError(.timedOut)), .unreachable)
        XCTAssertEqual(FoodSearchError(URLError(.cannotFindHost)), .unreachable)

        let messages = [FoodSearchError.rateLimited, .busy, .offline, .unreachable].map(FoodSearchError.message(for:))
        XCTAssertEqual(Set(messages).count, 4, "rate limit, busy, offline and no answer each read differently")
        XCTAssertEqual(FoodSearchError.message(for: CocoaError(.fileNoSuchFile)), FoodSearchError.message(for: FoodSearchError.unreachable))
        for key in ["fuel.search.error.busy", "fuel.search.error.rateLimited", "error.network", "fuel.search.error.network",
                    "fuel.barcodePartial", "fuel.estimatedNutrition", "fuel.scan.checkDigits", "fuel.label.storeCode",
                    "fuel.label.servingSize", "macro.fiber", "fuel.yourFoods"] {
            XCTAssertNotEqual(String(localized: String.LocalizationValue(key)), key, key)
        }
    }

    // MARK: - Request window

    func testRequestWindowPacesSearches() {
        var window = RequestWindow(limit: 2, window: 60)
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        XCTAssertEqual(window.reserve(at: t0, maxWait: 6), 0)
        XCTAssertEqual(window.reserve(at: t0 + 1, maxWait: 6), 0)
        XCTAssertNil(window.reserve(at: t0 + 2, maxWait: 6), "the next slot is 58 s away")
        XCTAssertEqual(window.starts.count, 2, "a refused request books nothing")
        XCTAssertEqual(window.reserve(at: t0 + 55, maxWait: 6), 5)
        XCTAssertEqual(window.reserve(at: t0 + 56, maxWait: 6), 5)
        XCTAssertNil(window.reserve(at: t0 + 57, maxWait: 6))
        XCTAssertEqual(window.reserve(at: t0 + 130, maxWait: 6), 0, "old starts expire")
    }

    // MARK: - Local matching

    func testLocalMatchingIgnoresCaseAndPolishDiacritics() {
        XCTAssertEqual(FoodMatch.fold("Żółty SER"), "zolty ser")
        XCTAssertEqual(FoodMatch.fold("Łosoś"), "losos")
        XCTAssertTrue(FoodMatch.matches("zolty ser", name: "Żółty ser Gouda", brand: nil))
        XCTAssertTrue(FoodMatch.matches("losos", name: "Łosoś świeży", brand: "MOWI"))
        XCTAssertTrue(FoodMatch.matches("mlekovita serek", name: "Serek wiejski", brand: "Mlekovita"))
        XCTAssertTrue(FoodMatch.matches("  ", name: "Anything", brand: nil))
        XCTAssertFalse(FoodMatch.matches("serek piatnica", name: "Serek wiejski", brand: "Mlekovita"))
        XCTAssertFalse(FoodMatch.matches("jogurt", name: "Serek wiejski", brand: nil))
    }
}
