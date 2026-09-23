import SwiftUI
import Observation

/// "Photograph the label" in `ProductLabelSheet`: photo (1600 px, the small print needs the pixels) → the same
/// provider and one-time consent as the AI photo estimate → the label read → the per-100 g fields, which the user checks
/// before saving. Nothing is saved here; a failure leaves the form as it was, still editable by hand.
@MainActor
@Observable
final class LabelPhotoReader {
    enum Status: Equatable {
        case idle
        case reading
        /// Values filled in; `needsReview` when they do not add up or the model was unsure.
        case filled(needsReview: Bool)
        /// The photo had no usable nutrition table (a printed name may still have been filled in).
        case unreadable
        case failed(String)
    }

    private(set) var status: Status = .idle
    var showConsent = false

    private var pending: Data?
    private var apply: ((LabelReading) -> Void)?
    private var task: Task<Void, Never>?
    private let config: AppConfig
    private let defaults: UserDefaults
    private let injectedService: (any AIEstimateService)?

    init(config: AppConfig = .shared, defaults: UserDefaults = .standard, service: (any AIEstimateService)? = nil) {
        self.config = config
        self.defaults = defaults
        self.injectedService = service
    }

    var upload: AIUpload { injectedService == nil ? AIUpload.current(config: config) : .none }
    var providerName: String { upload.providerName }
    var isReading: Bool { status == .reading }

    /// Downscales off the main thread, asks for consent if this provider has not had it yet, then reads. `apply`
    /// receives the reading (legible or not) to fill the form.
    func read(_ photo: PickedPhoto, apply: @escaping (LabelReading) -> Void) {
        guard !isReading else { return }
        self.apply = apply
        status = .reading
        Task { [weak self] in
            let data = await photo.preparedJPEG(maxLongEdge: ImageDownscaler.labelLongEdge)
            self?.photoReady(data)
        }
    }

    private func photoReady(_ data: Data?) {
        guard let data else {
            status = .failed(String(localized: "fuel.label.unreadable"))
            return
        }
        pending = data
        if upload.needsConsent(defaults) {
            status = .idle
            showConsent = true
        } else {
            send()
        }
    }

    func acceptConsent() {
        upload.recordConsent(defaults)
        showConsent = false
        send()
    }

    func declineConsent() {
        showConsent = false
        pending = nil
        status = .idle
    }

    func cancel() {
        task?.cancel()
        task = nil
    }

    private func send() {
        guard let jpeg = pending else { return }
        pending = nil
        status = .reading
        let service = injectedService ?? upload.makeService(config: config)
        let locale = Locale.current.language.languageCode?.identifier ?? "en"
        task?.cancel()
        let activity = BackgroundActivity(name: "ai-label")
        task = Task { [weak self] in
            defer { activity.end() }
            do {
                let reading = try await service.readLabel(imageJPEG: jpeg, locale: locale)
                guard !Task.isCancelled else { return }
                self?.finish(reading)
            } catch {
                guard !Task.isCancelled, !(error is CancellationError) else { return }
                self?.status = .failed((error as? AIEstimateError)?.labelMessage ?? String(localized: "fuel.label.unreadable"))
            }
        }
    }

    private func finish(_ reading: LabelReading) {
        apply?(reading)
        status = reading.legible && reading.per100 != nil ? .filled(needsReview: reading.needsReview) : .unreadable
    }
}

/// What a label reading puts into the form. A legible reading fills kcal, protein, carbs and fat, plus fiber and the
/// serving when printed; any reading fills the name, but only into an empty field. `nil` leaves a field as it is.
struct LabelFill: Equatable {
    var name: String?
    var brand: String?
    var kcal: String?
    var protein: String?
    var carbs: String?
    var fat: String?
    var fiber: String?
    var serving: String?

    static func from(_ reading: LabelReading, currentName: String, locale: Locale = Fmt.locale) -> LabelFill {
        var fill = LabelFill()
        let printedName = reading.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if currentName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !printedName.isEmpty {
            fill.name = printedName
        }
        let brand = reading.brand.trimmingCharacters(in: .whitespacesAndNewlines)
        fill.brand = brand.isEmpty ? nil : brand
        guard reading.legible, let per100 = reading.per100 else { return fill }
        let text = { (value: Double) in FuelText.fieldText(value, locale: locale) }
        fill.kcal = text(per100.kcal)
        fill.protein = text(per100.protein)
        fill.carbs = text(per100.carbs)
        fill.fat = text(per100.fat)
        fill.fiber = per100.fiber.map(text)
        fill.serving = reading.servingSizeG.map(text)
        return fill
    }
}
