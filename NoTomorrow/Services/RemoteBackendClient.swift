import Foundation

/// JSON-over-HTTPS implementation of `BackendClient` for the Fly.io backend.
/// Bearer token comes from `accessToken()` on every call so a refreshed session is picked up automatically.
final class RemoteBackendClient: BackendClient, @unchecked Sendable {
    let baseURL: URL
    private let accessToken: @Sendable () -> String?
    private let urlSession: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(baseURL: URL, accessToken: @escaping @Sendable () -> String?, urlSession: URLSession = .shared) {
        self.baseURL = baseURL
        self.accessToken = accessToken
        self.urlSession = urlSession
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
    }

    // MARK: Auth

    func signIn(apple identityToken: String, authorizationCode: String) async throws -> Session {
        try await send("POST", "auth/apple", body: ["identityToken": identityToken, "authorizationCode": authorizationCode])
    }

    func signIn(google idToken: String) async throws -> Session {
        try await send("POST", "auth/google", body: ["idToken": idToken])
    }

    func signIn(username: String, password: String) async throws -> Session {
        try await send("POST", "auth/password", body: ["username": username, "password": password])
    }

    func register(username: String, password: String, email: String?) async throws -> Session {
        try await send("POST", "auth/register", body: ["username": username, "password": password, "email": email])
    }

    func me() async throws -> Me { try await send("GET", "me") }

    // MARK: Pairing & attendance

    func createPairCode() async throws -> String {
        struct Reply: Decodable { var code: String }
        let reply: Reply = try await send("POST", "pair/code")
        return reply.code
    }

    func pair(withCode code: String) async throws -> Partner {
        try await send("POST", "pair", body: ["code": code])
    }

    func unpair() async throws { try await sendVoid("DELETE", "pair") }

    func pushSchedule(_ s: ScheduleDTO) async throws { try await sendVoid("PUT", "schedule", body: s) }

    func partnerState() async throws -> PartnerState { try await send("GET", "partner/state") }

    func setAttendance(day: Date, status: AttendanceStatus, reason: String?, note: String?, makeUpDay: Date?) async throws {
        struct Body: Encodable { var day: Date; var status: AttendanceStatus; var reason: String?; var note: String?; var makeUpDay: Date? }
        try await sendVoid("PUT", "attendance", body: Body(day: day, status: status, reason: reason, note: note, makeUpDay: makeUpDay))
    }

    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date) async throws {
        struct Body: Encodable { var kind: HeadsUpKind; var text: String; var sessionDay: Date }
        try await sendVoid("POST", "headsup", body: Body(kind: kind, text: text, sessionDay: sessionDay))
    }

    func registerPushToken(_ token: Data) async throws {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        try await sendVoid("POST", "push/token", body: ["token": hex])
    }

    // MARK: AI

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?) async throws -> AIEstimate {
        var request = makeRequest("POST", "ai/estimate")
        let boundary = "nt-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        if let anthropicKey { request.setValue(anthropicKey, forHTTPHeaderField: "X-Anthropic-Key") }
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!)
        }
        field("meal", meal.rawValue)
        field("locale", locale)
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"plate.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
        body.append(imageJPEG)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        return try await perform(request)
    }

    func deleteAccount() async throws { try await sendVoid("DELETE", "account") }

    // MARK: Plumbing

    private func makeRequest(_ method: String, _ path: String) -> URLRequest {
        var request = URLRequest(url: baseURL.appending(path: path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("NoTomorrow/0.1 iOS", forHTTPHeaderField: "User-Agent")
        if let token = accessToken() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return request
    }

    private func send<T: Decodable>(_ method: String, _ path: String) async throws -> T {
        try await perform(makeRequest(method, path))
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B) async throws -> T {
        var request = makeRequest(method, path)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        return try await perform(request)
    }

    private func sendVoid(_ method: String, _ path: String) async throws {
        _ = try await data(for: makeRequest(method, path))
    }

    private func sendVoid<B: Encodable>(_ method: String, _ path: String, body: B) async throws {
        var request = makeRequest(method, path)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        _ = try await data(for: request)
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await data(for: request)
        do { return try decoder.decode(T.self, from: data) } catch { throw BackendError.decoding }
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await urlSession.data(for: request) } catch { throw BackendError.network }
        guard let http = response as? HTTPURLResponse else { throw BackendError.network }
        switch http.statusCode {
        case 200..<300: return data
        case 401, 403: throw BackendError.unauthorized
        default:
            struct ServerMessage: Decodable { var error: String?; var message: String? }
            let parsed = try? decoder.decode(ServerMessage.self, from: data)
            throw BackendError.server(parsed?.message ?? parsed?.error ?? "HTTP \(http.statusCode)")
        }
    }
}
