import XCTest
@testable import NoTomorrow

/// `FoodSearchService` against a stubbed network: the single retry on 429 / 5xx, the order of barcode forms, the
/// search-a-licious → legacy fallback, and the search cooldown after a 429.
final class FoodSearchTransportTests: XCTestCase {
    private var service: FoodSearchService!

    override func setUp() {
        StubProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        service = FoodSearchService(session: URLSession(configuration: config), retryDelay: .milliseconds(10))
    }

    override func tearDown() {
        service = nil
        StubProtocol.reset()
    }

    private static let found = Data(#"{"code":"5901234123457","status":1,"product":{"code":"5901234123457","product_name_pl":"Skyr","nutriments":{"energy-kcal_100g":62,"proteins_100g":11}}}"#.utf8)

    // MARK: - Barcode

    func testRateLimitIsRetriedOnce() async throws {
        StubProtocol.responses = [.status(429), .ok(Self.found)]
        let result = try await service.lookup(barcode: "5901234123457")
        guard case .found(let food) = result else { return XCTFail("expected .found, got \(result)") }
        XCTAssertEqual(food.name, "Skyr")
        XCTAssertEqual(StubProtocol.requests.count, 2)
    }

    func testBusyTwiceSurfacesAsBusy() async {
        StubProtocol.responses = [.status(503), .status(503)]
        await assertThrows(.busy) { try await self.service.lookup(barcode: "5901234123457") }
        XCTAssertEqual(StubProtocol.requests.count, 2, "one retry, no more")
    }

    func testRateLimitedTwiceSurfacesAsRateLimited() async {
        StubProtocol.responses = [.status(429), .status(429)]
        await assertThrows(.rateLimited) { try await self.service.lookup(barcode: "5901234123457") }
    }

    func testOfflineIsNotRetried() async {
        StubProtocol.responses = [.fail(URLError(.notConnectedToInternet))]
        await assertThrows(.offline) { try await self.service.lookup(barcode: "5901234123457") }
        XCTAssertEqual(StubProtocol.requests.count, 1)
    }

    func testEveryFormIsTriedBeforeNotFound() async throws {
        StubProtocol.responses = [.status(404, OFFFixtures.notFound), .status(404, OFFFixtures.notFound)]
        let result = try await service.lookup(barcode: "0049000028911")
        XCTAssertEqual(result, .notFound)
        XCTAssertEqual(StubProtocol.requests.map(\.url!.path), ["/api/v2/product/0049000028911.json", "/api/v2/product/049000028911.json"])
        // A repeat within five minutes is answered from the cache.
        _ = try await service.lookup(barcode: "0049000028911")
        XCTAssertEqual(StubProtocol.requests.count, 2)
    }

    func testStubIsKeptWhileOtherFormsAreTried() async throws {
        let stub = Data(#"{"status":1,"product":{"code":"0049000028911","product_name":"Cola","brands":"Coca-Cola"}}"#.utf8)
        StubProtocol.responses = [.ok(stub), .status(503), .status(503)]
        let result = try await service.lookup(barcode: "0049000028911")
        guard case .partial(let found) = result else { return XCTFail("expected .partial, got \(result)") }
        XCTAssertEqual(found.name, "Cola")
        XCTAssertEqual(found.brand, "Coca-Cola")
    }

    func testLookupAsksForTheRescueFields() async throws {
        StubProtocol.responses = [.ok(Self.found)]
        _ = try await service.lookup(barcode: "5901234123457")
        let query = try XCTUnwrap(StubProtocol.requests.first?.url?.query)
        XCTAssertTrue(query.contains("nutriments_estimated"))
        XCTAssertTrue(query.contains("product_name_en"))
        XCTAssertEqual(StubProtocol.requests.first?.value(forHTTPHeaderField: "User-Agent"), FoodSearchService.userAgent)
    }

    // MARK: - Text search

    func testSearchUsesSearchALicious() async throws {
        StubProtocol.responses = [.ok(OFFFixtures.searchALicious)]
        let hits = try await service.search(query: "serek wiejski", locale: "pl_PL")
        XCTAssertEqual(hits.count, 4)
        let url = try XCTUnwrap(StubProtocol.requests.first?.url)
        XCTAssertEqual(url.host, "search.openfoodfacts.org")
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "q" }?.value, "serek wiejski")
        XCTAssertEqual(items.first { $0.name == "langs" }?.value, "pl,en")
    }

    func testSearchFallsBackToTheLegacyEndpoint() async throws {
        let legacy = Data(#"{"products":[{"code":"5900531000935","product_name_pl":"Serek wiejski","brands":"Piątnica","nutriments":{"energy-kcal_100g":110}}]}"#.utf8)
        StubProtocol.responses = [.status(503), .ok(legacy)]
        let hits = try await service.search(query: "serek", locale: "pl")
        XCTAssertEqual(hits.map(\.brand), ["Piątnica"])
        XCTAssertEqual(StubProtocol.requests.map(\.url!.host), ["search.openfoodfacts.org", "world.openfoodfacts.org"])
        XCTAssertEqual(StubProtocol.requests.last?.url?.path, "/cgi/search.pl")
    }

    func testBothSearchEndpointsDownReadsAsBusy() async {
        StubProtocol.responses = [.status(502), .status(503)]
        await assertThrows(.busy) { try await self.service.search(query: "serek", locale: "pl") }
    }

    func testOfflineSearchDoesNotFallBack() async {
        StubProtocol.responses = [.fail(URLError(.notConnectedToInternet))]
        await assertThrows(.offline) { try await self.service.search(query: "serek", locale: "pl") }
        XCTAssertEqual(StubProtocol.requests.count, 1)
    }

    // MARK: - Polish supplement

    /// A search-a-licious answer with one hit per code.
    private static func hits(_ codes: [String]) -> Data {
        let items = codes.map {
            #"{"code":"\#($0)","product_name":"Mięta \#($0)","brands":["Herbapol"],"nutriments":{"energy-kcal_100g":2}}"#
        }
        return Data(#"{"hits":[\#(items.joined(separator: ","))]}"#.utf8)
    }

    private static let legacyZurek = Data(#"{"products":[{"code":"5900000000011","product_name_pl":"Mięta 5900000000011","brands":"Herbapol","nutriments":{"energy-kcal_100g":2}},{"code":"5900397016613","product_name_pl":"Żurek","brands":"Krakus","nutriments":{"energy-kcal_100g":30}}]}"#.utf8)

    func testThinPolishAnswerIsToppedUpFromTheLegacySearch() async throws {
        StubProtocol.responses = [.ok(Self.hits(["5900000000011"])), .ok(Self.legacyZurek)]

        let found = try await service.search(query: "herbata", locale: "pl")

        XCTAssertEqual(found.map(\.code), ["5900000000011", "5900397016613"], "primary first, the legacy extra after, no duplicate")
        XCTAssertEqual(StubProtocol.requests.map(\.url!.host), ["search.openfoodfacts.org", "world.openfoodfacts.org"])
        let items = URLComponents(url: StubProtocol.requests[1].url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "search_terms" }?.value, "herbata")
    }

    func testEmptyPolishAnswerShowsTheLegacyResults() async throws {
        StubProtocol.responses = [.ok(Data(#"{"hits":[]}"#.utf8)), .ok(Self.legacyZurek)]

        let found = try await service.search(query: "żurek", locale: "pl")

        XCTAssertEqual(found.map(\.brand), ["Herbapol", "Krakus"])
    }

    func testFullPolishAnswerIsNotToppedUp() async throws {
        let codes = (1...FoodSearchService.supplementBelow).map { "590000000001\($0)" }
        StubProtocol.responses = [.ok(Self.hits(codes))]

        let found = try await service.search(query: "mięta", locale: "pl")

        XCTAssertEqual(found.count, FoodSearchService.supplementBelow)
        XCTAssertEqual(StubProtocol.requests.count, 1)
    }

    func testThinEnglishAnswerIsNotToppedUp() async throws {
        StubProtocol.responses = [.ok(Self.hits(["5900000000011"]))]

        let found = try await service.search(query: "mint", locale: "en_GB")

        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(StubProtocol.requests.count, 1)
    }

    func testFailedSupplementKeepsThePrimaryAnswerWithoutCachingIt() async throws {
        StubProtocol.responses = [.ok(Self.hits(["5900000000011"])), .status(503)]

        let found = try await service.search(query: "herbata", locale: "pl")

        XCTAssertEqual(found.map(\.code), ["5900000000011"])
        XCTAssertEqual(StubProtocol.requests.count, 2, "the supplement is tried once, without a retry")

        // The thin answer was not cached: the same query asks again, and this time the supplement answers.
        StubProtocol.responses = [.ok(Self.hits(["5900000000011"])), .ok(Self.legacyZurek)]
        let again = try await service.search(query: "herbata", locale: "pl")
        XCTAssertEqual(again.map(\.code), ["5900000000011", "5900397016613"])
        XCTAssertEqual(StubProtocol.requests.count, 4)
    }

    func testEmptyAnswerWithAFailedSupplementReadsAsBusyAndIsRetried() async throws {
        StubProtocol.responses = [.ok(Data(#"{"hits":[]}"#.utf8)), .status(503)]
        await assertThrows(.busy) { try await self.service.search(query: "herbata", locale: "pl") }

        StubProtocol.responses = [.ok(Data(#"{"hits":[]}"#.utf8)), .ok(Self.legacyZurek)]
        let found = try await service.search(query: "herbata", locale: "pl")
        XCTAssertEqual(found.map(\.brand), ["Herbapol", "Krakus"])
        XCTAssertEqual(StubProtocol.requests.count, 4, "nothing was cached for the failed attempt")
    }

    // MARK: - Polish spelling

    private func queryTerms(_ request: URLRequest) -> String? {
        URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "q" }?.value
    }

    func testUnaccentedPolishQueryAlsoAsksForItsPolishSpelling() async throws {
        let shelf = (1...FoodSearchService.supplementBelow).map { "590100000000\($0)" }
        // "mieta": one product without nutrition. "mięta": a shelf.
        let unusable = Data(#"{"hits":[{"code":"5900000000099","product_name":"Mieta z malina"}]}"#.utf8)
        StubProtocol.responses = [.ok(unusable), .ok(Self.hits(shelf))]

        let found = try await service.search(query: "mieta", locale: "pl")

        XCTAssertEqual(found.map(\.code), shelf)
        XCTAssertEqual(StubProtocol.requests.map(\.url!.host), ["search.openfoodfacts.org", "search.openfoodfacts.org"],
                       "a full answer needs no legacy supplement")
        XCTAssertEqual(StubProtocol.requests.map(queryTerms), ["mieta", "mięta"])

        // Complete, so cached: a repeat within five minutes stays off the network.
        _ = try await service.search(query: "mieta", locale: "pl")
        XCTAssertEqual(StubProtocol.requests.count, 2)
    }

    func testPolishSpellingResultsComeFirst() async throws {
        StubProtocol.responses = [.ok(Self.hits(["1", "2"])), .ok(Self.hits(["3", "2", "4", "5", "6"]))]

        let found = try await service.search(query: "zurek", locale: "pl")

        XCTAssertEqual(found.map(\.code), ["3", "2", "4", "5", "6", "1"], "no duplicate, the typed spelling's extra after")
        XCTAssertEqual(StubProtocol.requests.map(queryTerms), ["zurek", "żurek"])
    }

    func testTypedPolishLettersAskNoSecondSpelling() async throws {
        let full = Self.hits((1...FoodSearchService.supplementBelow).map { "59\($0)" })
        StubProtocol.responses = [.ok(full), .ok(full)]

        _ = try await service.search(query: "żurek", locale: "pl")
        _ = try await service.search(query: "zurek", locale: "en_GB")

        XCTAssertEqual(StubProtocol.requests.count, 2, "one request each: Polish letters typed, and an English search")
    }

    func testFailedPolishSpellingKeepsTheAnswerAndIsNotCached() async throws {
        let full = Self.hits((1...FoodSearchService.supplementBelow).map { "59\($0)" })
        StubProtocol.responses = [.ok(full), .status(503)]

        let found = try await service.search(query: "mieta", locale: "pl")
        XCTAssertEqual(found.count, FoodSearchService.supplementBelow)
        XCTAssertEqual(StubProtocol.requests.count, 2)

        StubProtocol.responses = [.ok(full), .ok(Self.hits(["60"]))]
        let again = try await service.search(query: "mieta", locale: "pl")
        XCTAssertEqual(again.first?.code, "60", "asked again, and the Polish spelling answered")
        XCTAssertEqual(StubProtocol.requests.count, 4)
    }

    func testSupplementNeverWaitsForASearchSlot() async throws {
        // Use up the budget but one slot: the primary takes it, the supplement finds none and is skipped.
        for index in 0..<(FoodSearchService.searchBudget - 1) {
            StubProtocol.responses = [.ok(Self.hits((1...FoodSearchService.supplementBelow).map { "59\(index)\($0)" }))]
            _ = try await service.search(query: "serek \(index)", locale: "pl")
        }
        StubProtocol.reset()
        StubProtocol.responses = [.ok(Self.hits(["5900000000011"]))]

        let found = try await service.search(query: "mieta", locale: "pl")

        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(StubProtocol.requests.count, 1)
    }

    func testSearchCoolsDownAfterARateLimit() async {
        StubProtocol.responses = [.status(429)]
        await assertThrows(.rateLimited) { try await self.service.search(query: "serek", locale: "pl") }
        await assertThrows(.rateLimited) { try await self.service.search(query: "jogurt", locale: "pl") }
        XCTAssertEqual(StubProtocol.requests.count, 1, "the second search never reaches the network")
    }

    // MARK: - Helpers

    private func assertThrows<T>(_ expected: FoodSearchError, _ body: @escaping () async throws -> T,
                                 file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? FoodSearchError, expected, "\(error)", file: file, line: line)
        }
    }
}

/// Serves `responses` in order, one per request, and records the requests.
private final class StubProtocol: URLProtocol {
    enum Response {
        case status(Int, Data = Data())
        case fail(URLError)

        static func ok(_ data: Data) -> Response { .status(200, data) }
    }

    private static let lock = NSLock()
    private static var queue: [Response] = []
    private static var log: [URLRequest] = []

    static var responses: [Response] {
        get { lock.withLock { queue } }
        set { lock.withLock { queue = newValue } }
    }
    static var requests: [URLRequest] { lock.withLock { log } }

    static func reset() {
        lock.withLock {
            queue = []
            log = []
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let next: Response? = Self.lock.withLock {
            Self.log.append(request)
            return Self.queue.isEmpty ? nil : Self.queue.removeFirst()
        }
        switch next ?? .status(599) {
        case .fail(let error):
            client?.urlProtocol(self, didFailWithError: error)
        case .status(let code, let data):
            let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}
