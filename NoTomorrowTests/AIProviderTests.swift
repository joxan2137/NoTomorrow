import XCTest
@testable import NoTomorrow

/// The bring-your-own-key request bodies and error mapping (contract §9–10), the backend error mapping, and decoding
/// of both the v2 and the old v1 backend answer.
final class AIProviderTests: XCTestCase {
    private var spec: AIEstimateSpec!
    private var session: URLSession!

    override func setUpWithError() throws {
        spec = try AIEstimateSpec.load(from: XCTUnwrap(Bundle.main.url(forResource: "estimate-spec", withExtension: "json")))
        AIStubProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AIStubProtocol.self]
        session = URLSession(configuration: config)
    }

    override func tearDown() {
        AIStubProtocol.reset()
        session = nil
    }

    private static let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x01])

    /// A schema-v2 model answer: 6 pierogi ruskie, grounded by the generic table.
    private static let modelAnswer = #"{"foods":[{"name":"Pierogi ruskie","cooking":"boiled","genericKey":"pierogi_ruskie","portionCount":6,"portionUnit":"szt.","gramsPerUnit":35,"grams":210,"per100":{"kcal":190,"protein":6,"carbs":29,"fat":5.5,"alcohol":0},"nutritionSource":"estimated","barcode":"","isGuess":false,"confidence":0.8}],"scaleReferenceUsed":"talerz","assumptions":[],"questions":[],"overallConfidence":0.8}"#

    private func claude(_ key: String? = "sk-test") -> DirectAnthropicEstimateService {
        DirectAnthropicEstimateService(apiKey: { key }, session: session, spec: spec, grounding: nil)
    }

    private func gemini(_ key: String? = "g-test") -> DirectGeminiEstimateService {
        DirectGeminiEstimateService(apiKey: { key }, session: session, spec: spec, grounding: nil)
    }

    private func assertThrows<T>(_ expected: AIEstimateError, file: StaticString = #filePath, line: UInt = #line,
                                 _ body: () async throws -> T) async {
        do {
            _ = try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? AIEstimateError, expected, file: file, line: line)
        }
    }

    private static func claudeReply(_ text: String, stop: String = "end_turn") -> Data {
        let body: JSONValue = ["content": [["type": "thinking", "thinking": ""], ["type": "text", "text": .string(text)]],
                               "stop_reason": .string(stop)]
        return body.serializedData()
    }

    // MARK: Claude

    func testClaudeBodyFollowsTheContract() throws {
        let body = DirectAnthropicEstimateService.body(spec: spec, system: "S", text: "T", schema: spec.estimateSchema(),
                                                       maxTokens: spec.claude.estimateMaxTokens, imageBase64: "IMG")
        XCTAssertEqual(body["model"], "claude-sonnet-5")
        XCTAssertEqual(body["max_tokens"], 8192)
        XCTAssertEqual(body["system"], "S")
        XCTAssertEqual(body["output_config"]?["effort"], "low")
        XCTAssertEqual(body["output_config"]?["format"]?["type"], "json_schema")
        XCTAssertEqual(body["output_config"]?["format"]?["schema"], spec.estimateSchema())
        XCTAssertEqual(body["output_config"]?["format"]?["schema"]?["additionalProperties"], false)
        for absent in ["temperature", "top_p", "top_k", "thinking", "output_format"] { XCTAssertNil(body[absent], absent) }
        let content = try XCTUnwrap(body["messages"]?.arrayValue?.first?["content"]?.arrayValue)
        XCTAssertEqual(content.map { $0["type"] }, ["image", "text"])
        XCTAssertEqual(content[0]["source"]?["data"], "IMG")
        XCTAssertEqual(content[0]["source"]?["media_type"], "image/jpeg")
        XCTAssertEqual(content[1]["text"], "T")
    }

    func testClaudeEstimateEndToEnd() async throws {
        AIStubProtocol.responses = [.status(200, Self.claudeReply(Self.modelAnswer))]
        let estimate = try await claude().estimate(imageJPEG: Self.jpeg, meal: .dinner, locale: "pl", notes: "6 pierogów")
        XCTAssertEqual(estimate.version, 2)
        XCTAssertEqual(estimate.foods.first?.nutritionSource, "generic_table")
        XCTAssertEqual(estimate.foods.first?.portionCount, 6)
        XCTAssertEqual(estimate.foods.first?.per100?.kcal, 200, "the table row wins over the model's per100")

        let request = try XCTUnwrap(AIStubProtocol.requests.first)
        XCTAssertEqual(request.url, DirectAnthropicEstimateService.endpoint)
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-api-key"), "sk-test")
        XCTAssertEqual(request.value(forHTTPHeaderField: "anthropic-version"), "2023-06-01")
        XCTAssertEqual(request.timeoutInterval, 90)
        let sent = try JSONValue.parse(XCTUnwrap(AIStubProtocol.bodies.first))
        XCTAssertEqual(sent["system"]?.stringValue, spec.estimateSystemInstruction(locale: "pl"))
        XCTAssertEqual(sent["messages"]?.arrayValue?.first?["content"]?.arrayValue?.last?["text"]?.stringValue,
                       spec.estimateRequestText(meal: "dinner", notes: "6 pierogów"))
    }

    func testClaudeLabelUsesTheLabelLimits() async throws {
        let label = #"{"legible":true,"name":"Serek","brand":"","basis":"per100g","servingSizeG":-1,"values":{"kcal":97,"kj":-1,"protein":11,"carbs":2,"fat":5,"fiber":-1,"sugar":-1,"salt":-1},"packageSizeG":200,"barcode":"","confidence":0.9}"#
        AIStubProtocol.responses = [.status(200, Self.claudeReply(label))]
        let reading = try await claude().readLabel(imageJPEG: Self.jpeg, locale: "pl")
        XCTAssertTrue(reading.legible)
        XCTAssertEqual(reading.per100?.kcal, 97)
        XCTAssertEqual(AIStubProtocol.requests.first?.timeoutInterval, 75)
        let sent = try JSONValue.parse(XCTUnwrap(AIStubProtocol.bodies.first))
        XCTAssertEqual(sent["max_tokens"], 4096)
        XCTAssertEqual(sent["output_config"]?["format"]?["schema"], spec.labelSchema())
    }

    func testClaudeStopReasons() {
        XCTAssertEqual(try DirectAnthropicEstimateService.answerText(status: 200, data: Self.claudeReply("{}")), "{}")
        XCTAssertThrowsError(try DirectAnthropicEstimateService.answerText(status: 200, data: Self.claudeReply("{\"fo", stop: "max_tokens"))) {
            XCTAssertEqual($0 as? AIEstimateError, .unreadable)
        }
        XCTAssertThrowsError(try DirectAnthropicEstimateService.answerText(status: 200, data: Self.claudeReply("", stop: "refusal"))) {
            XCTAssertEqual($0 as? AIEstimateError, .providerError(200, "refusal"))
        }
        XCTAssertEqual(try DirectAnthropicEstimateService.answerText(status: 200, data: Self.claudeReply("{}", stop: "pause_turn")), "{}")
    }

    func testClaudeStatusMapping() {
        let error = Data(#"{"type":"error","error":{"type":"invalid_request_error","message":"bad image"}}"#.utf8)
        let cases: [(Int, AIEstimateError)] = [
            (401, .keyRejected), (403, .keyRejected), (429, .busy), (500, .busy), (503, .busy), (529, .busy),
            (400, .providerError(400, "bad image")), (404, .providerError(404, "bad image")), (413, .providerError(413, "bad image")),
        ]
        for (status, expected) in cases {
            XCTAssertThrowsError(try DirectAnthropicEstimateService.answerText(status: status, data: error)) {
                XCTAssertEqual($0 as? AIEstimateError, expected, "HTTP \(status)")
            }
        }
    }

    func testClaudeWithoutKey() async {
        await assertThrows(.missingKey) { try await self.claude(" ").estimate(imageJPEG: Self.jpeg, meal: .lunch, locale: "en", notes: "") }
        XCTAssertTrue(AIStubProtocol.requests.isEmpty)
    }

    func testUnusableAnswerIsUnreadable() async {
        AIStubProtocol.responses = [.status(200, Self.claudeReply("Nie widzę jedzenia."))]
        await assertThrows(.unreadable) { try await self.claude().estimate(imageJPEG: Self.jpeg, meal: .lunch, locale: "pl", notes: "") }
    }

    // MARK: Gemini

    func testGeminiInteractionsBody() throws {
        let schema = AIEstimateSpec.forGemini(spec.estimateSchema())
        let body = DirectGeminiEstimateService.interactionsBody(spec: spec, system: "S", text: "T", schema: schema, imageBase64: "IMG")
        XCTAssertEqual(body["model"], "gemini-3.8-flash")
        XCTAssertEqual(body["store"], false)
        XCTAssertEqual(body["system_instruction"], "S")
        XCTAssertEqual(body["generation_config"], ["thinking_level": "low"])
        let input = try XCTUnwrap(body["input"]?.arrayValue)
        XCTAssertEqual(input.map { $0["type"] }, ["image", "text"])
        XCTAssertEqual(input[0]["resolution"], "high")
        XCTAssertEqual(input[0]["mime_type"], "image/jpeg")
        XCTAssertEqual(body["response_format"]?["mime_type"], "application/json")
        XCTAssertFalse(body.serialized().contains("additionalProperties"))
        XCTAssertFalse(body.serialized().contains("temperature"))
    }

    func testGeminiFallsBackToGenerateContentOn404() async throws {
        let reply: JSONValue = ["candidates": [["content": ["parts": [
            ["text": "ignored thought", "thought": true],
            ["text": .string(Self.modelAnswer)],
        ]]]]]
        AIStubProtocol.responses = [.status(404, Data(#"{"error":{"message":"not found"}}"#.utf8)), .status(200, reply.serializedData())]
        let estimate = try await gemini().estimate(imageJPEG: Self.jpeg, meal: .dinner, locale: "pl", notes: "")
        XCTAssertEqual(estimate.foods.count, 1)

        let urls = AIStubProtocol.requests.map { $0.url?.absoluteString }
        XCTAssertEqual(urls, ["https://generativelanguage.googleapis.com/v1beta/interactions",
                              "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"])
        for request in AIStubProtocol.requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: "x-goog-api-key"), "g-test")
            XCTAssertFalse(request.url?.absoluteString.contains("g-test") ?? true, "the key never goes in the URL")
        }
        let fallback = try JSONValue.parse(XCTUnwrap(AIStubProtocol.bodies.last))
        XCTAssertEqual(fallback["generationConfig"]?["thinkingConfig"], ["thinkingLevel": "low"])
        XCTAssertEqual(fallback["generationConfig"]?["mediaResolution"], "MEDIA_RESOLUTION_HIGH")
        XCTAssertEqual(fallback["generationConfig"]?["responseJsonSchema"], AIEstimateSpec.forGemini(spec.estimateSchema()))
        XCTAssertNil(fallback["generationConfig"]?["temperature"])
    }

    func testGeminiStatusMapping() async {
        AIStubProtocol.responses = [.status(400), .status(400, Data(#"{"error":{"message":"API key not valid. Please pass a valid API key."}}"#.utf8))]
        await assertThrows(.keyRejected) { try await self.gemini().estimate(imageJPEG: Self.jpeg, meal: .lunch, locale: "en", notes: "") }

        AIStubProtocol.reset()
        AIStubProtocol.responses = [.status(429)]
        await assertThrows(.busy) { try await self.gemini().estimate(imageJPEG: Self.jpeg, meal: .lunch, locale: "en", notes: "") }
        XCTAssertEqual(AIStubProtocol.requests.count, 1)

        AIStubProtocol.reset()
        AIStubProtocol.responses = [.status(404), .status(404, Data(#"{"error":{"message":"model not found"}}"#.utf8))]
        await assertThrows(.providerError(404, "model not found")) {
            try await self.gemini().readLabel(imageJPEG: Self.jpeg, locale: "en")
        }

        AIStubProtocol.reset()
        AIStubProtocol.responses = [.status(403)]
        await assertThrows(.keyRejected) { try await self.gemini().readLabel(imageJPEG: Self.jpeg, locale: "en") }

        AIStubProtocol.reset()
        AIStubProtocol.responses = [.status(200, Data(#"{"steps":[]}"#.utf8))]
        await assertThrows(.unreadable) { try await self.gemini().readLabel(imageJPEG: Self.jpeg, locale: "en") }
    }

    func testGeminiTextExtraction() {
        XCTAssertEqual(DirectGeminiEstimateService.extractText(["output_text": "A"]), "A")
        let interactions: JSONValue = ["outputs": [["type": "thought", "text": "hmm"], ["type": "text", "text": "{\"a\""],
                                                   ["type": "text", "text": ":1}"]]]
        XCTAssertEqual(DirectGeminiEstimateService.extractText(interactions), "{\"a\":1}")
        XCTAssertNil(DirectGeminiEstimateService.extractText(["candidates": []]))
    }

    func testTransportFailures() async {
        AIStubProtocol.responses = [.fail(URLError(.timedOut))]
        await assertThrows(.timeout) { try await self.gemini().estimate(imageJPEG: Self.jpeg, meal: .lunch, locale: "en", notes: "") }
        AIStubProtocol.reset()
        AIStubProtocol.responses = [.fail(URLError(.notConnectedToInternet))]
        await assertThrows(.offline) { try await self.claude().readLabel(imageJPEG: Self.jpeg, locale: "en") }
    }

    func testBarcodeGroundingUsesTheProduct() async throws {
        var answer = try JSONValue.parse(Self.modelAnswer)
        answer = answer.setting(["foods"], to: [["name": "Skyr", "grams": 150, "per100": ["kcal": 60, "protein": 10, "carbs": 4, "fat": 0.2],
                                                 "barcode": "5901234123457", "confidence": 0.9]])
        let estimate = try AIFinalizer.finalizeEstimate(answer, spec: spec, context: .init(weightGiven: false, measuredReference: false))
        let grounding = AIBarcodeGrounding { $0 == "5901234123457" ? AIPer100(kcal: 63, protein: 11, carbs: 4, fat: 0.2) : nil }
        let grounded = await grounding.ground(estimate)
        XCTAssertEqual(grounded.foods[0].nutritionSource, "open_food_facts")
        XCTAssertEqual(grounded.foods[0].kcal, 94.5)
        XCTAssertEqual(grounded.foods[0].adjustments?.last, "open_food_facts")
        XCTAssertEqual(grounded.totals?.kcal, 94.5)
    }

    /// Three packaged items with valid codes, 150 g each.
    private func packagedEstimate(_ codes: [String]) throws -> AIEstimate {
        var answer = try JSONValue.parse(Self.modelAnswer)
        let foods: [JSONValue] = codes.enumerated().map { index, code in
            ["name": .string("Item \(index)"), "grams": 150,
             "per100": ["kcal": 60, "protein": 10, "carbs": 4, "fat": 0.2], "barcode": .string(code), "confidence": 0.9]
        }
        answer = answer.setting(["foods"], to: .array(foods))
        return try AIFinalizer.finalizeEstimate(answer, spec: spec, context: .init(weightGiven: false, measuredReference: false))
    }

    /// Counts lookups in flight; each one waits until `expected` have started, so sequential lookups never finish.
    private actor Rendezvous {
        private var started = 0
        let expected: Int
        init(expected: Int) { self.expected = expected }
        func arrive() { started += 1 }
        var allStarted: Bool { started >= expected }
    }

    func testBarcodeGroundingRunsTheLookupsSideBySide() async throws {
        let codes = ["5901234123457", "5900259000002", "5900000000015"]
        let estimate = try packagedEstimate(codes)
        let rendezvous = Rendezvous(expected: codes.count)
        let grounding = AIBarcodeGrounding(budget: .seconds(20)) { _ in
            await rendezvous.arrive()
            while await !rendezvous.allStarted {
                guard !Task.isCancelled else { return nil }
                try? await Task.sleep(for: .milliseconds(5))
            }
            return AIPer100(kcal: 63, protein: 11, carbs: 4, fat: 0.2)
        }
        let clock = ContinuousClock()
        let started = clock.now
        let grounded = await grounding.ground(estimate)
        XCTAssertLessThan(clock.now - started, .seconds(10), "one after another, the first lookup would wait out the budget")
        XCTAssertEqual(grounded.foods.map(\.nutritionSource), Array(repeating: "open_food_facts", count: 3))
    }

    func testBarcodeGroundingStopsAtItsBudgetAndKeepsTheModelsValues() async throws {
        let fast = "5901234123457", slow = "5900259000002"
        let estimate = try packagedEstimate([fast, slow])
        let grounding = AIBarcodeGrounding(budget: .milliseconds(300)) { code in
            if code == slow {
                // OFF hanging on a lookup (each gets 15 s and a retry on the phone).
                try? await Task.sleep(for: .seconds(30))
                if Task.isCancelled { return nil }
            }
            return AIPer100(kcal: 63, protein: 11, carbs: 4, fat: 0.2)
        }
        let clock = ContinuousClock()
        let started = clock.now
        let grounded = await grounding.ground(estimate)
        XCTAssertLessThan(clock.now - started, .seconds(5), "the slow lookup is cut off at the budget")
        XCTAssertEqual(grounded.foods[0].nutritionSource, "open_food_facts", "a lookup that finished in time applies")
        XCTAssertEqual(grounded.foods[0].kcal, 94.5)
        XCTAssertNotEqual(grounded.foods[1].nutritionSource, "open_food_facts")
        XCTAssertEqual(grounded.foods[1].kcal, estimate.foods[1].kcal, "the late one keeps the model's values")
        XCTAssertEqual(grounded.totals?.kcal, 94.5 + estimate.foods[1].kcal)
    }

    private actor Counter {
        private(set) var value = 0
        func increment() { value += 1 }
    }

    func testBarcodeGroundingLooksUpARepeatedCodeOnce() async throws {
        let code = "5901234123457"
        let estimate = try packagedEstimate([code, code])
        let calls = Counter()
        let grounding = AIBarcodeGrounding { _ in
            await calls.increment()
            return AIPer100(kcal: 63, protein: 11, carbs: 4, fat: 0.2)
        }
        let grounded = await grounding.ground(estimate)
        XCTAssertEqual(grounded.foods.map(\.nutritionSource), ["open_food_facts", "open_food_facts"])
        let count = await calls.value
        XCTAssertEqual(count, 1, "the same product twice on the plate is one lookup")
    }

    // MARK: Backend

    func testBackendErrorMapping() {
        func http(_ status: Int, _ code: String) -> BackendError { .http(status: status, code: code, message: "m") }
        let cases: [(BackendError, AIEstimateError)] = [
            (.unauthorized, .signedOut), (.network, .offline), (.timedOut, .timeout), (.decoding, .unreadable),
            (http(403, "ai_not_allowed"), .notAllowed), (http(429, "ai_daily_limit"), .dailyLimit),
            (http(503, "ai_busy"), .busy), (http(504, "ai_timeout"), .timeout), (http(502, "ai_unparseable"), .unreadable),
            (http(502, "ai_upstream_error"), .providerError(502, "m")), (http(503, "ai_unavailable"), .providerError(503, "m")),
            (http(400, "invalid_body"), .providerError(400, "m")), (http(413, "image_too_large"), .providerError(413, "m")),
            (http(504, "http_504"), .timeout), (http(503, "http_503"), .busy), (http(429, "http_429"), .busy),
        ]
        for (error, expected) in cases {
            XCTAssertEqual(BackendAIEstimateService.map(error), expected, "\(error)")
        }
    }

    func testEveryErrorHasItsOwnMessage() {
        let errors: [AIEstimateError] = [.offline, .timeout, .busy, .dailyLimit, .notAllowed, .signedOut, .missingKey,
                                         .missingGeminiKey, .keyRejected, .unreadable, .providerError(500, nil)]
        let messages = errors.compactMap(\.errorDescription)
        XCTAssertEqual(Set(messages).count, errors.count)
        XCTAssertEqual(AIEstimateError.unreadable.labelMessage, String(localized: "fuel.label.unreadable"))
        XCTAssertNotEqual(AIEstimateError.timeout.errorDescription, AIEstimateError.offline.errorDescription)
    }

    func testOldServerEstimateStillDecodes() throws {
        let v1 = Data(#"{"foods":[{"name":"Rice","grams":200,"kcal":260,"proteinG":5.4,"carbsG":56,"fatG":0.6,"confidence":0.7,"isGuess":false}],"overallConfidence":0.7,"assumptions":[],"questions":["How much oil?"]}"#.utf8)
        let estimate = try JSONDecoder().decode(AIEstimate.self, from: v1)
        XCTAssertEqual(estimate.foods.first?.protein, 5.4)
        XCTAssertNil(estimate.foods.first?.per100)
        XCTAssertNil(estimate.version)
        XCTAssertEqual(estimate.questions, ["How much oil?"])
    }

    func testV2EstimateDecodes() throws {
        let raw = try JSONValue.parse(Self.modelAnswer)
        let finalized = try AIFinalizer.finalizeEstimate(raw, spec: spec, context: .init(weightGiven: false, measuredReference: false))
        let decoded = try JSONDecoder().decode(AIEstimate.self, from: finalized.contractJSON.serializedData())
        XCTAssertEqual(decoded.version, 2)
        XCTAssertEqual(decoded.foods[0].per100, finalized.foods[0].per100)
        XCTAssertEqual(decoded.foods[0].portionUnit, "szt.")
        XCTAssertEqual(decoded.foods[0].gramsPerUnit, 35)
        XCTAssertEqual(decoded.foods[0].adjustments, ["generic_table", "confidence_capped"])
        XCTAssertEqual(decoded.totals, finalized.totals)
        XCTAssertEqual(decoded.skipped, [])
    }

    func testLabelReadingDecodes() throws {
        let json = Data(#"{"version":1,"legible":false,"unreadableReason":"illegible","basis":"per100g","energyFrom":null,"name":"Baton","brand":"Sante","per100":null,"servingSizeG":null,"packageSizeG":30,"barcode":"","confidence":0.4,"needsReview":false}"#.utf8)
        let reading = try JSONDecoder().decode(LabelReading.self, from: json)
        XCTAssertFalse(reading.legible)
        XCTAssertEqual(reading.name, "Baton")
        XCTAssertNil(reading.per100)
        XCTAssertEqual(reading.packageSizeG, 30)
    }

    // MARK: Mock

    func testMockAnswersInTheV2Shape() async throws {
        let mock = MockAIEstimateService(delay: .zero)
        let estimate = try await mock.estimate(imageJPEG: Self.jpeg, meal: .breakfast, locale: "pl", notes: "")
        XCTAssertEqual(estimate.version, 2)
        XCTAssertTrue(estimate.foods.allSatisfy { $0.per100 != nil && $0.portionCount != nil })
        let reading = try await mock.readLabel(imageJPEG: Self.jpeg, locale: "pl")
        XCTAssertEqual(reading.name, "Serek wiejski")
        XCTAssertEqual(reading.per100?.kcal, 97)
    }
}

/// Queued canned responses; records each request and its body (URLSession hands the body over as a stream).
private final class AIStubProtocol: URLProtocol {
    enum Response {
        case status(Int, Data = Data())
        case fail(URLError)
    }

    private static let lock = NSLock()
    private static var queue: [Response] = []
    private static var log: [URLRequest] = []
    private static var bodyLog: [Data] = []

    static var responses: [Response] {
        get { lock.withLock { queue } }
        set { lock.withLock { queue = newValue } }
    }
    static var requests: [URLRequest] { lock.withLock { log } }
    static var bodies: [Data] { lock.withLock { bodyLog } }

    static func reset() {
        lock.withLock {
            queue = []
            log = []
            bodyLog = []
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = request.httpBody ?? request.httpBodyStream.map(Self.read) ?? Data()
        let next: Response? = Self.lock.withLock {
            Self.log.append(request)
            Self.bodyLog.append(body)
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

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 16_384)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
