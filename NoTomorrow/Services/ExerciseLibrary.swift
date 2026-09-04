import Foundation
import SwiftData

/// Loads the bundled free-exercise-db (The Unlicense, 876 exercises) into SwiftData on first launch.
/// Polish names come from `exercises_pl.json` (id → name) when present.
actor ExerciseLibrary {
    static let shared = ExerciseLibrary()

    struct Record: Decodable {
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

    private var didRun = false

    func importIfNeeded(into context: ModelContext) async {
        guard !didRun else { return }
        didRun = true

        let count = (try? context.fetchCount(FetchDescriptor<Exercise>())) ?? 0
        guard count == 0 else { return }

        guard let url = Bundle.main.url(forResource: "exercises", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let records = try? JSONDecoder().decode([Record].self, from: data) else { return }

        let polish: [String: String] = {
            guard let url = Bundle.main.url(forResource: "exercises_pl", withExtension: "json"),
                  let data = try? Data(contentsOf: url),
                  let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
            return map
        }()

        for r in records {
            let exercise = Exercise(
                id: r.id,
                name: r.name,
                namePL: polish[r.id],
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
        try? context.save()
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
