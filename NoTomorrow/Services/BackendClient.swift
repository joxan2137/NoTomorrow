import Foundation

// MARK: - Backend contract
//
// Everything the app needs from the Fly.io backend (auth, pairing, shared attendance, AI estimates).
// `MockBackendClient` implements it in memory for offline demos; `RemoteBackendClient` speaks JSON over HTTPS.

protocol BackendClient: Sendable {
    var baseURL: URL { get }

    func signIn(apple identityToken: String, authorizationCode: String) async throws -> Session
    func signIn(google idToken: String) async throws -> Session
    func signIn(username: String, password: String) async throws -> Session
    func register(username: String, password: String, email: String?) async throws -> Session
    /// Best-effort server-side revoke of the refresh token; the caller clears the Keychain regardless.
    func logout(refreshToken: String) async throws
    func me() async throws -> Me
    /// Locale/time zone the server uses for push copy and for "day" instants in replies.
    func updateMe(locale: String?, timeZone: String?) async throws

    func createPairCode() async throws -> String
    func pair(withCode: String) async throws -> Partner
    func unpair() async throws
    func pushSchedule(_ s: ScheduleDTO) async throws
    /// Partner schedule, attendance for the current week (plus recent history) and unread heads-ups.
    func partnerState() async throws -> PartnerState
    func setAttendance(day: Date, status: AttendanceStatus, reason: String?, note: String?, makeUpDay: Date?) async throws
    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date) async throws
    func registerPushToken(_ token: Data) async throws

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?, notes: String) async throws -> AIEstimate
    func deleteAccount() async throws
}

// MARK: - DTOs

struct Session: Codable, Sendable, Equatable {
    var accessToken: String
    var refreshToken: String
    var userId: String
}

struct Me: Codable, Sendable, Equatable {
    var id: String
    var username: String
    var displayName: String
    var email: String?
    var partner: Partner?
    /// The user's current pair code, if one was issued.
    var pairCode: String?
}

struct Partner: Codable, Sendable, Equatable, Identifiable {
    var id: String
    var name: String
    var pairedAt: Date
}

/// Wire form of `GymSchedule`. ISO weekdays (1 = Monday … 7 = Sunday), minutes since midnight.
struct ScheduleDTO: Codable, Sendable, Equatable {
    var weekdays: [Int]
    var defaultMinuteOfDay: Int
    /// Per-weekday overrides keyed by ISO weekday.
    var overrides: [Int: Int]
    /// Job toggles the server's reminder / 21:00 cron reads. `nil` leaves the server value untouched.
    var remindHourBefore: Bool?
    var askIfSkippedAt21: Bool?

    init(weekdays: [Int], defaultMinuteOfDay: Int, overrides: [Int: Int] = [:],
         remindHourBefore: Bool? = nil, askIfSkippedAt21: Bool? = nil) {
        self.weekdays = weekdays.sorted()
        self.defaultMinuteOfDay = defaultMinuteOfDay
        self.overrides = overrides
        self.remindHourBefore = remindHourBefore
        self.askIfSkippedAt21 = askIfSkippedAt21
    }

    init(_ schedule: GymSchedule) {
        self.init(weekdays: schedule.weekdays, defaultMinuteOfDay: schedule.defaultMinuteOfDay, overrides: schedule.overrides,
                  remindHourBefore: schedule.remindHourBefore, askIfSkippedAt21: schedule.askIfSkippedAt21)
    }

    func minuteOfDay(for isoWeekday: Int) -> Int { overrides[isoWeekday] ?? defaultMinuteOfDay }
    func isGymDay(_ isoWeekday: Int) -> Bool { weekdays.contains(isoWeekday) }

    // JSON objects need string keys; `[Int: Int]` would otherwise encode as a flat array.
    private enum CodingKeys: String, CodingKey { case weekdays, defaultMinuteOfDay, overrides, remindHourBefore, askIfSkippedAt21 }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        weekdays = try c.decode([Int].self, forKey: .weekdays).sorted()
        defaultMinuteOfDay = try c.decode(Int.self, forKey: .defaultMinuteOfDay)
        let raw = try c.decodeIfPresent([String: Int].self, forKey: .overrides) ?? [:]
        overrides = Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in Int(k).map { ($0, v) } })
        remindHourBefore = try c.decodeIfPresent(Bool.self, forKey: .remindHourBefore)
        askIfSkippedAt21 = try c.decodeIfPresent(Bool.self, forKey: .askIfSkippedAt21)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(weekdays, forKey: .weekdays)
        try c.encode(defaultMinuteOfDay, forKey: .defaultMinuteOfDay)
        try c.encode(Dictionary(uniqueKeysWithValues: overrides.map { (String($0.key), $0.value) }), forKey: .overrides)
        try c.encodeIfPresent(remindHourBefore, forKey: .remindHourBefore)
        try c.encodeIfPresent(askIfSkippedAt21, forKey: .askIfSkippedAt21)
    }
}

struct AttendanceDTO: Codable, Sendable, Equatable, Identifiable {
    var day: Date
    var participant: Participant
    var status: AttendanceStatus
    var reason: String?
    var note: String?
    var makeUpDay: Date?

    var id: String { "\(participant.rawValue)-\(Int(day.timeIntervalSince1970))" }
}

struct HeadsUpDTO: Codable, Sendable, Equatable, Identifiable {
    var id: String
    var fromMe: Bool
    var kind: HeadsUpKind
    var text: String
    var sessionDay: Date
    var sentAt: Date
}

struct PartnerState: Codable, Sendable, Equatable {
    var partnerName: String
    var partnerSchedule: ScheduleDTO
    var attendance: [AttendanceDTO]
    var headsUps: [HeadsUpDTO]
}

// MARK: - AI estimate

struct AIFood: Codable, Sendable, Equatable, Identifiable {
    /// Local identity for editable rows; not part of the wire format.
    var id = UUID()
    var name: String
    var grams: Double
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double
    /// 0…1
    var confidence: Double
    /// Item the model could not see clearly (e.g. cooking oil); shown with a "guess" badge.
    var isGuess: Bool

    init(name: String, grams: Double, kcal: Double, protein: Double, carbs: Double, fat: Double,
         confidence: Double, isGuess: Bool = false) {
        self.name = name
        self.grams = grams
        self.kcal = kcal
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.confidence = min(1, max(0, confidence))
        self.isGuess = isGuess
    }

    private enum CodingKeys: String, CodingKey { case name, grams, kcal, protein, carbs, fat, confidence, isGuess }

    /// Tolerant key lookup: the backend sends `proteinG`, Claude replies with `protein_g`, the mock uses `protein`.
    private struct AnyKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ s: String) { stringValue = s }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        func number(_ keys: [String], default fallback: Double? = nil) throws -> Double {
            for k in keys {
                if let v = try c.decodeIfPresent(Double.self, forKey: AnyKey(k)) { return v }
                if let s = try? c.decodeIfPresent(String.self, forKey: AnyKey(k)), let v = Double(s) { return v }
            }
            if let fallback { return fallback }
            throw DecodingError.keyNotFound(AnyKey(keys[0]), .init(codingPath: c.codingPath, debugDescription: "missing \(keys)"))
        }
        let name = try c.decodeIfPresent(String.self, forKey: AnyKey("name"))
            ?? c.decodeIfPresent(String.self, forKey: AnyKey("name_en")) ?? ""
        self.init(name: name,
                  grams: try number(["grams", "estimated_grams", "estimatedGrams"]),
                  kcal: try number(["kcal", "calories"]),
                  protein: try number(["protein", "proteinG", "protein_g"], default: 0),
                  carbs: try number(["carbs", "carbsG", "carbs_g"], default: 0),
                  fat: try number(["fat", "fatG", "fat_g"], default: 0),
                  confidence: try number(["confidence"], default: 0.5),
                  isGuess: try c.decodeIfPresent(Bool.self, forKey: AnyKey("isGuess"))
                    ?? c.decodeIfPresent(Bool.self, forKey: AnyKey("is_guess")) ?? false)
    }

    /// Same food scaled to a different portion; kcal and macros follow proportionally.
    func scaled(toGrams newGrams: Double) -> AIFood {
        guard grams > 0 else { return self }
        let f = newGrams / grams
        var copy = self
        copy.grams = newGrams
        copy.kcal = kcal * f
        copy.protein = protein * f
        copy.carbs = carbs * f
        copy.fat = fat * f
        return copy
    }
}

struct AIEstimate: Codable, Sendable, Equatable {
    var foods: [AIFood]
    /// 0…1
    var overallConfidence: Double
    var assumptions: [String]? = nil
    var questions: [String]? = nil
    var scaleReferenceUsed: String? = nil

    var totalKcal: Double { foods.reduce(0) { $0 + $1.kcal } }
    var totalProtein: Double { foods.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { foods.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { foods.reduce(0) { $0 + $1.fat } }
}

// MARK: - Errors

enum BackendError: Error, Sendable, Equatable, LocalizedError {
    /// No session, or the session could not be refreshed. The UI shows its signed-out state, not a banner.
    case unauthorized
    case network
    case server(String)
    /// Any other non-2xx reply, with the server's `{error, message}` envelope (`code` is e.g. "username_taken").
    case http(status: Int, code: String, message: String)
    case decoding

    var errorDescription: String? {
        switch self {
        case .unauthorized: String(localized: "error.unauthorized")
        case .network: String(localized: "error.network")
        case .server(let message): String(format: String(localized: "error.server"), message)
        case .http(_, _, let message): String(format: String(localized: "error.server"), message)
        case .decoding: String(localized: "error.decoding")
        }
    }

    /// Server error code when the reply carried one ("not_paired", "ai_busy", …).
    var code: String? {
        if case .http(_, let code, _) = self { return code }
        return nil
    }

    var status: Int? {
        if case .http(let status, _, _) = self { return status }
        return nil
    }

    /// Normalises any thrown error into a `BackendError` for display.
    static func wrap(_ error: Error) -> BackendError {
        if let e = error as? BackendError { return e }
        if error is DecodingError { return .decoding }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .userAuthenticationRequired: return .unauthorized
            default: return .network
            }
        }
        return .server(error.localizedDescription)
    }
}

extension BackendClient {
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?) async throws -> AIEstimate {
        try await estimate(imageJPEG: imageJPEG, meal: meal, locale: locale, anthropicKey: anthropicKey, notes: "")
    }
}
