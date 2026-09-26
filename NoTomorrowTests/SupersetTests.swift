import XCTest
import SwiftData
@testable import NoTomorrow

/// Superset groups (link, unlink, normalize, letters) and the active workout moving between superset exercises.
@MainActor
final class SupersetTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: Groups

    func testLinkUnlinkAndNormalize() {
        var groups: [Int?] = [nil, nil, nil, nil]
        groups = Superset.linkWithNext(groups, at: 0)
        XCTAssertEqual(groups, [1, 1, nil, nil])
        groups = Superset.linkWithNext(groups, at: 2)
        XCTAssertEqual(groups, [1, 1, 2, 2])
        groups = Superset.linkWithNext(groups, at: 1)
        XCTAssertEqual(groups, [1, 1, 1, 1], "linking two supersets merges them")
        groups = Superset.unlink(groups, at: 3)
        XCTAssertEqual(groups, [1, 1, 1, nil])
        XCTAssertEqual(Superset.normalized([5, nil, 7, 7, 5]), [nil, nil, 1, 1, nil], "lone members leave")
        XCTAssertEqual(Superset.letters([3, 3, nil, 9, 9]), ["A", "A", nil, "B", "B"])
        XCTAssertTrue(Superset.isLinkedToNext([1, 1, nil], at: 0))
        XCTAssertFalse(Superset.isLinkedToNext([1, 1, nil], at: 1))
    }

    func testRoutineDraftKeepsSupersetsTogetherWhenEdited() {
        var draft = RoutineDraft(name: "A", items: ["a", "b", "c"].map { RoutineItemDraft(exerciseID: $0, name: $0) })
        draft.linkWithNext(draft.items[0].id)
        XCTAssertEqual(draft.supersetLetters, ["A", "A", nil])
        draft.move(draft.items[1].id, by: 1)
        XCTAssertEqual(draft.items.map(\.supersetGroup), [nil, nil, nil], "moved apart: no longer a superset")
        draft.linkWithNext(draft.items[1].id)
        draft.remove(draft.items[2].id)
        XCTAssertEqual(draft.items.map(\.supersetGroup), [nil, nil])
    }

    // MARK: Active workout

    private func supersetWorkout() -> (Workout, WorkoutExercise, WorkoutExercise) {
        let workout = Workout(name: "W")
        context.insert(workout)
        var entries: [WorkoutExercise] = []
        for (index, id) in ["bench", "row"].enumerated() {
            let exercise = Exercise(id: id, name: id, primaryMuscles: ["chest"])
            context.insert(exercise)
            let entry = WorkoutExercise(order: index, exercise: exercise, restSeconds: 90)
            context.insert(entry)
            entry.workout = workout
            entry.supersetGroup = 1
            for row in 0..<2 {
                let set = SetEntry(order: row, weightKg: 60, reps: 10)
                context.insert(set)
                set.workoutExercise = entry
            }
            entries.append(entry)
        }
        try? context.save()
        return (workout, entries[0], entries[1])
    }

    func testTickMovesToTheNextSupersetExerciseWithoutRest() {
        let (workout, bench, row) = supersetWorkout()
        let model = ActiveWorkoutModel(workout: workout, context: context)
        XCTAssertEqual(model.supersetLetter(for: bench), "A")

        let rested = model.complete(bench.sortedSets[0], in: bench, restTimer: RestTimerController())
        XCTAssertFalse(rested)
        XCTAssertEqual(model.expandedExerciseID, row.persistentModelID)
    }

    func testRoundEndsWithRestAndGoesBackToTheFirst() {
        let (workout, bench, row) = supersetWorkout()
        let model = ActiveWorkoutModel(workout: workout, context: context)
        let timer = RestTimerController()
        model.complete(bench.sortedSets[0], in: bench, restTimer: timer)
        let rested = model.complete(row.sortedSets[0], in: row, restTimer: timer)
        XCTAssertTrue(rested)
        XCTAssertEqual(model.expandedExerciseID, bench.persistentModelID)
        XCTAssertEqual(model.upNext?.exerciseName, "bench")
        timer.skip()
    }

    func testRemovingAMemberDissolvesATwoExerciseSuperset() {
        let (workout, bench, row) = supersetWorkout()
        let model = ActiveWorkoutModel(workout: workout, context: context)
        model.remove(row)
        XCTAssertNil(bench.supersetGroup)
    }
}
