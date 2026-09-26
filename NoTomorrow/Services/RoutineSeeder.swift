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
    /// Rest before the user has a profile (Settings > Rest timer > Rest length overrides it).
    static let defaultRestSeconds = 90
    /// Squat / deadlift / bench variants rest this much longer than the user's default.
    static let heavyExtraRestSeconds = 30
    /// `RoutineItem.restSeconds` value meaning "use the user's default when the workout starts".
    static let inheritRest = 0

    static func isHeavy(_ exerciseId: String) -> Bool {
        let id = exerciseId.lowercased()
        return ["squat", "deadlift", "bench_press"].contains(where: id.contains)
    }

    /// Rest for an exercise given the user's default rest: heavy compounds get 30 s more (capped at the
    /// setting's 10 min maximum). With the stock 1:30 default that is 1:30 / 2:00.
    static func restSeconds(for exerciseId: String, defaultRest: Int = defaultRestSeconds) -> Int {
        isHeavy(exerciseId) ? min(600, defaultRest + heavyExtraRestSeconds) : defaultRest
    }

    /// Set once the starter routines exist, so deleting every routine does not bring them back.
    /// A local data wipe clears it (`LocalDataWipe`).
    static let seededKey = "nt.routines.seeded"

    /// Idempotent: runs once, and only when no `Routine` exists yet.
    static func seedIfNeeded(context: ModelContext, defaults: UserDefaults = .standard) {
        guard !defaults.bool(forKey: seededKey) else { return }
        let routineCount = (try? context.fetchCount(FetchDescriptor<Routine>())) ?? 0
        guard routineCount == 0 else {
            defaults.set(true, forKey: seededKey)
            return
        }

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
                                       restSeconds: inheritRest)
                item.routine = routine
                context.insert(item)
                order += 1
            }
        }
        try? context.save()
        defaults.set(true, forKey: seededKey)
    }

    /// One-time: routine items still holding the fixed rest older builds seeded (90 s, 120 s for heavy lifts)
    /// switch to "inherit", so the Rest length setting drives them too. Items with any other value are left alone.
    static func inheritDefaultRestIfNeeded(context: ModelContext, defaults: UserDefaults = .standard) {
        let key = "nt.routines.inheritRest"
        guard !defaults.bool(forKey: key) else { return }
        let items = (try? context.fetch(FetchDescriptor<RoutineItem>())) ?? []
        var changed = false
        for item in items {
            guard let id = item.exercise?.id, item.restSeconds == restSeconds(for: id) else { continue }
            item.restSeconds = inheritRest
            changed = true
        }
        if changed { try? context.save() }
        defaults.set(true, forKey: key)
    }
}
