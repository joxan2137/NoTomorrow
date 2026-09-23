import Foundation

/// The Polish spelling of a food query typed without diacritics: "mieta" → "mięta", "ser zolty" → "ser żółty".
///
/// search-a-licious matches the typed spelling only, and nearly every Polish product is named with its diacritics,
/// so "mieta" finds one product without nutrition and "mięta" finds a shelf. People skip the diacritics on a phone
/// keyboard all the time, so a diacritic-free Polish query also asks for its Polish spelling.
/// A vocabulary of everyday food words, not a guesser: a wrong spelling costs a search and adds noise, and most
/// words have several plausible diacritic forms.
enum PolishSpelling {
    /// The query with every known word in its Polish spelling, lowercased; nil when the query already has Polish
    /// letters (the user typed them) or no word is known.
    static func variant(of query: String) -> String? {
        let words = query.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty, !words.contains(where: hasPolishLetters) else { return nil }
        var changed = false
        let spelled = words.map { word -> String in
            guard let polish = lexicon[word] else { return word }
            changed = true
            return polish
        }
        return changed ? spelled.joined(separator: " ") : nil
    }

    static func hasPolishLetters(_ text: String) -> Bool {
        text.contains { polishLetters.contains($0) }
    }

    private static let polishLetters = Set("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ")

    /// Diacritic-free form → Polish spelling.
    private static let lexicon: [String: String] = {
        var map: [String: String] = [:]
        for word in words {
            let key = FoodMatch.fold(word)
            if key != word { map[key] = word }
        }
        return map
    }()

    /// Everyday food words with Polish letters, in the forms people search for (base form, plural, adjective).
    private static let words: [String] = [
        // Herbs, condiments, taste
        "mięta", "miętowy", "miętowa", "miętowe", "sól", "miód", "dżem", "ćwikła", "zioła", "ziołowa", "ziołowy",
        "słodki", "słodka", "słodkie", "słony", "słona", "słone", "kwaśny", "kwaśna", "kwaśne",
        // Soups, dishes, meat and fish
        "żurek", "rosół", "ogórkowa", "grochówka", "kapuśniak", "gołąbki", "gołąbek", "naleśnik", "naleśniki",
        "pierś", "piersi", "żeberka", "wątróbka", "wątroba", "pieczeń", "łosoś", "łososia", "śledź", "śledzie",
        "tuńczyk", "pstrąg", "węgorz", "wędlina", "wędliny", "wędzony", "wędzona", "wędzone", "kiełbasa",
        "kiełbaska", "kiełbaski", "parówka", "parówki", "słonina", "mięso", "mięsny", "mięsna", "wołowina",
        "wołowy", "wołowa", "cielęcina", "jagnięcina", "gęś", "gęsi", "smażony", "smażona", "smażone", "świeży",
        "świeża", "świeże", "mrożony", "mrożona", "mrożone",
        // Dairy, bakery, grains, sweets
        "masło", "maślanka", "maślany", "śmietana", "śmietanka", "twaróg", "twarożek", "żółty", "żółta", "żółte",
        "biały", "biała", "białe", "białko", "bułka", "bułki", "pączek", "pączki", "drożdżówka", "drożdże",
        "żytni", "żytnia", "żytnie", "płatki", "mąka", "ryż", "jęczmienna", "jęczmienny", "krówki", "żelki",
        // Fruit, vegetables, nuts
        "jabłko", "jabłka", "jabłkowy", "jabłkowa", "jabłkowe", "śliwka", "śliwki", "śliwkowy", "śliwkowa",
        "wiśnia", "wiśnie", "wiśniowy", "wiśniowa", "czereśnia", "czereśnie", "żurawina", "żurawinowy",
        "żurawinowa", "jeżyny", "borówka", "borówki", "pomarańcza", "pomarańcze", "pomarańczowy", "pomarańczowa",
        "ogórek", "ogórki", "sałata", "sałatka", "bakłażan", "brokuł", "brokuły", "słonecznik",
        "orzechów",
        // Drinks
        "napój", "napoje", "wódka",
    ]
}
