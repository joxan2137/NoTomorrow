import XCTest
import SwiftData
@testable import NoTomorrow

/// Exercise notes carry over as next session's placeholder; RPE is stored per set.
@MainActor
final class ExerciseNotesTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    @discardableResult
    private func workout(_ exercise: Exercise, note: String, daysAgo: Int?) -> (Workout, WorkoutExercise) {
        let workout = Workout(name: "W", startedAt: Date.now.addingTimeInterval(-Double(daysAgo ?? 0) * 86_400))
        context.insert(workout)
        if daysAgo != nil { workout.endedAt = workout.startedAt.addingTimeInterval(3600) }
        let item = WorkoutExercise(order: 0, exercise: exercise)
        context.insert(item)
        item.workout = workout
        item.notes = note
        return (workout, item)
    }

    func testPreviousNoteIsTheNewestNonEmptyOne() {
        let bench = Exercise(id: "bench", name: "Bench", primaryMuscles: ["chest"])
        context.insert(bench)
        workout(bench, note: "Seat 4", daysAgo: 7)
        workout(bench, note: "Seat 5, wide grip", daysAgo: 3)
        workout(bench, note: "  ", daysAgo: 1)
        let (current, item) = workout(bench, note: "", daysAgo: nil)
        try? context.save()
        let model = ActiveWorkoutModel(workout: current, context: context)
        XCTAssertEqual(model.previousNote(for: item), "Seat 5, wide grip")
    }

    func testSetRPEStoresAndClears() {
        let bench = Exercise(id: "bench", name: "Bench", primaryMuscles: ["chest"])
        context.insert(bench)
        let (current, item) = workout(bench, note: "", daysAgo: nil)
        let set = SetEntry(order: 0, weightKg: 80, reps: 5)
        context.insert(set)
        set.workoutExercise = item
        let model = ActiveWorkoutModel(workout: current, context: context)
        model.setRPE(8.5, for: set)
        XCTAssertEqual(set.rpe, 8.5)
        model.setRPE(nil, for: set)
        XCTAssertNil(set.rpe)
        XCTAssertEqual(RPE.options.first, 6)
        XCTAssertEqual(RPE.options.last, 10)
        XCTAssertEqual(RPE.options.count, 9)
    }
}
