import XCTest
import SwiftData
@testable import NoTomorrow

/// Exercise types: which library exercises are timed or body weight, the "Track as" override, and seconds taking
/// the place of reps for a timed exercise (ticking, Previous, records, the editor).
@MainActor
final class ExerciseTrackingTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private func plank() -> Exercise {
        let exercise = Exercise(id: "Plank", name: "Plank", primaryMuscles: ["abdominals"], equipment: "body only")
        context.insert(exercise)
        return exercise
    }

    /// A workout with one exercise; rows are (kg, reps, seconds, completed).
    private func workout(_ exercise: Exercise, start: Date, finished: Bool,
                         rows: [(Double, Int, Int, Bool)]) -> (Workout, WorkoutExercise) {
        let workout = Workout(name: "Core", startedAt: start)
        if finished { workout.endedAt = start.addingTimeInterval(3600) }
        context.insert(workout)
        let entry = WorkoutExercise(order: 0, exercise: exercise)
        context.insert(entry)
        entry.workout = workout
        for (index, row) in rows.enumerated() {
            let set = SetEntry(order: index, weightKg: row.0, reps: row.1, seconds: row.2)
            set.completedAt = row.3 ? start.addingTimeInterval(Double(index + 1) * 60) : nil
            context.insert(set)
            set.workoutExercise = entry
        }
        return (workout, entry)
    }

    // MARK: Inference

    func testLibraryTypesFollowIdCategoryAndEquipment() {
        func inferred(_ id: String, _ equipment: String?, _ category: String = "strength", custom: Bool = false) -> ExerciseTracking {
            ExerciseTracking.inferred(id: id, equipment: equipment, category: category, isCustom: custom)
        }
        XCTAssertEqual(inferred("Plank", "body only"), .duration)
        XCTAssertEqual(inferred("nt_copenhagen_side_plank", "body only"), .duration)
        XCTAssertEqual(inferred("Pushups", "body only"), .bodyweightReps)
        XCTAssertEqual(inferred("Decline_Push-Up", nil), .bodyweightReps)
        XCTAssertEqual(inferred("Parallel_Bar_Dip", "other"), .bodyweightReps)
        XCTAssertEqual(inferred("Barbell_Bench_Press_-_Medium_Grip", "barbell"), .weightReps)
        XCTAssertEqual(inferred("Farmers_Walk", "other", "strongman"), .weightReps)
        XCTAssertEqual(inferred("Hamstring_Stretch", nil, "stretching"), .duration)
        XCTAssertEqual(inferred("Rowing,_Stationary", "machine", "cardio"), .duration)
        // A custom exercise has no equipment to go by: weight × reps until the user says otherwise.
        XCTAssertEqual(inferred("custom-1", nil, custom: true), .weightReps)
    }

    func testTrackAsStoresOnlyAChoiceThatDiffersFromTheDefault() {
        let exercise = plank()
        XCTAssertEqual(exercise.tracking, .duration)
        XCTAssertNil(exercise.trackingRaw)
        exercise.tracking = .bodyweightReps
        XCTAssertEqual(exercise.tracking, .bodyweightReps)
        XCTAssertEqual(exercise.trackingRaw, "bodyweightReps")
        exercise.tracking = .duration
        XCTAssertNil(exercise.trackingRaw)
    }

    // MARK: Sets

    func testSecondsAreStoredOnlyWhenThere() {
        XCTAssertNil(SetEntry(order: 0, reps: 8).durationSeconds)
        let set = SetEntry(order: 0, seconds: 45)
        XCTAssertEqual(set.durationSeconds, 45)
        set.seconds = 0
        XCTAssertNil(set.durationSeconds)
    }

    func testATimedSetCountsItsSecondsNotItsReps() {
        let start = Date(timeIntervalSinceReferenceDate: 1_000_000)
        // A plank logged before exercise types, as "0 × 60", next to a real 45 s hold.
        let (_, entry) = workout(plank(), start: start, finished: true, rows: [(0, 60, 0, true), (0, 0, 45, true)])
        let sets = entry.sortedSets
        XCTAssertEqual(sets.map(\.amount), [0, 45])
        XCTAssertEqual(sets.map(\.estimatedOneRepMax), [0, 0])
    }

    func testATimedRowPrefillsSecondsFromPrevious() {
        let exercise = plank()
        let start = Date(timeIntervalSinceReferenceDate: 1_000_000)
        _ = workout(exercise, start: start, finished: true, rows: [(0, 0, 40, true), (0, 0, 35, true)])
        let (today, entry) = workout(exercise, start: start.addingTimeInterval(86_400), finished: false,
                                     rows: [(0, 0, 0, false), (0, 0, 0, false)])
        let model = ActiveWorkoutModel(workout: today, context: context)
        let sets = entry.sortedSets
        for set in sets { model.prefillFromPrevious(set, in: entry) }
        XCTAssertEqual(sets.map(\.seconds), [40, 35])
        XCTAssertEqual(sets.map(\.reps), [0, 0])
    }

    func testRepsTypedIntoATimedRowDoNotLogIt() {
        let start = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let (today, entry) = workout(plank(), start: start, finished: false, rows: [(0, 12, 0, false)])
        let model = ActiveWorkoutModel(workout: today, context: context)
        let set = entry.sortedSets[0]

        model.complete(set, in: entry, restTimer: RestTimerController())
        XCTAssertFalse(set.isCompleted)

        set.seconds = 30
        model.complete(set, in: entry, restTimer: RestTimerController())
        XCTAssertTrue(set.isCompleted)
        XCTAssertTrue(set.isPR, "the first hold is the exercise's first record")
    }

    // MARK: Records

    func testTheLongestHoldAtAWeightIsASetRecord() {
        func row(_ id: Int, at minute: Double, kg: Double = 0, reps: Int = 0, seconds: Int) -> RecordService.RecordRow {
            RecordService.RecordRow(id: id, group: id, order: 0, completedAt: Date(timeIntervalSinceReferenceDate: minute * 60),
                                    kind: .normal, weightKg: kg, reps: reps, seconds: seconds, tracking: .duration)
        }
        let flags = RecordService.rebuildFlags([
            row(1, at: 1, seconds: 45), row(2, at: 2, seconds: 40), row(3, at: 3, seconds: 60),
            row(4, at: 4, reps: 99, seconds: 0), row(5, at: 5, kg: 10, seconds: 30),
        ])
        XCTAssertEqual(flags[AnyHashable(1)], RecordService.RecordFlags(isPR: true, isSetRecord: false))
        XCTAssertEqual(flags[AnyHashable(2)], RecordService.RecordFlags(isPR: false, isSetRecord: false))
        XCTAssertEqual(flags[AnyHashable(3)], RecordService.RecordFlags(isPR: false, isSetRecord: true))
        // No seconds: not a logged hold, whatever its reps.
        XCTAssertEqual(flags[AnyHashable(4)], RecordService.RecordFlags(isPR: false, isSetRecord: false))
        // Heavier than ever: a PR, as for a weighted exercise.
        XCTAssertEqual(flags[AnyHashable(5)], RecordService.RecordFlags(isPR: true, isSetRecord: false))
    }

    // MARK: Editor

    func testAnEditorRowOfATimedExerciseIsLoggedBySeconds() {
        var row = SetDraft(id: UUID(), sourceID: nil, kind: .normal, weightKg: 0, reps: 5, tracking: .duration,
                           isDone: true, originalCompletedAt: nil)
        XCTAssertFalse(row.isLogged)
        row.seconds = 30
        XCTAssertTrue(row.isLogged)
    }

    // MARK: Formatting

    func testHoldsReadAsSecondsThenMinutes() {
        XCTAssertEqual(Fmt.hold(45), "45\u{00A0}s")
        XCTAssertEqual(Fmt.hold(90), "1:30")
        XCTAssertEqual(Fmt.set(0, 0, seconds: 90, tracking: .duration), "1:30")
        // A row logged as reps keeps showing its reps under a weighted type.
        XCTAssertEqual(Fmt.set(80, 8, seconds: 0, tracking: .weightReps), Fmt.set(80, 8))
    }
}
