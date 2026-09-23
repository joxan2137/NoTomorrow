import Foundation

/// Where a scanned code lives on this device: check-digit validation, the key an in-store weight label is saved under,
/// and which saved `FoodItem` wins when several share a code.
enum BarcodeKey {
    /// GTIN-8/12/13/14 mod-10 check digit (port of backend/src/food.ts `validBarcode`).
    static func isValidGTIN(_ code: String) -> Bool {
        guard [8, 12, 13, 14].contains(code.count), code.allSatisfy(\.isASCIIDigit) else { return false }
        let digits = code.compactMap(\.wholeNumberValue)
        let body = digits.dropLast().reversed()
        let sum = body.enumerated().reduce(0) { $0 + $1.element * ($1.offset % 2 == 0 ? 3 : 1) }
        return (10 - sum % 10) % 10 == digits.last
    }

    /// In-store variable-measure codes (GS1 Polska RCN: 23/27 packer-printed, 24/29 scale labels, 25/26/28
    /// retailer-internal) carry the weight or price in digits 8–12, so the full code changes with every package.
    /// They are saved under the 7-digit item prefix instead. Fixed retailer codes (20–22, e.g. Lidl, Carrefour own
    /// brands, which are in Open Food Facts) and codes whose value field is 00000 keep the full code.
    static func storageKey(for code: String) -> String {
        guard code.count == 13, code.allSatisfy(\.isASCIIDigit),
              let prefix = Int(code.prefix(2)), (23...29).contains(prefix),
              code.dropFirst(7).prefix(5) != "00000" else { return code }
        return String(code.prefix(7))
    }

    /// Every barcode a scanned code may already be saved under on this device: its OFF forms plus the RCN item key.
    static func localKeys(for code: String) -> [String] {
        var keys = FoodSearchService.barcodeForms(code)
        let key = storageKey(for: keys.first ?? code)
        if !keys.contains(key) { keys.append(key) }
        return keys
    }

    /// Several saved foods can share a code (a label the user typed and an Open Food Facts hit logged from search).
    /// The user's own label wins, then the most recently used, then the id, so the pick never depends on store order.
    static func preferred(_ items: [FoodItem]) -> FoodItem? {
        items.min { a, b in
            if (a.source == .custom) != (b.source == .custom) { return a.source == .custom }
            switch (a.lastUsedAt, b.lastUsedAt) {
            case let (x?, y?) where x != y: return x > y
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.id < b.id
            }
        }
    }
}

extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}
