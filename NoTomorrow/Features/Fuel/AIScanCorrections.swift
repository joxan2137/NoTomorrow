import Foundation

/// The user's fixes to an AI estimate: sent to the model as an authoritative line on "Recalculate with details", and
/// re-applied to its new answer so a refine never undoes them. The line is machine text in English, not localized.
enum AIScanCorrections {
    static let prefix = "User corrections (authoritative): "
    static let maxCount: Double = 99

    /// A corrected item and every name it was known by (the model's name first), so the refined answer's item of the
    /// same food is found even after a rename.
    struct Kept: Equatable {
        var food: AIFood
        var names: [String]
    }

    /// `User corrections (authoritative): Pierogi ruskie = 280 g (8 szt.); Kompot = 250 g; removed: Oliwa`.
    /// Nil when there is nothing to say.
    static func line(kept: [AIFood], removed: [String]) -> String? {
        var parts = kept.map { food -> String in
            var part = "\(food.name) = \(number(food.grams)) g"
            if let unit = food.unitName { part += " (\(number(food.units)) \(unit))" }
            return part
        }
        parts += removed.map { "removed: \($0)" }
        return parts.isEmpty ? nil : prefix + parts.joined(separator: "; ")
    }

    /// The notes sent with the photo: the typed details, then the corrections line, within `maxLength` UTF-16 units
    /// (the backend's limit). The typed part is cut first, at a character boundary.
    static func notes(typed: String, corrections: String?, maxLength: Int = 1500) -> String {
        let details = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let corrections, !corrections.isEmpty else { return truncated(details, utf16: maxLength) }
        let line = truncated(corrections, utf16: maxLength)
        let room = maxLength - line.utf16.count - 1
        let head = room > 0 ? truncated(details, utf16: room).trimmingCharacters(in: .whitespacesAndNewlines) : ""
        return head.isEmpty ? line : head + "\n" + line
    }

    /// The refined answer with the corrections applied again, so a refine never undoes them or counts a food twice.
    ///
    /// Each kept item takes the place of one of the model's items, looked for in three rounds over all kept items
    /// (an earlier round wins): the same name as one of its names (`FoodMatch.fold`), then the same generic-table
    /// key, then the same food under another name (`sameFood`: "Pierogi ruskie" ↔ "Pierogi ruskie z cebulką"). A kept
    /// item with no match is appended. The model's items left over are dropped when they are a part of a kept item
    /// (`isPart`: "Ziemniaki z masłem" split into Ziemniaki + Masło) or when the user removed them: the same name,
    /// the same food under another name, or a removed item's generic-table key ("Olej" back as "Olej rzepakowy").
    static func merge(refined: [AIFood], kept: [Kept], removed: [String], removedKeys: [String] = []) -> [AIFood] {
        var result = refined
        var taken = Set<Int>()
        var placed = Set<Int>()   // indices into `kept`
        func place(_ matches: (Kept, AIFood) -> Bool) {
            for (k, item) in kept.enumerated() where !placed.contains(k) {
                guard let index = result.indices.first(where: { !taken.contains($0) && matches(item, result[$0]) }) else {
                    continue
                }
                result[index] = item.food
                taken.insert(index)
                placed.insert(k)
            }
        }
        place { item, food in
            let name = FoodMatch.fold(food.name)
            return item.names.contains { FoodMatch.fold($0) == name }
        }
        place { item, food in genericKey(item.food).map { $0 == genericKey(food) } ?? false }
        place { item, food in item.names.contains { sameFood($0, food.name) } }

        let removedKeySet = Set(removedKeys.compactMap(validKey))
        let keptNames = kept.flatMap(\.names)
        let survivors = result.indices.filter { index in
            if taken.contains(index) { return true }
            let food = result[index]
            if keptNames.contains(where: { isPart(food.name, of: $0) }) { return false }
            if removed.contains(where: { FoodMatch.fold($0) == FoodMatch.fold(food.name) || sameFood($0, food.name) }) {
                return false
            }
            if let key = genericKey(food), removedKeySet.contains(key) { return false }
            return true
        }
        let appended = kept.indices.filter { !placed.contains($0) }.map { kept[$0].food }
        return survivors.map { result[$0] } + appended
    }

    // MARK: Name matching

    /// Two names of one food: the same head word (the first word, which carries the food in Polish names), and every
    /// word of one found in the other. "Olej" ↔ "Olej rzepakowy" match; "Olej" ↔ "Sałatka z olejem" do not.
    static func sameFood(_ a: String, _ b: String) -> Bool {
        let x = words(a), y = words(b)
        guard let hx = x.first, let hy = y.first, sameWord(hx, hy) else { return false }
        return covers(x, y) || covers(y, x)
    }

    /// Every word of `part` is in `whole`: the model's "Masło" is a part of the user's "Ziemniaki z masłem".
    static func isPart(_ part: String, of whole: String) -> Bool {
        let x = words(part), y = words(whole)
        return !x.isEmpty && covers(y, x)
    }

    /// Folded words without the linking ones ("z", "w", "i", "with" …), in order.
    static func words(_ name: String) -> [String] {
        FoodMatch.fold(name)
            .split { !$0.isLetter && !$0.isNumber }
            .map(String.init)
            .filter { $0.count > 1 && !linkingWords.contains($0) }
    }

    /// Equal, or one Polish stem with different endings ("masło" / "masłem", "ziemniaki" / "ziemniakami"): both at
    /// least 4 letters and sharing all but at most the last 2 letters of the shorter, and never fewer than 4.
    static func sameWord(_ a: String, _ b: String) -> Bool {
        if a == b { return true }
        guard a.count >= 4, b.count >= 4 else { return false }
        let common = zip(a, b).prefix { $0 == $1 }.count
        return common >= max(4, min(a.count, b.count) - 2)
    }

    private static let linkingWords: Set<String> = [
        "z", "ze", "w", "we", "i", "na", "do", "od", "po", "bez", "o", "a", "oraz",
        "with", "and", "in", "of", "the", "on", "or",
    ]

    /// Every word of `part` has the same word in `whole`.
    private static func covers(_ whole: [String], _ part: [String]) -> Bool {
        !part.isEmpty && part.allSatisfy { word in whole.contains { sameWord($0, word) } }
    }

    /// The item's generic-table key, nil for "none" or none.
    static func genericKey(_ food: AIFood) -> String? {
        food.genericKey.flatMap(validKey)
    }

    private static func validKey(_ key: String) -> String? {
        let trimmed = key.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty || trimmed == "none" ? nil : trimmed
    }

    /// The count after a −1 / +1 step: whole units, with half a unit as the floor ("ate half").
    static func steppedCount(_ count: Double, up: Bool) -> Double {
        if up { return count < 1 ? 1 : min(maxCount, AIFinalizer.round1(count + 1)) }
        return count > 1 ? max(0.5, AIFinalizer.round1(count - 1)) : 0.5
    }

    /// "280", "280.5": the machine form of a number, dot decimal, at most one decimal.
    static func number(_ value: Double) -> String {
        let r = AIFinalizer.round1(value)
        return r == r.rounded() && abs(r) < 1e15 ? String(Int64(r)) : String(r)
    }

    static func truncated(_ text: String, utf16 limit: Int) -> String {
        guard text.utf16.count > limit else { return text }
        var out = ""
        var used = 0
        for character in text {
            let size = character.utf16.count
            if used + size > limit { break }
            out.append(character)
            used += size
        }
        return out
    }
}

extension AIFood {
    /// A food-database product standing in for (or added to) the estimate: its per-100 g values at `grams`, the
    /// item's count kept when it had one, full confidence, logged as an ordinary food entry.
    func replaced(by food: PortionFood) -> AIFood {
        var copy = AIFood.fromDatabase(food, grams: grams)
        copy.id = id
        if let count = portionCount, count > 0 {
            copy.portionCount = count
            copy.portionUnit = portionUnit
            copy.gramsPerUnit = AIFinalizer.round1(copy.grams / count)
        }
        return copy
    }

    static func fromDatabase(_ food: PortionFood, grams: Double) -> AIFood {
        var item = AIFood(name: food.name, grams: AIFinalizer.round1(grams), kcal: 0, protein: 0, carbs: 0, fat: 0,
                          confidence: 1)
        item.portionCount = 1
        item.portionUnit = ""
        item.gramsPerUnit = item.grams
        item.nutritionSource = "database"
        item.genericKey = "none"
        item.adjustments = []
        switch food {
        case .item(let saved): item.databaseFood = .item(id: saved.id)
        case .candidate(let candidate): item.databaseFood = .candidate(candidate)
        }
        item.setNutrition(AIPer100(kcal: food.kcalPer100, protein: food.proteinPer100, carbs: food.carbsPer100,
                                   fat: food.fatPer100))
        return item
    }
}
