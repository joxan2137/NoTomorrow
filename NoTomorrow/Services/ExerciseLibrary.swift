import Foundation
import SwiftData

/// Loads the bundled free-exercise-db (The Unlicense) plus the app's own `nt_` additions (989 exercises) into SwiftData.
/// Polish names come from `exercises_pl.json` (id → name) when present.
/// Runs once per `libraryVersion`: the JSON is decoded off the main thread, the rows are written on the main actor
/// with the environment's (main) context, never from another thread.
enum ExerciseLibrary {

    /// Bump whenever `exercises.json` or `exercises_pl.json` changes, so the next launch imports again
    /// (new exercises inserted, missing Polish names filled in).
    static let libraryVersion = 2
    static let versionKey = "nt.exerciseLibrary.version"

    struct Record: Decodable, Sendable {
        let id: String
        let name: String
        let force: String?
        let level: String?
        let mechanic: String?
        let equipment: String?
        let primaryMuscles: [String]
        let secondaryMuscles: [String]
        let instructions: [String]
        let category: String
        let images: [String]?
    }

    /// The decoded bundle files.
    struct Bundled: Sendable {
        var records: [Record]
        var polish: [String: String]
    }

    @MainActor private static var isImporting = false

    /// Imports when the stamp in `defaults` is not the current `libraryVersion`, or the store has no library rows
    /// (a new or recreated store). The stamp is written only after a successful save.
    @MainActor
    static func importIfNeeded(into context: ModelContext, defaults: UserDefaults = .standard,
                               load: @escaping @Sendable () -> Bundled? = { loadBundled() }) async {
        guard !isImporting else { return }
        let libraryRows = (try? context.fetchCount(FetchDescriptor<Exercise>(predicate: #Predicate { $0.isCustom == false }))) ?? 0
        guard needsImport(storedVersion: defaults.object(forKey: versionKey) as? Int, libraryRows: libraryRows) else { return }
        isImporting = true
        defer { isImporting = false }

        guard let bundled = await Task.detached(priority: .userInitiated, operation: load).value else { return }
        if apply(bundled, into: context) { defaults.set(libraryVersion, forKey: versionKey) }
    }

    /// Pure gate: a different (or no) stamp, or an empty library.
    static func needsImport(storedVersion: Int?, libraryRows: Int) -> Bool {
        storedVersion != libraryVersion || libraryRows == 0
    }

    /// Inserts the records that are not in the store yet and fills in missing Polish names. True once saved.
    @MainActor
    @discardableResult
    static func apply(_ bundled: Bundled, into context: ModelContext) -> Bool {
        guard let existing = try? context.fetch(FetchDescriptor<Exercise>()) else { return false }
        let byID = Dictionary(existing.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for r in bundled.records {
            if let row = byID[r.id] {
                if !row.isCustom && row.namePL == nil { row.namePL = bundled.polish[r.id] }
                continue
            }
            let exercise = Exercise(
                id: r.id,
                name: r.name,
                namePL: bundled.polish[r.id],
                primaryMuscles: r.primaryMuscles,
                secondaryMuscles: r.secondaryMuscles,
                equipment: r.equipment,
                category: r.category,
                force: r.force,
                mechanic: r.mechanic,
                level: r.level,
                instructions: r.instructions
            )
            context.insert(exercise)
        }
        do {
            try context.save()
            return true
        } catch {
            context.rollback()
            return false
        }
    }

    /// Reads and decodes the two bundle files; nil when `exercises.json` is missing or unreadable.
    static func loadBundled() -> Bundled? {
        let polish: [String: String] = {
            guard let url = Bundle.main.url(forResource: "exercises_pl", withExtension: "json"),
                  let data = try? Data(contentsOf: url),
                  let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
            return map
        }()
        guard let url = Bundle.main.url(forResource: "exercises", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let records = try? JSONDecoder().decode([Record].self, from: data) else { return nil }
        return Bundled(records: records, polish: polish)
    }

    /// Muscle-group filter chips, mapped onto free-exercise-db primaryMuscles values.
    enum MuscleGroup: String, CaseIterable, Identifiable {
        case all, chest, back, legs, shoulders, arms, core
        var id: String { rawValue }

        var muscles: Set<String> {
            switch self {
            case .all: []
            case .chest: ["chest"]
            case .back: ["lats", "middle back", "lower back", "traps"]
            case .legs: ["quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors"]
            case .shoulders: ["shoulders"]
            case .arms: ["biceps", "triceps", "forearms"]
            case .core: ["abdominals"]
            }
        }

        var titleKey: String {
            switch self {
            case .all: "muscle.all"
            case .chest: "muscle.chest"
            case .back: "muscle.back"
            case .legs: "muscle.legs"
            case .shoulders: "muscle.shoulders"
            case .arms: "muscle.arms"
            case .core: "muscle.core"
            }
        }
    }
}
