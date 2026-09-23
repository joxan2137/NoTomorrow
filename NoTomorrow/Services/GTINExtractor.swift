import Foundation

/// Turns a scanner payload into the product code Open Food Facts files it under, or nil when the payload carries no
/// GTIN (a promo QR code, a misread), so the scanner keeps looking instead of firing on it.
enum GTINExtractor {
    /// The decoder's symbology, reduced to what changes the parsing.
    enum Symbology {
        case ean13, ean8, upce
        /// GS1 DataBar, DataBar Expanded and DataBar Limited (produce stickers, fresh-food labels).
        case gs1DataBar
        case qr, dataMatrix
        case other
    }

    static func gtin(payload: String, symbology: Symbology) -> String? {
        let text = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        switch symbology {
        case .ean13, .ean8:
            let digits = text.filter(\.isASCIIDigit)
            return [8, 12, 13, 14].contains(digits.count) ? digits : nil
        case .upce:
            return expandUPCE(text.filter(\.isASCIIDigit)).map { "0" + $0 }
        case .gs1DataBar:
            // Some decoders hand over the bare GTIN instead of the (01) element string.
            if BarcodeKey.isValidGTIN(text) { return text.count == 14 ? normalized(gtin14: text) : text }
            return elementStringGTIN(text)
        case .qr, .dataMatrix:
            return digitalLinkGTIN(text) ?? elementStringGTIN(text)
        case .other:
            return nil
        }
    }

    /// A code typed by hand: a valid GTIN as is, or an 8-digit UPC-E (which fails the EAN-8 check) expanded to its
    /// 13-digit form. Nil when the digits cannot be a real code, so the entry field can ask the user to check them.
    static func manual(_ digits: String) -> String? {
        if BarcodeKey.isValidGTIN(digits) { return digits }
        if digits.count == 8, let upca = expandUPCE(digits) { return "0" + upca }
        return nil
    }

    // MARK: - UPC-E

    /// UPC-E → 12-digit UPC-A (number system 0 or 1). Accepts the 8-digit form, 7 digits without the check digit, or
    /// the 6 compressed digits alone; a given check digit must match the expansion.
    static func expandUPCE(_ code: String) -> String? {
        guard code.allSatisfy(\.isASCIIDigit), (6...8).contains(code.count) else { return nil }
        let digits = code.map { String($0) }
        let numberSystem = code.count == 6 ? "0" : digits[0]
        guard numberSystem == "0" || numberSystem == "1" else { return nil }
        let x = code.count == 6 ? digits : Array(digits[1...6])
        let body: String
        switch x[5] {
        case "0", "1", "2": body = numberSystem + x[0] + x[1] + x[5] + "0000" + x[2] + x[3] + x[4]
        case "3": body = numberSystem + x[0] + x[1] + x[2] + "00000" + x[3] + x[4]
        case "4": body = numberSystem + x[0] + x[1] + x[2] + x[3] + "00000" + x[4]
        default: body = numberSystem + x[0] + x[1] + x[2] + x[3] + x[4] + "0000" + x[5]
        }
        guard let check = checkDigit(body) else { return nil }
        if code.count == 8, digits[7] != check { return nil }
        return body + check
    }

    // MARK: - GS1 element strings (DataBar, GS1 DataMatrix, GS1 QR)

    private static let groupSeparator: Character = "\u{1D}"

    /// Total length (AI + data) of the predefined fixed-length AIs, keyed by their first two digits (GS1 General
    /// Specifications, figure 7.8.5-2). Every other AI is variable length and ends at a group separator.
    private static let fixedLengths: [String: Int] = [
        "00": 20, "01": 16, "02": 16, "03": 16, "04": 18,
        "11": 8, "12": 8, "13": 8, "14": 8, "15": 8, "16": 8, "17": 8, "18": 8, "19": 8, "20": 4,
        "31": 10, "32": 10, "33": 10, "34": 10, "35": 10, "36": 10, "41": 16,
    ]

    /// AI (01) from "(01)05901234123457(3103)000150", or the raw form with an optional symbology identifier
    /// (]C1, ]e0, ]d2, ]Q3) and FNC1 / group separators. Nil when there is no (01) or its check digit is wrong.
    static func elementStringGTIN(_ payload: String) -> String? {
        var s = Substring(payload)
        if s.hasPrefix("]"), s.count >= 3 { s = s.dropFirst(3) }
        while s.first == groupSeparator { s = s.dropFirst() }

        let gtin: Substring?
        if s.hasPrefix("(") {
            gtin = s.firstMatch(of: #/\(01\)(\d{14})/#).map { $0.1 }
        } else {
            gtin = walkToGTIN(s)
        }
        guard let gtin, BarcodeKey.isValidGTIN(String(gtin)) else { return nil }
        return normalized(gtin14: String(gtin))
    }

    private static func walkToGTIN(_ element: Substring) -> Substring? {
        var s = element
        while s.count >= 2 {
            let ai = String(s.prefix(2))
            guard ai.allSatisfy(\.isASCIIDigit) else { return nil }
            if ai == "01" {
                let value = s.dropFirst(2).prefix(14)
                return value.count == 14 && value.allSatisfy(\.isASCIIDigit) ? value : nil
            }
            if let length = fixedLengths[ai] {
                s = s.dropFirst(length)
                if s.first == groupSeparator { s = s.dropFirst() }
            } else if let separator = s.firstIndex(of: groupSeparator) {
                s = s[s.index(after: separator)...]
            } else {
                return nil
            }
        }
        return nil
    }

    // MARK: - GS1 Digital Link

    /// "https://id.gs1.org/01/05901234123457/10/ABC" (any host; "gtin" is the long alias of "01"). The GTIN may be
    /// 8–14 digits; it is padded to 14 and must pass the check digit.
    static func digitalLinkGTIN(_ payload: String) -> String? {
        guard let url = URL(string: payload), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return nil }
        let parts = url.path.split(separator: "/")
        for (index, part) in parts.enumerated() where (part == "01" || part.lowercased() == "gtin") && index + 1 < parts.count {
            let value = parts[index + 1]
            guard (8...14).contains(value.count), value.allSatisfy(\.isASCIIDigit) else { continue }
            let gtin14 = String(repeating: "0", count: 14 - value.count) + value
            if BarcodeKey.isValidGTIN(gtin14) { return normalized(gtin14: gtin14) }
        }
        return nil
    }

    // MARK: - Helpers

    /// GTIN-14 → the form printed on retail packs: a GTIN-8 (six leading zeros, GS1 prefix 00000 is reserved for
    /// them), else the EAN-13 without the leading packaging-level zero, else the 14 digits (outer cases, variable measure).
    static func normalized(gtin14: String) -> String {
        if gtin14.hasPrefix("000000") { return String(gtin14.suffix(8)) }
        if gtin14.hasPrefix("0") { return String(gtin14.dropFirst()) }
        return gtin14
    }

    private static func checkDigit(_ body: String) -> String? {
        let digits = body.compactMap(\.wholeNumberValue)
        guard digits.count == body.count else { return nil }
        let sum = digits.reversed().enumerated().reduce(0) { $0 + $1.element * ($1.offset % 2 == 0 ? 3 : 1) }
        return String((10 - sum % 10) % 10)
    }
}
