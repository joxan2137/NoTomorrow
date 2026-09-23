import Foundation
import Observation
import SwiftData

/// State of the workout editor (Edit in the detail sheet): the untouched original and the draft being edited.
/// Everything stays in the draft until Save.
@Observable
@MainActor
final class WorkoutEditModel {
    let original: WorkoutDraft
    var draft: WorkoutDraft
    /// Weights are typed in the user's unit and stored in kg.
    let unit: WeightUnit

    init(workout: Workout, unit: WeightUnit) {
        let draft = WorkoutDraft(workout: workout)
        original = draft
        self.draft = draft
        self.unit = unit
    }

    // MARK: Derived

    var isDirty: Bool { draft != original }
    func canSave(now: Date = .now) -> Bool { isDirty && draft.isTimeValid(now: now) }

    // MARK: Actions

    func setKind(_ kind: SetKind, for setID: UUID, in exerciseID: UUID) {
        draft.updateSet(setID, in: exerciseID) { $0.kind = kind }
    }

    func setWeight(_ text: String, for setID: UUID, in exerciseID: UUID) {
        draft.updateSet(setID, in: exerciseID) { $0.weightKg = SetInput.weightKg(text, unit: unit) }
    }

    func setReps(_ text: String, for setID: UUID, in exerciseID: UUID) {
        draft.updateSet(setID, in: exerciseID) { $0.reps = SetInput.reps(text) }
    }

    /// Adds the exercises picked in the exercise picker, each with one row from the last time it was done.
    func append(_ exercises: [Exercise], editing workout: Workout, context: ModelContext) {
        let defaultRest = WorkoutStarter.defaultRestSeconds(in: context)
        for exercise in exercises {
            let last = WorkoutStarter.lastCompletedSets(for: exercise, excluding: workout).first
            draft.appendExercise(id: exercise.id, name: exercise.localizedName,
                                 primaryMuscle: exercise.primaryMuscles.first,
                                 restSeconds: RoutineSeeder.restSeconds(for: exercise.id, defaultRest: defaultRest),
                                 template: last.map { ($0.weightKg, $0.reps) })
        }
    }

    func save(to workout: Workout, context: ModelContext) {
        WorkoutEditor.apply(draft, to: workout, in: context)
    }
}
