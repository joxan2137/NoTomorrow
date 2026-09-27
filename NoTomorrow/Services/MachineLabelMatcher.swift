import Foundation

/// Matches the text read off a gym machine's placard ("SEATED LEG CURL", "Hammer Strength ISO-LATERAL ROW") against
/// the exercise library, on device. Spec: docs/machine-scan.md; Android port: `service/MachineLabelMatcher.kt`.
///
/// Every name and label word is folded, split and stemmed the same way; a word's weight is its rarity across the
/// library (so "machine" or "hammer strength" count for little and "adductor" for a lot) times how prominent its
/// line is on the label. Each exercise gets an F-score of how much of its name the label covers and how much of the
/// label's library words its name explains. Words the library never uses (brand logos, safety text) are ignored.
struct MachineLabelMatcher: Sendable {

    struct Candidate: Sendable {
        let id: String
        let name: String
        let namePL: String?
        let equipment: String?
    }

    /// One recognised line and how prominent it is (its text height over the tallest line's), 0...1.
    struct Line: Sendable, Equatable {
        let text: String
        let weight: Double
    }

    struct Match: Sendable, Equatable, Identifiable {
        let id: String
        let score: Double
    }

    /// Below this nothing is offered and the scan falls back to the picker's search.
    static let minimumScore = 0.4
    static let maxMatches = 3

    private struct Entry: Sendable {
        let id: String
        let nameLength: Int
        let names: [[String]]
        let freeWeight: Bool
    }

    private let entries: [Entry]
    private let idf: [String: Double]

    init(candidates: [Candidate]) {
        var df: [String: Int] = [:]
        var entries: [Entry] = []
        entries.reserveCapacity(candidates.count)
        for c in candidates {
            let en = Self.uniqueTokens(c.name)
            let pl = c.namePL.map(Self.uniqueTokens) ?? []
            for token in Set(en).union(pl) { df[token, default: 0] += 1 }
            entries.append(Entry(id: c.id, nameLength: c.name.count, names: [en, pl].filter { !$0.isEmpty },
                                 freeWeight: c.equipment.map(Self.freeWeightEquipment.contains) ?? false))
        }
        let n = Double(max(candidates.count, 1))
        self.idf = df.mapValues { log(1 + n / Double($0)) }
        self.entries = entries
    }

    /// Best matches first, at most `maxMatches`, none under `minimumScore`, skipping `excluding`.
    func match(_ lines: [Line], excluding: Set<String> = []) -> [Match] {
        let weights = labelWeights(lines)
        let labelMass = weights.reduce(0) { $0 + (idf[$1.key] ?? 0) * $1.value }
        guard labelMass > 0 else { return [] }

        var scored: [(match: Match, length: Int)] = []
        for entry in entries where !excluding.contains(entry.id) {
            var best = 0.0
            for name in entry.names {
                var total = 0.0, matched = 0.0, plainMatched = 0.0
                for token in name {
                    let rarity = idf[token] ?? 0
                    let w = weights[token] ?? 0
                    total += rarity
                    matched += rarity * w
                    plainMatched += w
                }
                guard matched > 0, total > 0 else { continue }
                // Recall mixes rarity-weighted and plain word coverage, so one rare word left out ("One Arm") and
                // several common ones left out both cost something.
                let recall = 0.5 * matched / total + 0.5 * plainMatched / Double(name.count)
                let precision = matched / labelMass
                best = max(best, 2 * recall * precision / (recall + precision))
            }
            // A placard sits on a machine or a cable stack, never on a dumbbell.
            if entry.freeWeight { best *= 0.85 }
            if best >= Self.minimumScore { scored.append((Match(id: entry.id, score: best), entry.nameLength)) }
        }
        scored.sort { a, b in
            if a.match.score != b.match.score { return a.match.score > b.match.score }
            if a.length != b.length { return a.length < b.length }
            return a.match.id < b.match.id
        }
        return scored.prefix(Self.maxMatches).map(\.match)
    }

    /// The label's library words, each at the weight of the most prominent line it appears on. A word the library
    /// does not use counts as its one-letter-off library neighbours (OCR misreads) at 0.7, else not at all.
    private func labelWeights(_ lines: [Line]) -> [String: Double] {
        var weights: [String: Double] = [:]
        for line in lines {
            let lineWeight = min(max(line.weight, 0), 1)
            var tokens = Self.tokens(line.text)
            tokens += tokens.flatMap { Self.labelExpansions[$0] ?? [] }
            for token in tokens {
                if idf[token] != nil {
                    weights[token] = max(weights[token] ?? 0, lineWeight)
                } else if token.count >= 6 {
                    for known in idf.keys where Self.isOneEditApart(token, known) {
                        weights[known] = max(weights[known] ?? 0, lineWeight * 0.7)
                    }
                }
            }
        }
        return weights
    }

    // MARK: Text

    static let freeWeightEquipment: Set<String> = [
        "dumbbell", "barbell", "kettlebells", "bands", "body only", "medicine ball", "exercise ball", "foam roll",
        "e-z curl bar",
    ]
    private static let stopWords: Set<String> = [
        "the", "on", "of", "and", "with", "a", "to", "for", "in", "na", "z", "do", "i", "w", "ze",
    ]
    /// Two-word spellings that the library writes as one word.
    private static let phrases: [(String, String)] = [
        ("pull down", "pulldown"), ("push down", "pushdown"), ("pec deck", "butterfly"),
    ]
    private static let canonical: [String: String] = [
        "flye": "fly", "abduction": "abductor", "adduction": "adductor", "calve": "calf", "ab": "abdominal",
        "abs": "abdominal", "delt": "deltoid",
    ]
    /// Label words that also stand for a library word: pec-fly and pectoral machines are the library's "Butterfly".
    private static let labelExpansions: [String: [String]] = ["pec": ["butterfly"], "pectoral": ["butterfly"]]

    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .replacingOccurrences(of: "ł", with: "l")
            .replacingOccurrences(of: "Ł", with: "l")
            .lowercased()
    }

    /// Folded, split on anything but a–z and 0–9, two-word phrases joined, stop words, numbers and single letters
    /// dropped, plurals stemmed.
    static func tokens(_ text: String) -> [String] {
        let folded = fold(text)
        let plain = String(folded.unicodeScalars.map { scalar -> Character in
            let v = scalar.value
            return (97...122).contains(v) || (48...57).contains(v) ? Character(scalar) : " "
        })
        var spaced = " " + plain.split(separator: " ").joined(separator: " ") + " "
        for (from, to) in phrases { spaced = spaced.replacingOccurrences(of: " \(from) ", with: " \(to) ") }
        return spaced.split(separator: " ").compactMap { word in
            let token = String(word)
            if token.count < 2 || stopWords.contains(token) || token.allSatisfy(\.isASCIIDigit) { return nil }
            return stem(token)
        }
    }

    static func stem(_ token: String) -> String {
        var t = token
        if t.count > 4 && t.hasSuffix("sses") {
            t.removeLast(2)
        } else if t.count > 3 && t.hasSuffix("ies") {
            t.removeLast(3); t += "y"
        } else if t.count > 3 && t.hasSuffix("s") && !t.hasSuffix("ss") && !t.hasSuffix("us") {
            t.removeLast()
        }
        return canonical[t] ?? t
    }

    private static func uniqueTokens(_ text: String) -> [String] {
        var seen = Set<String>()
        return tokens(text).filter { seen.insert($0).inserted }
    }

    /// One substitution, insertion or deletion apart (Levenshtein distance exactly 1).
    static func isOneEditApart(_ a: String, _ b: String) -> Bool {
        guard abs(a.count - b.count) <= 1, a != b else { return false }
        let a = Array(a), b = Array(b)
        if a.count == b.count { return zip(a, b).filter { $0 != $1 }.count == 1 }
        let (short, long) = a.count < b.count ? (a, b) : (b, a)
        var i = 0
        while i < short.count && short[i] == long[i] { i += 1 }
        return short[i...] == long[(i + 1)...]
    }
}
