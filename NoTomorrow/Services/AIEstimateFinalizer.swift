import Foundation

/// The model's answer cannot be used at all (as opposed to single items, which are skipped).
enum AIOutputError: Error, Equatable {
    case noJSON
    case invalidJSON
    case notObject
    case noFoodsArray

    /// The contract's code, as in the fixtures' `expectedError`.
    var code: String {
        switch self {
        case .noJSON: "no_json"
        case .invalidJSON: "invalid_json"
        case .notObject: "not_object"
        case .noFoodsArray: "no_foods_array"
        }
    }
}

/// Port of `backend/src/aiFinalize.ts`: raw model JSON → the finalized estimate (schema v2) or label reading.
/// Every step and rounding rule is part of the cross-platform contract and is checked against the shared fixtures
/// (`AIEstimateFinalizerTests`); keep expressions in the written order, floating-point results must match bit for bit.
enum AIFinalizer {
    // MARK: Numbers

    /// Half-up to one decimal for non-negative values: floor(x × 10 + 0.5) / 10. Never `rounded()`.
    static func round1(_ x: Double) -> Double { (x * 10 + 0.5).rounded(.down) / 10 }

    static func round2(_ x: Double) -> Double { (x * 100 + 0.5).rounded(.down) / 100 }

    static func clamp01(_ x: Double) -> Double { min(1, max(0, x)) }

    /// A finite JSON number, or a string like "12", "-3", "4,5", "4.5" (trimmed); anything else is nil.
    static func num(_ value: JSONValue?) -> Double? {
        switch value {
        case .number(let n)?:
            return n.isFinite ? n : nil
        case .string(let s)?:
            let trimmed = trim(s)
            guard trimmed.wholeMatch(of: #/[-+]?[0-9]+(?:[.,][0-9]+)?/#) != nil else { return nil }
            let normalized = trimmed.firstIndex(of: ",").map { trimmed.replacingCharacters(in: $0...$0, with: ".") } ?? trimmed
            guard let parsed = Double(normalized), parsed.isFinite else { return nil }
            return parsed
        default:
            return nil
        }
    }

    /// The first key whose value parses as a number.
    static func firstNum(_ object: JSONObject, _ keys: [String]) -> Double? {
        for key in keys {
            if let value = num(object[key]) { return value }
        }
        return nil
    }

    /// Trimmed string cut to `max` characters; non-strings become "".
    static func text(_ value: JSONValue?, _ max: Int) -> String {
        guard let s = value?.stringValue else { return "" }
        return String(trim(s).prefix(max))
    }

    static func isTrue(_ value: JSONValue?) -> Bool {
        switch value {
        case .bool(true)?: true
        case .string(let s)?: trim(s).lowercased() == "true"
        default: false
        }
    }

    static func shortStrings(_ value: JSONValue?, maxCount: Int, maxLength: Int) -> [String] {
        guard let items = value?.arrayValue else { return [] }
        var out: [String] = []
        for item in items {
            if out.count >= maxCount { break }
            let s = text(item, maxLength)
            if !s.isEmpty { out.append(s) }
        }
        return out
    }

    /// GTIN-8/12/13/14 with a valid mod-10 check digit.
    static func validGTIN(_ code: String) -> Bool {
        guard [8, 12, 13, 14].contains(code.count), code.allSatisfy(\.isASCIIDigit) else { return false }
        var digits = code.compactMap { $0.wholeNumberValue }
        let check = digits.removeLast()
        var sum = 0
        for i in 0..<digits.count {
            // Weight 3 on the digit next to the check digit, then alternating 1, 3, …
            sum += digits[digits.count - 1 - i] * (i % 2 == 0 ? 3 : 1)
        }
        return (10 - sum % 10) % 10 == check
    }

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    // MARK: Model text → JSON

    private static let fence = try! NSRegularExpression(pattern: "```(?:json|JSON)?")
    private static let trailingComma = try! NSRegularExpression(pattern: #",[ \t\r\n]*([}\]])"#)

    /// Removes every ``` / ```json / ```JSON fence, then returns the text from the first `{` to the last `}`.
    static func extractJSONObject(_ input: String) -> String? {
        let unfenced = fence.stringByReplacingMatches(in: input, range: NSRange(input.startIndex..., in: input),
                                                      withTemplate: "")
        let scalars = unfenced.unicodeScalars
        guard let start = scalars.firstIndex(of: "{"), let end = scalars.lastIndex(of: "}"), start < end else { return nil }
        return String(scalars[start...end])
    }

    /// Model text → JSON value. Tries strict JSON; then with trailing commas removed; then also with every `'`
    /// replaced by `"`.
    static func parseModelJSON(_ input: String) throws -> JSONValue {
        guard let json = extractJSONObject(input) else { throw AIOutputError.noJSON }
        let noTrailingCommas = trailingComma.stringByReplacingMatches(in: json, range: NSRange(json.startIndex..., in: json),
                                                                      withTemplate: "$1")
        for attempt in [json, noTrailingCommas, noTrailingCommas.replacingOccurrences(of: "'", with: "\"")] {
            if let value = try? JSONValue.parse(attempt) { return value }
        }
        throw AIOutputError.invalidJSON
    }

    // MARK: Estimate

    static let cookingValues: Set<String> = ["raw", "boiled", "steamed", "stewed", "baked", "grilled", "pan_fried",
                                             "deep_fried", "prepared", "packaged"]
    private static let modelSources: Set<String> = ["visible_label", "user_notes", "estimated"]

    /// Raw model JSON (schema v2, or a legacy v1 reply with totals only) → the finalized estimate. Throws only when the
    /// answer is not an object or has no foods array; bad items are skipped and listed in `skipped`.
    static func finalizeEstimate(_ raw: JSONValue, spec: AIEstimateSpec,
                                 context: AIEstimateSpec.NotesContext) throws -> AIEstimate {
        guard let obj = raw.objectValue else { throw AIOutputError.notObject }
        guard let list = obj["foods"]?.arrayValue ?? obj["items"]?.arrayValue else { throw AIOutputError.noFoodsArray }

        let L = spec.limits
        let capActive = !context.weightGiven && !context.measuredReference
        var foods: [AIFood] = []
        var skipped: [AISkippedItem] = []

        for (index, item) in list.enumerated() {
            if index >= L.maxItems {
                skipped.append(AISkippedItem(index: index, name: text(item["name"], L.maxNameLength), reason: "too_many_items"))
                continue
            }
            guard let f = item.objectValue else {
                skipped.append(AISkippedItem(index: index, name: "", reason: "not_object"))
                continue
            }
            var adjustments: [String] = []

            // 1. Name.
            let name = text(f["name"], L.maxNameLength)
            guard !name.isEmpty else {
                skipped.append(AISkippedItem(index: index, name: "", reason: "no_name"))
                continue
            }
            func skip(_ reason: String) { skipped.append(AISkippedItem(index: index, name: name, reason: reason)) }

            // 2. Grams, repaired from count × grams per unit when missing or out of range.
            var count: Double? = num(f["portionCount"]).flatMap { $0 > 0 ? round1($0) : nil }
            if let c = count, c <= 0 { count = nil }
            let perUnit: Double? = num(f["gramsPerUnit"]).flatMap { $0 > 0 ? $0 : nil }
            func gramsOK(_ g: Double?) -> Bool { g.map { $0 > 0 && $0 <= L.maxGrams } ?? false }
            var gramsValue = firstNum(f, ["grams", "estimatedGrams", "estimated_grams"])
            if !gramsOK(gramsValue), let count, let perUnit {
                gramsValue = count * perUnit
                adjustments.append("grams_from_portion")
            }
            guard gramsOK(gramsValue), let rawGrams = gramsValue else { skip("invalid_grams"); continue }
            let grams = round1(rawGrams)
            guard grams > 0 else { skip("invalid_grams"); continue }

            // 3. Per-100 g nutrition: generic table, the model's per100, or legacy totals.
            var source = f["nutritionSource"]?.stringValue.flatMap { modelSources.contains($0) ? $0 : nil } ?? "estimated"
            let keyRaw = f["genericKey"]?.stringValue.map(trim) ?? ""
            let tableRow = spec.table[keyRaw]
            let genericKey = tableRow != nil ? keyRaw : "none"
            var per100: AIPer100
            if let tableRow, source == "estimated" {
                per100 = tableRow.per100
                source = "generic_table"
                adjustments.append("generic_table")
            } else if let d = f["per100"]?.objectValue {
                guard let kcal = num(d["kcal"]), let protein = num(d["protein"]), let carbs = num(d["carbs"]),
                      let fat = num(d["fat"]) else { skip("invalid_per100"); continue }
                per100 = AIPer100(kcal: kcal, protein: protein, carbs: carbs, fat: fat, alcohol: num(d["alcohol"]) ?? 0)
            } else {
                guard let kcal = firstNum(f, ["kcal", "calories"]) else { skip("no_nutrition"); continue }
                let protein = firstNum(f, ["proteinG", "protein_g", "protein"]) ?? 0
                let carbs = firstNum(f, ["carbsG", "carbs_g", "carbs", "carbohydrates"]) ?? 0
                let fat = firstNum(f, ["fatG", "fat_g", "fat"]) ?? 0
                per100 = AIPer100(kcal: kcal * 100 / grams, protein: protein * 100 / grams, carbs: carbs * 100 / grams,
                                  fat: fat * 100 / grams, alcohol: 0)
                adjustments.append("per100_from_totals")
            }

            // 4. Plausibility.
            let values = [per100.kcal, per100.protein, per100.carbs, per100.fat, per100.alcohol]
            if values.contains(where: { $0 < 0 }) || per100.kcal > L.maxKcalPer100
                || per100.protein + per100.carbs + per100.fat + per100.alcohol > L.maxMassPer100 {
                skip("invalid_per100")
                continue
            }

            // 5. Energy consistency (Atwater 4/4/9/7).
            let energy = atwaterEnergy(spec, per100)
            let tolerance = spec.atwater.tolerance[source]!
            let allowed = max(tolerance.abs, tolerance.rel * energy)
            var repaired = false
            if abs(per100.kcal - energy) > allowed {
                if tolerance.repair {
                    per100.kcal = min(energy, L.maxKcalPer100)
                    repaired = true
                    adjustments.append("energy_repaired")
                } else {
                    adjustments.append("energy_mismatch_kept")
                }
            }

            // 6. Portion fields, consistent with grams.
            let portionCount: Double
            let gramsPerUnit: Double
            if let count {
                portionCount = count
                if let perUnit, abs(count * perUnit - grams) <= L.portionMismatchGrams {
                    gramsPerUnit = round1(perUnit)
                } else {
                    gramsPerUnit = round1(grams / count)
                    adjustments.append("portion_normalized")
                }
            } else {
                portionCount = 1
                gramsPerUnit = grams
            }

            // 7. Confidence.
            var confidence = num(f["confidence"]) ?? L.defaultConfidence
            if repaired { confidence -= L.energyRepairPenalty }
            confidence = clamp01(confidence)
            if capActive && confidence > L.confidenceCapWithoutReference {
                confidence = L.confidenceCapWithoutReference
                adjustments.append("confidence_capped")
            }

            let cooking = f["cooking"]?.stringValue.flatMap { cookingValues.contains($0) ? $0 : nil } ?? "prepared"
            let barcodeRaw = f["barcode"]?.stringValue.map(trim) ?? ""
            var food = AIFood(name: name, grams: grams, kcal: 0, protein: 0, carbs: 0, fat: 0,
                              confidence: round2(confidence), isGuess: isTrue(f["isGuess"]) || isTrue(f["is_guess"]))
            food.barcode = barcodeRaw.wholeMatch(of: #/[0-9]{8,14}/#) != nil ? barcodeRaw : ""
            food.nutritionSource = source
            food.cooking = cooking
            food.genericKey = genericKey
            food.portionCount = portionCount
            food.portionUnit = text(f["portionUnit"], L.maxUnitLength)
            food.gramsPerUnit = gramsPerUnit
            food.adjustments = adjustments
            food.setNutrition(per100)
            foods.append(food)
        }

        // Whole estimate.
        let mean = foods.isEmpty ? 0 : foods.reduce(0) { $0 + $1.confidence } / Double(foods.count)
        var overall = clamp01(firstNum(obj, ["overallConfidence", "overall_confidence"]) ?? mean)
        if capActive && overall > L.confidenceCapWithoutReference { overall = L.confidenceCapWithoutReference }
        let scale = text(obj["scaleReferenceUsed"], L.maxScaleReferenceLength)

        return AIEstimate(foods: foods,
                          overallConfidence: round2(overall),
                          assumptions: shortStrings(obj["assumptions"], maxCount: L.maxAssumptions, maxLength: L.maxTextLength),
                          questions: shortStrings(obj["questions"], maxCount: L.maxQuestions, maxLength: L.maxTextLength),
                          scaleReferenceUsed: scale.isEmpty ? "none" : scale,
                          version: 2,
                          totals: computeTotals(foods),
                          skipped: skipped)
    }

    /// Model text (fences, prose and small JSON slips tolerated) → finalized estimate.
    static func finalizeEstimateText(_ input: String, spec: AIEstimateSpec,
                                     context: AIEstimateSpec.NotesContext) throws -> AIEstimate {
        try finalizeEstimate(parseModelJSON(input), spec: spec, context: context)
    }

    private static func atwaterEnergy(_ spec: AIEstimateSpec, _ p: AIPer100) -> Double {
        let a = spec.atwater
        return a.protein * p.protein + a.carbs * p.carbs + a.fat * p.fat + a.alcohol * p.alcohol
    }

    /// Sums the rounded item totals in order, then rounds.
    static func computeTotals(_ foods: [AIFood]) -> AITotals {
        var kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0
        for food in foods {
            kcal += food.kcal
            protein += food.protein
            carbs += food.carbs
            fat += food.fat
        }
        return AITotals(kcal: round1(kcal), protein: round1(protein), carbs: round1(carbs), fat: round1(fat))
    }

    /// Database grounding after finalize (a readable barcode → Open Food Facts): the product's per-100 g values
    /// replace the model's, like the backend does. Confidence is unchanged.
    static func groundWithDatabase(_ estimate: inout AIEstimate, index: Int, per100: AIPer100) {
        guard estimate.foods.indices.contains(index) else { return }
        estimate.foods[index].setNutrition(per100)
        estimate.foods[index].nutritionSource = "open_food_facts"
        estimate.foods[index].adjustments = (estimate.foods[index].adjustments ?? []) + ["open_food_facts"]
        estimate.totals = computeTotals(estimate.foods)
    }

    // MARK: Nutrition label

    private static let bases: Set<String> = ["per100g", "per100ml", "perServing"]

    /// Raw label JSON → per-100 g values. A readable answer that is not a usable table is `legible: false`, not an error.
    static func finalizeLabel(_ raw: JSONValue, spec: AIEstimateSpec) throws -> LabelReading {
        guard let obj = raw.objectValue else { throw AIOutputError.notObject }
        let L = spec.limits
        let S = spec.label

        let basis = obj["basis"]?.stringValue.flatMap { bases.contains($0) ? $0 : nil } ?? "per100g"
        func size(_ value: JSONValue?, _ maximum: Double) -> Double? {
            guard let n = num(value), n > 0, n <= maximum else { return nil }
            let rounded = round1(n)
            return rounded > 0 ? rounded : nil
        }
        let servingSizeG = size(obj["servingSizeG"], S.maxServingSizeG)
        let barcodeRaw = obj["barcode"]?.stringValue.map(trim) ?? ""
        var reading = LabelReading(legible: false, unreadableReason: "illegible", basis: basis, energyFrom: nil,
                                   name: text(obj["name"], L.maxNameLength), brand: text(obj["brand"], L.maxBrandLength),
                                   per100: nil, servingSizeG: servingSizeG,
                                   packageSizeG: size(obj["packageSizeG"], S.maxPackageSizeG),
                                   barcode: validGTIN(barcodeRaw) ? barcodeRaw : "",
                                   confidence: round2(clamp01(num(obj["confidence"]) ?? L.defaultConfidence)),
                                   needsReview: false)
        func fail(_ reason: String) -> LabelReading {
            var failed = reading
            failed.unreadableReason = reason
            return failed
        }

        guard isTrue(obj["legible"]) else { return fail("illegible") }
        let values = obj["values"]?.objectValue ?? JSONObject()
        /// Printed value, or nil for missing / negative (the -1 "not printed" sentinel).
        func printed(_ key: String) -> Double? { num(values[key]).flatMap { $0 < 0 ? nil : $0 } }

        var kcal: Double
        var energyFrom = "kcal"
        if let printedKcal = printed("kcal") {
            kcal = printedKcal
        } else {
            guard let kj = printed("kj") else { return fail("no_energy") }
            kcal = kj / spec.atwater.kjPerKcal
            energyFrom = "kj"
        }
        guard var protein = printed("protein"), var carbs = printed("carbs"), var fat = printed("fat") else {
            return fail("incomplete")
        }
        var fiber = printed("fiber"), sugar = printed("sugar"), salt = printed("salt")

        if basis == "perServing" {
            guard let servingSizeG else { return fail("no_serving_size") }
            func scale(_ v: Double) -> Double { v * 100 / servingSizeG }
            kcal = scale(kcal)
            protein = scale(protein)
            carbs = scale(carbs)
            fat = scale(fat)
            fiber = fiber.map(scale)
            sugar = sugar.map(scale)
            salt = salt.map(scale)
        }

        if kcal > L.maxKcalPer100 || protein + carbs + fat + (fiber ?? 0) > L.maxMassPer100 { return fail("implausible") }

        // Salt is printed with two decimals (0,63 g), everything else with at most one.
        let per100 = LabelReading.Per100(kcal: round1(kcal), protein: round1(protein), carbs: round1(carbs), fat: round1(fat),
                                         fiber: fiber.map(round1), sugar: sugar.map(round1), salt: salt.map(round2))
        let a = spec.atwater
        let energy = a.protein * per100.protein + a.carbs * per100.carbs + a.fat * per100.fat + a.fiber * (per100.fiber ?? 0)
        let allowed = max(S.reviewAbs, S.reviewRel * per100.kcal)
        let needsReview = abs(per100.kcal - energy) > allowed
            || (per100.sugar.map { $0 > per100.carbs } ?? false)
            || reading.confidence < S.reviewBelowConfidence

        reading.legible = true
        reading.unreadableReason = nil
        reading.energyFrom = energyFrom
        reading.per100 = per100
        reading.needsReview = needsReview
        return reading
    }

    static func finalizeLabelText(_ input: String, spec: AIEstimateSpec) throws -> LabelReading {
        try finalizeLabel(parseModelJSON(input), spec: spec)
    }
}

// MARK: - Canonical JSON (the contract's key names; the fixtures compare against it)

extension AIEstimate {
    var contractJSON: JSONValue {
        var o = JSONObject()
        o["version"] = .number(Double(version ?? 2))
        o["foods"] = .array(foods.map(\.contractJSON))
        let t = totals ?? AIFinalizer.computeTotals(foods)
        let totalsJSON: JSONValue = ["kcal": .number(t.kcal), "protein": .number(t.protein), "carbs": .number(t.carbs),
                                     "fat": .number(t.fat)]
        o["totals"] = totalsJSON
        o["overallConfidence"] = .number(overallConfidence)
        o["scaleReferenceUsed"] = .string(scaleReferenceUsed ?? "none")
        o["assumptions"] = .array((assumptions ?? []).map { .string($0) })
        o["questions"] = .array((questions ?? []).map { .string($0) })
        o["skipped"] = .array((skipped ?? []).map { item -> JSONValue in
            ["index": .number(Double(item.index)), "name": .string(item.name), "reason": .string(item.reason)]
        })
        return .object(o)
    }
}

extension AIFood {
    var contractJSON: JSONValue {
        var o = JSONObject()
        o["name"] = .string(name)
        o["grams"] = .number(grams)
        o["kcal"] = .number(kcal)
        o["protein"] = .number(protein)
        o["carbs"] = .number(carbs)
        o["fat"] = .number(fat)
        o["proteinG"] = .number(protein)
        o["carbsG"] = .number(carbs)
        o["fatG"] = .number(fat)
        o["confidence"] = .number(confidence)
        o["isGuess"] = .bool(isGuess)
        o["barcode"] = .string(barcode ?? "")
        o["nutritionSource"] = .string(nutritionSource ?? "estimated")
        o["cooking"] = .string(cooking ?? "prepared")
        o["genericKey"] = .string(genericKey ?? "none")
        o["portionCount"] = .number(portionCount ?? 1)
        o["portionUnit"] = .string(portionUnit ?? "")
        o["gramsPerUnit"] = .number(gramsPerUnit ?? grams)
        if let per100 {
            let per100JSON: JSONValue = ["kcal": .number(per100.kcal), "protein": .number(per100.protein),
                                         "carbs": .number(per100.carbs), "fat": .number(per100.fat),
                                         "alcohol": .number(per100.alcohol)]
            o["per100"] = per100JSON
        }
        o["adjustments"] = .array((adjustments ?? []).map { .string($0) })
        return .object(o)
    }
}

extension LabelReading {
    var contractJSON: JSONValue {
        func optional(_ value: Double?) -> JSONValue { value.map { .number($0) } ?? .null }
        var o = JSONObject()
        o["version"] = .number(Double(version))
        o["legible"] = .bool(legible)
        o["unreadableReason"] = unreadableReason.map { .string($0) } ?? .null
        o["basis"] = .string(basis)
        o["energyFrom"] = energyFrom.map { .string($0) } ?? .null
        o["name"] = .string(name)
        o["brand"] = .string(brand)
        if let p = per100 {
            let per100JSON: JSONValue = ["kcal": .number(p.kcal), "protein": .number(p.protein), "carbs": .number(p.carbs),
                                         "fat": .number(p.fat), "fiber": optional(p.fiber), "sugar": optional(p.sugar),
                                         "salt": optional(p.salt)]
            o["per100"] = per100JSON
        } else {
            o["per100"] = .null
        }
        o["servingSizeG"] = optional(servingSizeG)
        o["packageSizeG"] = optional(packageSizeG)
        o["barcode"] = .string(barcode)
        o["confidence"] = .number(confidence)
        o["needsReview"] = .bool(needsReview)
        return .object(o)
    }
}

// MARK: - Rescaling (keeps the result screen consistent with the finalizer)

extension AIFood {
    /// Sets `per100` (rounded to 0.1) and recomputes the totals for the current grams, exactly as the finalizer does.
    mutating func setNutrition(_ values: AIPer100) {
        let r = AIFinalizer.round1
        let p = AIPer100(kcal: r(values.kcal), protein: r(values.protein), carbs: r(values.carbs), fat: r(values.fat),
                         alcohol: r(values.alcohol))
        per100 = p
        kcal = r(p.kcal * grams / 100)
        protein = r(p.protein * grams / 100)
        carbs = r(p.carbs * grams / 100)
        fat = r(p.fat * grams / 100)
    }

    /// The same food at a different portion. With `per100` the totals are recomputed from it (grams to 0.1 g);
    /// otherwise (older server) they follow proportionally. A counted portion keeps its count and re-derives the
    /// grams of one unit.
    func scaled(toGrams newGrams: Double) -> AIFood {
        var copy = self
        if let per100 {
            let g = AIFinalizer.round1(newGrams)
            guard g > 0 else { return self }
            copy.grams = g
            copy.setNutrition(per100)
        } else {
            guard grams > 0, newGrams > 0 else { return self }
            let f = newGrams / grams
            copy.grams = newGrams
            copy.kcal = kcal * f
            copy.protein = protein * f
            copy.carbs = carbs * f
            copy.fat = fat * f
        }
        if let count = portionCount, count > 0 { copy.gramsPerUnit = AIFinalizer.round1(copy.grams / count) }
        return copy
    }

    /// `n` units of the same size: `portionCount = n`, grams = n × grams per unit.
    func withCount(_ n: Double) -> AIFood {
        let count = AIFinalizer.round1(n)
        guard count > 0 else { return self }
        let perUnit = unitGrams
        var copy = scaled(toGrams: count * perUnit)
        copy.portionCount = count
        copy.gramsPerUnit = AIFinalizer.round1(perUnit)
        return copy
    }

    /// Units of `gramsPerUnit` grams each, keeping the count.
    func withGramsPerUnit(_ perUnit: Double) -> AIFood {
        guard perUnit > 0 else { return self }
        let count = units
        var copy = scaled(toGrams: count * perUnit)
        copy.portionCount = count
        copy.gramsPerUnit = AIFinalizer.round1(perUnit)
        return copy
    }

    /// Visible units; 1 for a single mass or an older server's item.
    var units: Double { portionCount.flatMap { $0 > 0 ? $0 : nil } ?? 1 }

    /// Grams of one unit; the whole portion when the item has no count.
    var unitGrams: Double {
        if let perUnit = gramsPerUnit, perUnit > 0 { return perUnit }
        return grams / units
    }

    /// The printed unit ("szt.", "kromka"), nil when the model gave none.
    var unitName: String? {
        guard let unit = portionUnit?.trimmingCharacters(in: .whitespacesAndNewlines), !unit.isEmpty else { return nil }
        return unit
    }

    /// kcal per 100 g: `per100` when known, otherwise derived from the totals.
    var kcalPer100: Double? {
        if let per100 { return per100.kcal }
        return grams > 0 ? kcal * 100 / grams : nil
    }
}
