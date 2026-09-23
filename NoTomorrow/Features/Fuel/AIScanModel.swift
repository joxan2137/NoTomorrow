import SwiftUI
import SwiftData
import Observation

/// State machine for the AI photo flow: pick a source → analyse → correct the estimate → log.
@MainActor
@Observable
final class AIScanModel {
    enum Phase: Equatable {
        case pickSource
        case analyzing
        case result
        case failed(String)
        /// Backend 403 `ai_not_allowed`: the account is not whitelisted, so the card offers Settings instead.
        case notAllowed
    }

    var notes = ""
    var assumptions: [String] = []
    var questions: [String] = []
    var meal: MealSlot
    var phase: Phase = .pickSource
    var image: UIImage?
    var foods: [AIFood] = []
    var overallConfidence: Double = 0
    var showConsent = false
    /// A short note over the result: a failed refine's reason, or "coming soon". The view clears it after
    /// `toastDuration` and announces it to VoiceOver.
    var toast: String?

    /// Long enough to read a failed refine's reason (two or three lines in Polish) and for VoiceOver to finish
    /// saying it before it goes; two seconds was not.
    static let toastDuration: Duration = .seconds(5)

    /// Items the user corrected or added, by id → every name the item had (the model's first). They survive
    /// "Recalculate with details": the model is told about them and its answer is merged back.
    private(set) var kept: [AIFood.ID: [String]] = [:]
    /// Items the user added from the food database (not the model's; removing one tells the model nothing).
    private(set) var added: Set<AIFood.ID> = []
    /// Names of the model's items the user removed.
    private(set) var removedNames: [String] = []
    /// Their generic-table keys, so a removed item that comes back renamed stays removed.
    private(set) var removedKeys: [String] = []

    private var jpeg: Data?
    private var task: Task<Void, Never>?
    private var isPreparingPhoto = false
    private let config: AppConfig
    private let defaults: UserDefaults
    /// Tests inject a service; the app resolves one from the provider settings.
    private let injectedService: (any AIEstimateService)?

    init(meal: MealSlot, config: AppConfig = .shared, defaults: UserDefaults = .standard,
         service: (any AIEstimateService)? = nil) {
        self.meal = meal
        self.config = config
        self.defaults = defaults
        self.injectedService = service
    }

    // MARK: Derived

    var totalKcal: Double { foods.reduce(0) { $0 + $1.kcal } }
    var totalProtein: Double { foods.reduce(0) { $0 + $1.protein } }
    var totalCarbs: Double { foods.reduce(0) { $0 + $1.carbs } }
    var totalFat: Double { foods.reduce(0) { $0 + $1.fat } }

    /// The items `log` would write. Empty once the user removed everything: Log and Recalculate are then disabled.
    var loggableFoods: [AIFood] { foods.filter { $0.kcal > 0 || $0.grams > 0 } }
    var hasItems: Bool { !loggableFoods.isEmpty }

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
    var upload: AIUpload { injectedService == nil ? AIUpload.current(config: config) : .none }

    var providerName: String { upload.providerName }

    /// The corrections line the next "Recalculate" sends, if the user changed anything.
    var correctionsLine: String? {
        AIScanCorrections.line(kept: foods.filter { kept[$0.id] != nil }, removed: removedNames)
    }

    /// The full notes string for a request: typed details plus, on a refine, the corrections line.
    func outgoingNotes(refining: Bool) -> String {
        AIScanCorrections.notes(typed: notes, corrections: refining ? correctionsLine : nil)
    }

    // MARK: Photo intake

    /// Downscales the picked photo off the main thread, then either asks for first-use consent or starts the analysis.
    func handlePicked(_ photo: PickedPhoto) {
        guard !isPreparingPhoto else { return }
        isPreparingPhoto = true
        Task { [weak self] in
            let data = await photo.preparedJPEG(maxLongEdge: ImageDownscaler.plateLongEdge)
            self?.photoReady(data)
        }
    }

    private func photoReady(_ data: Data?) {
        isPreparingPhoto = false
        guard let data, let preview = UIImage(data: data) else {
            phase = .failed(String(localized: "fuel.ai.failed"))
            return
        }
        image = preview
        jpeg = data
        if upload.needsConsent(defaults) {
            showConsent = true
        } else {
            analyze()
        }
    }

    func acceptConsent() {
        upload.recordConsent(defaults)
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
        kept = [:]
        added = []
        removedNames = []
        removedKeys = []
        assumptions = []
        questions = []
        overallConfidence = 0
        phase = .pickSource
    }

    /// Sends the photo. From the result screen ("Recalculate with details") it also sends the user's corrections and
    /// merges them back into the answer; if that request fails, the current result stays and a toast says why.
    func analyze() {
        guard let jpeg else { return }
        let refining = phase == .result
        // Everything removed: nothing to recalculate against (the button is disabled too).
        if refining, !hasItems { return }
        let previous = refining ? Snapshot(of: self) : nil
        let sentNotes = outgoingNotes(refining: refining)
        let service = injectedService ?? upload.makeService(config: config)
        let meal = meal
        let locale = Locale.current.language.languageCode?.identifier ?? "en"
        phase = .analyzing
        task?.cancel()
        // Keeps the request alive if the user switches apps while waiting.
        let activity = BackgroundActivity(name: "ai-estimate")
        task = Task { [weak self] in
            defer { activity.end() }
            do {
                let estimate = try await service.estimate(imageJPEG: jpeg, meal: meal, locale: locale, notes: sentNotes)
                guard !Task.isCancelled else { return }
                self?.apply(estimate, refining: refining, previous: previous)
            } catch {
                guard !Task.isCancelled, !(error is CancellationError) else { return }
                self?.fail(error, previous: previous)
            }
        }
    }

    private func apply(_ estimate: AIEstimate, refining: Bool, previous: Snapshot?) {
        let keptItems = foods.compactMap { food in kept[food.id].map { AIScanCorrections.Kept(food: food, names: $0) } }
        let merged = refining
            ? AIScanCorrections.merge(refined: estimate.foods, kept: keptItems, removed: removedNames,
                                      removedKeys: removedKeys)
            : estimate.foods
        guard !merged.isEmpty else {
            // The model found no food (an empty answer is not an error on the wire).
            fail(nil, previous: previous)
            return
        }
        assumptions = estimate.assumptions ?? []
        questions = estimate.questions ?? []
        foods = merged
        overallConfidence = estimate.overallConfidence
        phase = .result
    }

    private func fail(_ error: Error?, previous: Snapshot?) {
        if (error as? AIEstimateError) == .notAllowed {
            phase = .notAllowed
            return
        }
        let message = (error as? AIEstimateError)?.errorDescription ?? String(localized: "fuel.ai.failed")
        if let previous {
            previous.restore(into: self)
            withAnimation { toast = message }
        } else {
            phase = .failed(message)
        }
    }

    /// The result screen as it was before a refine, restored when the refine fails.
    private struct Snapshot {
        let foods: [AIFood]
        let assumptions: [String]
        let questions: [String]
        let overallConfidence: Double

        @MainActor init(of model: AIScanModel) {
            foods = model.foods
            assumptions = model.assumptions
            questions = model.questions
            overallConfidence = model.overallConfidence
        }

        @MainActor func restore(into model: AIScanModel) {
            model.foods = foods
            model.assumptions = assumptions
            model.questions = questions
            model.overallConfidence = overallConfidence
            model.phase = .result
        }
    }

    // MARK: Corrections

    func setGrams(_ grams: Double, for id: AIFood.ID) {
        guard let food = food(id), grams > 0 else { return }
        update(food.scaled(toGrams: grams))
    }

    func scale(by factor: Double, for id: AIFood.ID) {
        guard let food = food(id) else { return }
        setGrams((food.grams * factor).rounded(), for: id)
    }

    /// −1 / +1 unit ("6 szt." → "7 szt."), grams follow the unit weight.
    func stepCount(up: Bool, for id: AIFood.ID) {
        guard let food = food(id) else { return }
        update(food.withCount(AIScanCorrections.steppedCount(food.units, up: up)))
    }

    /// Replaces the item with the editor's copy (name, count, grams, or a food-database product). No-op when nothing
    /// changed, so opening and closing the editor does not count as a correction.
    func update(_ edited: AIFood) {
        guard let index = foods.firstIndex(where: { $0.id == edited.id }), foods[index] != edited else { return }
        let original = foods[index]
        var names = kept[edited.id] ?? [original.name]
        if !names.contains(edited.name) { names.append(edited.name) }
        kept[edited.id] = names
        foods[index] = edited
    }

    /// Drops a wrong item (the guessed oil, a hallucinated side). A removed model item is reported on refine.
    func remove(_ id: AIFood.ID) {
        guard let index = foods.firstIndex(where: { $0.id == id }) else { return }
        let food = foods.remove(at: index)
        if !added.contains(id) {
            removedNames.append(kept[id]?.first ?? food.name)
            if let key = AIScanCorrections.genericKey(food) { removedKeys.append(key) }
        }
        kept[id] = nil
        added.remove(id)
    }

    /// Something the model missed, picked from the food database and sized by the user.
    func append(_ food: AIFood) {
        foods.append(food)
        added.insert(food.id)
        kept[food.id] = [food.name]
    }

    private func food(_ id: AIFood.ID) -> AIFood? { foods.first { $0.id == id } }

    // MARK: Logging

    /// One `MealEntry` per food. Model items log as AI estimates; items the user took from the food database log as
    /// ordinary food entries (and the product is saved to the library, like the portion sheet does). Returns the
    /// number of rows written.
    @discardableResult
    func log(into context: ModelContext, day: Date = .now) -> Int {
        var count = 0
        for food in loggableFoods {
            let entry: MealEntry
            if let item = Self.databaseItem(for: food, in: context) {
                let factor = food.grams / 100
                entry = MealEntry(day: day, slot: meal, food: item, grams: food.grams,
                                  kcal: item.kcalPer100 * factor, proteinG: item.proteinPer100 * factor,
                                  carbsG: item.carbsPer100 * factor, fatG: item.fatPer100 * factor)
            } else {
                entry = MealEntry(day: day, slot: meal, customName: food.name, grams: food.grams,
                                  kcal: food.kcal, proteinG: food.protein, carbsG: food.carbs, fatG: food.fat,
                                  isAIEstimate: true, confidence: food.confidence)
            }
            context.insert(entry)
            count += 1
        }
        try? context.save()
        return count
    }

    /// The library food behind a database pick, inserted on first use; nil for the model's own items (or a saved food
    /// deleted in the meantime, which then logs with its figures as a custom row).
    static func databaseItem(for food: AIFood, in context: ModelContext) -> FoodItem? {
        switch food.databaseFood {
        case .item(let id)?:
            let descriptor = FetchDescriptor<FoodItem>(predicate: #Predicate { $0.id == id })
            guard let saved = try? context.fetch(descriptor).first else { return nil }
            return PortionFood.item(saved).resolveItem(in: context)
        case .candidate(let candidate)?:
            return PortionFood.candidate(candidate).resolveItem(in: context)
        case nil:
            return nil
        }
    }

    // MARK: Stubs

    func saveAsRecipe() {
        toast = String(localized: "fuel.ai.recipeSoon")
    }
}
