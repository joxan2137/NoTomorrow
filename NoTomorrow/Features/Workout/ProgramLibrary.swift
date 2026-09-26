import Foundation

/// A built-in training program ("Browse programs" on the Train tab): a few routines of library exercises with their
/// target sets, reps and rest. Decoded from `data/programs/programs.json`, the one file the Android app reads too.
/// `name`, `summary` and each routine's `name` are catalog keys, so the copy follows the app's language.
struct TrainingProgram: Decodable, Identifiable, Equatable, Sendable {
    let id: String
    /// Catalog key of the program's name.
    let name: String
    /// Catalog key of its one-line description.
    let summary: String
    let level: Level
    let daysPerWeek: Int
    let routines: [Day]

    enum Level: String, Decodable, Sendable {
        case beginner, intermediate

        var titleKey: String { "program.level." + rawValue }
    }

    /// One routine of the program.
    struct Day: Decodable, Identifiable, Equatable, Sendable {
        let id: String
        /// Catalog key of the routine's name ("Full Body A").
        let name: String
        let items: [Line]
    }

    /// One exercise of a routine: a library id and its targets (`rest` in seconds, from `RoutineDraft.restOptions`).
    struct Line: Decodable, Equatable, Sendable {
        let exercise: String
        let sets: Int
        let reps: Int
        let rest: Int
    }

    /// Every library id the program uses.
    var exerciseIDs: Set<String> { Set(routines.flatMap { $0.items.map(\.exercise) }) }

    /// Every catalog key the program shows: its name, description and routine names.
    var localizationKeys: [String] { [name, summary] + routines.map(\.name) }

    /// A catalog key in the app's language.
    static func text(_ key: String) -> String { String(localized: String.LocalizationValue(key)) }
}

/// The bundled programs and the routine drafts "Add N routines" writes (`RoutineStore.add`).
enum ProgramLibrary {

    private struct File: Decodable {
        let version: Int
        let programs: [TrainingProgram]
    }

    static func decode(_ data: Data) throws -> [TrainingProgram] {
        try JSONDecoder().decode(File.self, from: data).programs
    }

    /// `programs.json` from `bundle`; empty when it is missing or unreadable.
    static func loadBundled(bundle: Bundle = .main) -> [TrainingProgram] {
        guard let url = bundle.url(forResource: "programs", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let programs = try? decode(data) else { return [] }
        return programs
    }

    /// Read once, on first use.
    static let all: [TrainingProgram] = loadBundled()

    /// What a routine line needs from the library exercise.
    struct ExerciseInfo: Equatable {
        var name: String
        var primaryMuscle: String?
    }

    /// One draft per program routine, in the program's order. Names are localized and made unique against `taken`
    /// and against each other (`RoutineDraft.uniqueName`, so a second add gives "Full Body A 2"). Lines whose
    /// exercise `exercise` does not know are left out.
    static func drafts(for program: TrainingProgram, taken: [String],
                       exercise: (String) -> ExerciseInfo?,
                       localize: (String) -> String = TrainingProgram.text) -> [RoutineDraft] {
        var used = taken
        var drafts: [RoutineDraft] = []
        for day in program.routines {
            let name = RoutineDraft.uniqueName(localize(day.name), taken: used)
            used.append(name)
            let items = day.items.compactMap { line -> RoutineItemDraft? in
                guard let info = exercise(line.exercise) else { return nil }
                return RoutineItemDraft(exerciseID: line.exercise, name: info.name, primaryMuscle: info.primaryMuscle,
                                        sets: line.sets, reps: line.reps, restSeconds: line.rest)
            }
            drafts.append(RoutineDraft(name: name, items: items))
        }
        return drafts
    }
}
