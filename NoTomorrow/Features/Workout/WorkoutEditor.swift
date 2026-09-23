import Foundation
import SwiftData

/// Writes an edited draft back onto a finished workout, or deletes one, and keeps everything derived from it
/// consistent: set times on the records timeline, PR / set-record flags of every workout with those exercises,
/// attendance for the old and new day (locally and on the backend). Posts `.workoutHistoryDidChange` when done.
@MainActor
enum WorkoutEditor {

    static func apply(_ draft: WorkoutDraft, to workout: Workout, in context: ModelContext, today: Date = .now) {
        let cal = Calendar.current
        let oldStart = workout.startedAt
        let oldEnd = workout.endedAt ?? oldStart
        let oldDay = workout.completedSetCount > 0 ? cal.startOfDay(for: oldStart) : nil
        let exercisesBefore = exerciseIDs(of: workout)
        let times = WorkoutTimeline.completedTimes(for: draft, oldStart: oldStart, oldEnd: oldEnd)

        let name = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { workout.name = name }
        workout.notes = draft.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        workout.startedAt = draft.startedAt
        workout.endedAt = draft.endedAt

        // Exercises no longer in the draft; their sets go first (cascade is not reliable on iOS 17).
        let keptExercises = Set(draft.exercises.compactMap(\.sourceID))
        for entry in workout.exercises.filter({ !keptExercises.contains($0.persistentModelID) }) {
            for set in Array(entry.sets) { context.delete(set) }
            workout.exercises.removeAll { $0.persistentModelID == entry.persistentModelID }
            context.delete(entry)
        }

        for (index, item) in draft.exercises.enumerated() {
            guard let entry = entry(for: item, order: index, in: workout, context: context) else { continue }
            entry.order = index

            let keptSets = Set(item.sets.compactMap(\.sourceID))
            for set in entry.sets.filter({ !keptSets.contains($0.persistentModelID) }) {
                entry.sets.removeAll { $0.persistentModelID == set.persistentModelID }
                context.delete(set)
            }
            for (row, draftSet) in item.sets.enumerated() {
                let set: SetEntry
                if let id = draftSet.sourceID, let existing = entry.sets.first(where: { $0.persistentModelID == id }) {
                    set = existing
                } else {
                    set = SetEntry(order: row)
                    context.insert(set)
                    set.workoutExercise = entry
                }
                set.order = row
                set.kind = draftSet.kind
                set.weightKg = max(0, draftSet.weightKg)
                set.reps = max(0, draftSet.reps)
                set.completedAt = draftSet.isLogged ? (times[draftSet.id] ?? draft.startedAt) : nil
                if !draftSet.isLogged {
                    set.isPR = false
                    set.isSetRecord = false
                }
            }
        }
        try? context.save()

        RecordService.rebuild(exerciseIDs: exercisesBefore.union(exerciseIDs(of: workout)), in: context)
        let newDay = workout.completedSetCount > 0 ? cal.startOfDay(for: workout.startedAt) : nil
        let changes = AttendanceService.applyWorkoutDayChange(from: oldDay, to: newDay, excluding: workout.id,
                                                              context: context, today: today)
        try? context.save()
        reportAttendance(changes, in: context)
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
    }

    /// Deletes a finished workout. Call it once nothing shows the workout any more (after its sheet is gone).
    static func delete(_ workout: Workout, in context: ModelContext, today: Date = .now) {
        let ids = exerciseIDs(of: workout)
        let oldDay = workout.completedSetCount > 0 ? Calendar.current.startOfDay(for: workout.startedAt) : nil
        let workoutID = workout.id
        for entry in Array(workout.exercises) {
            for set in Array(entry.sets) { context.delete(set) }
            context.delete(entry)
        }
        context.delete(workout)
        try? context.save()

        RecordService.rebuild(exerciseIDs: ids, in: context)
        let changes = AttendanceService.applyWorkoutDayChange(from: oldDay, to: nil, excluding: workoutID,
                                                              context: context, today: today)
        try? context.save()
        reportAttendance(changes, in: context)
        NotificationCenter.default.post(name: .workoutHistoryDidChange, object: nil)
    }

    /// A workout in progress that no longer counts: "Edit sets" on the summary reopened it after its first Finish had
    /// marked the start day attended (and told the backend), every set was unticked, and it was then finished with
    /// nothing done or discarded. Reverts the day the way a delete does, unless another finished workout keeps it,
    /// and reports that. Nothing changes for a day that is not attended.
    static func releaseAttendance(of workout: Workout, in context: ModelContext, today: Date = .now) {
        let changes = AttendanceService.applyWorkoutDayChange(from: workout.startedAt, to: nil, excluding: workout.id,
                                                              context: context, today: today)
        guard !changes.isEmpty else { return }
        try? context.save()
        reportAttendance(changes, in: context)
    }

    // MARK: Helpers

    /// Tells the backend about the attendance the edit moved (attended on the new day, missed or planned again on
    /// the old one), the same way Finish reports a new workout.
    private static func reportAttendance(_ changes: [AttendanceService.WorkoutDayChange], in context: ModelContext) {
        guard !changes.isEmpty else { return }
        let schedule = AttendanceService.schedule(in: context)
        AttendanceSync.report(changes) { schedule?.isGymDay(Calendar.current.isoWeekday(for: $0)) ?? false }
    }

    private static func exerciseIDs(of workout: Workout) -> Set<String> {
        Set(workout.exercises.compactMap { $0.exercise?.id })
    }

    /// The row a draft exercise writes to: the one it came from, or a new one for an exercise added in the editor.
    private static func entry(for item: ExerciseDraft, order: Int, in workout: Workout,
                              context: ModelContext) -> WorkoutExercise? {
        if let id = item.sourceID, let existing = workout.exercises.first(where: { $0.persistentModelID == id }) {
            return existing
        }
        let exerciseID = item.exerciseID
        var d = FetchDescriptor<Exercise>(predicate: #Predicate { $0.id == exerciseID })
        d.fetchLimit = 1
        guard let exercise = try? context.fetch(d).first else { return nil }
        let entry = WorkoutExercise(order: order, exercise: exercise, restSeconds: item.restSeconds)
        context.insert(entry)
        entry.workout = workout
        return entry
    }
}
