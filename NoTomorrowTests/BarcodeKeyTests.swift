import XCTest
import SwiftData
@testable import NoTomorrow

/// Barcode plumbing: GTIN check digits, UPC-E expansion, what the scanner accepts from each symbology, in-store (RCN)
/// item keys, which saved food a scan picks, and saving a label under its key.
@MainActor
final class BarcodeKeyTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                       configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: - Check digit

    func testGTINCheckDigit() {
        for valid in ["5901234123457", "20582555", "96385074", "036000291452", "05901234123457", "5900056007181", "2050401935713"] {
            XCTAssertTrue(BarcodeKey.isValidGTIN(valid), valid)
        }
        for invalid in ["5901234123458", "20582556", "036000291453", "590123412345", "59012341234570", "590123412345a", "", "04252614"] {
            XCTAssertFalse(BarcodeKey.isValidGTIN(invalid), invalid)
        }
    }

    // MARK: - UPC-E

    func testUPCEExpansion() {
        XCTAssertEqual(GTINExtractor.expandUPCE("04252614"), "042100005264")   // last digit 0–2
        XCTAssertEqual(GTINExtractor.expandUPCE("01234531"), "012300000451")   // 3
        XCTAssertEqual(GTINExtractor.expandUPCE("01234543"), "012340000053")   // 4
        XCTAssertEqual(GTINExtractor.expandUPCE("01234572"), "012345000072")   // 5–9
        XCTAssertEqual(GTINExtractor.expandUPCE("11234562"), "112345000062")   // number system 1
        XCTAssertEqual(GTINExtractor.expandUPCE("425261"), "042100005264")     // compressed digits only
        XCTAssertEqual(GTINExtractor.expandUPCE("0425261"), "042100005264")    // without the check digit
        XCTAssertNil(GTINExtractor.expandUPCE("04252615"), "wrong check digit")
        XCTAssertNil(GTINExtractor.expandUPCE("24252614"), "number system 2 has no UPC-E")
        for upca in ["042100005264", "012300000451", "012340000053", "012345000072", "112345000062"] {
            XCTAssertTrue(BarcodeKey.isValidGTIN(upca), upca)
        }
    }

    // MARK: - Scanner payloads

    func testLinearCodesPassThrough() {
        XCTAssertEqual(GTINExtractor.gtin(payload: "5901234123457", symbology: .ean13), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "20582555", symbology: .ean8), "20582555")
        XCTAssertEqual(GTINExtractor.gtin(payload: "04252614", symbology: .upce), "0042100005264")
        XCTAssertNil(GTINExtractor.gtin(payload: "04252615", symbology: .upce))
        XCTAssertNil(GTINExtractor.gtin(payload: "12345", symbology: .ean13))
        XCTAssertNil(GTINExtractor.gtin(payload: "5901234123457", symbology: .other))
    }

    func testGS1ElementStrings() {
        let gs = "\u{1D}"
        XCTAssertEqual(GTINExtractor.gtin(payload: "(01)05901234123457(3103)000150", symbology: .gs1DataBar), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "0105901234123457", symbology: .gs1DataBar), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "05901234123457", symbology: .gs1DataBar), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "]e00105901234123457310300015015260131", symbology: .gs1DataBar), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "]d2" + gs + "0105901234123457" + "10LOT7" + gs + "17260131", symbology: .dataMatrix),
                       "5901234123457")
        // (01) after a fixed-length AI and after a variable-length one.
        XCTAssertEqual(GTINExtractor.gtin(payload: "172601310105901234123457", symbology: .dataMatrix), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "10ABC" + gs + "0105901234123457", symbology: .dataMatrix), "5901234123457")
        // Variable-measure GTIN-14 (indicator 9) stays 14 digits; a GTIN-8 inside a GTIN-14 comes back as 8.
        XCTAssertEqual(GTINExtractor.gtin(payload: "(01)00000020582555", symbology: .gs1DataBar), "20582555")
        XCTAssertNil(GTINExtractor.gtin(payload: "(01)05901234123458", symbology: .gs1DataBar), "wrong check digit")
        XCTAssertNil(GTINExtractor.gtin(payload: "10ABC", symbology: .dataMatrix))
    }

    func testGS1DigitalLinkAndPromoCodes() {
        XCTAssertEqual(GTINExtractor.gtin(payload: "https://id.gs1.org/01/05901234123457/10/ABC?17=260131", symbology: .qr),
                       "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "https://brand.example.pl/gtin/5901234123457", symbology: .qr), "5901234123457")
        XCTAssertEqual(GTINExtractor.gtin(payload: "https://id.gs1.org/01/20582555", symbology: .dataMatrix), "20582555")
        XCTAssertEqual(GTINExtractor.gtin(payload: "]Q3(01)05901234123457", symbology: .qr), "5901234123457")
        // Promo and plain QR codes never fire the scanner.
        XCTAssertNil(GTINExtractor.gtin(payload: "https://www.example.pl/promocja?kod=0105901234123457", symbology: .qr))
        XCTAssertNil(GTINExtractor.gtin(payload: "https://id.gs1.org/01/05901234123458", symbology: .qr))
        XCTAssertNil(GTINExtractor.gtin(payload: "5901234123457", symbology: .qr))
        XCTAssertNil(GTINExtractor.gtin(payload: "Wygraj nagrody!", symbology: .qr))
        XCTAssertEqual(GTINExtractor.normalized(gtin14: "95901234123454"), "95901234123454")
    }

    func testManualEntry() {
        XCTAssertEqual(GTINExtractor.manual("5901234123457"), "5901234123457")
        XCTAssertEqual(GTINExtractor.manual("036000291452"), "036000291452")
        XCTAssertEqual(GTINExtractor.manual("20582555"), "20582555")
        XCTAssertEqual(GTINExtractor.manual("04252614"), "0042100005264", "a UPC-E fails the EAN-8 check and is expanded")
        XCTAssertNil(GTINExtractor.manual("5901234123458"), "typo in the last digit")
        XCTAssertNil(GTINExtractor.manual("5901234"))
    }

    // MARK: - In-store codes

    func testInStoreCodesAreFiledUnderTheItemKey() {
        XCTAssertEqual(BarcodeKey.storageKey(for: "2412345004526"), "2412345")   // scale label, weight or price in 8–12
        XCTAssertEqual(BarcodeKey.storageKey(for: "2912345012342"), "2912345")
        XCTAssertEqual(BarcodeKey.storageKey(for: "2712345123457"), "2712345")
        XCTAssertEqual(BarcodeKey.storageKey(for: "2312345000002"), "2312345000002", "value field 00000 is a fixed code")
        XCTAssertEqual(BarcodeKey.storageKey(for: "2212345004522"), "2212345004522", "20–22 are fixed retailer codes")
        XCTAssertEqual(BarcodeKey.storageKey(for: "2050401935713"), "2050401935713")
        XCTAssertEqual(BarcodeKey.storageKey(for: "2873330000006"), "2873330000006")
        XCTAssertEqual(BarcodeKey.storageKey(for: "20582555"), "20582555")
        XCTAssertEqual(BarcodeKey.storageKey(for: "5901234123457"), "5901234123457")
    }

    func testLocalKeys() {
        XCTAssertEqual(BarcodeKey.localKeys(for: "2412345004526"), ["2412345004526", "2412345"])
        XCTAssertEqual(BarcodeKey.localKeys(for: "0049000028911"), ["0049000028911", "049000028911"])
        XCTAssertEqual(BarcodeKey.localKeys(for: "049000028911"), ["049000028911", "0049000028911"])
        XCTAssertEqual(BarcodeKey.localKeys(for: "5901234123457"), ["5901234123457"])
    }

    // MARK: - Saved foods

    private func food(_ id: String, source: FoodSource, barcode: String?, lastUsed: TimeInterval?) -> FoodItem {
        let item = FoodItem(id: id, name: id, source: source, barcode: barcode,
                            kcalPer100: 100, proteinPer100: 1, carbsPer100: 1, fatPer100: 1)
        item.lastUsedAt = lastUsed.map { Date(timeIntervalSinceReferenceDate: $0) }
        context.insert(item)
        return item
    }

    func testPreferredPickIsDeterministic() {
        let offOld = food("off:a", source: .openFoodFacts, barcode: "1", lastUsed: 100)
        let offNew = food("off:b", source: .openFoodFacts, barcode: "1", lastUsed: 200)
        let offNever = food("off:c", source: .openFoodFacts, barcode: "1", lastUsed: nil)
        XCTAssertEqual(BarcodeKey.preferred([offOld, offNever, offNew])?.id, "off:b", "most recently used")
        XCTAssertEqual(BarcodeKey.preferred([offNever, offOld])?.id, "off:a", "never used goes last")
        let label = food("label:1", source: .custom, barcode: "1", lastUsed: nil)
        XCTAssertEqual(BarcodeKey.preferred([offNew, label, offOld])?.id, "label:1", "the user's own label wins")
        let twinA = food("off:x", source: .openFoodFacts, barcode: "2", lastUsed: nil)
        let twinB = food("off:w", source: .openFoodFacts, barcode: "2", lastUsed: nil)
        XCTAssertEqual(BarcodeKey.preferred([twinA, twinB])?.id, "off:w", "ties break on id")
        XCTAssertNil(BarcodeKey.preferred([]))
    }

    func testScanFindsSavedFoodsUnderEveryKey() throws {
        _ = food("label:2412345", source: .custom, barcode: "2412345", lastUsed: nil)
        _ = food("off:049000028911", source: .openFoodFacts, barcode: "049000028911", lastUsed: 10)
        _ = food("off:0049000028911", source: .openFoodFacts, barcode: "0049000028911", lastUsed: 20)
        try context.save()

        XCTAssertEqual(BarcodeLookupFlow.savedFood(for: "2412345009995", in: context)?.id, "label:2412345",
                       "another package of the same deli item")
        XCTAssertEqual(BarcodeLookupFlow.savedFood(for: "049000028911", in: context)?.id, "off:0049000028911",
                       "the UPC-A and EAN-13 forms both match; the most recent wins")
        XCTAssertNil(BarcodeLookupFlow.savedFood(for: "5901234123457", in: context))
    }

    // MARK: - Label form

    func testLabelValues() {
        let full = LabelValues.parse(kcal: "250", protein: "12,5", carbs: "30", fat: "8", fiber: "3", serving: "30")
        XCTAssertEqual(full, LabelValues(kcal: 250, protein: 12.5, carbs: 30, fat: 8, fiber: 3, servingG: 30))
        XCTAssertEqual(LabelValues.parse(kcal: "0", protein: "0", carbs: "0", fat: "0", fiber: " ", serving: ""),
                       LabelValues(kcal: 0, protein: 0, carbs: 0, fat: 0), "water; fiber and serving are optional")
        XCTAssertNotNil(LabelValues.parse(kcal: "900", protein: "0,5", carbs: "0,5", fat: "100", fiber: "", serving: ""),
                        "rounded oil label")
        XCTAssertNil(LabelValues.parse(kcal: "", protein: "1", carbs: "1", fat: "1", fiber: "", serving: ""))
        XCTAssertNil(LabelValues.parse(kcal: "951", protein: "1", carbs: "1", fat: "1", fiber: "", serving: ""))
        XCTAssertNil(LabelValues.parse(kcal: "500", protein: "40", carbs: "40", fat: "30", fiber: "", serving: ""))
        XCTAssertNil(LabelValues.parse(kcal: "100", protein: "1", carbs: "1", fat: "1", fiber: "abc", serving: ""))
        XCTAssertNil(LabelValues.parse(kcal: "100", protein: "1", carbs: "1", fat: "1", fiber: "", serving: "0"))
        XCTAssertNil(LabelValues.parse(kcal: "100", protein: "-1", carbs: "1", fat: "1", fiber: "", serving: ""))
    }

    func testSavingALabelFilesItUnderTheKeyAndUpdatesInPlace() throws {
        let stub = ProductStub(code: "2050401935713", name: "Łosoś świeży", brand: "MOWI", quantity: "150 g",
                               servingSizeG: 150, imageURL: "https://example.org/front.jpg")
        let values = LabelValues(kcal: 208, protein: 20, carbs: 0, fat: 13, fiber: nil, servingG: 150)
        let saved = try ProductLabel.save(key: "2050401935713", name: "Łosoś świeży", stub: stub, values: values, in: context)
        XCTAssertEqual(saved.id, "label:2050401935713")
        XCTAssertEqual(saved.source, .custom)
        XCTAssertEqual(saved.barcode, "2050401935713")
        XCTAssertEqual(saved.brand, "MOWI")
        XCTAssertEqual(saved.imageURL, "https://example.org/front.jpg")
        XCTAssertEqual(saved.servingSizeG, 150)

        let corrected = LabelValues(kcal: 200, protein: 20.5, carbs: 0, fat: 12, fiber: 0, servingG: nil)
        let again = try ProductLabel.save(key: "2050401935713", name: "Łosoś", stub: nil, values: corrected, in: context)
        XCTAssertTrue(again === saved)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<FoodItem>()), 1)
        XCTAssertEqual(again.name, "Łosoś")
        XCTAssertEqual(again.kcalPer100, 200)
        XCTAssertEqual(again.fiberPer100, 0)
        XCTAssertNil(again.servingSizeG)
        XCTAssertEqual(again.brand, "MOWI", "a later save without a stub keeps the brand")

        let deli = try ProductLabel.save(key: BarcodeKey.storageKey(for: "2412345004526"), name: "Szynka",
                                         stub: nil, values: values, in: context)
        XCTAssertEqual(deli.barcode, "2412345")
        XCTAssertEqual(BarcodeLookupFlow.savedFood(for: "2412345011111", in: context)?.id, "label:2412345")
    }
}
