import XCTest
import SwiftData
@testable import NoTomorrow

/// Progress → "Muscles this week": `ProgressModel.buildMuscleWeek`, `MuscleWeek`'s top five and "not trained yet"
/// list, and `MuscleHeatView`'s heat steps. Android mirrors these in `ProgressDerivationsTest`.
@MainActor
final class ProgressMuscleWeekTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private let cal = Calendar.current

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: Fixtures

    private var weekStart: Date { cal.startOfISOWeek(for: .now) }

    private func exercise(_ id: String, _ muscles: [String]) -> Exercise {
        let exercise = Exercise(id: id, name: id, primaryMuscles: muscles)
        context.insert(exercise)
        return exercise
    }

    /// A workout started at `start`; each entry is an exercise with `done` completed sets and `open` sets not ticked.
    private func workout(start: Date, finished: Bool = true, _ entries: [(Exercise, done: Int, open: Int)]) {
        let workout = Workout(name: "Workout", startedAt: start)
        if finished { workout.endedAt = start.addingTimeInterval(3600) }
        context.insert(workout)
        for (order, item) in entries.enumerated() {
            let entry = WorkoutExercise(order: order, exercise: item.0)
            context.insert(entry)
            entry.workout = workout
            for index in 0..<(item.done + item.open) {
                let set = SetEntry(order: index, weightKg: 60, reps: 8)
                if index < item.done { set.completedAt = start.addingTimeInterval(Double(index + 1) * 60) }
                context.insert(set)
                set.workoutExercise = entry
            }
        }
        try? context.save()
    }

    private func workouts() -> [Workout] {
        (try? context.fetch(FetchDescriptor<Workout>())) ?? []
    }

    // MARK: buildMuscleWeek

    func testCountsCompletedSetsPerPrimaryMuscleOfFinishedWorkoutsThisWeek() {
        let bench = exercise("bench", ["chest"])
        let squat = exercise("squat", ["quadriceps", "glutes"])
        let curl = exercise("custom-curl", [])
        let thisWeek = weekStart.addingTimeInterval(3600)
        workout(start: thisWeek, [(bench, done: 3, open: 1), (squat, done: 4, open: 0), (curl, done: 2, open: 0)])
        workout(start: weekStart.addingTimeInterval(-3600), [(bench, done: 5, open: 0)])   // last week
        workout(start: thisWeek, finished: false, [(squat, done: 6, open: 0)])            // still running

        let week = ProgressModel.buildMuscleWeek(from: workouts(), since: weekStart)

        XCTAssertEqual(week.setsByMuscle, ["chest": 3, "quadriceps": 4, "glutes": 4])
        XCTAssertEqual(week.totalSets, 9)
    }

    func testNothingThisWeekIsEmpty() {
        let bench = exercise("bench", ["chest"])
        workout(start: weekStart.addingTimeInterval(-86_400), [(bench, done: 3, open: 0)])

        let week = ProgressModel.buildMuscleWeek(from: workouts(), since: weekStart)

        XCTAssertEqual(week, MuscleWeek())
        XCTAssertTrue(week.top.isEmpty)
        XCTAssertTrue(week.notTrainedYet.isEmpty)
    }

    // MARK: MuscleWeek

    func testTopFiveSortsBySetsThenName() {
        let week = MuscleWeek(setsByMuscle: ["quadriceps": 10, "middle back": 7, "lats": 8, "hamstrings": 7,
                                             "glutes": 7, "chest": 2], totalSets: 30)
        XCTAssertEqual(week.top.map { $0.muscle }, ["quadriceps", "lats", "glutes", "hamstrings", "middle back"])
        XCTAssertEqual(week.top.map { $0.sets }, [10, 8, 7, 7, 7])
    }

    func testNotTrainedYetListsTheKeyMusclesAtZeroInOrder() {
        XCTAssertEqual(MuscleWeek(setsByMuscle: ["chest": 3, "lats": 2, "biceps": 4], totalSets: 9).notTrainedYet,
                       ["shoulders", "quadriceps", "hamstrings"])
        // Sets logged, none on a muscle the model knows: every key muscle is still untrained.
        XCTAssertEqual(MuscleWeek(setsByMuscle: [:], totalSets: 2).notTrainedYet, MuscleWeek.keyMuscles)
        let all = Dictionary(uniqueKeysWithValues: MuscleWeek.keyMuscles.map { ($0, 1) })
        XCTAssertEqual(MuscleWeek(setsByMuscle: all, totalSets: 5).notTrainedYet, [])
        XCTAssertEqual(MuscleWeek().notTrainedYet, [])
    }

    func testHeatLevels() {
        let expected = [0: 0, 1: 1, 3: 1, 4: 2, 6: 2, 7: 3, 9: 3, 10: 4, 25: 4, -1: 0]
        for (sets, level) in expected {
            XCTAssertEqual(MuscleHeatView.level(sets: sets), level, "\(sets) sets")
        }
    }
}
