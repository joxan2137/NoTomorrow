import Foundation

/// Sharing a routine with a friend as text, and reading one back ("Share routine" / "Import routine"). Offline: the
/// text itself carries the routine. The Android app writes and reads the same format (`RoutineShare.kt`).
///
///     No Tomorrow routine: Push A
///     1. Barbell Bench Press - Medium Grip — 4 × 8, rest 2:00
///     2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]
///     3. Dumbbell Flyes — 3 × 12 [Superset A]
///     nt1:eyJ2IjoxLCJuYW1lIjoiUHVzaCBBIiwiaXRlbXMiOlt7ImlkIjoi…
///
/// The header and numbered lines are for people (localized; rest is left out when it is the user's default). The
/// `nt1:` line is the exact data: base64url (no padding) of compact UTF-8 JSON
/// `{"v":1,"name":…,"items":[{"id":…,"sets":…,"reps":…,"rest":…,"ss":…}]}` where a library exercise has `id` and a
/// custom one `name` instead, `rest` is seconds (0 = the user's default) and `ss` the superset group (left out when on
/// its own). Reading uses the `nt1:` line; without one (or when it is damaged) it falls back to the numbered lines,
/// matched by exercise name. Everything read goes through the routine editor's limits.
enum RoutineShare {

    static let marker = "nt1:"
    static let formatVersion = 1
    /// A shared routine longer than this is cut; the editor has no limit, but a pasted text should not add hundreds.
    static let maxLines = 50
    static let maxTokenLength = 20_000

    /// One exercise of a shared routine: a library id, or a name (custom exercises, or the numbered-line fallback).
    struct Line: Equatable {
        var exerciseID: String?
        var name: String?
        var sets: Int
        var reps: Int
        var restSeconds: Int
        var supersetGroup: Int?
    }

    struct Shared: Equatable {
        var name: String
        var lines: [Line]
    }

    /// The human-readable parts: "No Tomorrow routine: %@", "rest %@", "Superset %@".
    struct Labels {
        var header: (String) -> String
        var rest: (String) -> String
        var superset: (String) -> String

        static var english: Labels {
            Labels(header: { "No Tomorrow routine: \($0)" }, rest: { "rest \($0)" }, superset: { "Superset \($0)" })
        }

        /// In the app's language.
        static var current: Labels {
            Labels(header: { String(localized: "routine.share.header \($0)") },
                   rest: { String(localized: "routine.share.rest \($0)") },
                   superset: { String(localized: "superset.tag \($0)") })
        }
    }

    // MARK: Writing

    /// The whole share text for `draft`: header, numbered lines, then the `nt1:` line.
    static func text(_ draft: RoutineDraft, labels: Labels = .english) -> String {
        let letters = Superset.letters(draft.items.map(\.supersetGroup))
        var out = [labels.header(oneLine(draft.trimmedName))]
        for (index, item) in draft.items.enumerated() {
            var line = "\(index + 1). \(oneLine(item.name)) — \(item.sets) × \(item.reps)"
            if item.restSeconds > 0 { line += ", " + labels.rest(clock(item.restSeconds)) }
            if let letter = letters[index] { line += " [" + labels.superset(letter) + "]" }
            out.append(line)
        }
        out.append(payload(shared(from: draft)))
        return out.joined(separator: "\n")
    }

    /// What the `nt1:` line carries for `draft`: library ids, custom exercises (`custom-…`) by name.
    static func shared(from draft: RoutineDraft) -> Shared {
        let groups = Superset.normalized(draft.items.map(\.supersetGroup))
        let lines = draft.items.enumerated().map { index, item -> Line in
            let isCustom = item.exerciseID.hasPrefix("custom-")
            return Line(exerciseID: isCustom ? nil : item.exerciseID, name: isCustom ? oneLine(item.name) : nil,
                        sets: item.sets, reps: item.reps, restSeconds: max(0, item.restSeconds),
                        supersetGroup: groups[index])
        }
        return Shared(name: oneLine(draft.trimmedName), lines: lines)
    }

    /// `nt1:` + base64url of the compact JSON. Keys in a fixed order, so both apps write the same bytes.
    static func payload(_ shared: Shared) -> String {
        var json = "{\"v\":\(formatVersion),\"name\":\(quoted(shared.name)),\"items\":["
        for (index, line) in shared.lines.enumerated() {
            if index > 0 { json += "," }
            json += "{"
            if let id = line.exerciseID {
                json += "\"id\":" + quoted(id)
            } else {
                json += "\"name\":" + quoted(line.name ?? "")
            }
            json += ",\"sets\":\(line.sets),\"reps\":\(line.reps),\"rest\":\(line.restSeconds)"
            if let group = line.supersetGroup { json += ",\"ss\":\(group)" }
            json += "}"
        }
        json += "]}"
        return marker + base64URL(Data(json.utf8))
    }

    /// "2:00".
    static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        let rem = s % 60
        return "\(s / 60):" + (rem < 10 ? "0\(rem)" : "\(rem)")
    }

    private static func oneLine(_ text: String) -> String {
        text.split(whereSeparator: { $0.isNewline }).joined(separator: " ").trimmingCharacters(in: .whitespaces)
    }

    /// A JSON string: `"` and `\` escaped, control characters as `\n`, `\r`, `\t` or `\u00XX` (lower-case hex).
    private static func quoted(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Accepts base64url or standard base64, with or without padding.
    static func base64URLDecoded(_ token: String) -> Data? {
        var base = token
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
        let remainder = base.count % 4
        if remainder == 1 { return nil }
        if remainder > 0 { base += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: base)
    }

    // MARK: Reading

    /// The routine in a pasted text: the `nt1:` line when there is a readable one (names of library exercises are
    /// taken from the numbered lines, for a library that lacks the id), else the numbered lines. Nil when neither
    /// gives an exercise.
    static func decode(_ text: String) -> Shared? {
        let listed = parseLines(text)
        guard var shared = decodePayload(in: text) else { return listed }
        if let listed, listed.lines.count == shared.lines.count {
            for index in shared.lines.indices where shared.lines[index].name == nil {
                shared.lines[index].name = listed.lines[index].name
            }
        }
        return shared
    }

    /// Whether `text` has an `nt1:` line that decodes (the Paste button's check).
    static func containsPayload(_ text: String) -> Bool { decodePayload(in: text) != nil }

    /// The last `nt1:` in `text`: the rest of its line, or, when a messenger wrapped it, every base64 character
    /// after it up to the first other one.
    static func decodePayload(in text: String) -> Shared? {
        guard let range = text.range(of: marker, options: .backwards) else { return nil }
        let after = text[range.upperBound...]
        let lineEnd = after.firstIndex(where: { $0.isNewline }) ?? after.endIndex
        let line = after[..<lineEnd].trimmingCharacters(in: .whitespaces)
        if let shared = decodeToken(line) { return shared }
        var wrapped = ""
        for character in after {
            if character.isWhitespace || character.isNewline { continue }
            guard isBase64Character(character) else { break }
            wrapped.append(character)
        }
        return wrapped == line ? nil : decodeToken(wrapped)
    }

    private static func isBase64Character(_ character: Character) -> Bool {
        character.isASCII && (character.isLetter || character.isNumber || "-_+/=".contains(character))
    }

    private struct Payload: Decodable {
        struct Item: Decodable {
            var id: String?
            var name: String?
            var sets: Int?
            var reps: Int?
            var rest: Int?
            var ss: Int?
        }

        var v: Int
        var name: String?
        var items: [Item]
    }

    static func decodeToken(_ token: String) -> Shared? {
        guard !token.isEmpty, token.count <= maxTokenLength,
              let data = base64URLDecoded(token),
              let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.v == formatVersion else { return nil }
        let lines = payload.items.compactMap { item -> Line? in
            let id = nonEmpty(item.id?.trimmingCharacters(in: .whitespaces))
            let name = nonEmpty(item.name.map(oneLine))
            guard id != nil || name != nil else { return nil }
            let group: Int? = (item.ss ?? 0) > 0 ? item.ss : nil
            return Line(exerciseID: id, name: name,
                        sets: RoutineDraft.clampSets(item.sets ?? RoutineDraft.defaultSets),
                        reps: RoutineDraft.clampReps(item.reps ?? RoutineDraft.defaultReps),
                        restSeconds: snapRest(item.rest ?? RoutineDraft.inheritRest), supersetGroup: group)
        }
        return finished(name: oneLine(payload.name ?? ""), lines: lines)
    }

    private static func nonEmpty(_ text: String?) -> String? {
        guard let text, !text.isEmpty else { return nil }
        return text
    }

    /// The numbered lines ("2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]"), matched by shape, not by
    /// words, so any language reads. The first unnumbered line before them is the header; its name is what follows
    /// the first ":".
    static func parseLines(_ text: String) -> Shared? {
        var name = ""
        var lines: [Line] = []
        var sawHeader = false
        for raw in text.split(whereSeparator: { $0.isNewline }) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix(marker) { continue }
            if let parsed = parseLine(line) {
                lines.append(parsed)
            } else if lines.isEmpty && !sawHeader {
                sawHeader = true
                if let colon = line.firstIndex(of: ":") {
                    name = oneLine(String(line[line.index(after: colon)...]))
                } else {
                    name = oneLine(line)
                }
            }
        }
        return finished(name: name, lines: lines)
    }

    /// "3. Name — 3 × 10, rest 1:30 [Superset A]" → a line; nil when it is not numbered.
    static func parseLine(_ line: String) -> Line? {
        let chars = Array(line)
        var i = 0
        while i < chars.count, chars[i].isASCII, chars[i].isNumber { i += 1 }
        guard i > 0, i <= 3, i < chars.count, chars[i] == "." || chars[i] == ")" else { return nil }
        var body = String(chars[(i + 1)...]).trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty, i + 1 < chars.count, chars[i + 1].isWhitespace else { return nil }

        var group: Int?
        if body.hasSuffix("]"), let open = body.lastIndex(of: "[") {
            let inside = body[body.index(after: open)..<body.index(before: body.endIndex)]
            let token = inside.split(separator: " ").last.map(String.init) ?? ""
            if token.count == 1, let scalar = token.uppercased().unicodeScalars.first,
               scalar.value >= 65, scalar.value <= 90 {
                group = Int(scalar.value) - 64
                body = String(body[..<open]).trimmingCharacters(in: .whitespaces)
            }
        }

        var name = body
        var sets = RoutineDraft.defaultSets
        var reps = RoutineDraft.defaultReps
        var rest = RoutineDraft.inheritRest
        if let split = targetsSplit(body) {
            name = split.name
            sets = split.sets
            reps = split.reps
            rest = split.rest
        }
        name = oneLine(name)
        guard !name.isEmpty else { return nil }
        return Line(exerciseID: nil, name: name, sets: RoutineDraft.clampSets(sets), reps: RoutineDraft.clampReps(reps),
                    restSeconds: snapRest(rest), supersetGroup: group)
    }

    /// The last " — " / " – " / " - " whose tail starts with "sets × reps": the name before it, the targets after.
    private static func targetsSplit(_ body: String) -> (name: String, sets: Int, reps: Int, rest: Int)? {
        let chars = Array(body)
        var index = chars.count - 2
        while index >= 1 {
            let isDash = chars[index] == "—" || chars[index] == "–" || chars[index] == "-"
            if isDash, chars[index - 1] == " ", chars[index + 1] == " ",
               let targets = parseTargets(Array(chars[(index + 2)...])) {
                return (String(chars[..<(index - 1)]), targets.sets, targets.reps, targets.rest)
            }
            index -= 1
        }
        return nil
    }

    /// "3 × 10, rest 1:30" → 3, 10, 90 ("x", "X" or "*" for "×"; the rest is the first m:ss).
    private static func parseTargets(_ chars: [Character]) -> (sets: Int, reps: Int, rest: Int)? {
        var i = 0
        func skipSpaces() { while i < chars.count, chars[i] == " " { i += 1 } }
        func number() -> Int? {
            let start = i
            while i < chars.count, chars[i].isASCII, chars[i].isNumber, i - start < 4 { i += 1 }
            return i > start ? Int(String(chars[start..<i])) : nil
        }
        skipSpaces()
        guard let sets = number() else { return nil }
        skipSpaces()
        guard i < chars.count, "×xX*".contains(chars[i]) else { return nil }
        i += 1
        skipSpaces()
        guard let reps = number() else { return nil }
        let tail = Array(chars[i...])
        return (sets, reps, firstClock(tail))
    }

    /// Seconds of the first "m:ss" in `chars`, 0 when there is none.
    private static func firstClock(_ chars: [Character]) -> Int {
        var index = 0
        while index < chars.count {
            if chars[index] == ":" {
                var start = index
                while start > 0, chars[start - 1].isASCII, chars[start - 1].isNumber, index - start < 2 { start -= 1 }
                let end = index + 3
                if index > start, chars.count >= end,
                   chars[index + 1].isASCII, chars[index + 1].isNumber,
                   chars[index + 2].isASCII, chars[index + 2].isNumber,
                   end == chars.count || !(chars[end].isASCII && chars[end].isNumber),
                   let minutes = Int(String(chars[start..<index])),
                   let seconds = Int(String(chars[(index + 1)..<end])) {
                    return minutes * 60 + seconds
                }
            }
            index += 1
        }
        return 0
    }

    /// The nearest length of the routine rest menu; 0 (the user's default) stays 0.
    static func snapRest(_ seconds: Int) -> Int {
        guard seconds > 0 else { return RoutineDraft.inheritRest }
        let lengths = RoutineDraft.restOptions.filter { $0 > 0 }
        var best = lengths[0]
        for length in lengths where abs(length - seconds) < abs(best - seconds) { best = length }
        return best
    }

    private static func finished(name: String, lines: [Line]) -> Shared? {
        guard !lines.isEmpty else { return nil }
        var kept = Array(lines.prefix(maxLines))
        let groups = Superset.normalized(kept.map(\.supersetGroup))
        for index in kept.indices { kept[index].supersetGroup = groups[index] }
        return Shared(name: name, lines: kept)
    }

    // MARK: Matching to the library

    /// The user's exercises, looked up by id and by name (English and Polish, `WorkoutImport.matchKeys`).
    struct Catalog {
        struct Entry: Equatable {
            var id: String
            var name: String
            var primaryMuscle: String?
        }

        private(set) var byID: [String: Entry] = [:]
        private(set) var byKey: [String: Entry] = [:]

        /// `names` are what an exercise is matched on; `entry.name` is what the preview shows.
        mutating func add(_ entry: Entry, names: [String]) {
            if byID[entry.id] == nil { byID[entry.id] = entry }
            for name in names {
                for key in WorkoutImport.matchKeys(name) where byKey[key] == nil { byKey[key] = entry }
            }
        }

        func match(_ name: String) -> Entry? {
            for key in WorkoutImport.matchKeys(name) { if let found = byKey[key] { return found } }
            return nil
        }
    }

    /// One line of the import preview: a library exercise (`exerciseID`) or a custom exercise "Add routine" creates
    /// (`exerciseID` nil).
    struct Planned: Equatable, Identifiable {
        var index: Int
        var exerciseID: String?
        var name: String
        var primaryMuscle: String?
        var sets: Int
        var reps: Int
        var restSeconds: Int
        var supersetGroup: Int?

        var id: Int { index }
        var isNew: Bool { exerciseID == nil }
    }

    /// Matches every line: by id, else by name (as the workout import does); a name the library does not know becomes
    /// one new custom exercise (a second line with the same name is dropped, as the editor allows an exercise once).
    /// A line with an unknown id and no name is left out.
    static func plan(_ shared: Shared, catalog: Catalog) -> [Planned] {
        var planned: [Planned] = []
        var seenIDs = Set<String>()
        var seenNew = Set<String>()
        for line in shared.lines {
            var entry = line.exerciseID.flatMap { catalog.byID[$0] }
            if entry == nil, let name = line.name { entry = catalog.match(name) }
            if let entry {
                guard seenIDs.insert(entry.id).inserted else { continue }
                planned.append(Planned(index: planned.count, exerciseID: entry.id, name: entry.name,
                                       primaryMuscle: entry.primaryMuscle, sets: line.sets, reps: line.reps,
                                       restSeconds: line.restSeconds, supersetGroup: line.supersetGroup))
            } else if let name = line.name, !name.isEmpty {
                let key = WorkoutImport.matchKeys(name).first ?? name.lowercased()
                guard seenNew.insert(key).inserted else { continue }
                planned.append(Planned(index: planned.count, exerciseID: nil, name: name, primaryMuscle: nil,
                                       sets: line.sets, reps: line.reps, restSeconds: line.restSeconds,
                                       supersetGroup: line.supersetGroup))
            }
        }
        let groups = Superset.normalized(planned.map(\.supersetGroup))
        for index in planned.indices { planned[index].supersetGroup = groups[index] }
        return planned
    }
}
