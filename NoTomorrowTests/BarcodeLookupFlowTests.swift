import XCTest
import SwiftData
@testable import NoTomorrow

/// The scan → lookup flow around Open Food Facts: the "Looking up" pill across overlapping lookups, and the alert copy
/// for a lookup OFF refused.
@MainActor
final class BarcodeLookupFlowTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                       configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    /// Holds every lookup until the test answers it, in the order they started.
    private actor LookupGate {
        private var waiting: [CheckedContinuation<BarcodeLookup, Error>] = []
        var started: Int { waiting.count }

        func wait() async throws -> BarcodeLookup {
            try await withCheckedThrowingContinuation { waiting.append($0) }
        }

        func answer(_ index: Int, with result: BarcodeLookup) {
            waiting[index].resume(returning: result)
        }
    }

    private func waitUntil(_ gate: LookupGate, started count: Int) async {
        while await gate.started < count { await Task.yield() }
    }

    func testThePillStaysUpUntilTheLastLookupEnds() async {
        let gate = LookupGate()
        let lookup: BarcodeLookupFlow.Lookup = { _, _ in try await gate.wait() }
        let flow = BarcodeLookupFlow()
        XCTAssertFalse(flow.isLookingUp)

        let first = Task { await flow.run("5901234123457", in: context, lookup: lookup) { _ in } }
        await waitUntil(gate, started: 1)
        // A retry (or a second scan) while the first lookup is still waiting for OFF.
        let second = Task { await flow.run("5900259000002", in: context, lookup: lookup) { _ in } }
        await waitUntil(gate, started: 2)
        XCTAssertTrue(flow.isLookingUp)

        await gate.answer(0, with: .notFound)
        await first.value
        XCTAssertTrue(flow.isLookingUp, "the newer lookup is still running")

        await gate.answer(1, with: .notFound)
        await second.value
        XCTAssertFalse(flow.isLookingUp)
        XCTAssertEqual(flow.runningLookups, 0)
    }

    func testASavedFoodAnswersWithoutThePillStuckOn() async throws {
        let food = FoodItem(id: "off:5901234123457", name: "Serek", source: .openFoodFacts, barcode: "5901234123457",
                            kcalPer100: 97, proteinPer100: 11, carbsPer100: 2, fatPer100: 5)
        context.insert(food)
        try context.save()
        let flow = BarcodeLookupFlow()
        var picked: PortionFood?
        await flow.run("5901234123457", in: context,
                       lookup: { _, _ in XCTFail("a saved food needs no lookup"); return .notFound }) {
            picked = $0
        }
        XCTAssertEqual(picked?.id, PortionFood.item(food).id)
        XCTAssertFalse(flow.isLookingUp)
    }

    // MARK: - Error copy

    func testRefusedLookupsReadAsBusy() {
        let busy = String(localized: "fuel.search.error.busy")
        XCTAssertNotEqual(busy, "fuel.search.error.busy", "the key is in the catalog")
        XCTAssertEqual(FoodSearchError.lookupMessage(for: FoodSearchError.rateLimited), busy,
                       "a scan is never 'too many searches'")
        XCTAssertEqual(FoodSearchError.lookupMessage(for: FoodSearchError.busy), busy)
        let others: [Error] = [FoodSearchError.offline, FoodSearchError.unreachable, FoodSearchError.badResponse(404),
                               CocoaError(.fileNoSuchFile)]
        for other in others {
            XCTAssertEqual(FoodSearchError.lookupMessage(for: other), FoodSearchError.message(for: other))
        }
        XCTAssertNotEqual(FoodSearchError.message(for: FoodSearchError.rateLimited), busy,
                          "text search keeps its own rate-limit copy")
    }

    func testARateLimitedScanShowsTheBusyAlert() async {
        let flow = BarcodeLookupFlow()
        await flow.run("5901234123457", in: context, lookup: { _, _ in throw FoodSearchError.rateLimited }) { _ in }

        guard case .failed(let message, let code)? = flow.prompt else { return XCTFail("a refused lookup asks to retry") }
        XCTAssertEqual(message, String(localized: "fuel.search.error.busy"))
        XCTAssertEqual(code, "5901234123457")
        XCTAssertFalse(flow.isLookingUp)
    }

    // MARK: - Partial title

    private func stub(_ name: String, brand: String?) -> ProductStub {
        ProductStub(code: "5901234123457", name: name, brand: brand, quantity: nil, servingSizeG: nil, imageURL: nil)
    }

    func testPartialTitleNamesTheBrandOnce() {
        func title(_ name: String, _ brand: String?) -> String {
            BarcodeLookupFlow.Prompt.partial(stub(name, brand: brand), code: "5901234123457").title
        }
        XCTAssertEqual(title("Łosoś świeży", "MOWI"), "Łosoś świeży · MOWI")
        XCTAssertEqual(title("Łosoś świeży", nil), "Łosoś świeży")
        XCTAssertEqual(title("Łosoś świeży", "  "), "Łosoś świeży")
        // The name was built from "brand quantity" because Open Food Facts had no name.
        XCTAssertEqual(title("Pudliszki 200 g", "Pudliszki"), "Pudliszki 200 g")
        XCTAssertEqual(title("PUDLISZKI 200 g", "Pudliszki"), "PUDLISZKI 200 g", "case does not matter")
        XCTAssertEqual(title("Łowicz 450 g", "Lowicz"), "Łowicz 450 g", "diacritics do not matter")
        XCTAssertEqual(title("Zott", "Zott"), "Zott", "the name is only the brand")
        XCTAssertEqual(title("Mlekovita masło", "Mleko"), "Mlekovita masło · Mleko", "a word that only starts the same")
        XCTAssertEqual(title("Serek wiejski Piątnica", "Piątnica"), "Serek wiejski Piątnica · Piątnica",
                       "only a leading brand is dropped")
    }
}
