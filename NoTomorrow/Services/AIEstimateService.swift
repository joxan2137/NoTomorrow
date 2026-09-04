import Foundation

// `AIEstimate` / `AIFood` are the backend contract types defined in Services/BackendClient.swift.

// MARK: - Protocol

protocol AIEstimateService {
    func estimate(imageJPEG: Data, meal: MealSlot, locale: String) async throws -> AIEstimate
}

enum AIEstimateError: LocalizedError {
    case missingKey
    /// The user's own Anthropic key was rejected.
    case unauthorized
    /// No account session (or it expired and could not be refreshed) for the backend path.
    case signedOut
    /// Backend 429 (`ai_busy`), 502 (`ai_upstream_error`, `ai_unparseable`) or 503 (`ai_unavailable`).
    case busy
    /// Backend 429 `ai_daily_limit`.
    case dailyLimit
    case badResponse(Int, String?)
    case invalidJSON
    case network

    var errorDescription: String? {
        switch self {
        case .missingKey: String(localized: "fuel.ai.error.missingKey")
        case .unauthorized: String(localized: "fuel.ai.error.unauthorized")
        case .signedOut: String(localized: "fuel.ai.error.signedOut")
        case .busy: String(localized: "fuel.ai.error.busy")
        case .dailyLimit: String(localized: "fuel.ai.error.dailyLimit")
        case .network: String(localized: "error.network")
        case .badResponse: String(localized: "fuel.ai.failed")
        case .invalidJSON: String(localized: "fuel.ai.error.unreadable")
        }
    }
}

// MARK: - Shared prompt

enum AIEstimatePrompt {
    /// The scale-reference ladder and output contract. Shared by the backend (documented) and the direct Claude path.
    static func text(meal: MealSlot, locale: String) -> String {
        let language = locale.lowercased().hasPrefix("pl") ? "Polish" : "English"
        return """
        You estimate the food on a plate from one photo for a calorie-tracking app.
        Meal slot: \(meal.rawValue). Write food names in \(language), short and specific (e.g. "Grilled chicken breast").

        Use these scale references when judging portions:
        - a dinner plate is 26–28 cm across; a fork is about 19 cm long
        - a fist ≈ 150 g of cooked rice or pasta
        - a palm (no fingers) ≈ 100–120 g of cooked meat or fish
        - a thumb ≈ 1 tablespoon (≈ 14 g) of fat, butter or oil
        Photos tend to hide oil and sauces: include cooking fat as a separate guessed item (confidence ≤ 0.4) whenever the food looks fried, roasted or glossy.
        Give macros for the whole portion (not per 100 g), in grams. kcal should be consistent with the macros (4/4/9).
        confidence and overall_confidence are 0–1.

        Respond with strict JSON only, no prose, no code fences:
        {"foods":[{"name":"","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"confidence":0}],"overall_confidence":0}
        """
    }

    /// Accepts ```json fences, leading prose and trailing commentary; returns the outermost JSON object.
    static func extractJSON(from text: String) -> Data? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("```") {
            s = s.replacingOccurrences(of: "```json", with: "").replacingOccurrences(of: "```", with: "")
        }
        guard let start = s.firstIndex(of: "{"), let end = s.lastIndex(of: "}"), start < end else { return nil }
        return String(s[start...end]).data(using: .utf8)
    }
}

/// Wire shape requested from Claude (snake_case, whole-portion macros). Mapped to `AIFood`.
struct AIEstimateWire: Decodable {
    struct Food: Decodable {
        let name: String
        let grams: Double?
        let kcal: Double?
        let proteinG: Double?
        let carbsG: Double?
        let fatG: Double?
        let protein: Double?
        let carbs: Double?
        let fat: Double?
        let confidence: Double?
        let isGuess: Bool?
    }
    let foods: [Food]
    let overallConfidence: Double?

    func toEstimate() -> AIEstimate {
        let items: [AIFood] = foods.map { f in
            let confidence: Double = min(1, max(0, f.confidence ?? 0.5))
            return AIFood(name: f.name, grams: f.grams ?? 0, kcal: f.kcal ?? 0, protein: f.proteinG ?? f.protein ?? 0,
                          carbs: f.carbsG ?? f.carbs ?? 0, fat: f.fatG ?? f.fat ?? 0, confidence: confidence,
                          isGuess: f.isGuess ?? (confidence < 0.5))
        }
        let overall: Double
        if let overallConfidence {
            overall = overallConfidence
        } else if items.isEmpty {
            overall = 0
        } else {
            let sum: Double = items.reduce(0) { $0 + $1.confidence }
            overall = sum / Double(items.count)
        }
        return AIEstimate(foods: items, overallConfidence: min(1, max(0, overall)))
    }

    static func decode(_ data: Data) throws -> AIEstimate {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let wire = try? decoder.decode(AIEstimateWire.self, from: data) else { throw AIEstimateError.invalidJSON }
        return wire.toEstimate()
    }
}

// MARK: - Mock

/// Offline stand-in: a plausible plate after 1.2 s, matching the AIScan canvas.
struct MockAIEstimateService: AIEstimateService {
    var delay: Duration = .milliseconds(1200)

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String) async throws -> AIEstimate {
        try await Task.sleep(for: delay)
        let t = { (key: String) in AIEstimateLocalizer.string(key, locale: locale) }
        switch meal {
        case .breakfast:
            return AIEstimate(foods: [
                AIFood(name: t("fuel.ai.mock.eggs"), grams: 150, kcal: 232, protein: 19, carbs: 2, fat: 16, confidence: 0.85),
                AIFood(name: t("fuel.ai.mock.toast"), grams: 70, kcal: 186, protein: 6, carbs: 34, fat: 2, confidence: 0.8),
                AIFood(name: t("fuel.ai.mock.butter"), grams: 10, kcal: 72, protein: 0, carbs: 0, fat: 8, confidence: 0.35, isGuess: true),
            ], overallConfidence: 0.7)
        case .snack:
            return AIEstimate(foods: [
                AIFood(name: t("fuel.ai.mock.skyr"), grams: 200, kcal: 126, protein: 22, carbs: 8, fat: 0, confidence: 0.75),
                AIFood(name: t("fuel.ai.mock.banana"), grams: 120, kcal: 107, protein: 1, carbs: 27, fat: 0, confidence: 0.9),
                AIFood(name: t("fuel.ai.mock.almonds"), grams: 20, kcal: 116, protein: 4, carbs: 4, fat: 10, confidence: 0.55),
            ], overallConfidence: 0.7)
        case .lunch, .dinner:
            return AIEstimate(foods: [
                AIFood(name: t("fuel.ai.mock.chicken"), grams: 180, kcal: 297, protein: 56, carbs: 0, fat: 6, confidence: 0.8),
                AIFood(name: t("fuel.ai.mock.rice"), grams: 220, kcal: 286, protein: 6, carbs: 62, fat: 1, confidence: 0.7),
                AIFood(name: t("fuel.ai.mock.broccoli"), grams: 90, kcal: 31, protein: 3, carbs: 6, fat: 0, confidence: 0.85),
                AIFood(name: t("fuel.ai.mock.oliveOil"), grams: 14, kcal: 119, protein: 0, carbs: 0, fat: 14, confidence: 0.3, isGuess: true),
            ], overallConfidence: 0.6)
        }
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
