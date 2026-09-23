import Foundation

// `AIEstimate` / `AIFood` / `LabelReading` are the backend contract types defined in Services/BackendClient.swift.
// Prompts, schemas and post-processing for the bring-your-own-key paths come from the shared spec
// (`AIEstimateSpec`, `AIFinalizer`), so the backend and both providers agree on every answer.

// MARK: - Protocol

protocol AIEstimateService {
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate
    /// A photo of a pack's nutrition table → per-100 g values for the label form.
    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading
}

/// One taxonomy for the photo estimate and the label read; each case maps to one message.
enum AIEstimateError: LocalizedError, Equatable {
    /// No answer at all (no connection, DNS, TLS, reset).
    case offline
    /// App-side timeout, or backend 504 `ai_timeout`.
    case timeout
    /// Backend 503 `ai_busy`; the user's provider answered 429 / 5xx / 529.
    case busy
    /// Backend 429 `ai_daily_limit`.
    case dailyLimit
    /// Backend 403 `ai_not_allowed`: the account is not on the server's AI whitelist.
    case notAllowed
    /// No account session (or it expired and could not be refreshed) for the backend path.
    case signedOut
    case missingKey
    case missingGeminiKey
    /// The user's own Anthropic or Gemini key was rejected.
    case keyRejected
    /// No usable JSON in the answer (backend 502 `ai_unparseable`, BYOK parse failure, Claude `max_tokens`).
    case unreadable
    /// Any other provider or server error (backend `ai_upstream_error`, `ai_unavailable`, a 4xx; Claude `refusal`).
    case providerError(Int, String?)

    var errorDescription: String? {
        switch self {
        case .offline: String(localized: "error.network")
        case .timeout: String(localized: "fuel.ai.error.timeout")
        case .busy: String(localized: "fuel.ai.error.busy")
        case .dailyLimit: String(localized: "fuel.ai.error.dailyLimit")
        case .notAllowed: String(localized: "fuel.ai.error.notAllowed")
        case .signedOut: String(localized: "fuel.ai.error.signedOut")
        case .missingKey: String(localized: "fuel.ai.error.missingKey")
        case .missingGeminiKey: String(localized: "fuel.ai.error.missingGeminiKey")
        case .keyRejected: String(localized: "fuel.ai.error.unauthorized")
        case .unreadable: String(localized: "fuel.ai.error.unreadable")
        case .providerError: String(localized: "fuel.ai.error.provider")
        }
    }

    /// The label read words "couldn't read" as the nutrition table, not the estimate.
    var labelMessage: String {
        self == .unreadable ? String(localized: "fuel.label.unreadable") : (errorDescription ?? "")
    }

    /// Transport failures from `URLSession`: a timeout is its own case, anything else is "no connection".
    static func transport(_ error: Error) -> Error {
        if error is CancellationError { return error }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cancelled: return CancellationError()
            case .timedOut: return AIEstimateError.timeout
            default: return AIEstimateError.offline
            }
        }
        return AIEstimateError.offline
    }
}

// MARK: - Provider choice and consent

/// Where a photo would go, given the current config and whether a key is stored. `.none` is the offline mock
/// (nothing leaves the device); `.gemini` is the user's own key, `.google` our backend: both end up at Google, so
/// they share one consent. The photo estimate and the label read use the same provider and the same consent.
enum AIUpload: Equatable {
    case none
    case google
    case gemini
    case anthropic

    static func current(config: AppConfig = .shared) -> AIUpload {
        if config.useMockBackend { return .none }
        if config.aiProvider == .claudeBYOK, let key = KeychainHelper.readAnthropicKey(), !key.isEmpty { return .anthropic }
        if config.aiProvider == .geminiBYOK, let key = KeychainHelper.readGeminiKey(), !key.isEmpty { return .gemini }
        return .google
    }

    var consentKey: String? {
        switch self {
        case .none: nil
        case .google, .gemini: "nt.aiConsent.google"
        case .anthropic: "nt.aiConsent.anthropic"
        }
    }

    var providerNameKey: String {
        switch self {
        case .none, .google, .gemini: "fuel.ai.provider.google"
        case .anthropic: "fuel.ai.provider.anthropic"
        }
    }

    var providerName: String { String(localized: String.LocalizationValue(providerNameKey)) }

    /// True when this provider still needs the user's one-time consent before a photo leaves the device.
    func needsConsent(_ defaults: UserDefaults = .standard) -> Bool {
        consentKey.map { !defaults.bool(forKey: $0) } ?? false
    }

    func recordConsent(_ defaults: UserDefaults = .standard) {
        if let consentKey { defaults.set(true, forKey: consentKey) }
    }

    func makeService(config: AppConfig = .shared) -> any AIEstimateService {
        switch self {
        case .none: MockAIEstimateService()
        case .anthropic: DirectAnthropicEstimateService(apiKey: { KeychainHelper.readAnthropicKey() })
        case .gemini: DirectGeminiEstimateService(apiKey: { KeychainHelper.readGeminiKey() })
        case .google: BackendAIEstimateService(client: config.makeBackendClient())
        }
    }
}

// MARK: - Mock

/// Offline stand-in: a plausible plate after 1.2 s, matching the AIScan canvas, in the v2 shape (per-100 g values and
/// counted portions, totals computed like the finalizer).
struct MockAIEstimateService: AIEstimateService {
    var delay: Duration = .milliseconds(1200)

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, notes: String) async throws -> AIEstimate {
        try await Task.sleep(for: delay)
        let t = { (key: String) in AIEstimateLocalizer.string(key, locale: locale) }
        let pl = AIEstimateSpec.languageCode(locale) == "pl"
        let piece = pl ? "szt." : "piece"
        let slice = pl ? "kromka" : "slice"
        let portion = pl ? "porcja" : "portion"
        let spoon = pl ? "łyżka" : "tbsp"
        let foods: [AIFood]
        let overall: Double
        switch meal {
        case .breakfast:
            foods = [
                .mock(t("fuel.ai.mock.eggs"), AIPer100(kcal: 155, protein: 12.7, carbs: 1.3, fat: 10.7), count: 1, unit: portion, perUnit: 150, confidence: 0.65),
                .mock(t("fuel.ai.mock.toast"), AIPer100(kcal: 266, protein: 8.6, carbs: 48.6, fat: 2.9), count: 2, unit: slice, perUnit: 35, confidence: 0.65),
                .mock(t("fuel.ai.mock.butter"), AIPer100(kcal: 740, protein: 0.7, carbs: 0.7, fat: 82), count: 2, unit: piece, perUnit: 5, confidence: 0.35, isGuess: true),
            ]
            overall = 0.6
        case .snack:
            foods = [
                .mock(t("fuel.ai.mock.skyr"), AIPer100(kcal: 63, protein: 11, carbs: 4, fat: 0.2), count: 1, unit: portion, perUnit: 200, confidence: 0.65),
                .mock(t("fuel.ai.mock.banana"), AIPer100(kcal: 95, protein: 1.1, carbs: 21, fat: 0.3), count: 1, unit: piece, perUnit: 120, confidence: 0.65),
                .mock(t("fuel.ai.mock.almonds"), AIPer100(kcal: 579, protein: 21, carbs: 9.7, fat: 50), count: 1, unit: portion, perUnit: 20, confidence: 0.55),
            ]
            overall = 0.6
        case .lunch, .dinner:
            foods = [
                .mock(t("fuel.ai.mock.chicken"), AIPer100(kcal: 160, protein: 31, carbs: 0, fat: 3.6), count: 1, unit: portion, perUnit: 180, confidence: 0.65),
                .mock(t("fuel.ai.mock.rice"), AIPer100(kcal: 130, protein: 2.7, carbs: 28.2, fat: 0.3), count: 1, unit: portion, perUnit: 220, confidence: 0.6),
                .mock(t("fuel.ai.mock.broccoli"), AIPer100(kcal: 35, protein: 2.4, carbs: 4.4, fat: 0.4), count: 1, unit: portion, perUnit: 90, confidence: 0.65),
                .mock(t("fuel.ai.mock.oliveOil"), AIPer100(kcal: 884, protein: 0, carbs: 0, fat: 100), count: 1, unit: spoon, perUnit: 10, confidence: 0.3, isGuess: true),
            ]
            overall = 0.55
        }
        return AIEstimate(foods: foods, overallConfidence: overall, assumptions: [], questions: [],
                          scaleReferenceUsed: "none", version: 2, totals: AIFinalizer.computeTotals(foods), skipped: [])
    }

    /// A fixed, legible cottage-cheese table: 97 kcal, P 11 · C 2 · F 5.
    func readLabel(imageJPEG: Data, locale: String) async throws -> LabelReading {
        try await Task.sleep(for: delay)
        return LabelReading(legible: true, basis: "per100g", energyFrom: "kcal", name: "Serek wiejski", brand: "",
                            per100: .init(kcal: 97, protein: 11, carbs: 2, fat: 5, fiber: nil, sugar: 2, salt: 0.6),
                            servingSizeG: 200, packageSizeG: 200, barcode: "", confidence: 0.9, needsReview: false)
    }
}

extension AIFood {
    /// A v2 item built like the finalizer builds one: grams = count × grams per unit, totals from the rounded per100.
    static func mock(_ name: String, _ per100: AIPer100, count: Double, unit: String, perUnit: Double,
                     confidence: Double, isGuess: Bool = false) -> AIFood {
        var food = AIFood(name: name, grams: AIFinalizer.round1(count * perUnit), kcal: 0, protein: 0, carbs: 0, fat: 0,
                          confidence: confidence, isGuess: isGuess)
        food.portionCount = count
        food.portionUnit = unit
        food.gramsPerUnit = perUnit
        food.nutritionSource = "estimated"
        food.cooking = "prepared"
        food.genericKey = "none"
        food.barcode = ""
        food.adjustments = []
        food.setNutrition(per100)
        return food
    }
}

/// Resolves catalog strings for an explicit locale ("pl"/"en") rather than the process locale.
enum AIEstimateLocalizer {
    static func string(_ key: String, locale: String) -> String {
        let lang = locale.lowercased().hasPrefix("pl") ? "pl" : "en"
        if let path = Bundle.main.path(forResource: lang, ofType: "lproj"), let bundle = Bundle(path: path) {
            return String(localized: String.LocalizationValue(key), bundle: bundle)
        }
        return String(localized: String.LocalizationValue(key))
    }
}

// Keep existing integrations source-compatible while allowing weighed portions and cooking notes.
extension AIEstimateService {
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String) async throws -> AIEstimate {
        try await estimate(imageJPEG: imageJPEG, meal: meal, locale: locale, notes: "")
    }
}
