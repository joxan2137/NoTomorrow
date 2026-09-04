import SwiftUI
import SwiftData
import Observation

/// State machine for the AI photo flow: pick a source → analyse → edit the estimate → log.
@Observable
final class AIScanModel {
    enum Phase: Equatable {
        case pickSource
        case analyzing
        case result
        case failed(String)
    }

    /// Which network provider the photo would go to. `.none` means the offline mock; nothing leaves the device.
    enum Upload: Equatable {
        case none
        case google
        case anthropic

        var consentKey: String? {
            switch self {
            case .none: nil
            case .google: "nt.aiConsent.google"
            case .anthropic: "nt.aiConsent.anthropic"
            }
        }

        var providerNameKey: String {
            switch self {
            case .none, .google: "fuel.ai.provider.google"
            case .anthropic: "fuel.ai.provider.anthropic"
            }
        }
    }

    var meal: MealSlot
    var phase: Phase = .pickSource
    var image: UIImage?
    var foods: [AIFood] = []
    var overallConfidence: Double = 0
    var showConsent = false
    var toast: String?

    private var jpeg: Data?
    private var task: Task<Void, Never>?
    private let config: AppConfig
    private let defaults: UserDefaults

    init(meal: MealSlot, config: AppConfig = .shared, defaults: UserDefaults = .standard) {
        self.meal = meal
        self.config = config
        self.defaults = defaults
    }

    // MARK: Derived

    var totalKcal: Double { foods.reduce(0) { $0 + $1.kcal } }
    var totalProtein: Double { foods.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { foods.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { foods.reduce(0) { $0 + $1.fat } }

    enum ConfidenceLevel {
        case high, medium, low
        var bars: Int { switch self { case .high: 3; case .medium: 2; case .low: 1 } }
        var labelKey: LocalizedStringKey {
            switch self {
            case .high: "fuel.ai.confidence.high"
            case .medium: "fuel.ai.confidence.medium"
            case .low: "fuel.ai.confidence.low"
            }
        }
    }

    var confidenceLevel: ConfidenceLevel {
        switch overallConfidence {
        case 0.75...: .high
        case 0.45..<0.75: .medium
        default: .low
        }
    }

    /// Where the photo goes, given the current config and whether a key is stored.
    var upload: Upload {
        if config.useMockBackend { return .none }
        if config.aiProvider == .claudeBYOK, let key = KeychainHelper.readAnthropicKey(), !key.isEmpty { return .anthropic }
        return .google
    }

    var providerName: String { String(localized: String.LocalizationValue(upload.providerNameKey)) }

    // MARK: Photo intake

    /// Downscales the picked photo, then either asks for first-use consent or starts the analysis.
    func handlePicked(_ picked: UIImage) {
        guard let data = ImageDownscaler.jpegData(from: picked) else {
            phase = .failed(String(localized: "fuel.ai.failed"))
            return
        }
        image = UIImage(data: data) ?? picked
        jpeg = data
        if let key = upload.consentKey, !defaults.bool(forKey: key) {
            showConsent = true
        } else {
            analyze()
        }
    }

    func acceptConsent() {
        if let key = upload.consentKey { defaults.set(true, forKey: key) }
        showConsent = false
        analyze()
    }

    func declineConsent() {
        showConsent = false
        retake()
    }

    func retake() {
        task?.cancel()
        task = nil
        image = nil
        jpeg = nil
        foods = []
        overallConfidence = 0
        phase = .pickSource
    }

    func analyze() {
        guard let jpeg else { return }
        phase = .analyzing
        let service = makeService()
        let meal = meal
        let locale = Locale.current.language.languageCode?.identifier ?? "en"
        task?.cancel()
        task = Task { [weak self] in
            do {
                let estimate = try await service.estimate(imageJPEG: jpeg, meal: meal, locale: locale)
                guard !Task.isCancelled else { return }
                await MainActor.run { self?.apply(estimate) }
            } catch {
                guard !Task.isCancelled else { return }
                let message = (error as? AIEstimateError)?.errorDescription ?? String(localized: "fuel.ai.failed")
                await MainActor.run { self?.phase = .failed(message) }
            }
        }
    }

    private func apply(_ estimate: AIEstimate) {
        guard !estimate.foods.isEmpty else {
            phase = .failed(String(localized: "fuel.ai.failed"))
            return
        }
        foods = estimate.foods
        overallConfidence = estimate.overallConfidence
        phase = .result
    }

    private func makeService() -> any AIEstimateService {
        switch upload {
        case .none:
            return MockAIEstimateService()
        case .anthropic:
            return DirectAnthropicEstimateService(apiKey: { KeychainHelper.readAnthropicKey() })
        case .google:
            return BackendAIEstimateService(
                baseURL: config.backendBaseURL,
                authToken: { KeychainHelper.readSession()?.accessToken },
                anthropicKey: { nil }
            )
        }
    }

    // MARK: Editing

    func setGrams(_ grams: Double, for id: AIFood.ID) {
        guard let index = foods.firstIndex(where: { $0.id == id }), grams > 0 else { return }
        foods[index] = foods[index].scaled(toGrams: grams)
    }

    func scale(by factor: Double, for id: AIFood.ID) {
        guard let food = foods.first(where: { $0.id == id }) else { return }
        setGrams((food.grams * factor).rounded(), for: id)
    }

    func append(_ food: AIFood) {
        foods.append(food)
    }

    // MARK: Logging

    /// One `MealEntry` per food, flagged as an AI estimate. Returns the number of rows written.
    @discardableResult
    func log(into context: ModelContext, day: Date = .now) -> Int {
        var count = 0
        for food in foods where food.kcal > 0 || food.grams > 0 {
            let entry = MealEntry(
                day: day, slot: meal, customName: food.name, grams: food.grams,
                kcal: food.kcal, proteinG: food.protein, carbsG: food.carbs, fatG: food.fat,
                isAIEstimate: true, confidence: food.confidence
            )
            context.insert(entry)
            count += 1
        }
        try? context.save()
        return count
    }

    // MARK: Stubs

    func saveAsRecipe() {
        toast = String(localized: "fuel.ai.recipeSoon")
    }
}
