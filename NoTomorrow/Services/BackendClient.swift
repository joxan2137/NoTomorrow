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
    /// `POST /ai/label`: a photo of a pack's nutrition table → per-100 g values. Shares the daily AI quota.
    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading
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

/// Nutrition per 100 g of a food as eaten (schema v2). `alcohol` lets beer and wine pass the energy check.
struct AIPer100: Codable, Sendable, Equatable {
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double
    var alcohol: Double = 0

    init(kcal: Double, protein: Double, carbs: Double, fat: Double, alcohol: Double = 0) {
        self.kcal = kcal
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
        self.alcohol = alcohol
    }

    private enum CodingKeys: String, CodingKey { case kcal, protein, carbs, fat, alcohol }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kcal = try c.decode(Double.self, forKey: .kcal)
        protein = try c.decode(Double.self, forKey: .protein)
        carbs = try c.decode(Double.self, forKey: .carbs)
        fat = try c.decode(Double.self, forKey: .fat)
        alcohol = (try? c.decodeIfPresent(Double.self, forKey: .alcohol)) ?? 0
    }
}

struct AIFood: Codable, Sendable, Equatable, Identifiable {
    /// A food-database product that stands in for the model's guess: the user replaced the item or added it by hand.
    /// Such rows log as ordinary food entries (linked to the product), not as AI estimates.
    enum DatabaseFood: Sendable, Equatable {
        /// A food already saved on this device, by `FoodItem.id`.
        case item(id: String)
        /// An Open Food Facts hit, saved to the library when the estimate is logged.
        case candidate(FoodCandidate)
    }

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

    // Schema v2 (backend v2 or the on-device finalizer). All optional: an older server sends none of them.
    var per100: AIPer100?
    var portionCount: Double?
    var portionUnit: String?
    var gramsPerUnit: Double?
    /// `estimated`, `generic_table`, `visible_label`, `user_notes`, `open_food_facts`; `database` for local picks.
    var nutritionSource: String?
    var cooking: String?
    var genericKey: String?
    var barcode: String?
    var adjustments: [String]?
    /// Local only, never on the wire.
    var databaseFood: DatabaseFood?

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

    private enum CodingKeys: String, CodingKey {
        case name, grams, kcal, protein, carbs, fat, confidence, isGuess
        case per100, portionCount, portionUnit, gramsPerUnit, nutritionSource, cooking, genericKey, barcode, adjustments
    }

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
                if let v = try? c.decodeIfPresent(Double.self, forKey: AnyKey(k)) { return v }
                if let s = try? c.decodeIfPresent(String.self, forKey: AnyKey(k)), let v = Double(s) { return v }
            }
            if let fallback { return fallback }
            throw DecodingError.keyNotFound(AnyKey(keys[0]), .init(codingPath: c.codingPath, debugDescription: "missing \(keys)"))
        }
        /// v2 fields are decoded leniently: a malformed optional field is dropped, never fails the estimate.
        func optional<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
            (try? c.decodeIfPresent(type, forKey: AnyKey(key))) ?? nil
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
                  isGuess: optional(Bool.self, "isGuess") ?? optional(Bool.self, "is_guess") ?? false)
        per100 = optional(AIPer100.self, "per100")
        portionCount = optional(Double.self, "portionCount")
        portionUnit = optional(String.self, "portionUnit")
        gramsPerUnit = optional(Double.self, "gramsPerUnit")
        nutritionSource = optional(String.self, "nutritionSource")
        cooking = optional(String.self, "cooking")
        genericKey = optional(String.self, "genericKey")
        barcode = optional(String.self, "barcode")
        adjustments = optional([String].self, "adjustments")
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(name, forKey: .name)
        try c.encode(grams, forKey: .grams)
        try c.encode(kcal, forKey: .kcal)
        try c.encode(protein, forKey: .protein)
        try c.encode(carbs, forKey: .carbs)
        try c.encode(fat, forKey: .fat)
        try c.encode(confidence, forKey: .confidence)
        try c.encode(isGuess, forKey: .isGuess)
        try c.encodeIfPresent(per100, forKey: .per100)
        try c.encodeIfPresent(portionCount, forKey: .portionCount)
        try c.encodeIfPresent(portionUnit, forKey: .portionUnit)
        try c.encodeIfPresent(gramsPerUnit, forKey: .gramsPerUnit)
        try c.encodeIfPresent(nutritionSource, forKey: .nutritionSource)
        try c.encodeIfPresent(cooking, forKey: .cooking)
        try c.encodeIfPresent(genericKey, forKey: .genericKey)
        try c.encodeIfPresent(barcode, forKey: .barcode)
        try c.encodeIfPresent(adjustments, forKey: .adjustments)
    }
}

struct AITotals: Codable, Sendable, Equatable {
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double
}

/// A model item the finalizer dropped (`invalid_grams`, `no_name`, …) instead of failing the whole estimate.
struct AISkippedItem: Codable, Sendable, Equatable {
    var index: Int
    var name: String
    var reason: String
}

struct AIEstimate: Codable, Sendable, Equatable {
    var foods: [AIFood]
    /// 0…1
    var overallConfidence: Double
    var assumptions: [String]? = nil
    var questions: [String]? = nil
    var scaleReferenceUsed: String? = nil
    // Schema v2 additions; an older server sends none of them.
    var version: Int? = nil
    var totals: AITotals? = nil
    var skipped: [AISkippedItem]? = nil

    init(foods: [AIFood], overallConfidence: Double, assumptions: [String]? = nil, questions: [String]? = nil,
         scaleReferenceUsed: String? = nil, version: Int? = nil, totals: AITotals? = nil, skipped: [AISkippedItem]? = nil) {
        self.foods = foods
        self.overallConfidence = overallConfidence
        self.assumptions = assumptions
        self.questions = questions
        self.scaleReferenceUsed = scaleReferenceUsed
        self.version = version
        self.totals = totals
        self.skipped = skipped
    }

    private enum CodingKeys: String, CodingKey {
        case foods, overallConfidence, assumptions, questions, scaleReferenceUsed, version, totals, skipped
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        foods = try c.decode([AIFood].self, forKey: .foods)
        let mean = foods.isEmpty ? 0 : foods.reduce(0) { $0 + $1.confidence } / Double(foods.count)
        overallConfidence = min(1, max(0, (try? c.decodeIfPresent(Double.self, forKey: .overallConfidence)) ?? mean))
        assumptions = (try? c.decodeIfPresent([String].self, forKey: .assumptions)) ?? nil
        questions = (try? c.decodeIfPresent([String].self, forKey: .questions)) ?? nil
        scaleReferenceUsed = (try? c.decodeIfPresent(String.self, forKey: .scaleReferenceUsed)) ?? nil
        version = (try? c.decodeIfPresent(Int.self, forKey: .version)) ?? nil
        totals = (try? c.decodeIfPresent(AITotals.self, forKey: .totals)) ?? nil
        skipped = (try? c.decodeIfPresent([AISkippedItem].self, forKey: .skipped)) ?? nil
    }

    var totalKcal: Double { foods.reduce(0) { $0 + $1.kcal } }
    var totalProtein: Double { foods.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { foods.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { foods.reduce(0) { $0 + $1.fat } }
}

// MARK: - Nutrition label read

/// What the AI read from a photo of a pack's nutrition table (`POST /ai/label`, or the on-device finalizer for the
/// bring-your-own-key paths). `legible == false` is an answer, not an error: the form stays editable.
struct LabelReading: Codable, Sendable, Equatable {
    /// Always per 100 g; a per-100 ml table is stored as per 100 g, like drinks elsewhere in the app.
    struct Per100: Codable, Sendable, Equatable {
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        var fiber: Double?
        var sugar: Double?
        var salt: Double?
    }

    var version: Int = 1
    var legible: Bool
    /// `illegible`, `no_energy`, `incomplete`, `no_serving_size`, `implausible`; nil when legible.
    var unreadableReason: String?
    /// `per100g`, `per100ml` or `perServing`: the column the numbers came from.
    var basis: String = "per100g"
    /// `kcal` or `kj` (converted); nil when not legible.
    var energyFrom: String?
    var name: String = ""
    var brand: String = ""
    var per100: Per100?
    var servingSizeG: Double?
    var packageSizeG: Double?
    var barcode: String = ""
    var confidence: Double = 0.5
    /// The numbers do not add up (Atwater, sugar above carbs) or the model was unsure: ask the user to check.
    var needsReview: Bool = false

    init(legible: Bool, unreadableReason: String? = nil, basis: String = "per100g", energyFrom: String? = nil,
         name: String = "", brand: String = "", per100: Per100? = nil, servingSizeG: Double? = nil,
         packageSizeG: Double? = nil, barcode: String = "", confidence: Double = 0.5, needsReview: Bool = false) {
        self.legible = legible
        self.unreadableReason = unreadableReason
        self.basis = basis
        self.energyFrom = energyFrom
        self.name = name
        self.brand = brand
        self.per100 = per100
        self.servingSizeG = servingSizeG
        self.packageSizeG = packageSizeG
        self.barcode = barcode
        self.confidence = confidence
        self.needsReview = needsReview
    }

    private enum CodingKeys: String, CodingKey {
        case version, legible, unreadableReason, basis, energyFrom, name, brand, per100, servingSizeG, packageSizeG
        case barcode, confidence, needsReview
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func optional<T: Decodable>(_ type: T.Type, _ key: CodingKeys) -> T? {
            (try? c.decodeIfPresent(type, forKey: key)) ?? nil
        }
        version = optional(Int.self, .version) ?? 1
        legible = try c.decode(Bool.self, forKey: .legible)
        unreadableReason = optional(String.self, .unreadableReason)
        basis = optional(String.self, .basis) ?? "per100g"
        energyFrom = optional(String.self, .energyFrom)
        name = optional(String.self, .name) ?? ""
        brand = optional(String.self, .brand) ?? ""
        per100 = optional(Per100.self, .per100)
        servingSizeG = optional(Double.self, .servingSizeG)
        packageSizeG = optional(Double.self, .packageSizeG)
        barcode = optional(String.self, .barcode) ?? ""
        confidence = optional(Double.self, .confidence) ?? 0.5
        needsReview = optional(Bool.self, .needsReview) ?? false
    }
}

// MARK: - Errors

enum BackendError: Error, Sendable, Equatable, LocalizedError {
    /// No session, or the session could not be refreshed. The UI shows its signed-out state, not a banner.
    case unauthorized
    case network
    /// The request left but no answer came back in time (`URLError.timedOut`).
    case timedOut
    case server(String)
    /// Any other non-2xx reply, with the server's `{error, message}` envelope (`code` is e.g. "username_taken").
    case http(status: Int, code: String, message: String)
    case decoding

    var errorDescription: String? {
        switch self {
        case .unauthorized: String(localized: "error.unauthorized")
        case .network, .timedOut: String(localized: "error.network")
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
            case .timedOut: return .timedOut
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
