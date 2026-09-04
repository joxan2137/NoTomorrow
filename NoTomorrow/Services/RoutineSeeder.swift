import Foundation
import SwiftData

/// Creates the three starter routines (Push A / Pull A / Legs) once, from exercises that already exist
/// in the store (imported by `ExerciseLibrary`). Ids that are missing are skipped; if none are found the
/// seed is postponed so a later call (after the import) can do it.
enum RoutineSeeder {

    struct Template {
        let name: String
        let exerciseIds: [String]
    }

    static let templates: [Template] = [
        Template(name: "Push A", exerciseIds: [
            "Barbell_Bench_Press_-_Medium_Grip",
            "Barbell_Shoulder_Press",
            "Incline_Dumbbell_Press",
            "Triceps_Pushdown",
            "Side_Lateral_Raise",
        ]),
        Template(name: "Pull A", exerciseIds: [
            "Barbell_Deadlift",
            "Bent_Over_Barbell_Row",
            "Pullups",
            "Barbell_Curl",
            "Seated_Cable_Rows",
        ]),
        Template(name: "Legs", exerciseIds: [
            "Barbell_Squat",
            "Romanian_Deadlift",
            "Leg_Press",
            "Leg_Extensions",
            "Standing_Calf_Raises",
        ]),
    ]

    static let defaultSets = 3
    static let defaultReps = 8
    static let defaultRestSeconds = 90
    static let heavyRestSeconds = 120

    /// Squat / deadlift / bench variants rest longer.
    static func restSeconds(for exerciseId: String) -> Int {
        let id = exerciseId.lowercased()
        let heavy = ["squat", "deadlift", "bench_press"]
        return heavy.contains(where: id.contains) ? heavyRestSeconds : defaultRestSeconds
    }

    /// Idempotent: only runs when no `Routine` exists yet.
    static func seedIfNeeded(context: ModelContext) {
        let routineCount = (try? context.fetchCount(FetchDescriptor<Routine>())) ?? 0
        guard routineCount == 0 else { return }

        let ids = templates.flatMap(\.exerciseIds)
        let descriptor = FetchDescriptor<Exercise>(predicate: #Predicate { ids.contains($0.id) })
        let found = (try? context.fetch(descriptor)) ?? []
        guard !found.isEmpty else { return }
        let byId = Dictionary(found.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        for (index, template) in templates.enumerated() {
            let routine = Routine(name: template.name, order: index)
            context.insert(routine)
            var order = 0
            for id in template.exerciseIds {
                guard let exercise = byId[id] else { continue }
                let item = RoutineItem(order: order, exercise: exercise,
                                       targetSets: defaultSets, targetReps: defaultReps,
                                       restSeconds: restSeconds(for: id))
                item.routine = routine
                context.insert(item)
                order += 1
            }
        }
        try? context.save()
    }
}
