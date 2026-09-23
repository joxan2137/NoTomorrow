import Foundation

/// JSON-over-HTTPS implementation of `BackendClient` for the Fly.io backend (`backend/src/routes/*.ts` is the
/// source of truth for paths and shapes). The Bearer token is read from `SessionStorage` on every call; a 401
/// triggers one refresh + retry (see `RemoteTransport`). Without a session every authenticated call throws
/// `BackendError.unauthorized` before touching the network, so the UI can show its signed-out state.
final class RemoteBackendClient: BackendClient, @unchecked Sendable {
    let baseURL: URL
    private let transport: RemoteTransport

    init(baseURL: URL, storage: SessionStorage, urlSession: URLSession = .shared) {
        self.baseURL = baseURL
        transport = RemoteTransport(baseURL: baseURL, storage: storage, urlSession: urlSession)
    }

    // MARK: Auth (public routes)

    func signIn(apple identityToken: String, authorizationCode: String) async throws -> Session {
        try await transport.send("POST", "auth/apple", auth: .none,
                                 json: ["identityToken": identityToken, "authorizationCode": authorizationCode])
    }

    func signIn(google idToken: String) async throws -> Session {
        try await transport.send("POST", "auth/google", auth: .none, json: ["idToken": idToken])
    }

    func signIn(username: String, password: String) async throws -> Session {
        try await transport.send("POST", "auth/login", auth: .none, json: ["username": username, "password": password])
    }

    func register(username: String, password: String, email: String?) async throws -> Session {
        struct Body: Encodable { var username: String; var password: String; var email: String? }
        return try await transport.send("POST", "auth/register", auth: .none,
                                        json: Body(username: username, password: password, email: email))
    }

    func logout(refreshToken: String) async throws {
        try await transport.sendVoid("POST", "auth/logout", auth: .none, json: ["refreshToken": refreshToken])
    }

    // MARK: Account

    func me() async throws -> Me { try await transport.send("GET", "me") }

    func updateMe(locale: String?, timeZone: String?) async throws {
        struct Body: Encodable { var locale: String?; var tz: String? }
        try await transport.sendVoid("PATCH", "me", json: Body(locale: locale, tz: timeZone))
    }

    func deleteAccount() async throws { try await transport.sendVoid("DELETE", "me") }

    // MARK: Pairing

    func createPairCode() async throws -> String {
        struct Reply: Decodable { var code: String }
        let reply: Reply = try await transport.send("POST", "pair/code")
        return reply.code
    }

    /// The reply carries the partner both at the top level and under `partner`; `Partner` decodes the top level.
    func pair(withCode code: String) async throws -> Partner {
        try await transport.send("POST", "pair", json: ["code": code])
    }

    func unpair() async throws { try await transport.sendVoid("DELETE", "pair") }

    // MARK: Schedule, attendance, heads-ups

    func pushSchedule(_ s: ScheduleDTO) async throws { try await transport.sendVoid("PUT", "schedule", json: s) }

    /// `/partner/state` merges partner and own rows into one `attendance` list tagged with `participant`.
    func partnerState() async throws -> PartnerState { try await transport.send("GET", "partner/state") }

    func setAttendance(day: Date, status: AttendanceStatus, reason: String?, note: String?, makeUpDay: Date?) async throws {
        struct Body: Encodable { var status: AttendanceStatus; var reason: String?; var note: String?; var makeUpDay: String? }
        let body = Body(status: status, reason: reason, note: note, makeUpDay: makeUpDay.map { WireDay.string($0) })
        try await transport.sendVoid("PUT", "attendance/\(WireDay.string(day))", json: body)
    }

    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date) async throws {
        struct Body: Encodable { var kind: HeadsUpKind; var text: String; var sessionDay: String }
        try await transport.sendVoid("POST", "headsups", json: Body(kind: kind, text: text, sessionDay: WireDay.string(sessionDay)))
    }

    // MARK: Push

    /// APNs token as lowercase hex; the server picks the environment its provider targets.
    func registerPushToken(_ token: Data) async throws {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        try await transport.sendVoid("POST", "push/token", json: ["token": hex])
    }

    // MARK: AI

    /// Multipart `image` + `meal` + `locale` + `notes`. The server answers the v2 estimate (per-100 g values, portions,
    /// totals); an older server's `{foods, overallConfidence}` still decodes. Errors surface as `BackendError.http` with
    /// the server code (`ai_daily_limit`, `ai_busy`, `ai_timeout`, …) for the AI service to map.
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?, notes: String) async throws -> AIEstimate {
        let body = MultipartBody()
            .field("meal", meal.rawValue)
            .field("locale", locale)
            // The server counts UTF-16 units and refuses more than 1500 with a 400.
            .field("notes", AIEstimateSpec.prefixUTF16(notes, 1500))
            .file("image", filename: "plate.jpg", mimeType: "image/jpeg", data: imageJPEG)
        var headers: [String: String] = [:]
        if let anthropicKey, !anthropicKey.isEmpty { headers["X-Anthropic-Key"] = anthropicKey }
        let request = RemoteTransport.Request(method: "POST", path: "ai/estimate", auth: .required,
                                              payload: .multipart(body), headers: headers, timeout: 90)
        return try transport.decode(try await transport.perform(request))
    }

    /// Multipart `image` (a label shot, up to 1600 px) + `locale`. `legible: false` is a 200, not an error. The server
    /// gives up after 65 s, so 75 s here leaves room for the upload.
    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        let body = MultipartBody()
            .field("locale", locale)
            .file("image", filename: "label.jpg", mimeType: "image/jpeg", data: imageJPEG)
        let request = RemoteTransport.Request(method: "POST", path: "ai/label", auth: .required,
                                              payload: .multipart(body), timeout: 75)
        return try transport.decode(try await transport.perform(request))
    }
}
