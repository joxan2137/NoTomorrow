import Foundation

/// One exercise line of a routine being edited: target sets × reps and its rest (0 = the user's default).
struct RoutineItemDraft: Identifiable, Equatable {
    let id: UUID
    var exerciseID: String
    var name: String
    var primaryMuscle: String?
    var sets: Int
    var reps: Int
    var restSeconds: Int

    init(id: UUID = UUID(), exerciseID: String, name: String, primaryMuscle: String? = nil,
         sets: Int = RoutineDraft.defaultSets, reps: Int = RoutineDraft.defaultReps,
         restSeconds: Int = RoutineDraft.inheritRest) {
        self.id = id
        self.exerciseID = exerciseID
        self.name = name
        self.primaryMuscle = primaryMuscle
        self.sets = RoutineDraft.clampSets(sets)
        self.reps = RoutineDraft.clampReps(reps)
        self.restSeconds = restSeconds
    }
}

/// A routine being created or edited: its name and exercise lines. Plain values, so the editor can be cancelled
/// without touching the store; `RoutineStore` writes it.
struct RoutineDraft: Equatable {
    var name: String
    var items: [RoutineItemDraft]

    static let defaultSets = 3
    static let defaultReps = 8
    static let setRange = 1...10
    static let repRange = 1...50
    /// `RoutineItem.restSeconds` 0 = use the user's Rest length setting when the workout starts.
    static let inheritRest = 0
    /// The rest menu: the default first, then 30 s to 5 min.
    static let restOptions = [inheritRest, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300]

    init(name: String = "", items: [RoutineItemDraft] = []) {
        self.name = name
        self.items = items
    }

    static func clampSets(_ n: Int) -> Int { min(max(n, setRange.lowerBound), setRange.upperBound) }
    static func clampReps(_ n: Int) -> Int { min(max(n, repRange.lowerBound), repRange.upperBound) }

    var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    var exerciseIDs: Set<String> { Set(items.map(\.exerciseID)) }

    /// A name and at least one exercise; a name another routine already uses is refused so Today's
    /// "up next" (which matches workouts to routines by name) stays unambiguous.
    func canSave(otherNames: [String]) -> Bool {
        !trimmedName.isEmpty && !items.isEmpty && !isNameTaken(otherNames: otherNames)
    }

    func isNameTaken(otherNames: [String]) -> Bool {
        let mine = trimmedName.lowercased()
        return !mine.isEmpty && otherNames.contains { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == mine }
    }

    // MARK: Edits

    mutating func append(_ item: RoutineItemDraft) {
        guard !items.contains(where: { $0.exerciseID == item.exerciseID }) else { return }
        items.append(item)
    }

    mutating func remove(_ id: UUID) {
        items.removeAll { $0.id == id }
    }

    mutating func move(_ id: UUID, by offset: Int) {
        guard let from = items.firstIndex(where: { $0.id == id }) else { return }
        let to = from + offset
        guard items.indices.contains(to) else { return }
        items.swapAt(from, to)
    }

    mutating func stepSets(_ id: UUID, by delta: Int) {
        update(id) { $0.sets = Self.clampSets($0.sets + delta) }
    }

    mutating func stepReps(_ id: UUID, by delta: Int) {
        update(id) { $0.reps = Self.clampReps($0.reps + delta) }
    }

    mutating func setRest(_ id: UUID, seconds: Int) {
        update(id) { $0.restSeconds = max(0, seconds) }
    }

    private mutating func update(_ id: UUID, _ change: (inout RoutineItemDraft) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[index])
    }

    // MARK: Names

    /// `base`, or `base 2`, `base 3`… : the first that no name in `taken` uses (case-insensitive).
    static func uniqueName(_ base: String, taken: [String]) -> String {
        let used = Set(taken.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() })
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard used.contains(trimmed.lowercased()) else { return trimmed }
        var n = 2
        while used.contains("\(trimmed) \(n)".lowercased()) { n += 1 }
        return "\(trimmed) \(n)"
    }

    // MARK: From a finished workout

    /// One logged exercise of a workout, reduced to what a routine keeps.
    struct LoggedExercise {
        var exerciseID: String
        var name: String
        var primaryMuscle: String?
        /// Completed sets that are not warm-ups, in row order.
        var workingReps: [Int]
        var restSeconds: Int
        /// The rest equals what a routine line with the default rest would give this exercise.
        var usesDefaultRest: Bool
    }

    /// "Save as routine": one line per exercise that has a completed working set, sets = how many were done,
    /// reps = the first working set's reps. The rest the workout used is kept unless it is the user's default.
    static func from(workoutName: String, exercises: [LoggedExercise], takenNames: [String]) -> RoutineDraft {
        let items = exercises.compactMap { logged -> RoutineItemDraft? in
            guard let firstReps = logged.workingReps.first(where: { $0 > 0 }) else { return nil }
            let rest = logged.usesDefaultRest ? inheritRest : logged.restSeconds
            return RoutineItemDraft(exerciseID: logged.exerciseID, name: logged.name, primaryMuscle: logged.primaryMuscle,
                                    sets: logged.workingReps.count, reps: firstReps, restSeconds: rest)
        }
        var seen = Set<String>()
        let unique = items.filter { seen.insert($0.exerciseID).inserted }
        return RoutineDraft(name: uniqueName(workoutName, taken: takenNames), items: unique)
    }
}
