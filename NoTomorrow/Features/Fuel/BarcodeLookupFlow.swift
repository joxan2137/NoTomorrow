import SwiftUI
import SwiftData

/// Scan → saved foods → Open Food Facts, shared by Fuel home and the search sheet.
/// The scanner only hands over the code (`pendingCode`); the lookup starts from the scanner sheet's `onDismiss`
/// (`startPending`), because SwiftUI drops a sheet presented while another is still going away and a saved food answers
/// at once. For the same reason a label saved in `ProductLabelSheet` opens the portion sheet from that sheet's `onDismiss`.
@Observable
final class BarcodeLookupFlow {
    /// The alert after a lookup that found nothing ready to size.
    enum Prompt: Identifiable {
        /// Open Food Facts has the product but no usable nutrition: the label form starts from its name and brand.
        case partial(ProductStub, code: String)
        case notFound(code: String)
        /// 429 / 5xx after the retry, offline, or no answer: the message says which, and Try again repeats the lookup.
        case failed(message: String, code: String)

        var id: String {
            switch self {
            case .partial(_, let code): "partial-\(code)"
            case .notFound(let code): "missing-\(code)"
            case .failed(_, let code): "failed-\(code)"
            }
        }

        /// Partial: the product ("Łosoś świeży · MOWI") so the user sees what was found; otherwise the verdict.
        var title: String {
            switch self {
            case .partial(let stub, _): Self.partialTitle(stub)
            case .notFound: String(localized: "fuel.barcodeNotFound")
            case .failed(let message, _): message
            }
        }

        /// "Name · Brand". A name that already starts with the brand (Open Food Facts had no name, so it was built
        /// from the brand and quantity: "Pudliszki 200 g") does not repeat it; case and diacritics are ignored.
        static func partialTitle(_ stub: ProductStub) -> String {
            let brand = stub.brand?.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let brand, !brand.isEmpty, !startsWithWord(stub.name, brand) else { return stub.name }
            return "\(stub.name) · \(brand)"
        }

        /// `text` is `word`, or begins with it followed by something other than a letter or digit ("Pudliszki 200 g",
        /// but not "Mlekovita" for "Mleko"). Compared folded (`FoodMatch.fold`: case, diacritics and ł).
        static func startsWithWord(_ text: String, _ word: String) -> Bool {
            let text = FoodMatch.fold(text.trimmingCharacters(in: .whitespacesAndNewlines))
            let word = FoodMatch.fold(word.trimmingCharacters(in: .whitespacesAndNewlines))
            guard !word.isEmpty, text.hasPrefix(word) else { return false }
            guard let next = text.dropFirst(word.count).first else { return true }
            return !(next.isLetter || next.isNumber)
        }

        var message: String? {
            if case .partial = self { return String(localized: "fuel.barcodePartial") }
            return nil
        }
    }

    struct LabelRequest: Identifiable {
        let code: String
        let stub: ProductStub?
        var id: String { code }
    }

    /// Lookups still running. A count, not a flag: a lookup that ends while a newer one is running (a retry, a second
    /// scan) must not hide the "Looking up" pill.
    private(set) var runningLookups = 0
    var isLookingUp: Bool { runningLookups > 0 }
    var prompt: Prompt?
    var labelRequest: LabelRequest?
    /// The scanned code, waiting for the scanner sheet to finish dismissing.
    var pendingCode: String?
    /// The food just saved from a label, waiting for the label sheet to finish dismissing.
    var savedLabel: FoodItem?

    /// Looks up the code the scanner handed over, if any. Call from the scanner sheet's `onDismiss`.
    @MainActor
    func startPending(in context: ModelContext, onFood: @escaping (PortionFood) -> Void) {
        guard let code = pendingCode else { return }
        pendingCode = nil
        Task { await run(code, in: context, onFood: onFood) }
    }

    /// The Open Food Facts call; tests swap it for a stub.
    typealias Lookup = @Sendable (_ barcode: String, _ locale: String) async throws -> BarcodeLookup
    static let liveLookup: Lookup = { try await FoodSearchService.shared.lookup(barcode: $0, locale: $1) }

    @MainActor
    func run(_ code: String, in context: ModelContext, lookup: Lookup = BarcodeLookupFlow.liveLookup,
             onFood: (PortionFood) -> Void) async {
        runningLookups += 1
        defer { runningLookups -= 1 }
        let normalized = FoodSearchService.barcodeForms(code).first ?? code
        if let saved = Self.savedFood(for: normalized, in: context) {
            onFood(.item(saved))
            return
        }
        do {
            switch try await lookup(normalized, FuelText.locale) {
            case .found(let candidate): onFood(.candidate(candidate))
            case .partial(let stub): prompt = .partial(stub, code: normalized)
            case .notFound: prompt = .notFound(code: normalized)
            }
        } catch is CancellationError {
        } catch FoodSearchError.alreadyInFlight {
        } catch {
            prompt = .failed(message: FoodSearchError.lookupMessage(for: error), code: normalized)
        }
    }

    /// The saved food for a scanned code: any of its EAN/UPC forms or its in-store item key, picked by
    /// `BarcodeKey.preferred` when several share it.
    static func savedFood(for code: String, in context: ModelContext) -> FoodItem? {
        var matches: [FoodItem] = []
        for key in BarcodeKey.localKeys(for: code) {
            let request = FetchDescriptor<FoodItem>(predicate: #Predicate { $0.barcode == key })
            matches += (try? context.fetch(request)) ?? []
        }
        return BarcodeKey.preferred(matches)
    }
}

extension View {
    /// The lookup's alerts and the label sheet. `onFood` opens the portion sheet; `onQuickAdd` opens quick add with a
    /// name (nil hides the action, e.g. when picking a food for an AI estimate).
    func barcodeLookupFlow(_ flow: BarcodeLookupFlow, onFood: @escaping (PortionFood) -> Void,
                           onQuickAdd: ((String) -> Void)?) -> some View {
        modifier(BarcodeLookupPrompts(flow: flow, onFood: onFood, onQuickAdd: onQuickAdd))
    }
}

private struct BarcodeLookupPrompts: ViewModifier {
    @Bindable var flow: BarcodeLookupFlow
    var onFood: (PortionFood) -> Void
    var onQuickAdd: ((String) -> Void)?
    @Environment(\.modelContext) private var modelContext

    /// Lets the alert finish going away before a retry can raise the next one.
    private static let retryPause: Duration = .milliseconds(400)

    func body(content: Content) -> some View {
        content
            .alert(Text(flow.prompt?.title ?? ""), isPresented: isPrompting, presenting: flow.prompt) { prompt in
                switch prompt {
                case .partial(let stub, let code):
                    Button("fuel.label.title") { flow.labelRequest = .init(code: code, stub: stub) }
                    if let onQuickAdd { Button("fuel.quickAdd") { onQuickAdd(stub.name) } }
                case .notFound(let code):
                    Button("fuel.label.title") { flow.labelRequest = .init(code: code, stub: nil) }
                    if let onQuickAdd { Button("fuel.quickAdd") { onQuickAdd("") } }
                case .failed(_, let code):
                    Button("fuel.search.retry") { retry(code) }
                    Button("fuel.label.title") { flow.labelRequest = .init(code: code, stub: nil) }
                }
                Button("common.cancel", role: .cancel) {}
            } message: { prompt in
                if let message = prompt.message { Text(message) }
            }
            .sheet(item: $flow.labelRequest, onDismiss: {
                guard let item = flow.savedLabel else { return }
                flow.savedLabel = nil
                onFood(.item(item))
            }) { request in
                ProductLabelSheet(code: request.code, stub: request.stub) { item in
                    flow.savedLabel = item
                    flow.labelRequest = nil
                }
            }
    }

    private var isPrompting: Binding<Bool> {
        Binding(get: { flow.prompt != nil }, set: { if !$0 { flow.prompt = nil } })
    }

    private func retry(_ code: String) {
        Task { @MainActor in
            try? await Task.sleep(for: Self.retryPause)
            await flow.run(code, in: modelContext, onFood: onFood)
        }
    }
}
