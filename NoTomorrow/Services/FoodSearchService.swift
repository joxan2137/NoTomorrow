import Foundation

/// One Open Food Facts hit, normalised per 100 g. `makeFoodItem()` turns it into a persisted `FoodItem` when the user taps it.
struct FoodCandidate: Identifiable, Hashable {
    let id: String               // "off:<code>"
    let code: String
    let name: String
    let brand: String?
    let quantity: String?        // "500 g" as printed on the pack
    let servingSizeG: Double?
    let servingLabel: String?
    let kcalPer100: Double
    let proteinPer100: Double
    let carbsPer100: Double
    let fatPer100: Double
    let fiberPer100: Double?
    let imageURL: String?
    /// The figures are OFF's `nutriments_estimated` (computed from the category and ingredients) because the label
    /// values are missing. Not persisted: the portion sheet captions it the first time the product is sized.
    var isEstimated = false

    func makeFoodItem() -> FoodItem {
        FoodItem(id: id, name: name, brand: brand, source: .openFoodFacts, barcode: code,
                 kcalPer100: kcalPer100, proteinPer100: proteinPer100, carbsPer100: carbsPer100, fatPer100: fatPer100,
                 fiberPer100: fiberPer100, servingSizeG: servingSizeG, servingLabel: servingLabel, imageURL: imageURL)
    }
}

/// A product Open Food Facts knows by barcode but cannot size: a name, maybe a brand, pack size and photo, and no
/// usable nutrition. The label form starts from it.
struct ProductStub: Hashable {
    let code: String
    let name: String
    let brand: String?
    let quantity: String?
    let servingSizeG: Double?
    let imageURL: String?
}

/// What a barcode lookup found: a product ready to size, a product without nutrition, or nothing.
enum BarcodeLookup: Hashable {
    case found(FoodCandidate)
    case partial(ProductStub)
    case notFound
}

enum FoodSearchError: LocalizedError, Equatable {
    case alreadyInFlight
    /// HTTP 429: this phone (or everyone behind its carrier's NAT) used up OFF's per-minute budget.
    case rateLimited
    /// HTTP 5xx: OFF is overloaded or down.
    case busy
    /// The phone has no connection.
    case offline
    /// Online, but OFF did not answer (timeout, DNS, TLS).
    case unreachable
    case badResponse(Int)

    var errorDescription: String? {
        switch self {
        case .alreadyInFlight: String(localized: "fuel.search.error.inFlight")
        case .rateLimited: String(localized: "fuel.search.error.rateLimited")
        case .busy: String(localized: "fuel.search.error.busy")
        case .offline: String(localized: "error.network")
        case .unreachable, .badResponse: String(localized: "fuel.search.error.network")
        }
    }

    init(_ error: URLError) {
        switch error.code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff, .callIsActive:
            self = .offline
        default:
            self = .unreachable
        }
    }

    init(status: Int) {
        switch status {
        case 429: self = .rateLimited
        case 500..<600: self = .busy
        default: self = .badResponse(status)
        }
    }

    /// Worth one more try after a short pause.
    var isTransient: Bool { self == .rateLimited || self == .busy }

    /// The message for any error a lookup or search throws; unknown errors read as "couldn't reach".
    static func message(for error: Error) -> String {
        (error as? FoodSearchError)?.errorDescription ?? String(localized: "fuel.search.error.network")
    }

    /// The barcode alert's message. A 429 or 5xx after the lookup's own retry reads as "busy": one scan is not "too
    /// many searches", and the alert offers Try again. Everything else reads as in `message(for:)`.
    static func lookupMessage(for error: Error) -> String {
        switch error as? FoodSearchError {
        case .rateLimited?, .busy?: String(localized: "fuel.search.error.busy")
        default: message(for: error)
        }
    }
}

/// Open Food Facts client. Debouncing (500 ms, ≥ 2 chars) is the view's job; this actor refuses a second identical in-flight
/// query, memoises complete results for five minutes and paces text searches so back-and-forth typing stays inside
/// OFF's budget.
/// Text search goes to search-a-licious, which does not fold diacritics ("mieta" misses "Mięta"). So a Polish query
/// typed without them also asks search-a-licious for its Polish spelling (`PolishSpelling`), and a Polish answer
/// that is still thin is topped up from the legacy `cgi/search.pl`, which does fold them (and which answered 503 to
/// anonymous users much of September 2026). The legacy search is also the fallback when search-a-licious fails.
/// An answer missing one of those extra requests (it failed, or no search slot was free) is not cached, so the same
/// query asks again; empty and short of an extra that failed, it reads as that failure (OFF busy, Try again).
/// Barcodes use the v2 product API, which allows 15 reads per minute per IP.
actor FoodSearchService {
    static let shared = FoodSearchService()

    static let userAgent = "NoTomorrow/0.1 (markzaluben@proton.me)"
    /// One list for barcode reads and both search endpoints. The name fallbacks and `nutriments_estimated` rescue
    /// products whose label name or values were never typed in.
    static let fields = "code,product_name,product_name_pl,product_name_en,generic_name,generic_name_pl,abbreviated_product_name,lang,brands,quantity,serving_size,serving_quantity,nutriments,nutriments_estimated,image_front_small_url,countries_tags"
    static let minimumQueryLength = 2
    /// OFF allows 10 searches a minute per IP. A query past that waits up to `maxSearchWait` for a slot, else fails fast.
    static let searchBudget = 10
    static let searchWindow: TimeInterval = 60
    static let maxSearchWait: TimeInterval = 6
    /// Search pause after a 429 when OFF sends no usable Retry-After.
    static let rateLimitCooldown: TimeInterval = 60
    /// A Polish search-a-licious answer with fewer usable products than this also asks the legacy search.
    static let supplementBelow = 5
    /// Extra requests (the Polish spelling, the legacy supplement) never take the last slots of the search budget,
    /// so the next query typed does not have to wait for one.
    static let extraHeadroom = 2

    private let session: URLSession
    private let retryDelay: Duration
    private var cache = LRUCache<String, [FoodCandidate]>(capacity: 40, ttl: 5 * 60)
    private var lookups = LRUCache<String, BarcodeLookup>(capacity: 40, ttl: 5 * 60)
    private var inFlight: Set<String> = []
    private var searchSlots = RequestWindow(limit: FoodSearchService.searchBudget, window: FoodSearchService.searchWindow)
    private var searchCooldownUntil: Date?

    init(session: URLSession? = nil, retryDelay: Duration = .milliseconds(1500)) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 15
            config.waitsForConnectivity = false
            self.session = URLSession(configuration: config)
        }
        self.retryDelay = retryDelay
    }

    // MARK: - Text search

    func search(query: String, locale: String) async throws -> [FoodCandidate] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= Self.minimumQueryLength else { return [] }
        let terms = Self.searchTerms(q)
        guard !terms.isEmpty else { return [] }
        let lc = Self.languageCode(locale)
        let key = "\(lc)|\(q.lowercased())"
        if let hit = cache.value(for: key) { return hit }
        guard !inFlight.contains(key) else { throw FoodSearchError.alreadyInFlight }
        inFlight.insert(key)
        defer { inFlight.remove(key) }

        try await waitForSearchSlot()
        var results: [FoodCandidate]
        // The answer went without an extra request (not cached), and the error of one that failed.
        var isIncomplete = false
        var extraFailure: Error?
        do {
            results = candidates(try await searchALicious(terms, lc: lc), lc: lc)
            if lc == "pl", let spelled = PolishSpelling.variant(of: terms) {
                // The Polish spelling's products first: they are named the way the pack is.
                switch await extra(.searchALicious(spelled), lc: lc) {
                case .found(let extra): results = Self.merged(extra, results)
                case .skipped: isIncomplete = true
                case .failed(let error): isIncomplete = true; extraFailure = error
                }
            }
            if Self.needsSupplement(results.count, lc: lc) {
                switch await extra(.legacy(q), lc: lc) {
                case .found(let extra): results = Self.merged(results, extra)
                case .skipped: isIncomplete = true
                case .failed(let error): isIncomplete = true; extraFailure = extraFailure ?? error
                }
            }
        } catch let error where Self.fallsBack(error) {
            results = candidates(try await legacySearch(q, lc: lc), lc: lc)
        }
        // A search cut short by a newer query must not leave its thinner answer in the cache.
        try Task.checkCancellation()
        guard isIncomplete else {
            cache.set(results, for: key)
            return results
        }
        if results.isEmpty, let extraFailure { throw Self.surfaced(extraFailure) }
        return results
    }

    /// Usable products, one per code, in the order OFF ranked them.
    private func candidates(_ products: [OFFProduct], lc: String) -> [FoodCandidate] {
        Self.merged([], products.compactMap { Self.candidate(from: $0, locale: lc) })
    }

    /// Polish only: search-a-licious matches the typed spelling exactly, so a thin answer is topped up.
    static func needsSupplement(_ count: Int, lc: String) -> Bool {
        lc == "pl" && count < supplementBelow
    }

    /// `primary` first, then the products of `extra` it does not already have (by code).
    static func merged(_ primary: [FoodCandidate], _ extra: [FoodCandidate]) -> [FoodCandidate] {
        var seen = Set<String>()
        return (primary + extra).filter { seen.insert($0.id).inserted }
    }

    /// An extra request of a text search: the Polish spelling on search-a-licious, or the legacy search.
    private enum ExtraSource {
        case searchALicious(String)
        case legacy(String)
    }

    private enum ExtraOutcome {
        case found([FoodCandidate])
        /// Not sent: cooling down after a 429, or no search slot free without taking the headroom.
        case skipped
        case failed(Error)
    }

    /// One extra request, only with a search slot free right now (it counts against the same budget and never makes
    /// the user wait) and without a retry; a failure leaves the primary answer standing.
    private func extra(_ source: ExtraSource, lc: String) async -> ExtraOutcome {
        if let until = searchCooldownUntil, until > .now { return .skipped }
        guard searchSlots.reserveNow(at: .now, keepingFree: Self.extraHeadroom) else { return .skipped }
        do {
            let products: [OFFProduct]
            switch source {
            case .searchALicious(let terms): products = try await searchALicious(terms, lc: lc)
            case .legacy(let q): products = try await legacySearch(q, lc: lc)
            }
            return .found(candidates(products, lc: lc))
        } catch {
            return .failed(error)
        }
    }

    /// What an empty answer short of a failed extra request reads as: the failure (a 5xx is "busy"), and an odd body
    /// reads as "couldn't reach".
    static func surfaced(_ error: Error) -> Error {
        if error is CancellationError || error is FoodSearchError { return error }
        return FoodSearchError.unreachable
    }

    private func searchALicious(_ terms: String, lc: String) async throws -> [OFFProduct] {
        var comps = URLComponents(string: "https://search.openfoodfacts.org/search")!
        comps.queryItems = [
            .init(name: "q", value: terms),
            .init(name: "langs", value: lc == "pl" ? "pl,en" : "en"),
            .init(name: "page_size", value: "24"),
            .init(name: "fields", value: Self.fields),
        ]
        let data = try await get(comps.url!, isSearch: true)
        return try JSONDecoder().decode(SearchALiciousResponse.self, from: data).hits ?? []
    }

    private func legacySearch(_ q: String, lc: String) async throws -> [OFFProduct] {
        var comps = URLComponents(string: "https://world.openfoodfacts.org/cgi/search.pl")!
        comps.queryItems = [
            .init(name: "search_terms", value: q),
            .init(name: "search_simple", value: "1"),
            .init(name: "action", value: "process"),
            .init(name: "json", value: "1"),
            .init(name: "page_size", value: "24"),
            .init(name: "fields", value: Self.fields),
            .init(name: "lc", value: lc),
        ]
        let data = try await get(comps.url!, isSearch: true)
        return try JSONDecoder().decode(OFFSearchResponse.self, from: data).products ?? []
    }

    /// search-a-licious is still labelled beta: a server error, timeout or odd body tries the legacy endpoint once.
    /// A 429 (same budget), no connection or a cancel does not.
    static func fallsBack(_ error: Error) -> Bool {
        if error is CancellationError { return false }
        guard let error = error as? FoodSearchError else { return true }
        return ![.rateLimited, .offline, .alreadyInFlight].contains(error)
    }

    /// search-a-licious parses `q` as Lucene: typed punctuation would become operators or field filters
    /// ("kawa: latte", "Coca-Cola"), so it is turned into spaces.
    static func searchTerms(_ query: String) -> String {
        let operators = Set(#"+-&|!(){}[]^"~*?:\/"#)
        let cleaned = String(query.map { operators.contains($0) ? " " : $0 })
        return cleaned.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private func waitForSearchSlot() async throws {
        let now = Date.now
        if let until = searchCooldownUntil, until > now { throw FoodSearchError.rateLimited }
        guard let wait = searchSlots.reserve(at: now, maxWait: Self.maxSearchWait) else { throw FoodSearchError.rateLimited }
        if wait > 0 { try await Task.sleep(for: .seconds(wait)) }
    }

    // MARK: - Barcode

    /// Tries every EAN/UPC form of the code. A product with usable nutrition wins at once; a product with only a name
    /// is remembered while the other forms are tried, and comes back as `.partial`.
    func lookup(barcode: String, locale: String = "pl") async throws -> BarcodeLookup {
        let forms = Self.barcodeForms(barcode)
        guard let first = forms.first else { return .notFound }
        let lc = Self.languageCode(locale)
        let key = "barcode|\(lc)|\(first)"
        if let hit = lookups.value(for: key) { return hit }
        guard !inFlight.contains(key) else { throw FoodSearchError.alreadyInFlight }
        inFlight.insert(key)
        defer { inFlight.remove(key) }

        var stub: ProductStub?
        for code in forms {
            let body: OFFProductResponse?
            do {
                body = try await product(code, lc: lc)
            } catch let error where stub != nil && !(error is CancellationError) {
                break   // a later form failing does not undo what an earlier one found
            }
            guard let body else { continue }
            switch Self.outcome(of: body, locale: lc) {
            case .found(let candidate):
                lookups.set(.found(candidate), for: key)
                return .found(candidate)
            case .partial(let found):
                if stub == nil { stub = found }
            case .notFound:
                continue
            }
        }
        let result = stub.map(BarcodeLookup.partial) ?? .notFound
        lookups.set(result, for: key)
        return result
    }

    /// One product read: nil on 404. A 429 or 5xx is retried once after `retryDelay`, then surfaces as
    /// `.rateLimited` / `.busy`.
    private func product(_ code: String, lc: String) async throws -> OFFProductResponse? {
        var comps = URLComponents(string: "https://world.openfoodfacts.org/api/v2/product/\(code).json")!
        comps.queryItems = [.init(name: "fields", value: Self.fields), .init(name: "lc", value: lc)]
        let data: Data
        do {
            do {
                data = try await get(comps.url!)
            } catch let error as FoodSearchError where error.isTransient {
                try await Task.sleep(for: retryDelay)
                data = try await get(comps.url!)
            }
        } catch FoodSearchError.badResponse(404) {
            return nil
        }
        do {
            return try JSONDecoder().decode(OFFProductResponse.self, from: data)
        } catch {
            throw FoodSearchError.badResponse(200)
        }
    }

    /// A v2 product response → lookup result. Empty stubs (no name in any field, no brand) count as not found.
    static func outcome(of body: OFFProductResponse, locale: String) -> BarcodeLookup {
        guard body.status == 1, var product = body.product else { return .notFound }
        if (product.code ?? "").isEmpty { product.code = body.code }
        if let candidate = candidate(from: product, locale: locale) { return .found(candidate) }
        guard let code = product.code, !code.isEmpty, let name = displayName(product, locale: locale) else { return .notFound }
        return .partial(ProductStub(code: code, name: name, brand: firstBrand(product), quantity: trimmed(product.quantity),
                                    servingSizeG: servingGrams(product), imageURL: product.imageFrontSmallURL))
    }

    /// EAN-13 from VisionKit; a leading zero usually means the product is stored under its 12-digit UPC-A form.
    static func barcodeForms(_ raw: String) -> [String] {
        let digits = raw.filter { $0 >= "0" && $0 <= "9" }
        guard [8, 12, 13, 14].contains(digits.count) else { return [] }
        var forms = [digits]
        if digits.count == 14, digits.hasPrefix("0") { forms.append(String(digits.dropFirst())) }
        if digits.count == 13, digits.hasPrefix("0") { forms.append(String(digits.dropFirst())) }
        if digits.count == 12 { forms.append("0" + digits) }
        return forms
    }

    // MARK: - Transport

    private func get(_ url: URL, isSearch: Bool = false) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw FoodSearchError(error)
        }
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        if (200..<300).contains(status) { return data }
        if status == 429, isSearch {
            let retryAfter = http?.value(forHTTPHeaderField: "Retry-After").flatMap(TimeInterval.init)
            searchCooldownUntil = .now + min(max(retryAfter ?? Self.rateLimitCooldown, 5), 120)
        }
        throw FoodSearchError(status: status)
    }

    static func languageCode(_ locale: String) -> String {
        locale.lowercased().hasPrefix("pl") ? "pl" : "en"
    }

    // MARK: - Mapping

    /// Per-100 g figures the app can size a portion with.
    struct Per100: Equatable {
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        var fiber: Double?
        var isEstimated: Bool
    }

    static func candidate(from p: OFFProduct, locale: String) -> FoodCandidate? {
        guard let code = p.code, !code.isEmpty,
              let name = displayName(p, locale: locale),
              let n = per100(p) else { return nil }
        return FoodCandidate(
            id: "off:\(code)", code: code, name: name, brand: firstBrand(p),
            quantity: p.quantity, servingSizeG: servingGrams(p), servingLabel: servingLabel(p),
            kcalPer100: n.kcal, proteinPer100: n.protein, carbsPer100: n.carbs, fatPer100: n.fat, fiberPer100: n.fiber,
            imageURL: p.imageFrontSmallURL, isEstimated: n.isEstimated
        )
    }

    /// Many Polish products have the name in only one field, or none. Polish UI: the Polish name, the main name when
    /// the product's main language is Polish, English, the main name in any language, the generic names, the
    /// abbreviated name. Then "Brand quantity" ("Pudliszki 200 g"), which the user can rename from the label.
    static func displayName(_ p: OFFProduct, locale: String) -> String? {
        let mainIsPolish = p.lang == "pl"
        let chain: [String?] = locale == "pl"
            ? [p.productNamePL, mainIsPolish ? p.productName : nil, p.productNameEN, p.productName,
               p.genericNamePL, p.genericName, p.abbreviatedProductName]
            : [p.productNameEN, p.productName, p.productNamePL, p.genericName, p.genericNamePL, p.abbreviatedProductName]
        if let name = chain.lazy.compactMap(trimmed).first { return name }
        guard let brand = firstBrand(p) else { return nil }
        return [brand, trimmed(p.quantity)].compactMap { $0 }.joined(separator: " ")
    }

    /// The label values (`nutriments`) when they have energy, else OFF's estimate (`nutriments_estimated`). Energy
    /// is kcal, or kJ / 4.184; kcal must be 0…950 and protein, carbs and fat 0…100, otherwise nothing is usable.
    static func per100(_ p: OFFProduct) -> Per100? {
        if let n = p.nutriments, kcal(in: n) != nil { return per100(n, estimated: false) }
        if let n = p.nutrimentsEstimated, kcal(in: n) != nil { return per100(n, estimated: true) }
        return nil
    }

    private static func kcal(in n: [String: LooseNumber]) -> Double? {
        let kj = n["energy-kj_100g"]?.value ?? n["energy_100g"]?.value
        return n["energy-kcal_100g"]?.value ?? kj.map { $0 / 4.184 }
    }

    private static func per100(_ n: [String: LooseNumber], estimated: Bool) -> Per100? {
        guard let kcal = kcal(in: n), kcal.isFinite, (0...950).contains(kcal) else { return nil }
        for key in ["proteins_100g", "carbohydrates_100g", "fat_100g"] {
            if let value = n[key]?.value, !value.isFinite || !(0...100).contains(value) { return nil }
        }
        return Per100(kcal: kcal,
                      protein: n["proteins_100g"]?.value ?? 0,
                      carbs: n["carbohydrates_100g"]?.value ?? 0,
                      fat: n["fat_100g"]?.value ?? 0,
                      fiber: n["fiber_100g"]?.value,
                      isEstimated: estimated)
    }

    static func firstBrand(_ p: OFFProduct) -> String? {
        trimmed(p.brands?.split(separator: ",").first.map(String.init))
    }

    private static func servingLabel(_ p: OFFProduct) -> String? {
        trimmed(p.servingSize)
    }

    private static func servingGrams(_ p: OFFProduct) -> Double? {
        let label = servingLabel(p)
        var grams = (label?.lowercased().contains("ml") == true) ? nil : p.servingQuantity?.value
        if grams == nil, let label { grams = Self.grams(fromLabel: label) }
        if let g = grams, g <= 0 { grams = nil }
        return grams
    }

    private static func trimmed(_ text: String?) -> String? {
        let value = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : value
    }

    /// Parse mass, including "2 x 15 g" and "1 porcja (30 g)". Volume is not mass.
    static func grams(fromLabel label: String) -> Double? {
        let text = label.lowercased().replacingOccurrences(of: ",", with: ".")
        let pattern = #"(?:(\d+(?:\.\d+)?)\s*[x×]\s*)?(\d+(?:\.\d+)?)\s*(kg|g)\b"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let numberRange = Range(match.range(at: 2), in: text),
              let amount = Double(text[numberRange]), amount > 0,
              let unitRange = Range(match.range(at: 3), in: text) else { return nil }
        let multiplier = Range(match.range(at: 1), in: text).flatMap { Double(text[$0]) } ?? 1
        return amount * multiplier * (text[unitRange] == "kg" ? 1000 : 1)
    }

}

/// Sliding-window request budget: at most `limit` requests start in any `window` seconds.
struct RequestWindow {
    let limit: Int
    let window: TimeInterval
    private(set) var starts: [Date] = []

    init(limit: Int, window: TimeInterval) {
        self.limit = max(1, limit)
        self.window = window
    }

    /// Books a start right now only when that leaves `keepingFree` more requests in the window; false books nothing.
    mutating func reserveNow(at now: Date, keepingFree: Int) -> Bool {
        starts.removeAll { now.timeIntervalSince($0) >= window }
        guard starts.count + 1 + max(0, keepingFree) <= limit else { return false }
        starts.append(now)
        return true
    }

    /// Books the earliest start for a new request and returns how long to wait for it (0 = now), or nil, booking
    /// nothing, when that wait would be longer than `maxWait`.
    mutating func reserve(at now: Date, maxWait: TimeInterval) -> TimeInterval? {
        starts.removeAll { now.timeIntervalSince($0) >= window }
        let start = starts.count >= limit ? starts[starts.count - limit].addingTimeInterval(window) : now
        let wait = max(0, start.timeIntervalSince(now))
        guard wait <= maxWait else { return nil }
        starts.append(max(start, now))
        return wait
    }
}

// MARK: - Wire types

struct OFFSearchResponse: Decodable {
    let products: [OFFProduct]?
}

/// search-a-licious (`search.openfoodfacts.org/search`): the products are under `hits`, and `brands` is an array.
struct SearchALiciousResponse: Decodable {
    let hits: [OFFProduct]?
}

struct OFFProductResponse: Decodable {
    let code: String?
    let status: Int?
    let product: OFFProduct?

    enum CodingKeys: String, CodingKey { case code, status, product }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        code = try? c.decode(String.self, forKey: .code)
        status = try? c.decode(Int.self, forKey: .status)
        product = try? c.decode(OFFProduct.self, forKey: .product)
    }
}

struct OFFProduct: Decodable {
    var code: String?
    let productName: String?
    let productNamePL: String?
    let productNameEN: String?
    let genericName: String?
    let genericNamePL: String?
    let abbreviatedProductName: String?
    /// The product's main language ("pl", "fr"…): `product_name` is written in it.
    let lang: String?
    /// "Lidl, Nergi". search-a-licious sends an array, which is joined the same way.
    let brands: String?
    let quantity: String?
    let servingSize: String?
    let servingQuantity: LooseNumber?
    let nutriments: [String: LooseNumber]?
    let nutrimentsEstimated: [String: LooseNumber]?
    let imageFrontSmallURL: String?

    enum CodingKeys: String, CodingKey {
        case code
        case productName = "product_name"
        case productNamePL = "product_name_pl"
        case productNameEN = "product_name_en"
        case genericName = "generic_name"
        case genericNamePL = "generic_name_pl"
        case abbreviatedProductName = "abbreviated_product_name"
        case lang
        case brands, quantity
        case servingSize = "serving_size"
        case servingQuantity = "serving_quantity"
        case nutriments
        case nutrimentsEstimated = "nutriments_estimated"
        case imageFrontSmallURL = "image_front_small_url"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // OFF sometimes returns the code as a number.
        if let s = try? c.decode(String.self, forKey: .code) { code = s }
        else if let i = try? c.decode(Int64.self, forKey: .code) { code = String(i) }
        else { code = nil }
        productName = try? c.decode(String.self, forKey: .productName)
        productNamePL = try? c.decode(String.self, forKey: .productNamePL)
        productNameEN = try? c.decode(String.self, forKey: .productNameEN)
        genericName = try? c.decode(String.self, forKey: .genericName)
        genericNamePL = try? c.decode(String.self, forKey: .genericNamePL)
        abbreviatedProductName = try? c.decode(String.self, forKey: .abbreviatedProductName)
        lang = try? c.decode(String.self, forKey: .lang)
        if let s = try? c.decode(String.self, forKey: .brands) { brands = s }
        else if let list = try? c.decode([String].self, forKey: .brands) { brands = list.joined(separator: ", ") }
        else { brands = nil }
        quantity = try? c.decode(String.self, forKey: .quantity)
        servingSize = try? c.decode(String.self, forKey: .servingSize)
        servingQuantity = try? c.decode(LooseNumber.self, forKey: .servingQuantity)
        nutriments = try? c.decode([String: LooseNumber].self, forKey: .nutriments)
        nutrimentsEstimated = try? c.decode([String: LooseNumber].self, forKey: .nutrimentsEstimated)
        imageFrontSmallURL = try? c.decode(String.self, forKey: .imageFrontSmallURL)
    }
}

/// OFF nutriment values arrive as numbers or numeric strings (and occasionally as unit labels, which decode to nil).
struct LooseNumber: Decodable {
    let value: Double?

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let d = try? c.decode(Double.self) { value = d }
        else if let s = try? c.decode(String.self) { value = Double(s.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)) }
        else { value = nil }
    }
}

// MARK: - LRU cache with TTL

struct LRUCache<Key: Hashable, Value> {
    private struct Entry { let value: Value; let storedAt: Date }
    private var storage: [Key: Entry] = [:]
    private var order: [Key] = []
    let capacity: Int
    let ttl: TimeInterval

    init(capacity: Int, ttl: TimeInterval) {
        self.capacity = max(1, capacity)
        self.ttl = ttl
    }

    mutating func value(for key: Key) -> Value? {
        guard let entry = storage[key] else { return nil }
        if Date.now.timeIntervalSince(entry.storedAt) > ttl {
            remove(key)
            return nil
        }
        touch(key)
        return entry.value
    }

    mutating func set(_ value: Value, for key: Key) {
        storage[key] = Entry(value: value, storedAt: .now)
        touch(key)
        while order.count > capacity, let oldest = order.first { remove(oldest) }
    }

    private mutating func touch(_ key: Key) {
        order.removeAll { $0 == key }
        order.append(key)
    }

    private mutating func remove(_ key: Key) {
        storage[key] = nil
        order.removeAll { $0 == key }
    }
}
