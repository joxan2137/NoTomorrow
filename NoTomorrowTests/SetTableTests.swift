import XCTest
import SwiftData
@testable import NoTomorrow

/// The active set table: Previous lined up with warm-ups, no "0 × 0" sets, Delete set, and lb in and out.
@MainActor
final class SetTableTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private func bench() -> Exercise {
        let exercise = Exercise(id: "Barbell_Bench_Press_-_Medium_Grip", name: "Bench", primaryMuscles: ["chest"])
        context.insert(exercise)
        return exercise
    }

    /// A workout with one exercise; rows are (kind, kg, reps, completed).
    private func workout(_ exercise: Exercise, start: Date, finished: Bool,
                         rows: [(SetKind, Double, Int, Bool)]) -> (Workout, WorkoutExercise) {
        let workout = Workout(name: "Push A", startedAt: start)
        if finished { workout.endedAt = start.addingTimeInterval(3600) }
        context.insert(workout)
        let entry = WorkoutExercise(order: 0, exercise: exercise)
        context.insert(entry)
        entry.workout = workout
        for (index, row) in rows.enumerated() {
            let set = SetEntry(order: index, kind: row.0, weightKg: row.1, reps: row.2)
            set.completedAt = row.3 ? start.addingTimeInterval(Double(index + 1) * 60) : nil
            context.insert(set)
            set.workoutExercise = entry
        }
        try? context.save()
        return (workout, entry)
    }

    // MARK: Previous (product-ux-6)

    func testPreviousLinesUpWithWarmups() {
        let ex = bench()
        _ = workout(ex, start: .now.addingTimeInterval(-86_400), finished: true, rows: [
            (.warmup, 40, 10, true), (.normal, 80, 8, true), (.normal, 80, 8, true), (.normal, 80, 7, true),
        ])
        let (today, entry) = workout(ex, start: .now.addingTimeInterval(-600), finished: false, rows: [
            (.warmup, 0, 0, false), (.normal, 0, 0, false), (.normal, 0, 0, false), (.normal, 0, 0, false),
            (.normal, 0, 0, false),
        ])
        let model = ActiveWorkoutModel(workout: today, context: context)
        let sets = entry.sortedSets

        let previous = sets.map { model.previous(for: $0, in: entry) }
        XCTAssertEqual(previous[0], .init(weightKg: 40, reps: 10), "warm-up against warm-up")
        XCTAssertEqual(previous[1], .init(weightKg: 80, reps: 8), "set 1 against set 1, not the warm-up")
        XCTAssertEqual(previous[3], .init(weightKg: 80, reps: 7))
        XCTAssertEqual(previous[4], .init(weightKg: 80, reps: 7), "past the end: the most recent completed set")

        model.prefillFromPrevious(sets[1], in: entry)
        XCTAssertEqual(sets[1].weightKg, 80)
        XCTAssertEqual(sets[1].reps, 8)
    }

    func testEmptyRowIsNotLoggedAsZeroByZero() {
        let (w, entry) = workout(bench(), start: .now.addingTimeInterval(-600), finished: false,
                                 rows: [(.normal, 0, 0, false)])
        let model = ActiveWorkoutModel(workout: w, context: context)
        let set = entry.sortedSets[0]

        let rested = model.complete(set, in: entry, restTimer: RestTimerController())

        XCTAssertFalse(rested)
        XCTAssertFalse(set.isCompleted, "never done before and no reps: nothing to log")
        XCTAssertFalse(set.isPR)
    }

    func testDeleteSetRenumbersTheRest() throws {
        let (w, entry) = workout(bench(), start: .now.addingTimeInterval(-600), finished: false,
                                 rows: [(.normal, 80, 8, true), (.normal, 80, 8, false), (.normal, 80, 8, false)])
        let model = ActiveWorkoutModel(workout: w, context: context)
        let middle = entry.sortedSets[1]
        let last = entry.sortedSets[2]

        model.removeSet(middle, in: entry)

        XCTAssertEqual(entry.sortedSets.count, 2)
        XCTAssertEqual(last.order, 1)
        XCTAssertEqual(model.setNumber(for: last, in: entry), 2)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SetEntry>()).count, 2)
    }

    func testDeleteSetTellsProgressWhichNeverReadsTheDeletedSet() throws {
        let ex = bench()
        let (w, entry) = workout(ex, start: .now.addingTimeInterval(-600), finished: false,
                                 rows: [(.normal, 80, 8, true), (.normal, 100, 3, true)])
        let progress = ProgressModel()
        progress.reload(context)
        let before = try XCTUnwrap(progress.lift(for: ex))
        XCTAssertEqual(before.heaviest?.weightKg, 100)
        let model = ActiveWorkoutModel(workout: w, context: context)
        let posted = expectation(forNotification: .workoutHistoryDidChange, object: nil)

        model.removeSet(entry.sortedSets[1], in: entry)

        wait(for: [posted], timeout: 1)
        // The summary built before the delete is still safe to read: it holds values, not the deleted model.
        XCTAssertEqual(before.heaviest?.weightKg, 100)
        XCTAssertEqual(before.mostReps?.reps, 8)
        progress.reload(context)
        XCTAssertEqual(progress.lift(for: ex)?.heaviest?.weightKg, 80)
        XCTAssertEqual(progress.lift(for: ex)?.sets.count, 1)
    }

    func testRemoveExerciseDeletesItsSetsAndTellsProgress() throws {
        let (w, entry) = workout(bench(), start: .now.addingTimeInterval(-600), finished: false,
                                 rows: [(.normal, 80, 8, true), (.normal, 80, 8, false)])
        let model = ActiveWorkoutModel(workout: w, context: context)
        let posted = expectation(forNotification: .workoutHistoryDidChange, object: nil)

        model.remove(entry)

        wait(for: [posted], timeout: 1)
        XCTAssertTrue(w.exercises.isEmpty)
        XCTAssertEqual(try context.fetch(FetchDescriptor<SetEntry>()).count, 0)
    }

    // MARK: Ties (edited sets share one time)

    func testPreviousPastTheEndTakesTheBottomRowOfSetsLoggedAtOneTime() {
        let ex = bench()
        let start = Date.now.addingTimeInterval(-86_400)
        let (_, done) = workout(ex, start: start, finished: true,
                                rows: [(.normal, 100, 3, true), (.normal, 90, 5, true), (.normal, 80, 8, true)])
        // Rows logged in the workout editor all take the same time.
        for set in done.sets { set.completedAt = start.addingTimeInterval(120) }
        try? context.save()
        let (today, entry) = workout(ex, start: .now.addingTimeInterval(-600), finished: false,
                                     rows: [(.normal, 0, 0, false), (.normal, 0, 0, false), (.normal, 0, 0, false),
                                            (.normal, 0, 0, false)])
        let model = ActiveWorkoutModel(workout: today, context: context)

        XCTAssertEqual(model.previous(for: entry.sortedSets[3], in: entry), .init(weightKg: 80, reps: 8),
                       "the last row, not whichever tied set came first")
        XCTAssertEqual(RecordService.lastSet(for: ex, excluding: today)?.order, 2)
    }

    func testCompletionOrderBreaksTiesByExerciseThenRow() {
        let ex = bench()
        let at = Date.now.addingTimeInterval(-3600)
        let (_, first) = workout(ex, start: at, finished: true, rows: [(.normal, 80, 8, true), (.normal, 80, 8, true)])
        let second = WorkoutExercise(order: 1, exercise: ex)
        context.insert(second)
        second.workout = first.workout
        let other = SetEntry(order: 0, kind: .normal, weightKg: 60, reps: 10)
        context.insert(other)
        other.workoutExercise = second
        let sets = first.sortedSets + [other]
        for set in sets { set.completedAt = at }

        XCTAssertTrue(RecordService.completedEarlier(sets[0], sets[1]), "same exercise: by row")
        XCTAssertFalse(RecordService.completedEarlier(sets[1], sets[0]))
        XCTAssertTrue(RecordService.completedEarlier(sets[1], other), "then by the exercise's place in the workout")
        other.completedAt = at.addingTimeInterval(-1)
        XCTAssertTrue(RecordService.completedEarlier(other, sets[0]), "time comes first")
    }

    func testFinishRebuildsStaleFlags() {
        let (w, entry) = workout(bench(), start: .now.addingTimeInterval(-600), finished: false,
                                 rows: [(.normal, 80, 8, true), (.normal, 90, 8, true)])
        let sets = entry.sortedSets
        sets[0].isPR = true
        sets[1].isPR = true
        sets[1].kind = .warmup   // changed after its tick: no longer a record
        let model = ActiveWorkoutModel(workout: w, context: context)

        model.finish()

        XCTAssertEqual(sets.map(\.isPR), [true, false])
    }

    // MARK: Units (product-ux-12)

    func testPoundsRoundTrip() {
        let kg = SetInput.weightKg("135", unit: .lb)
        XCTAssertEqual(kg, 61.235, accuracy: 0.001, "stored in kg")
        XCTAssertEqual(SetInput.text(weightKg: kg, unit: .lb), "135", "shown as typed")
        XCTAssertEqual(Fmt.weight(kg, unit: .lb, withUnit: false), "135")
        XCTAssertEqual(SetInput.weightKg("100", unit: .kg), 100)
    }

    func testSetInputParsing() {
        XCTAssertEqual(SetInput.number("82,5"), 82.5)
        XCTAssertEqual(SetInput.number("82.5"), 82.5)
        XCTAssertEqual(SetInput.number(""), 0)
        XCTAssertEqual(SetInput.number("abc"), 0)
        XCTAssertEqual(SetInput.number("nan"), 0)
        XCTAssertEqual(SetInput.reps("8"), 8)
        XCTAssertEqual(SetInput.reps("1e30"), 9_999, "never overflows")
        XCTAssertEqual(SetInput.text(weightKg: 0, unit: .kg), "")
        XCTAssertEqual(SetInput.text(reps: 0), "")
        XCTAssertEqual(SetInput.number(SetInput.text(weightKg: 81.25, unit: .kg)), 81.25, "quarter plates survive")
    }

    func testVolumeInPounds() {
        XCTAssertEqual(Fmt.volume(100, unit: .lb, withUnit: false), "220")
        XCTAssertTrue(Fmt.volume(100, unit: .lb).hasSuffix("lb"))
        XCTAssertTrue(Fmt.volume(100).hasSuffix("kg"))
    }
}
