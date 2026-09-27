import Foundation
import SwiftData

/// Reads workout history exported from Strong, Hevy or NoTomorrow itself (Settings > Import workouts) into plain
/// values; `WorkoutImporter` writes them. The parsing uses no store, so it is unit-tested on plain text.
///
/// Formats (header row decides; comma or semicolon separated):
///   Strong      Date, Workout Name, Duration, Exercise Name, Set Order (1, 2… or W / D / F), Weight, Reps, RPE, Notes…
///   Hevy        title, start_time, end_time, exercise_title, exercise_notes, set_type, weight_kg / weight_lbs, reps, rpe…
///   NoTomorrow  workout_id, workout_name, started_at, ended_at, exercise, set, kind, weight_kg, reps, completed_at…
enum WorkoutImport {

    enum Format: String, Equatable { case strong, hevy, noTomorrow }

    struct ImportedSet: Equatable {
        var kind: SetKind
        var weightKg: Double
        var reps: Int
        var rpe: Double?
    }

    struct ImportedExercise: Equatable {
        var name: String
        var notes: String = ""
        var sets: [ImportedSet] = []
    }

    struct ImportedWorkout: Equatable {
        var name: String
        var startedAt: Date
        var endedAt: Date
        var notes: String = ""
        var exercises: [ImportedExercise] = []

        var setCount: Int { exercises.reduce(0) { $0 + $1.sets.count } }
    }

    struct Parsed: Equatable {
        var format: Format
        var workouts: [ImportedWorkout]
        /// Rows that could not be read (no date, no reps…), skipped.
        var skippedRows: Int
    }

    enum Failure: Error, Equatable { case empty, unknownFormat }

    /// `unit` is what a weight column without a unit in its name is in (Strong writes the app's unit).
    static func parse(_ text: String, unit: WeightUnit) throws -> Parsed {
        let rows = CSV.rows(text)
        guard let header = rows.first, rows.count > 1 else { throw Failure.empty }
        let columns = Columns(header)
        let body = rows.dropFirst().filter { !($0.count == 1 && $0[0].isEmpty) }
        if columns.has("exercise name"), columns.has("set order") {
            return parseStrong(body, columns, unit: unit)
        }
        if columns.has("exercise_title"), columns.has("start_time") {
            return parseHevy(body, columns)
        }
        if columns.has("workout_id"), columns.has("exercise") {
            return parseNoTomorrow(body, columns)
        }
        throw Failure.unknownFormat
    }

    // MARK: Strong

    private static func parseStrong(_ rows: [[String]], _ c: Columns, unit: WeightUnit) -> Parsed {
        var builder = Builder()
        let weightColumn = c.first(["weight (kg)", "weight (lbs)", "weight"]) ?? "weight"
        let weightUnit: WeightUnit = weightColumn.contains("lbs") ? .lb : (weightColumn.contains("kg") ? .kg : unit)
        for row in rows {
            let order = c.value("set order", in: row).trimmingCharacters(in: .whitespaces)
            guard let start = Dates.parse(c.value("date", in: row)) else { builder.skipped += 1; continue }
            // Strong writes rest timers and notes as rows of their own; only rows with reps are sets.
            let reps = Int(Numbers.parse(c.value("reps", in: row)) ?? 0)
            guard order.lowercased() != "rest timer" else { continue }
            guard reps > 0 else { builder.skipped += 1; continue }
            let kind: SetKind = switch order.uppercased() {
            case "W": .warmup
            case "D": .drop
            case "F": .failure
            default: .normal
            }
            let weight = Numbers.parse(c.value(weightColumn, in: row)) ?? 0
            // Older exports name the unit per row.
            let rowUnit: WeightUnit = switch c.value("weight unit", in: row).lowercased() {
            case "lbs", "lb": .lb
            case "kg": .kg
            default: weightUnit
            }
            let duration = Durations.seconds(c.value(c.first(["duration (sec)", "duration"]) ?? "duration", in: row))
            builder.add(workoutName: c.value("workout name", in: row), start: start,
                        end: start.addingTimeInterval(duration ?? 3600),
                        workoutNotes: c.value("workout notes", in: row),
                        exercise: c.value("exercise name", in: row), exerciseNotes: c.value("notes", in: row),
                        set: ImportedSet(kind: kind, weightKg: kg(weight, rowUnit), reps: reps,
                                         rpe: Numbers.parse(c.value("rpe", in: row))))
        }
        return Parsed(format: .strong, workouts: builder.workouts, skippedRows: builder.skipped)
    }

    // MARK: Hevy

    private static func parseHevy(_ rows: [[String]], _ c: Columns) -> Parsed {
        var builder = Builder()
        let usesPounds = !c.has("weight_kg") && c.has("weight_lbs")
        for row in rows {
            guard let start = Dates.parse(c.value("start_time", in: row)) else { builder.skipped += 1; continue }
            let reps = Int(Numbers.parse(c.value("reps", in: row)) ?? 0)
            guard reps > 0 else { builder.skipped += 1; continue }
            let kind: SetKind = switch c.value("set_type", in: row).lowercased() {
            case "warmup": .warmup
            case "dropset": .drop
            case "failure": .failure
            default: .normal
            }
            let weight = Numbers.parse(c.value(usesPounds ? "weight_lbs" : "weight_kg", in: row)) ?? 0
            let end = Dates.parse(c.value("end_time", in: row)) ?? start.addingTimeInterval(3600)
            builder.add(workoutName: c.value("title", in: row), start: start, end: max(end, start),
                        workoutNotes: c.value("description", in: row),
                        exercise: c.value("exercise_title", in: row), exerciseNotes: c.value("exercise_notes", in: row),
                        set: ImportedSet(kind: kind, weightKg: kg(weight, usesPounds ? .lb : .kg), reps: reps,
                                         rpe: Numbers.parse(c.value("rpe", in: row))))
        }
        return Parsed(format: .hevy, workouts: builder.workouts, skippedRows: builder.skipped)
    }

    // MARK: NoTomorrow (Settings > Export)

    private static func parseNoTomorrow(_ rows: [[String]], _ c: Columns) -> Parsed {
        var builder = Builder()
        for row in rows {
            guard let start = Dates.parse(c.value("started_at", in: row)) else { builder.skipped += 1; continue }
            let reps = Int(Numbers.parse(c.value("reps", in: row)) ?? 0)
            guard reps > 0 else { builder.skipped += 1; continue }
            let end = Dates.parse(c.value("ended_at", in: row)) ?? start.addingTimeInterval(3600)
            builder.add(workoutName: c.value("workout_name", in: row), start: start, end: max(end, start),
                        workoutNotes: "", exercise: c.value("exercise", in: row), exerciseNotes: "",
                        set: ImportedSet(kind: SetKind(rawValue: c.value("kind", in: row)) ?? .normal,
                                         weightKg: Numbers.parse(c.value("weight_kg", in: row)) ?? 0, reps: reps,
                                         rpe: nil))
        }
        return Parsed(format: .noTomorrow, workouts: builder.workouts, skippedRows: builder.skipped)
    }

    private static func kg(_ value: Double, _ unit: WeightUnit) -> Double {
        unit == .kg ? value : value / Fmt.lbPerKg
    }

    // MARK: Grouping

    /// Rows → workouts (same start and name), exercises in the order they first appear.
    private struct Builder {
        var workouts: [ImportedWorkout] = []
        var skipped = 0
        private var index: [String: Int] = [:]

        mutating func add(workoutName: String, start: Date, end: Date, workoutNotes: String,
                          exercise: String, exerciseNotes: String, set: ImportedSet) {
            let exerciseName = exercise.trimmingCharacters(in: .whitespaces)
            guard !exerciseName.isEmpty else { skipped += 1; return }
            let name = workoutName.trimmingCharacters(in: .whitespaces)
            let key = "\(Int(start.timeIntervalSince1970))|\(name)"
            let w: Int
            if let existing = index[key] {
                w = existing
            } else {
                w = workouts.count
                index[key] = w
                workouts.append(ImportedWorkout(name: name.isEmpty ? "Workout" : name, startedAt: start, endedAt: end,
                                                notes: workoutNotes.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
            let notes = exerciseNotes.trimmingCharacters(in: .whitespacesAndNewlines)
            if let e = workouts[w].exercises.lastIndex(where: { $0.name == exerciseName }) {
                workouts[w].exercises[e].sets.append(set)
                // Strong repeats or places the note on any set row; keep the first non-empty one.
                if workouts[w].exercises[e].notes.isEmpty { workouts[w].exercises[e].notes = notes }
            } else {
                workouts[w].exercises.append(ImportedExercise(name: exerciseName, notes: notes, sets: [set]))
            }
        }
    }

    // MARK: Exercise names

    /// Keys a name is matched on: folded, "(Barbell)" style suffixes moved to the front ("Bench Press (Barbell)" →
    /// "barbell bench press"), punctuation dropped.
    static func matchKeys(_ name: String) -> [String] {
        let folded = WorkoutStrings.fold(name)
        var keys = [clean(folded)]
        if let open = folded.firstIndex(of: "("), let close = folded[open...].firstIndex(of: ")") {
            let inside = String(folded[folded.index(after: open)..<close])
            let outside = String(folded[..<open]) + String(folded[folded.index(after: close)...])
            keys.append(clean(inside + " " + outside))
            keys.append(clean(outside))
        }
        return keys.filter { !$0.isEmpty }
    }

    private static func clean(_ text: String) -> String {
        text.lowercased()
            .map { $0.isLetter || $0.isNumber ? $0 : " " }
            .reduce(into: "") { $0.append($1) }
            .split(separator: " ").joined(separator: " ")
    }
}

// MARK: - CSV

/// RFC 4180-ish reader: quoted fields with doubled quotes and line breaks inside, comma or semicolon (whichever the
/// header uses more), CRLF or LF, a leading byte-order mark.
enum CSV {
    static func rows(_ text: String) -> [[String]] {
        var input = Substring(text)
        if input.first == "\u{FEFF}" { input = input.dropFirst() }
        let headerLine = input.prefix { !$0.isNewline }
        let delimiter: Character = headerLine.filter { $0 == ";" }.count > headerLine.filter { $0 == "," }.count ? ";" : ","

        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var iterator = input.makeIterator()
        var pending: Character? = nil
        while let char = pending ?? iterator.next() {
            pending = nil
            if inQuotes {
                if char == "\"" {
                    if let next = iterator.next() {
                        if next == "\"" { field.append("\"") } else { inQuotes = false; pending = next }
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(char)
                }
            } else if char == "\"" {
                inQuotes = true
            } else if char == delimiter {
                row.append(field)
                field = ""
            } else if char.isNewline {
                row.append(field)
                rows.append(row)
                row = []
                field = ""
            } else {
                field.append(char)
            }
        }
        if !field.isEmpty || !row.isEmpty {
            row.append(field)
            rows.append(row)
        }
        return rows.filter { !($0.count == 1 && $0[0].isEmpty) }
    }
}

/// Header name → column index, matched case-insensitively.
private struct Columns {
    private var index: [String: Int] = [:]

    init(_ header: [String]) {
        for (i, name) in header.enumerated() {
            let key = name.trimmingCharacters(in: .whitespaces).lowercased()
            if index[key] == nil { index[key] = i }
        }
    }

    func has(_ name: String) -> Bool { index[name] != nil }

    func first(_ names: [String]) -> String? { names.first(where: has) }

    func value(_ name: String, in row: [String]) -> String {
        guard let i = index[name], i < row.count else { return "" }
        return row[i].trimmingCharacters(in: .whitespaces)
    }
}

private enum Numbers {
    /// "82.5", "82,5" (a semicolon file's decimal comma), empty → nil.
    static func parse(_ text: String) -> Double? {
        let cleaned = text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty, let value = Double(cleaned), value.isFinite, value >= 0 else { return nil }
        return value
    }
}

private enum Durations {
    /// "3600", "1h 5m", "45m", "1:05:00" → seconds.
    static func seconds(_ text: String) -> TimeInterval? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let plain = Double(trimmed) { return plain }
        if trimmed.contains(":") {
            let parts = trimmed.split(separator: ":").compactMap { Double($0) }
            return parts.reduce(0) { $0 * 60 + $1 }
        }
        var total: TimeInterval = 0
        var number = ""
        for char in trimmed {
            if char.isNumber { number.append(char); continue }
            let value = Double(number) ?? 0
            switch char {
            case "h": total += value * 3600
            case "m": total += value * 60
            case "s": total += value
            default: break
            }
            if char.isLetter { number = "" }
        }
        return total > 0 ? total : nil
    }
}

private enum Dates {
    private static let formats = [
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "d MMM yyyy, HH:mm", "d MMM yyyy HH:mm", "MMM d, yyyy, h:mm a",
        "dd.MM.yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
    ]

    private static let formatters: [DateFormatter] = formats.map { format in
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = format
        return formatter
    }

    /// ISO 8601 with a zone first (NoTomorrow's own export), then the local-time formats Strong and Hevy write.
    static func parse(_ text: String) -> Date? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let iso = try? Date(trimmed, strategy: .iso8601) { return iso }
        for formatter in formatters {
            if let date = formatter.date(from: trimmed) { return date }
        }
        return nil
    }
}

// MARK: - Writing

/// Writes parsed workouts into the store: finished workouts with completed sets, exercises matched to the library by
/// name (English or Polish, "Bench Press (Barbell)" style included) or created as custom exercises. A workout that
/// already exists (same name, start within a minute) is skipped, so importing the same file twice adds nothing.
@MainActor
enum WorkoutImporter {

    struct Summary: Equatable {
        var workouts = 0
        var sets = 0
        var duplicates = 0
        var newExercises: [String] = []
    }

    static func importWorkouts(_ workouts: [WorkoutImport.ImportedWorkout], into context: ModelContext) -> Summary {
        var summary = Summary()
        let library = (try? context.fetch(FetchDescriptor<Exercise>())) ?? []
        var byKey: [String: Exercise] = [:]
        for exercise in library {
            for name in [exercise.name, exercise.namePL].compactMap({ $0 }) {
                for key in WorkoutImport.matchKeys(name) where byKey[key] == nil { byKey[key] = exercise }
            }
        }
        let existing = (try? context.fetch(FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt != nil }))) ?? []
        var touched = Set<String>()

        for imported in workouts where imported.setCount > 0 {
            if existing.contains(where: { $0.name == imported.name && abs($0.startedAt.timeIntervalSince(imported.startedAt)) < 60 }) {
                summary.duplicates += 1
                continue
            }
            let workout = Workout(name: imported.name, startedAt: imported.startedAt)
            workout.endedAt = imported.endedAt
            workout.notes = imported.notes
            context.insert(workout)
            let span = max(60, imported.endedAt.timeIntervalSince(imported.startedAt))
            let step = span / Double(max(1, imported.setCount))
            var tick = 0
            for (order, item) in imported.exercises.enumerated() {
                let exercise = match(item.name, keys: &byKey, context: context, summary: &summary)
                let entry = WorkoutExercise(order: order, exercise: exercise,
                                            restSeconds: RoutineSeeder.restSeconds(for: exercise.id))
                entry.notes = item.notes
                context.insert(entry)
                entry.workout = workout
                for (index, value) in item.sets.enumerated() {
                    let set = SetEntry(order: index, kind: value.kind, weightKg: value.weightKg, reps: value.reps)
                    set.rpe = value.rpe
                    // Spread over the workout in row order, so the records timeline reads them in the order done.
                    tick += 1
                    set.completedAt = imported.startedAt.addingTimeInterval(step * Double(tick))
                    context.insert(set)
                    set.workoutExercise = entry
                    summary.sets += 1
                }
                exercise.lastUsedAt = max(exercise.lastUsedAt ?? .distantPast, imported.startedAt)
                touched.insert(exercise.id)
            }
            summary.workouts += 1
        }
        try? context.save()
        RecordService.rebuild(exerciseIDs: touched, in: context)
        try? context.save()
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
        return summary
    }

    private static func match(_ name: String, keys: inout [String: Exercise], context: ModelContext,
                              summary: inout Summary) -> Exercise {
        let candidates = WorkoutImport.matchKeys(name)
        for key in candidates { if let found = keys[key] { return found } }
        let exercise = Exercise(id: "custom-\(UUID().uuidString.lowercased())", name: name, primaryMuscles: [],
                                isCustom: true)
        context.insert(exercise)
        for key in candidates where keys[key] == nil { keys[key] = exercise }
        summary.newExercises.append(name)
        return exercise
    }
}
