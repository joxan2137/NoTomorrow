import Foundation

/// The shared AI spec, `backend/data/ai/estimate-spec.json` (bundled as `estimate-spec.json`): prompts, response
/// schemas, Atwater constants, limits, the notes-context patterns, provider settings and the generic Polish food table.
/// The backend (`aiSpec.ts`) and Android load the same file and assemble every string the same way;
/// `fixtures/prompt-*.json` pins the results for all three (see `AIEstimateFinalizerTests`).
struct AIEstimateSpec {
    struct GenericFood {
        let key: String
        let en: String
        let pl: String
        let per100: AIPer100
        let unitPl: String
        let unitEn: String
        let typical: Int
        let min: Int
        let max: Int
    }

    struct Tolerance {
        let rel: Double
        let abs: Double
        /// Estimated and table values are corrected to Atwater; label and user values are only flagged.
        let repair: Bool
    }

    struct Atwater {
        let protein: Double
        let carbs: Double
        let fat: Double
        let alcohol: Double
        let fiber: Double
        let kjPerKcal: Double
        /// Keyed by nutrition source (`estimated`, `generic_table`, `visible_label`, `user_notes`).
        let tolerance: [String: Tolerance]
    }

    struct Limits {
        let maxItems: Int
        let maxGrams: Double
        let maxKcalPer100: Double
        let maxMassPer100: Double
        let portionMismatchGrams: Double
        let defaultConfidence: Double
        let energyRepairPenalty: Double
        let confidenceCapWithoutReference: Double
        let maxNameLength: Int
        let maxUnitLength: Int
        let maxBrandLength: Int
        let maxScaleReferenceLength: Int
        let maxAssumptions: Int
        let maxQuestions: Int
        let maxTextLength: Int
        let maxNotesLength: Int
    }

    struct Label {
        let promptLines: [String]
        let languageLine: String
        let requestText: String
        let schema: JSONValue
        let reviewRel: Double
        let reviewAbs: Double
        let reviewBelowConfidence: Double
        let maxServingSizeG: Double
        let maxPackageSizeG: Double
    }

    struct Gemini {
        let model: String
        let thinkingLevel: String
        /// Interactions API per-image resolution.
        let imageResolution: String
        /// generateContent `generationConfig.mediaResolution`.
        let generateContentMediaResolution: String
    }

    struct Claude {
        let model: String
        let anthropicVersion: String
        let effort: String
        /// Thinking tokens count toward `max_tokens`, hence 8192 / 4096 rather than 1024.
        let estimateMaxTokens: Int
        let labelMaxTokens: Int
    }

    /// What the notes tell the finalizer: a stated weight or a measured length lifts the confidence cap.
    struct NotesContext: Equatable {
        var weightGiven: Bool
        var measuredReference: Bool
    }

    struct LoadError: Error, CustomStringConvertible {
        let description: String
    }

    let languages: [String: String]
    let estimatePromptLines: [String]
    let estimateLanguageLine: String
    let genericFoodFormat: String
    let genericFoodSeparator: String
    let requestTemplate: String
    /// Canonical schema; `genericKey.enum` is empty in the file and filled by `estimateSchema()`.
    let estimateSchemaTemplate: JSONValue
    let label: Label
    let atwater: Atwater
    let limits: Limits
    let weightPattern: NSRegularExpression
    let referencePattern: NSRegularExpression
    let gemini: Gemini
    let claude: Claude
    /// In file order: the order matters (schema enum, prompt table).
    let genericFoods: [GenericFood]
    let table: [String: GenericFood]

    // MARK: Loading

    /// The bundled spec. A missing or invalid file is a build mistake (the unit tests load it), not a runtime state.
    static let shared: AIEstimateSpec = {
        guard let url = Bundle.main.url(forResource: "estimate-spec", withExtension: "json") else {
            preconditionFailure("estimate-spec.json is not bundled (project.yml → backend/data/ai/estimate-spec.json)")
        }
        do {
            return try load(from: url)
        } catch {
            preconditionFailure("estimate-spec.json is invalid: \(error)")
        }
    }()

    static func load(from url: URL) throws -> AIEstimateSpec {
        try AIEstimateSpec(json: JSONValue.parse(Data(contentsOf: url)))
    }

    init(json: JSONValue) throws {
        let root = try Reader(json, path: "spec")
        guard try root.number("version") == 2 else { throw LoadError(description: "unsupported spec version") }

        let langs = try root.object("languages")
        var languages: [String: String] = [:]
        for key in langs.members.keys { languages[key] = try langs.string(key) }
        self.languages = languages

        let estimate = try root.object("estimate")
        estimatePromptLines = try estimate.strings("promptLines")
        estimateLanguageLine = try estimate.string("languageLine")
        genericFoodFormat = try estimate.string("genericFoodFormat")
        genericFoodSeparator = try estimate.string("genericFoodSeparator")
        requestTemplate = try estimate.string("requestTemplate")
        estimateSchemaTemplate = try estimate.value("schema")
        guard estimateSchemaTemplate["properties"]?["foods"]?["items"]?["properties"]?["genericKey"]?.objectValue != nil else {
            throw LoadError(description: "estimate schema has no foods.items.properties.genericKey")
        }

        let labelNode = try root.object("label")
        let review = try labelNode.object("reviewTolerance")
        label = Label(promptLines: try labelNode.strings("promptLines"),
                      languageLine: try labelNode.string("languageLine"),
                      requestText: try labelNode.string("requestText"),
                      schema: try labelNode.value("schema"),
                      reviewRel: try review.number("rel"),
                      reviewAbs: try review.number("abs"),
                      reviewBelowConfidence: try labelNode.number("reviewBelowConfidence"),
                      maxServingSizeG: try labelNode.number("maxServingSizeG"),
                      maxPackageSizeG: try labelNode.number("maxPackageSizeG"))

        let a = try root.object("atwater")
        let toleranceNode = try a.object("tolerance")
        var tolerance: [String: Tolerance] = [:]
        for key in toleranceNode.members.keys {
            let t = try toleranceNode.object(key)
            tolerance[key] = Tolerance(rel: try t.number("rel"), abs: try t.number("abs"), repair: try t.bool("repair"))
        }
        for source in ["estimated", "generic_table", "visible_label", "user_notes"] where tolerance[source] == nil {
            throw LoadError(description: "atwater.tolerance.\(source) is missing")
        }
        atwater = Atwater(protein: try a.number("protein"), carbs: try a.number("carbs"), fat: try a.number("fat"),
                          alcohol: try a.number("alcohol"), fiber: try a.number("fiber"),
                          kjPerKcal: try a.number("kjPerKcal"), tolerance: tolerance)

        let l = try root.object("limits")
        limits = Limits(maxItems: try l.int("maxItems"), maxGrams: try l.number("maxGrams"),
                        maxKcalPer100: try l.number("maxKcalPer100"), maxMassPer100: try l.number("maxMassPer100"),
                        portionMismatchGrams: try l.number("portionMismatchGrams"),
                        defaultConfidence: try l.number("defaultConfidence"),
                        energyRepairPenalty: try l.number("energyRepairPenalty"),
                        confidenceCapWithoutReference: try l.number("confidenceCapWithoutReference"),
                        maxNameLength: try l.int("maxNameLength"), maxUnitLength: try l.int("maxUnitLength"),
                        maxBrandLength: try l.int("maxBrandLength"),
                        maxScaleReferenceLength: try l.int("maxScaleReferenceLength"),
                        maxAssumptions: try l.int("maxAssumptions"), maxQuestions: try l.int("maxQuestions"),
                        maxTextLength: try l.int("maxTextLength"), maxNotesLength: try l.int("maxNotesLength"))

        let context = try root.object("context")
        do {
            weightPattern = try NSRegularExpression(pattern: try context.string("weightPattern"))
            referencePattern = try NSRegularExpression(pattern: try context.string("referencePattern"))
        } catch let error as LoadError {
            throw error
        } catch {
            throw LoadError(description: "context pattern does not compile: \(error)")
        }

        let providers = try root.object("providers")
        let g = try providers.object("gemini")
        gemini = Gemini(model: try g.string("model"), thinkingLevel: try g.string("thinkingLevel"),
                        imageResolution: try g.string("imageResolution"),
                        generateContentMediaResolution: try g.string("generateContentMediaResolution"))
        let c = try providers.object("claude")
        claude = Claude(model: try c.string("model"), anthropicVersion: try c.string("anthropicVersion"),
                        effort: try c.string("effort"), estimateMaxTokens: try c.int("estimateMaxTokens"),
                        labelMaxTokens: try c.int("labelMaxTokens"))

        var foods: [GenericFood] = []
        var table: [String: GenericFood] = [:]
        for (index, node) in try root.array("genericFoods").enumerated() {
            let f = try Reader(node, path: "genericFoods[\(index)]")
            let key = try f.string("key")
            guard key.wholeMatch(of: #/[a-z0-9_]+/#) != nil, key != "none" else {
                throw LoadError(description: "bad generic food key \(key)")
            }
            guard table[key] == nil else { throw LoadError(description: "duplicate generic food key \(key)") }
            let p = try f.object("per100")
            let unit = try f.object("unit")
            let typical = try unit.int("typical"), min = try unit.int("min"), max = try unit.int("max")
            guard min <= typical, typical <= max else { throw LoadError(description: "bad unit for \(key)") }
            let food = GenericFood(
                key: key, en: try f.string("en"), pl: try f.string("pl"),
                per100: AIPer100(kcal: try p.number("kcal"), protein: try p.number("protein"), carbs: try p.number("carbs"),
                                 fat: try p.number("fat"), alcohol: try p.number("alcohol")),
                unitPl: try unit.string("pl"), unitEn: try unit.string("en"), typical: typical, min: min, max: max)
            foods.append(food)
            table[key] = food
        }
        genericFoods = foods
        self.table = table
    }

    // MARK: Language

    /// `pl`, `pl-PL`, `pl_PL` → "pl"; everything else → "en".
    static func languageCode(_ locale: String) -> String {
        let lang = locale.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? ""
        return lang == "pl" ? "pl" : "en"
    }

    func languageName(_ locale: String) -> String {
        languages[Self.languageCode(locale)] ?? languages["en"] ?? "English"
    }

    // MARK: Prompts

    /// Literal replace-all, the same operation as the backend's `split(token).join(value)`.
    static func substitute(_ template: String, _ token: String, _ value: String) -> String {
        template.replacingOccurrences(of: token, with: value, options: .literal)
    }

    /// The `{genericFoods}` text: one formatted entry per table row, in file order.
    var genericFoodsText: String {
        genericFoods.map { food in
            var entry = genericFoodFormat
            entry = Self.substitute(entry, "{key}", food.key)
            entry = Self.substitute(entry, "{pl}", food.pl)
            entry = Self.substitute(entry, "{unit}", food.unitPl)
            entry = Self.substitute(entry, "{typical}", String(food.typical))
            entry = Self.substitute(entry, "{min}", String(food.min))
            return Self.substitute(entry, "{max}", String(food.max))
        }
        .joined(separator: genericFoodSeparator)
    }

    /// Static per language, so Gemini's implicit prompt caching applies.
    func estimateSystemInstruction(locale: String) -> String {
        let foods = genericFoodsText
        var lines = estimatePromptLines.map { Self.substitute($0, "{genericFoods}", foods) }
        lines.append(Self.substitute(estimateLanguageLine, "{language}", languageName(locale)))
        return lines.joined(separator: "\n")
    }

    /// The per-request text: meal slot (`breakfast|lunch|snack|dinner`) and the notes, JSON-quoted and capped at
    /// `maxNotesLength` UTF-16 units like the backend.
    func estimateRequestText(meal: String, notes: String) -> String {
        let quoted = JSONValue.stringLiteral(Self.prefixUTF16(notes, limits.maxNotesLength))
        return Self.substitute(Self.substitute(requestTemplate, "{meal}", meal), "{notes}", quoted)
    }

    func labelSystemInstruction(locale: String) -> String {
        (label.promptLines + [Self.substitute(label.languageLine, "{language}", languageName(locale))])
            .joined(separator: "\n")
    }

    var labelRequestText: String { label.requestText }

    // MARK: Schemas

    /// Table keys in file order, then "none".
    var genericKeys: [String] { genericFoods.map(\.key) + ["none"] }

    /// Canonical (Claude) estimate schema with `genericKey.enum` filled in.
    func estimateSchema() -> JSONValue {
        estimateSchemaTemplate.setting(["properties", "foods", "items", "properties", "genericKey", "enum"],
                                       to: .array(genericKeys.map { .string($0) }))
    }

    func labelSchema() -> JSONValue { label.schema }

    /// Gemini gets the same schema with every `additionalProperties` removed, at any depth.
    static func forGemini(_ schema: JSONValue) -> JSONValue {
        switch schema {
        case .array(let items):
            return .array(items.map(forGemini))
        case .object(let object):
            var out = JSONObject()
            for (key, value) in object.entries where key != "additionalProperties" { out[key] = forGemini(value) }
            return .object(out)
        default:
            return schema
        }
    }

    // MARK: Notes context

    /// Both patterns run case-sensitively on the lower-cased notes. Pass the full notes string that is sent
    /// (typed details plus any "User corrections" line).
    func notesContext(_ notes: String) -> NotesContext {
        let text = notes.lowercased()
        let range = NSRange(text.startIndex..., in: text)
        return NotesContext(weightGiven: weightPattern.firstMatch(in: text, range: range) != nil,
                            measuredReference: referencePattern.firstMatch(in: text, range: range) != nil)
    }

    /// The first `limit` UTF-16 units, as JavaScript `slice` counts (a split surrogate pair becomes U+FFFD).
    static func prefixUTF16(_ text: String, _ limit: Int) -> String {
        let units = text.utf16
        guard units.count > limit else { return text }
        return String(decoding: Array(units.prefix(limit)), as: UTF16.self)
    }
}

// MARK: - Reading the spec

private struct Reader {
    let members: JSONObject
    let path: String

    init(_ value: JSONValue, path: String) throws {
        guard let object = value.objectValue else { throw AIEstimateSpec.LoadError(description: "\(path) is not an object") }
        self.members = object
        self.path = path
    }

    func value(_ key: String) throws -> JSONValue {
        guard let value = members[key] else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is missing") }
        return value
    }

    func object(_ key: String) throws -> Reader { try Reader(value(key), path: "\(path).\(key)") }

    func string(_ key: String) throws -> String {
        guard let s = try value(key).stringValue else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is not a string") }
        return s
    }

    func number(_ key: String) throws -> Double {
        guard let n = try value(key).numberValue else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is not a number") }
        return n
    }

    func int(_ key: String) throws -> Int {
        let n = try number(key)
        guard n == n.rounded(), abs(n) < 1e9 else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is not an integer") }
        return Int(n)
    }

    func bool(_ key: String) throws -> Bool {
        guard let b = try value(key).boolValue else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is not a boolean") }
        return b
    }

    func array(_ key: String) throws -> [JSONValue] {
        guard let a = try value(key).arrayValue else { throw AIEstimateSpec.LoadError(description: "\(path).\(key) is not an array") }
        return a
    }

    func strings(_ key: String) throws -> [String] {
        try array(key).enumerated().map { index, item in
            guard let s = item.stringValue else {
                throw AIEstimateSpec.LoadError(description: "\(path).\(key)[\(index)] is not a string")
            }
            return s
        }
    }
}

extension JSONValue {
    /// A copy with the value at `path` (object keys) replaced; intermediate objects must exist.
    func setting(_ path: [String], to newValue: JSONValue) -> JSONValue {
        guard let key = path.first else { return newValue }
        guard var object = objectValue, let child = object[key] else { return self }
        object[key] = child.setting(Array(path.dropFirst()), to: newValue)
        return .object(object)
    }
}
