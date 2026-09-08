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

    func makeFoodItem() -> FoodItem {
        FoodItem(id: id, name: name, brand: brand, source: .openFoodFacts, barcode: code,
                 kcalPer100: kcalPer100, proteinPer100: proteinPer100, carbsPer100: carbsPer100, fatPer100: fatPer100,
                 fiberPer100: fiberPer100, servingSizeG: servingSizeG, servingLabel: servingLabel, imageURL: imageURL)
    }
}

enum FoodSearchError: LocalizedError {
    case alreadyInFlight
    case rateLimited
    case badResponse(Int)

    var errorDescription: String? {
        switch self {
        case .alreadyInFlight: String(localized: "fuel.search.error.inFlight")
        case .rateLimited: String(localized: "fuel.search.error.rateLimited")
        case .badResponse: String(localized: "fuel.search.error.network")
        }
    }
}

/// Open Food Facts client. Debouncing (500 ms, ≥ 2 chars) is the view's job; this actor refuses a second identical in-flight
/// query and memoises results for five minutes so back-and-forth typing does not burn the 10 req/min budget.
actor FoodSearchService {
    static let shared = FoodSearchService()

    static let userAgent = "NoTomorrow/0.1 (markzaluben@proton.me)"
    static let fields = "code,product_name,product_name_pl,brands,quantity,serving_size,serving_quantity,nutriments,image_front_small_url,countries_tags"
    static let minimumQueryLength = 2

    private let session: URLSession
    private var cache = LRUCache<String, [FoodCandidate]>(capacity: 40, ttl: 5 * 60)
    private var inFlight: Set<String> = []

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 15
            config.waitsForConnectivity = false
            self.session = URLSession(configuration: config)
        }
    }

    // MARK: - Text search

    func search(query: String, locale: String) async throws -> [FoodCandidate] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= Self.minimumQueryLength else { return [] }
        let lc = Self.languageCode(locale)
        let key = "\(lc)|\(q.lowercased())"
        if let hit = cache.value(for: key) { return hit }
        guard !inFlight.contains(key) else { throw FoodSearchError.alreadyInFlight }
        inFlight.insert(key)
        defer { inFlight.remove(key) }

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
        let data = try await get(comps.url!)
        let page = try JSONDecoder().decode(OFFSearchResponse.self, from: data)
        var seen = Set<String>()
        let results = (page.products ?? []).compactMap { Self.candidate(from: $0, locale: lc) }
            .filter { seen.insert($0.id).inserted }
        cache.set(results, for: key)
        return results
    }

    // MARK: - Barcode

    func lookup(barcode: String) async throws -> FoodCandidate? {
        let forms = Self.barcodeForms(barcode)
        guard !forms.isEmpty else { return nil }
        for code in forms {
            let key = "barcode|\(code)"
            if let hit = cache.value(for: key)?.first { return hit }
            guard !inFlight.contains(key) else { throw FoodSearchError.alreadyInFlight }
            inFlight.insert(key)
            defer { inFlight.remove(key) }

            var comps = URLComponents(string: "https://world.openfoodfacts.org/api/v2/product/\(code).json")!
            comps.queryItems = [.init(name: "fields", value: Self.fields), .init(name: "lc", value: "pl")]
            let data: Data
            do {
                data = try await get(comps.url!)
            } catch FoodSearchError.badResponse(404) {
                continue
            }
            let body = try JSONDecoder().decode(OFFProductResponse.self, from: data)
            guard body.status == 1, let product = body.product,
                  let candidate = Self.candidate(from: product, locale: "pl") else { continue }
            cache.set([candidate], for: key)
            return candidate
        }
        return nil
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

    private func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200..<300: return data
        case 429: throw FoodSearchError.rateLimited
        default: throw FoodSearchError.badResponse(status)
        }
    }

    static func languageCode(_ locale: String) -> String {
        locale.lowercased().hasPrefix("pl") ? "pl" : "en"
    }

    // MARK: - Mapping

    static func candidate(from p: OFFProduct, locale: String) -> FoodCandidate? {
        guard let code = p.code, !code.isEmpty else { return nil }
        let pl = p.productNamePL?.trimmingCharacters(in: .whitespaces) ?? ""
        let en = p.productName?.trimmingCharacters(in: .whitespaces) ?? ""
        let name = locale == "pl" && !pl.isEmpty ? pl : (!en.isEmpty ? en : pl)
        guard !name.isEmpty else { return nil }
        let n = p.nutriments ?? [:]
        let kj = n["energy-kj_100g"]?.value ?? n["energy_100g"]?.value
        guard let kcal = n["energy-kcal_100g"]?.value ?? kj.map({ $0 / 4.184 }),
              kcal.isFinite, (0...950).contains(kcal) else { return nil }
        for key in ["proteins_100g", "carbohydrates_100g", "fat_100g"] {
            if let value = n[key]?.value, !value.isFinite || !(0...100).contains(value) { return nil }
        }

        let brand = p.brands?.split(separator: ",").first.map { $0.trimmingCharacters(in: .whitespaces) }
        let servingLabel = p.servingSize?.trimmingCharacters(in: .whitespaces)
        var servingG = (servingLabel?.lowercased().contains("ml") == true) ? nil : p.servingQuantity?.value
        if servingG == nil, let servingLabel { servingG = Self.grams(fromLabel: servingLabel) }
        if let g = servingG, g <= 0 { servingG = nil }

        return FoodCandidate(
            id: "off:\(code)", code: code, name: name, brand: brand.flatMap { $0.isEmpty ? nil : $0 },
            quantity: p.quantity, servingSizeG: servingG,
            servingLabel: (servingLabel?.isEmpty ?? true) ? nil : servingLabel,
            kcalPer100: kcal,
            proteinPer100: n["proteins_100g"]?.value ?? 0,
            carbsPer100: n["carbohydrates_100g"]?.value ?? 0,
            fatPer100: n["fat_100g"]?.value ?? 0,
            fiberPer100: n["fiber_100g"]?.value,
            imageURL: p.imageFrontSmallURL
        )
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

// MARK: - Wire types

struct OFFSearchResponse: Decodable {
    let products: [OFFProduct]?
}

struct OFFProductResponse: Decodable {
    let status: Int?
    let product: OFFProduct?
}

struct OFFProduct: Decodable {
    let code: String?
    let productName: String?
    let productNamePL: String?
    let brands: String?
    let quantity: String?
    let servingSize: String?
    let servingQuantity: LooseNumber?
    let nutriments: [String: LooseNumber]?
    let imageFrontSmallURL: String?

    enum CodingKeys: String, CodingKey {
        case code
        case productName = "product_name"
        case productNamePL = "product_name_pl"
        case brands, quantity
        case servingSize = "serving_size"
        case servingQuantity = "serving_quantity"
        case nutriments
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
        brands = try? c.decode(String.self, forKey: .brands)
        quantity = try? c.decode(String.self, forKey: .quantity)
        servingSize = try? c.decode(String.self, forKey: .servingSize)
        servingQuantity = try? c.decode(LooseNumber.self, forKey: .servingQuantity)
        nutriments = try? c.decode([String: LooseNumber].self, forKey: .nutriments)
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
