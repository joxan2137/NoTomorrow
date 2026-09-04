import Foundation

// MARK: - Backend (Fly.io) provider

/// POSTs multipart `image` + `meal` + `locale` to `<baseURL>/ai/estimate`. The backend runs Gemini by default; when the
/// user stored their own Claude key we forward it as `X-Anthropic-Key` and the backend calls Claude instead.
struct BackendAIEstimateService: AIEstimateService {
    let baseURL: URL
    let authToken: () async -> String?
    let anthropicKey: () -> String?
    var session: URLSession = .shared

    init(baseURL: URL, authToken: @escaping () async -> String?, anthropicKey: @escaping () -> String? = { nil },
         session: URLSession = .shared) {
        self.baseURL = baseURL
        self.authToken = authToken
        self.anthropicKey = anthropicKey
        self.session = session
    }

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String) async throws -> AIEstimate {
        let boundary = "NoTomorrow-\(UUID().uuidString)"
        var request = URLRequest(url: baseURL.appendingPathComponent("ai/estimate"))
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = await authToken() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let key = anthropicKey(), !key.isEmpty { request.setValue(key, forHTTPHeaderField: "X-Anthropic-Key") }
        request.httpBody = MultipartBody(boundary: boundary)
            .field("meal", meal.rawValue)
            .field("locale", locale)
            .file("image", filename: "plate.jpg", mimeType: "image/jpeg", data: imageJPEG)
            .encoded()

        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) } catch { throw AIEstimateError.network }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200..<300:
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            if let estimate = try? decoder.decode(AIEstimate.self, from: data) { return estimate }
            return try AIEstimateWire.decode(data)
        case 401, 403: throw AIEstimateError.unauthorized
        default: throw AIEstimateError.badResponse(status, String(data: data, encoding: .utf8))
        }
    }
}

/// Minimal multipart/form-data writer.
struct MultipartBody {
    let boundary: String
    private var parts: [Data] = []

    init(boundary: String) { self.boundary = boundary }

    func field(_ name: String, _ value: String) -> MultipartBody {
        var copy = self
        var d = Data()
        d.append("--\(boundary)\r\n")
        d.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
        d.append("\(value)\r\n")
        copy.parts.append(d)
        return copy
    }

    func file(_ name: String, filename: String, mimeType: String, data: Data) -> MultipartBody {
        var copy = self
        var d = Data()
        d.append("--\(boundary)\r\n")
        d.append("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n")
        d.append("Content-Type: \(mimeType)\r\n\r\n")
        d.append(data)
        d.append("\r\n")
        copy.parts.append(d)
        return copy
    }

    func encoded() -> Data {
        var out = Data()
        parts.forEach { out.append($0) }
        out.append("--\(boundary)--\r\n")
        return out
    }
}

private extension Data {
    mutating func append(_ string: String) { append(Data(string.utf8)) }
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

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String) async throws -> AIEstimate {
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
                    ["type": "text", "text": AIEstimatePrompt.text(meal: meal, locale: locale)],
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
