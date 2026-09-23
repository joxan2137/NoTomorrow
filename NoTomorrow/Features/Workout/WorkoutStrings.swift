import Foundation

/// Copy helpers for the Train tab: counted nouns with Polish plural categories (one / few / many)
/// and the muscle / equipment vocabulary from free-exercise-db mapped onto catalog keys.
enum WorkoutStrings {

    // MARK: Plurals

    /// CLDR plural category as used by Polish and English (the English catalog values for `few` and `many` are identical).
    private static func pluralSuffix(_ n: Int) -> String {
        if n == 1 { return "one" }
        let mod10 = n % 10, mod100 = n % 100
        if (2...4).contains(mod10) && !(12...14).contains(mod100) { return "few" }
        return "many"
    }

    private static func counted(_ base: String, _ n: Int) -> String {
        let key = "\(base).\(pluralSuffix(n))"
        let pattern = String(localized: String.LocalizationValue(key))
        return String(format: pattern, locale: .current, n)
    }

    /// "5 exercises" / "5 ćwiczeń"
    static func exercises(_ n: Int) -> String { counted("workout.exerciseCount", n) }

    /// "3 sets" / "3 serie"
    static func sets(_ n: Int) -> String { counted("workout.setCount", n) }

    /// "2 PRs" — existing dashboard key, Polish keeps "PR" invariant.
    static func prs(_ n: Int) -> String {
        String(format: String(localized: "dashboard.prs"), locale: .current, n)
    }

    /// "7 results"
    static func results(_ n: Int) -> String {
        String(format: String(localized: "exercises.results"), locale: .current, n)
    }

    /// "Add 3"
    static func add(_ n: Int) -> String {
        String(format: String(localized: "exercises.addCount"), locale: .current, n)
    }

    /// Create "<query>" as a new exercise
    static func create(_ query: String) -> String {
        String(format: String(localized: "exercises.create"), locale: .current, query)
    }

    // MARK: Muscles & equipment

    private static func slug(_ raw: String) -> String {
        raw.lowercased()
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
    }

    /// Localized muscle name ("chest" → "Chest" / "Klatka"). Unknown values fall back to a capitalized raw string.
    static func muscle(_ raw: String) -> String {
        let key = "muscleName.\(slug(raw))"
        let value = String(localized: String.LocalizationValue(key))
        return value == key ? raw.capitalized(with: .current) : value
    }

    static func equipment(_ raw: String) -> String {
        let key = "equipment.\(slug(raw))"
        let value = String(localized: String.LocalizationValue(key))
        return value == key ? raw.capitalized(with: .current) : value
    }

    /// "Chest · Triceps, Shoulders" — primary muscles, then secondaries; equipment when there are no secondaries.
    static func subtitle(for exercise: Exercise) -> String {
        let primary = exercise.primaryMuscles.map(muscle).joined(separator: ", ")
        let secondary = exercise.secondaryMuscles.prefix(2).map(muscle).joined(separator: ", ")
        var parts: [String] = []
        if !primary.isEmpty { parts.append(primary) }
        if !secondary.isEmpty {
            parts.append(secondary)
        } else if let equipment = exercise.equipment, !equipment.isEmpty {
            parts.append(self.equipment(equipment))
        }
        return parts.joined(separator: " · ")
    }

    /// Case- and diacritic-insensitive search key. Foundation strips the marks from ą ę ó ś ż ź ć ń but keeps ł
    /// (it has no decomposition), so ł is flattened by hand: "lawka" finds "Ławka". Locale-independent, like Android.
    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .replacingOccurrences(of: "ł", with: "l")
            .replacingOccurrences(of: "Ł", with: "l")
    }
}
