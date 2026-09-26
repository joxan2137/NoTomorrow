import Foundation
import SwiftData

/// Writes `RoutineDraft`s to the store and the list actions on routines (duplicate, reorder, delete).
@MainActor
enum RoutineStore {

    /// The draft of an existing routine; lines whose exercise was deleted from the library are left out.
    static func draft(of routine: Routine) -> RoutineDraft {
        let items = routine.sortedItems.compactMap { item -> RoutineItemDraft? in
            guard let exercise = item.exercise else { return nil }
            return RoutineItemDraft(exerciseID: exercise.id, name: exercise.localizedName,
                                    primaryMuscle: exercise.primaryMuscles.first,
                                    sets: item.targetSets, reps: item.targetReps, restSeconds: item.restSeconds,
                                    supersetGroup: item.supersetGroup)
        }
        var draft = RoutineDraft(name: routine.name, items: items)
        draft.normalizeSupersets()   // a line whose exercise was deleted may have split a superset
        return draft
    }

    /// "Save as routine" from a finished workout.
    static func draft(from workout: Workout, in context: ModelContext) -> RoutineDraft {
        let defaultRest = WorkoutStarter.defaultRestSeconds(in: context)
        let logged = workout.sortedExercises.compactMap { item -> RoutineDraft.LoggedExercise? in
            guard let exercise = item.exercise else { return nil }
            let working = item.sortedSets.filter { $0.isCompleted && $0.kind != .warmup }
            let defaultForExercise = RoutineSeeder.restSeconds(for: exercise.id, defaultRest: defaultRest)
            return .init(exerciseID: exercise.id, name: exercise.localizedName,
                         primaryMuscle: exercise.primaryMuscles.first,
                         workingReps: working.map(\.reps), restSeconds: item.restSeconds,
                         usesDefaultRest: item.restSeconds == defaultForExercise, supersetGroup: item.supersetGroup)
        }
        return RoutineDraft.from(workoutName: workout.name, exercises: logged, takenNames: names(in: context))
    }

    static func names(in context: ModelContext, excluding routine: Routine? = nil) -> [String] {
        let all = (try? context.fetch(FetchDescriptor<Routine>())) ?? []
        return all.filter { $0.persistentModelID != routine?.persistentModelID }.map(\.name)
    }

    /// Writes `draft` into `routine` (its lines are replaced), or into a new routine at the end of the list.
    @discardableResult
    static func save(_ draft: RoutineDraft, into routine: Routine?, in context: ModelContext) -> Routine {
        let target: Routine
        if let routine {
            target = routine
            for item in routine.items { context.delete(item) }
            routine.items = []
        } else {
            let all = (try? context.fetch(FetchDescriptor<Routine>())) ?? []
            target = Routine(name: draft.trimmedName, order: (all.map(\.order).max() ?? -1) + 1)
            context.insert(target)
        }
        target.name = draft.trimmedName

        let ids = draft.items.map(\.exerciseID)
        let found = (try? context.fetch(FetchDescriptor<Exercise>(predicate: #Predicate { ids.contains($0.id) }))) ?? []
        let byID = Dictionary(found.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var order = 0
        for line in draft.items {
            guard let exercise = byID[line.exerciseID] else { continue }
            let item = RoutineItem(order: order, exercise: exercise, targetSets: line.sets, targetReps: line.reps,
                                   restSeconds: line.restSeconds)
            item.supersetGroup = line.supersetGroup
            context.insert(item)
            item.routine = target
            order += 1
        }
        try? context.save()
        return target
    }

    /// "Add N routines" in the program browser: one new routine per program routine, appended in the program's
    /// order through `save`, named uniquely ("Full Body A 2" when that name is taken). A routine none of whose
    /// exercises is in the library is skipped.
    @discardableResult
    static func add(_ program: TrainingProgram, in context: ModelContext) -> [Routine] {
        let ids = Array(program.exerciseIDs)
        let found = (try? context.fetch(FetchDescriptor<Exercise>(predicate: #Predicate { ids.contains($0.id) }))) ?? []
        let byID = Dictionary(found.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let drafts = ProgramLibrary.drafts(for: program, taken: names(in: context), exercise: { id in
            guard let exercise = byID[id] else { return nil }
            return ProgramLibrary.ExerciseInfo(name: exercise.localizedName, primaryMuscle: exercise.primaryMuscles.first)
        })
        return drafts.filter { !$0.items.isEmpty }.map { save($0, into: nil, in: context) }
    }

    static func delete(_ routine: Routine, in context: ModelContext) {
        context.delete(routine)
        try? context.save()
    }

    /// A copy right after the original, named "Push A 2".
    static func duplicate(_ routine: Routine, in context: ModelContext) {
        var copy = draft(of: routine)
        copy.name = RoutineDraft.uniqueName(routine.name, taken: names(in: context))
        let saved = save(copy, into: nil, in: context)
        let all = ((try? context.fetch(FetchDescriptor<Routine>(sortBy: [SortDescriptor(\.order)]))) ?? [])
            .filter { $0.persistentModelID != saved.persistentModelID }
        var ordered = all
        let index = (ordered.firstIndex { $0.persistentModelID == routine.persistentModelID } ?? ordered.count - 1) + 1
        ordered.insert(saved, at: min(index, ordered.count))
        renumber(ordered)
        try? context.save()
    }

    /// Moves a routine one place up (-1) or down (+1) in the Train list.
    static func move(_ routine: Routine, by offset: Int, in context: ModelContext) {
        var ordered = (try? context.fetch(FetchDescriptor<Routine>(sortBy: [SortDescriptor(\.order)]))) ?? []
        guard let from = ordered.firstIndex(where: { $0.persistentModelID == routine.persistentModelID }) else { return }
        let to = from + offset
        guard ordered.indices.contains(to) else { return }
        ordered.swapAt(from, to)
        renumber(ordered)
        try? context.save()
    }

    private static func renumber(_ routines: [Routine]) {
        for (index, routine) in routines.enumerated() where routine.order != index { routine.order = index }
    }
}
