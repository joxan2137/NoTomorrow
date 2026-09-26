import XCTest
import SwiftData
@testable import NoTomorrow

/// The active workout's exercise menu: Replace exercise (and Move up / Move down).
@MainActor
final class ActiveWorkoutEditTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private func exercise(_ id: String) -> Exercise {
        let exercise = Exercise(id: id, name: id, primaryMuscles: ["chest"])
        context.insert(exercise)
        return exercise
    }

    /// A workout in progress with one entry per id; `groups` are the superset ids, `done` the completed rows.
    private func makeWorkout(_ ids: [String], groups: [Int?]? = nil, done: [String: Int] = [:]) -> (Workout, [WorkoutExercise]) {
        let workout = Workout(name: "W", startedAt: .now.addingTimeInterval(-600))
        context.insert(workout)
        var entries: [WorkoutExercise] = []
        for (index, id) in ids.enumerated() {
            let entry = WorkoutExercise(order: index, exercise: exercise(id), restSeconds: 120)
            context.insert(entry)
            entry.workout = workout
            entry.supersetGroup = groups?[index]
            for row in 0..<2 {
                let set = SetEntry(order: row, weightKg: 60, reps: 10)
                if row < done[id, default: 0] { set.completedAt = .now }
                context.insert(set)
                set.workoutExercise = entry
            }
            entries.append(entry)
        }
        try? context.save()
        return (workout, entries)
    }

    /// A finished session of `exercise` with completed rows (weight, reps).
    private func finishedSession(of exercise: Exercise, rows: [(Double, Int)]) {
        let workout = Workout(name: "Old", startedAt: .now.addingTimeInterval(-86_400))
        workout.endedAt = .now.addingTimeInterval(-82_800)
        context.insert(workout)
        let entry = WorkoutExercise(order: 0, exercise: exercise)
        context.insert(entry)
        entry.workout = workout
        for (index, row) in rows.enumerated() {
            let set = SetEntry(order: index, weightKg: row.0, reps: row.1)
            set.completedAt = workout.startedAt.addingTimeInterval(Double(index) * 60)
            context.insert(set)
            set.workoutExercise = entry
        }
        try? context.save()
    }

    // MARK: Replace

    func testReplaceKeepsTheSlotSupersetAndRestAndClearsTheNumbers() {
        let (workout, entries) = makeWorkout(["bench", "row", "curl"], groups: [1, 1, nil])
        let model = ActiveWorkoutModel(workout: workout, context: context)
        let row = entries[1]
        row.notes = "seat 4"
        let pulldown = exercise("pulldown")

        XCTAssertTrue(model.canReplace(row))
        XCTAssertTrue(model.replace(row, with: pulldown))

        XCTAssertEqual(model.exercises.map { $0.exercise?.id }, ["bench", "pulldown", "curl"])
        XCTAssertEqual(row.order, 1)
        XCTAssertEqual(row.supersetGroup, 1)
        XCTAssertEqual(model.supersetLetter(for: row), "A")
        XCTAssertEqual(row.restSeconds, 120)
        XCTAssertEqual(row.sets.count, 2, "open rows stay")
        XCTAssertTrue(row.sets.allSatisfy { $0.weightKg == 0 && $0.reps == 0 }, "never done: the old numbers go")
        XCTAssertEqual(row.notes, "")
        XCTAssertTrue(pulldown.usages.contains { $0.persistentModelID == row.persistentModelID })
    }

    func testReplacePrefillsFromTheNewExercisesPreviousSession() {
        let (workout, entries) = makeWorkout(["bench"])
        let incline = exercise("incline")
        finishedSession(of: incline, rows: [(40, 12), (42.5, 10)])
        let model = ActiveWorkoutModel(workout: workout, context: context)

        model.replace(entries[0], with: incline)

        let sets = entries[0].sortedSets
        XCTAssertEqual(sets[0].weightKg, 40)
        XCTAssertEqual(sets[0].reps, 12)
        XCTAssertEqual(sets[1].weightKg, 42.5)
        XCTAssertEqual(sets[1].reps, 10)
    }

    func testReplaceIsBlockedOnceASetIsCompleted() {
        let (workout, entries) = makeWorkout(["bench", "row"], done: ["bench": 1])
        let model = ActiveWorkoutModel(workout: workout, context: context)

        XCTAssertFalse(model.canReplace(entries[0]))
        XCTAssertFalse(model.replace(entries[0], with: exercise("dips")))
        XCTAssertEqual(entries[0].exercise?.id, "bench")
        XCTAssertEqual(entries[0].sortedSets.map(\.weightKg), [60, 60], "nothing touched")
        XCTAssertTrue(model.canReplace(entries[1]))
    }

    func testReplaceWithTheSameExerciseDoesNothing() {
        let (workout, entries) = makeWorkout(["bench"])
        let model = ActiveWorkoutModel(workout: workout, context: context)
        let bench = entries[0].exercise!

        XCTAssertFalse(model.replace(entries[0], with: bench))
        XCTAssertEqual(entries[0].sortedSets.map(\.reps), [10, 10])
    }

    // MARK: Move

    func testMoveSwapsWithTheNeighbourAndStopsAtTheEnds() {
        let (workout, entries) = makeWorkout(["a", "b", "c"])
        let model = ActiveWorkoutModel(workout: workout, context: context)

        XCTAssertFalse(model.canMove(entries[0], by: -1))
        XCTAssertFalse(model.canMove(entries[2], by: 1))
        XCTAssertTrue(model.canMove(entries[1], by: -1))

        model.move(entries[2], by: -1)
        XCTAssertEqual(model.exercises.map { $0.exercise?.id }, ["a", "c", "b"])
        XCTAssertEqual(model.exercises.map(\.order), [0, 1, 2])

        model.move(entries[0], by: -1)
        XCTAssertEqual(model.exercises.map { $0.exercise?.id }, ["a", "c", "b"], "the first cannot go up")
    }

    func testMoveNormalizesSupersets() {
        let (workout, entries) = makeWorkout(["a", "b", "c"], groups: [1, 1, nil])
        let model = ActiveWorkoutModel(workout: workout, context: context)

        model.move(entries[0], by: 1)
        XCTAssertEqual(model.exercises.map { $0.exercise?.id }, ["b", "a", "c"])
        XCTAssertEqual(model.exercises.map(\.supersetGroup), [1, 1, nil], "swapped inside the superset: still one")

        model.move(entries[0], by: 1)
        XCTAssertEqual(model.exercises.map { $0.exercise?.id }, ["b", "c", "a"])
        XCTAssertEqual(model.exercises.map(\.supersetGroup), [nil, nil, nil], "moved apart: no longer a superset")
        XCTAssertNil(model.supersetLetter(for: entries[1]))
    }
}
