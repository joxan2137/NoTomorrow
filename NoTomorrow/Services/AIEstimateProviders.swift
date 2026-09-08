import Foundation

// MARK: - Backend (Fly.io) provider

/// Sends the photo through `BackendClient.estimate` (multipart `image` + `meal` + `locale`, Bearer session,
/// refresh-on-401) and maps the server's error codes to user-facing `AIEstimateError`s. The user's own Claude key
/// never goes through the backend; `DirectAnthropicEstimateService` handles that path.
struct BackendAIEstimateService: AIEstimateService {
    let client: any BackendClient

    init(client: any BackendClient = AppConfig.shared.makeBackendClient()) {
        self.client = client
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        do {
            return try await client.estimate(imageJPEG: imageJPEG, meal: meal, locale: locale, anthropicKey: nil, notes: notes)
        } catch {
            throw Self.map(BackendError.wrap(error))
        }
    }

    /// 429/502/503 (and any `ai_*` code) → "busy, try again in a minute"; `ai_daily_limit` and `ai_not_allowed`
    /// get their own lines.
    static func map(_ error: BackendError) -> AIEstimateError {
        switch error {
        case .unauthorized: return .signedOut
        case .network: return .network
        case .decoding: return .invalidJSON
        case .server(let message): return .badResponse(0, message)
        case .http(let status, let code, let message):
            if code == "ai_not_allowed" { return .notAllowed }
            if code == "ai_daily_limit" { return .dailyLimit }
            if [429, 502, 503].contains(status) || code.hasPrefix("ai_") { return .busy }
            return .badResponse(status, message)
        }
    }
}

// MARK: - Direct Claude provider (bring your own key)

/// Calls the Messages API straight from the device with the user's own key. Only used when the user opted in to
/// "Claude with your key" in Settings; the photo leaves the device by explicit user action.
struct DirectAnthropicEstimateService: AIEstimateService {
    static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!
    static let model = "claude-sonnet-5"
    static let apiVersion = "2023-06-01"

    let apiKey: () -> String?
    var session: URLSession = .shared

    init(apiKey: @escaping () -> String?, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.session = session
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        guard let key = apiKey()?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            throw AIEstimateError.missingKey
        }
        let body: [String: Any] = [
            "model": Self.model,
            "max_tokens": 1024,
            "messages": [[
                "role": "user",
                "content": [
                    ["type": "image",
                     "source": ["type": "base64", "media_type": "image/jpeg", "data": imageJPEG.base64EncodedString()]],
                    ["type": "text", "text": AIEstimatePrompt.text(meal: meal, locale: locale) + "\nMeal details: " + String(notes.prefix(1500))],
                ],
            ]],
        ]
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue(Self.apiVersion, forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) } catch { throw AIEstimateError.network }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200..<300: break
        case 401, 403: throw AIEstimateError.unauthorized
        default:
            let message = (try? JSONDecoder().decode(AnthropicErrorEnvelope.self, from: data))?.error?.message
            throw AIEstimateError.badResponse(status, message)
        }

        let envelope = try? JSONDecoder().decode(AnthropicMessageResponse.self, from: data)
        let text = (envelope?.content ?? []).compactMap { $0.type == "text" ? $0.text : nil }.joined(separator: "\n")
        guard let json = AIEstimatePrompt.extractJSON(from: text) else { throw AIEstimateError.invalidJSON }
        return try AIEstimateWire.decode(json)
    }
}

struct AnthropicMessageResponse: Decodable {
    struct Block: Decodable {
        let type: String
        let text: String?
    }
    let content: [Block]?
    let stopReason: String?

    enum CodingKeys: String, CodingKey {
        case content
        case stopReason = "stop_reason"
    }
}

struct AnthropicErrorEnvelope: Decodable {
    struct Detail: Decodable {
        let type: String?
        let message: String?
    }
    let error: Detail?
}

// MARK: - Direct Gemini provider (bring your own key)

/// Calls the Generative Language API straight from the device with the user's own key. Only used when the user
/// opted in to "Gemini with your key" in Settings; the photo goes to Google, same as the backend path.
struct DirectGeminiEstimateService: AIEstimateService {
    /// The same default the backend uses.
    static let model = "gemini-3.8-flash"
    static let endpoint = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent")!

    let apiKey: () -> String?
    var session: URLSession = .shared

    init(apiKey: @escaping () -> String?, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.session = session
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        guard let key = apiKey()?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty else {
            throw AIEstimateError.missingGeminiKey
        }
        let body: [String: Any] = [
            "contents": [[
                "role": "user",
                "parts": [
                    ["text": AIEstimatePrompt.text(meal: meal, locale: locale) + "\nMeal details: " + String(notes.prefix(1500))],
                    ["inlineData": ["mimeType": "image/jpeg", "data": imageJPEG.base64EncodedString()]],
                ],
            ]],
            "generationConfig": ["responseMimeType": "application/json"],
        ]
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) } catch { throw AIEstimateError.network }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200..<300: break
        case 401, 403: throw AIEstimateError.unauthorized
        case 429: throw AIEstimateError.busy
        default:
            let message = (try? JSONDecoder().decode(GeminiErrorEnvelope.self, from: data))?.error?.message
            // Google answers a bad or expired key with 400 INVALID_ARGUMENT, not 401.
            if status == 400, message?.contains("API key") == true { throw AIEstimateError.unauthorized }
            throw AIEstimateError.badResponse(status, message)
        }

        let envelope = try? JSONDecoder().decode(GeminiGenerateContentResponse.self, from: data)
        let parts = envelope?.candidates?.first?.content?.parts ?? []
        let text = parts.compactMap(\.text).joined(separator: "\n")
        guard let json = AIEstimatePrompt.extractJSON(from: text) else { throw AIEstimateError.invalidJSON }
        return try AIEstimateWire.decode(json)
    }
}

struct GeminiGenerateContentResponse: Decodable {
    struct Candidate: Decodable {
        struct Content: Decodable {
            struct Part: Decodable {
                let text: String?
            }
            let parts: [Part]?
        }
        let content: Content?
    }
    let candidates: [Candidate]?
}

struct GeminiErrorEnvelope: Decodable {
    struct Detail: Decodable {
        let status: String?
        let message: String?
    }
    let error: Detail?
}
