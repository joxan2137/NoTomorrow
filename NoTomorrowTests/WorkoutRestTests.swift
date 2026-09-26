import XCTest
import SwiftData
@testable import NoTomorrow

/// The active workout's per-exercise Rest timer menu: its options, the single checkmark, and what a pick stores.
@MainActor
final class WorkoutRestTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: Options

    func testDefaultComesFirstThenTheRoutineLengths() {
        let options = WorkoutRest.options(current: 90, defaultSeconds: 90)
        XCTAssertEqual(options.first, WorkoutRest.Option(seconds: 90, isDefault: true))
        XCTAssertEqual(options.dropFirst().map(\.seconds), [30, 45, 60, 75, 90, 120, 150, 180, 240, 300])
        XCTAssertFalse(options.dropFirst().contains { $0.isDefault })
    }

    func testAnOddCurrentLengthGetsItsOwnRowInOrder() {
        let options = WorkoutRest.options(current: 100, defaultSeconds: 90)
        XCTAssertEqual(options.dropFirst().map(\.seconds), [30, 45, 60, 75, 90, 100, 120, 150, 180, 240, 300])
        // The default is not duplicated when it is itself an odd length.
        let heavy = WorkoutRest.options(current: 130, defaultSeconds: 130)
        XCTAssertEqual(heavy.filter { $0.seconds == 130 }.count, 1)
    }

    func testExactlyOneOptionIsChecked() {
        func checked(current: Int, defaultSeconds: Int) -> [WorkoutRest.Option] {
            WorkoutRest.options(current: current, defaultSeconds: defaultSeconds)
                .filter { WorkoutRest.isChecked($0, current: current, defaultSeconds: defaultSeconds) }
        }
        XCTAssertEqual(checked(current: 90, defaultSeconds: 90), [WorkoutRest.Option(seconds: 90, isDefault: true)],
                       "the default length ticks Default, not the 1:30 row")
        XCTAssertEqual(checked(current: 60, defaultSeconds: 90), [WorkoutRest.Option(seconds: 60, isDefault: false)])
        XCTAssertEqual(checked(current: 100, defaultSeconds: 90), [WorkoutRest.Option(seconds: 100, isDefault: false)])
    }

    // MARK: Model

    private func model(exerciseID: String, rest: Int) -> (ActiveWorkoutModel, WorkoutExercise) {
        let workout = Workout(name: "Push A")
        context.insert(workout)
        let exercise = Exercise(id: exerciseID, name: exerciseID, primaryMuscles: ["chest"])
        context.insert(exercise)
        let entry = WorkoutExercise(order: 0, exercise: exercise, restSeconds: rest)
        context.insert(entry)
        entry.workout = workout
        let set = SetEntry(order: 0, weightKg: 50, reps: 8)
        context.insert(set)
        set.workoutExercise = entry
        try? context.save()
        return (ActiveWorkoutModel(workout: workout, context: context), entry)
    }

    func testDefaultFollowsTheRestSettingAndHeavyLifts() {
        context.insert(UserProfile(name: "A", defaultRestSeconds: 120))
        try? context.save()
        let (curlModel, curl) = model(exerciseID: "Dumbbell_Curl", rest: 120)
        XCTAssertEqual(curlModel.defaultRestSeconds(for: curl), 120)
        let (squatModel, squat) = model(exerciseID: "Barbell_Squat", rest: 150)
        XCTAssertEqual(squatModel.defaultRestSeconds(for: squat), 150, "heavy compounds rest 30 s longer")
    }

    func testPickingALengthStoresItOnThisWorkoutExercise() {
        let (model, entry) = model(exerciseID: "Dumbbell_Curl", rest: 90)
        model.setRest(45, for: entry)
        XCTAssertEqual(entry.restSeconds, 45)
        model.setRest(0, for: entry)
        XCTAssertEqual(entry.restSeconds, 45, "a zero length is ignored (the timer never runs shorter than 5 s)")
        model.setRest(model.defaultRestSeconds(for: entry), for: entry)
        XCTAssertEqual(entry.restSeconds, 90)
    }
}
