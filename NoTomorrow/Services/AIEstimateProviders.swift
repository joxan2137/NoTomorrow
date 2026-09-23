import Foundation

// MARK: - Backend (Fly.io) provider

/// Sends the photo through `BackendClient.estimate` / `readLabel` (multipart, Bearer session, refresh-on-401) and maps
/// the server's error codes to `AIEstimateError`. The user's own keys never go through the backend; the direct
/// providers below handle those paths. The server answers the v2 estimate (per-100 g values, portions); an older
/// server's v1 answer still decodes, with the v2 fields left nil.
struct BackendAIEstimateService: AIEstimateService {
    let client: any BackendClient

    init(client: any BackendClient = AppConfig.shared.makeBackendClient()) {
        self.client = client
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        do {
            return try await client.estimate(imageJPEG: imageJPEG, meal: meal, locale: locale, anthropicKey: nil, notes: notes)
        } catch {
            throw Self.map(error)
        }
    }

    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        do {
            return try await client.readLabel(imageJPEG: imageJPEG, locale: locale)
        } catch {
            throw Self.map(error)
        }
    }

    static func map(_ error: Error) -> Error {
        if error is CancellationError { return error }
        return map(BackendError.wrap(error))
    }

    /// The server's `error` code first, the HTTP status second (contract §10).
    static func map(_ error: BackendError) -> AIEstimateError {
        switch error {
        case .unauthorized: return .signedOut
        case .network: return .offline
        case .timedOut: return .timeout
        case .decoding: return .unreadable
        case .server(let message): return .providerError(0, message)
        case .http(let status, let code, let message):
            switch code {
            case "ai_not_allowed": return .notAllowed
            case "ai_daily_limit": return .dailyLimit
            case "ai_busy": return .busy
            case "ai_timeout": return .timeout
            case "ai_unparseable": return .unreadable
            case "ai_upstream_error", "ai_unavailable": return .providerError(status, message)
            default: break
            }
            switch status {
            case 504: return .timeout
            case 429, 503: return .busy
            default: return .providerError(status, message)
            }
        }
    }
}

// MARK: - Shared by the bring-your-own-key providers

/// One POST of a JSON body; transport failures become `timeout` / `offline`.
enum AIDirectTransport {
    static func post(_ url: URL, headers: [String: String], body: JSONValue, timeout: TimeInterval,
                     session: URLSession) async throws -> (status: Int, data: Data) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        request.httpBody = body.serializedData()
        do {
            let (data, response) = try await session.data(for: request)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
        } catch {
            throw AIEstimateError.transport(error)
        }
    }

    /// `error.message` of a provider's error envelope.
    static func errorMessage(_ data: Data) -> String? {
        (try? JSONValue.parse(data))?["error"]?["message"]?.stringValue
    }

    /// Model text → finalized estimate with the notes context; unusable output is `unreadable`.
    static func finalizeEstimate(_ text: String, spec: AIEstimateSpec, notes: String) throws -> AIEstimate {
        do {
            return try AIFinalizer.finalizeEstimateText(text, spec: spec, context: spec.notesContext(notes))
        } catch is AIOutputError {
            throw AIEstimateError.unreadable
        }
    }

    static func finalizeLabel(_ text: String, spec: AIEstimateSpec) throws -> LabelReading {
        do {
            return try AIFinalizer.finalizeLabelText(text, spec: spec)
        } catch is AIOutputError {
            throw AIEstimateError.unreadable
        }
    }

    static func imageBase64(_ jpeg: Data) -> String { jpeg.base64EncodedString() }
}

/// Barcode grounding for the direct paths, like the backend: an item with a fully readable barcode that Open Food Facts
/// knows takes the product's per-100 g values. Best effort: any failure keeps the model's values.
///
/// The lookups run side by side (the server's `Promise.all`), one per distinct code, and the whole grounding gets
/// `budget`: the user is already waiting on the model and the request runs on the background grace, so a lookup still
/// running then (OFF slow, its retry after a 429 / 5xx) is cancelled and that item keeps the model's values. Lookups
/// that finished in time still apply.
struct AIBarcodeGrounding {
    /// The first items only, as on the server.
    static let maxItems = 8
    /// The server gives each lookup 6 s; a phone lookup that has to retry after a 429 / 5xx (1.5 s pause) still fits
    /// one slow answer.
    static let defaultBudget: Duration = .seconds(7)

    var budget: Duration = AIBarcodeGrounding.defaultBudget
    var lookup: @Sendable (String) async -> AIPer100?

    init(budget: Duration = AIBarcodeGrounding.defaultBudget, lookup: @escaping @Sendable (String) async -> AIPer100?) {
        self.budget = budget
        self.lookup = lookup
    }

    static let openFoodFacts = AIBarcodeGrounding { code in
        guard case .found(let candidate)? = try? await FoodSearchService.shared.lookup(barcode: code, locale: FuelText.locale)
        else { return nil }
        return AIPer100(kcal: candidate.kcalPer100, protein: candidate.proteinPer100, carbs: candidate.carbsPer100,
                        fat: candidate.fatPer100, alcohol: 0)
    }

    func ground(_ estimate: AIEstimate) async -> AIEstimate {
        var indicesByCode: [String: [Int]] = [:]
        var codes: [String] = []
        for index in estimate.foods.indices.prefix(Self.maxItems) {
            guard let code = estimate.foods[index].barcode, !code.isEmpty else { continue }
            if indicesByCode[code] == nil { codes.append(code) }
            indicesByCode[code, default: []].append(index)
        }
        guard !codes.isEmpty else { return estimate }

        let lookup = self.lookup
        let budget = self.budget
        // nil = the deadline. The group waits for its children when it ends, so the late lookups are cancelled
        // (URLSession and the retry pause both stop on cancellation) rather than left to run.
        let found = await withTaskGroup(of: (code: String, per100: AIPer100?)?.self) { group -> [String: AIPer100] in
            for code in codes {
                group.addTask { (code, await lookup(code)) }
            }
            group.addTask {
                try? await Task.sleep(for: budget)
                return nil
            }
            var found: [String: AIPer100] = [:]
            var pending = codes.count
            while pending > 0, let result = await group.next() {
                guard let result else { break }
                pending -= 1
                if let per100 = result.per100 { found[result.code] = per100 }
            }
            group.cancelAll()
            return found
        }

        var grounded = estimate
        for code in codes {
            guard let per100 = found[code] else { continue }
            for index in indicesByCode[code] ?? [] {
                AIFinalizer.groundWithDatabase(&grounded, index: index, per100: per100)
            }
        }
        return grounded
    }
}

// MARK: - Direct Claude provider (bring your own key)

/// Calls the Messages API straight from the device with the user's own key. Only used when the user opted in to
/// "Claude with your key" in Settings; the photo leaves the device by explicit user action. Structured output with the
/// shared schema, `effort: low` (Sonnet 5 thinks adaptively by default; thinking counts toward `max_tokens`).
struct DirectAnthropicEstimateService: AIEstimateService {
    static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!
    static let estimateTimeout: TimeInterval = 90
    static let labelTimeout: TimeInterval = 75

    let apiKey: () -> String?
    var session: URLSession = .shared
    var spec: AIEstimateSpec = .shared
    var grounding: AIBarcodeGrounding? = .openFoodFacts

    init(apiKey: @escaping () -> String?, session: URLSession = .shared, spec: AIEstimateSpec = .shared,
         grounding: AIBarcodeGrounding? = .openFoodFacts) {
        self.apiKey = apiKey
        self.session = session
        self.spec = spec
        self.grounding = grounding
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        let text = try await send(system: spec.estimateSystemInstruction(locale: locale),
                                  text: spec.estimateRequestText(meal: meal.rawValue, notes: notes),
                                  schema: spec.estimateSchema(), maxTokens: spec.claude.estimateMaxTokens,
                                  imageJPEG: imageJPEG, timeout: Self.estimateTimeout)
        let estimate = try AIDirectTransport.finalizeEstimate(text, spec: spec, notes: notes)
        return await grounding?.ground(estimate) ?? estimate
    }

    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        let text = try await send(system: spec.labelSystemInstruction(locale: locale), text: spec.labelRequestText,
                                  schema: spec.labelSchema(), maxTokens: spec.claude.labelMaxTokens,
                                  imageJPEG: imageJPEG, timeout: Self.labelTimeout)
        return try AIDirectTransport.finalizeLabel(text, spec: spec)
    }

    private func send(system: String, text: String, schema: JSONValue, maxTokens: Int, imageJPEG: Data,
                      timeout: TimeInterval) async throws -> String {
        guard let key = apiKey()?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            throw AIEstimateError.missingKey
        }
        let body = Self.body(spec: spec, system: system, text: text, schema: schema, maxTokens: maxTokens,
                             imageBase64: AIDirectTransport.imageBase64(imageJPEG))
        let reply = try await AIDirectTransport.post(Self.endpoint,
                                                     headers: ["x-api-key": key, "anthropic-version": spec.claude.anthropicVersion],
                                                     body: body, timeout: timeout, session: session)
        return try Self.answerText(status: reply.status, data: reply.data)
    }

    /// No `temperature` / `top_p` / `top_k` (a non-default value is a 400 on Sonnet 5), no `thinking`, no prefill.
    static func body(spec: AIEstimateSpec, system: String, text: String, schema: JSONValue, maxTokens: Int,
                     imageBase64: String) -> JSONValue {
        [
            "model": .string(spec.claude.model),
            "max_tokens": .number(Double(maxTokens)),
            "system": .string(system),
            "output_config": [
                "effort": .string(spec.claude.effort),
                "format": ["type": "json_schema", "schema": schema],
            ],
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "image", "source": ["type": "base64", "media_type": "image/jpeg", "data": .string(imageBase64)]],
                    ["type": "text", "text": .string(text)],
                ],
            ]],
        ]
    }

    /// Status first, then `stop_reason`: a cut-off answer is unreadable, a refusal is a provider error.
    static func answerText(status: Int, data: Data) throws -> String {
        switch status {
        case 200..<300:
            break
        case 401, 403:
            throw AIEstimateError.keyRejected
        case 429, 500, 503, 529:
            throw AIEstimateError.busy
        default:
            throw AIEstimateError.providerError(status, AIDirectTransport.errorMessage(data))
        }
        guard let json = try? JSONValue.parse(data) else { throw AIEstimateError.unreadable }
        switch json["stop_reason"]?.stringValue {
        case "max_tokens": throw AIEstimateError.unreadable
        case "refusal": throw AIEstimateError.providerError(status, "refusal")
        default: break
        }
        let text = (json["content"]?.arrayValue ?? [])
            .compactMap { $0["type"]?.stringValue == "text" ? $0["text"]?.stringValue : nil }
            .joined()
        guard !text.isEmpty else { throw AIEstimateError.unreadable }
        return text
    }
}

// MARK: - Direct Gemini provider (bring your own key)

/// Calls the Generative Language API straight from the device with the user's own key. Only used when the user
/// opted in to "Gemini with your key" in Settings; the photo goes to Google, same as the backend path. Interactions
/// API first (static system instruction, thinking level, image resolution, response schema), `generateContent` when
/// Interactions answers 404 or 400. The key goes in a header, never in the URL.
struct DirectGeminiEstimateService: AIEstimateService {
    static let base = URL(string: "https://generativelanguage.googleapis.com/v1beta")!
    static let timeout: TimeInterval = 60

    let apiKey: () -> String?
    var session: URLSession = .shared
    var spec: AIEstimateSpec = .shared
    var grounding: AIBarcodeGrounding? = .openFoodFacts

    init(apiKey: @escaping () -> String?, session: URLSession = .shared, spec: AIEstimateSpec = .shared,
         grounding: AIBarcodeGrounding? = .openFoodFacts) {
        self.apiKey = apiKey
        self.session = session
        self.spec = spec
        self.grounding = grounding
    }

    static var interactionsURL: URL { base.appending(path: "interactions") }

    static func generateContentURL(model: String) -> URL { base.appending(path: "models/\(model):generateContent") }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        let text = try await send(system: spec.estimateSystemInstruction(locale: locale),
                                  text: spec.estimateRequestText(meal: meal.rawValue, notes: notes),
                                  schema: spec.estimateSchema(), imageJPEG: imageJPEG)
        let estimate = try AIDirectTransport.finalizeEstimate(text, spec: spec, notes: notes)
        return await grounding?.ground(estimate) ?? estimate
    }

    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        let text = try await send(system: spec.labelSystemInstruction(locale: locale), text: spec.labelRequestText,
                                  schema: spec.labelSchema(), imageJPEG: imageJPEG)
        return try AIDirectTransport.finalizeLabel(text, spec: spec)
    }

    private func send(system: String, text: String, schema: JSONValue, imageJPEG: Data) async throws -> String {
        guard let key = apiKey()?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            throw AIEstimateError.missingGeminiKey
        }
        let image = AIDirectTransport.imageBase64(imageJPEG)
        let geminiSchema = AIEstimateSpec.forGemini(schema)
        let headers = ["x-goog-api-key": key]
        let first = try await AIDirectTransport.post(
            Self.interactionsURL, headers: headers,
            body: Self.interactionsBody(spec: spec, system: system, text: text, schema: geminiSchema, imageBase64: image),
            timeout: Self.timeout, session: session)
        if first.status == 400 || first.status == 404 {
            // 404: the model is not served by Interactions for this key. 400: this request shape was refused; the
            // established API answers it, and a bad key fails there too.
            let second = try await AIDirectTransport.post(
                Self.generateContentURL(model: spec.gemini.model), headers: headers,
                body: Self.generateContentBody(spec: spec, system: system, text: text, schema: geminiSchema, imageBase64: image),
                timeout: Self.timeout, session: session)
            return try Self.answerText(status: second.status, data: second.data, isFallback: true)
        }
        return try Self.answerText(status: first.status, data: first.data, isFallback: false)
    }

    /// `schema` is the Gemini variant (no `additionalProperties`). No `temperature`: Gemini 3 runs at its default.
    static func interactionsBody(spec: AIEstimateSpec, system: String, text: String, schema: JSONValue,
                                 imageBase64: String) -> JSONValue {
        [
            "model": .string(spec.gemini.model),
            "store": false,
            "system_instruction": .string(system),
            "generation_config": ["thinking_level": .string(spec.gemini.thinkingLevel)],
            "input": [
                ["type": "image", "data": .string(imageBase64), "mime_type": "image/jpeg",
                 "resolution": .string(spec.gemini.imageResolution)],
                ["type": "text", "text": .string(text)],
            ],
            "response_format": ["type": "text", "mime_type": "application/json", "schema": schema],
        ]
    }

    static func generateContentBody(spec: AIEstimateSpec, system: String, text: String, schema: JSONValue,
                                    imageBase64: String) -> JSONValue {
        [
            "systemInstruction": ["parts": [["text": .string(system)]]],
            "contents": [[
                "role": "user",
                "parts": [
                    ["inlineData": ["mimeType": "image/jpeg", "data": .string(imageBase64)]],
                    ["text": .string(text)],
                ],
            ]],
            "generationConfig": [
                "responseMimeType": "application/json",
                "responseJsonSchema": schema,
                "thinkingConfig": ["thinkingLevel": .string(spec.gemini.thinkingLevel)],
                "mediaResolution": .string(spec.gemini.generateContentMediaResolution),
            ],
        ]
    }

    /// Status mapping for either step (the 400/404 fallback is decided by the caller for step 1).
    static func answerText(status: Int, data: Data, isFallback: Bool) throws -> String {
        switch status {
        case 200..<300:
            guard let json = try? JSONValue.parse(data), let text = extractText(json), !text.isEmpty else {
                throw AIEstimateError.unreadable
            }
            return text
        case 401, 403:
            throw AIEstimateError.keyRejected
        case 400:
            let message = AIDirectTransport.errorMessage(data)
            // Google answers a bad or expired key with 400 INVALID_ARGUMENT, not 401.
            if message?.contains("API key") == true { throw AIEstimateError.keyRejected }
            throw AIEstimateError.providerError(status, message)
        case 429, 500..<600:
            throw AIEstimateError.busy
        default:
            throw AIEstimateError.providerError(status, AIDirectTransport.errorMessage(data))
        }
    }

    /// The model's text from either API: `output_text`, else a depth-first walk (≤ 8 levels) through `steps`,
    /// `outputs`, `output`, `candidates`, `content` and `parts`, skipping thought parts. Joined with "".
    static func extractText(_ json: JSONValue) -> String? {
        guard let root = json.objectValue else { return nil }
        if let text = root["output_text"]?.stringValue { return text }
        var texts: [String] = []
        func visit(_ node: JSONValue, _ depth: Int) {
            guard depth <= 8 else { return }
            switch node {
            case .array(let items):
                for item in items { visit(item, depth + 1) }
            case .object(let o):
                if o["thought"] == .bool(true) || o["type"] == .string("thought") { return }
                if let text = o["text"]?.stringValue, o["type"] == nil || o["type"] == .string("text") { texts.append(text) }
                for key in ["steps", "outputs", "output", "candidates", "content", "parts"] {
                    if let child = o[key] { visit(child, depth + 1) }
                }
            default:
                return
            }
        }
        visit(json, 0)
        return texts.isEmpty ? nil : texts.joined()
    }
}
