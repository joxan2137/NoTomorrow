import Foundation

/// Local matching for the saved-food library: people type "zolty ser" for "Żółty ser" and "mlekovita serek" for
/// "Serek wiejski · Mlekovita", so matching folds case, diacritics and ł, and every typed word must appear somewhere.
enum FoodMatch {
    /// Case-, diacritic- and width-insensitive key. "ł" has no Unicode decomposition, so it is flattened by hand.
    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .replacingOccurrences(of: "ł", with: "l")
    }

    /// True when every whitespace-separated word of `query` appears in the name or the brand. An empty query matches.
    static func matches(_ query: String, name: String, brand: String?) -> Bool {
        let words = fold(query).split(whereSeparator: \.isWhitespace)
        guard !words.isEmpty else { return true }
        let haystack = fold(brand.map { "\(name) \($0)" } ?? name)
        return words.allSatisfy { haystack.contains($0) }
    }
}
