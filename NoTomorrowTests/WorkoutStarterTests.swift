import XCTest
import SwiftData
@testable import NoTomorrow

/// One start path for Train and Today, the one-workout-at-a-time gate, and the Rest length setting driving rest.
@MainActor
final class WorkoutStarterTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        suiteName = "WorkoutStarterTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
    }

    private func makeSession() -> WorkoutSessionController { WorkoutSessionController(defaults: defaults) }

    private func exercise(_ id: String) -> Exercise {
        let exercise = Exercise(id: id, name: id, primaryMuscles: ["chest"])
        context.insert(exercise)
        return exercise
    }

    private func routine(_ name: String, items: [(Exercise, Int)]) -> Routine {
        let routine = Routine(name: name)
        context.insert(routine)
        for (index, (exercise, rest)) in items.enumerated() {
            let item = RoutineItem(order: index, exercise: exercise, targetSets: 2, targetReps: 10, restSeconds: rest)
            context.insert(item)
            item.routine = routine
        }
        return routine
    }

    private func profile(rest: Int) {
        context.insert(UserProfile(name: "J", defaultRestSeconds: rest))
    }

    // MARK: Rest length

    func testRestFollowsProfileDefault() {
        profile(rest: 150)
        let bench = exercise("Barbell_Bench_Press_-_Medium_Grip")
        let curl = exercise("Barbell_Curl")
        let r = routine("Push A", items: [(bench, RoutineSeeder.inheritRest), (curl, RoutineSeeder.inheritRest)])

        let workout = WorkoutStarter.start(routine: r, in: context, session: makeSession())

        let rests = workout.sortedExercises.map(\.restSeconds)
        XCTAssertEqual(rests, [180, 150], "heavy compounds rest 30 s longer than the default")
    }

    func testRestWithoutProfileIsNinetySeconds() {
        let squat = exercise("Barbell_Squat")
        let raise = exercise("Side_Lateral_Raise")
        let r = routine("Legs", items: [(squat, RoutineSeeder.inheritRest), (raise, RoutineSeeder.inheritRest)])

        let workout = WorkoutStarter.start(routine: r, in: context, session: makeSession())

        XCTAssertEqual(workout.sortedExercises.map(\.restSeconds), [120, 90])
    }

    func testCustomRoutineItemRestIsKept() {
        profile(rest: 60)
        let row = exercise("Bent_Over_Barbell_Row")
        let r = routine("Pull A", items: [(row, 75)])

        let workout = WorkoutStarter.start(routine: r, in: context, session: makeSession())

        XCTAssertEqual(workout.sortedExercises.first?.restSeconds, 75)
    }

    func testAppendedExerciseUsesProfileDefault() {
        profile(rest: 45)
        let workout = Workout(name: "Workout")
        context.insert(workout)

        let entry = WorkoutStarter.append(exercise("Triceps_Pushdown"), to: workout, order: 0, in: context)

        XCTAssertEqual(entry.restSeconds, 45)
    }

    func testHeavyRestIsCappedAtTheSettingMaximum() {
        XCTAssertEqual(RoutineSeeder.restSeconds(for: "Barbell_Deadlift", defaultRest: 600), 600)
        XCTAssertEqual(RoutineSeeder.restSeconds(for: "Barbell_Deadlift", defaultRest: 90), 120)
        XCTAssertEqual(RoutineSeeder.restSeconds(for: "Leg_Press", defaultRest: 90), 90)
    }

    func testLegacySeededRestSwitchesToInheritOnce() {
        let bench = exercise("Barbell_Bench_Press_-_Medium_Grip")
        let press = exercise("Barbell_Shoulder_Press")
        let custom = exercise("Pullups")
        let r = routine("Push A", items: [(bench, 120), (press, 90), (custom, 75)])
        try? context.save()

        RoutineSeeder.inheritDefaultRestIfNeeded(context: context, defaults: defaults)

        XCTAssertEqual(r.sortedItems.map(\.restSeconds), [0, 0, 75])

        r.sortedItems[1].restSeconds = 90
        RoutineSeeder.inheritDefaultRestIfNeeded(context: context, defaults: defaults)
        XCTAssertEqual(r.sortedItems[1].restSeconds, 90, "the pass runs once")
    }

    // MARK: Prefill

    func testNeverDoneExerciseGetsTargetReps() {
        let r = routine("Push A", items: [(exercise("Incline_Dumbbell_Press"), 0)])

        let workout = WorkoutStarter.start(routine: r, in: context, session: makeSession())

        let sets = workout.sortedExercises.first?.sortedSets ?? []
        XCTAssertEqual(sets.count, 2)
        XCTAssertEqual(sets.map(\.reps), [10, 10])
        XCTAssertEqual(sets.map(\.weightKg), [0, 0])
    }

    func testDoneExerciseCopiesLastSessionRowByRow() {
        let press = exercise("Barbell_Shoulder_Press")
        let earlier = Workout(name: "Push A", startedAt: .now.addingTimeInterval(-86_400))
        earlier.endedAt = .now.addingTimeInterval(-82_800)
        context.insert(earlier)
        let entry = WorkoutExercise(order: 0, exercise: press)
        context.insert(entry)
        entry.workout = earlier
        for (index, (kg, reps)) in [(50.0, 8), (52.5, 6)].enumerated() {
            let set = SetEntry(order: index, weightKg: kg, reps: reps)
            set.completedAt = earlier.startedAt.addingTimeInterval(Double(index) * 120)
            context.insert(set)
            set.workoutExercise = entry
        }
        let r = routine("Push A", items: [(press, 0)])

        let workout = WorkoutStarter.start(routine: r, in: context, session: makeSession())

        let sets = workout.sortedExercises.first?.sortedSets ?? []
        XCTAssertEqual(sets.map(\.weightKg), [50, 52.5])
        XCTAssertEqual(sets.map(\.reps), [8, 6])
    }

    // MARK: Gate

    func testGateIsClearWithoutWorkout() {
        guard case .clear = WorkoutStarter.gate(in: context, session: makeSession()) else {
            return XCTFail("nothing is running")
        }
    }

    func testGateBlocksWhileAWorkoutRuns() {
        let session = makeSession()
        let running = WorkoutStarter.startEmpty(in: context, session: session)

        guard case .blocked(let active, let canDiscard) = WorkoutStarter.gate(in: context, session: session) else {
            return XCTFail("a second workout must not start silently")
        }
        XCTAssertEqual(active.id, running.id)
        XCTAssertTrue(canDiscard)

        let r = routine("Push A", items: [(exercise("Barbell_Curl"), 0)])
        let set = WorkoutStarter.append(exercise("Pullups"), to: running, order: 0, setCount: 1, in: context).sortedSets.first
        set?.completedAt = .now
        try? context.save()
        guard case .blocked(_, let canDiscardNow) = WorkoutStarter.gate(in: context, session: session) else {
            return XCTFail("still running")
        }
        XCTAssertFalse(canDiscardNow, "a workout with completed sets can only be resumed")

        let result = WorkoutStarter.discardAndStart(running, then: .routine(r), in: context, session: session)
        XCTAssertEqual(result.id, running.id, "discard is refused once sets are done")
        XCTAssertEqual(session.activeWorkoutID, running.id)
    }

    func testDiscardAndStartReplacesAnEmptyWorkout() async throws {
        let session = makeSession()
        let empty = WorkoutStarter.startEmpty(in: context, session: session)
        let emptyID = empty.id
        let r = routine("Pull A", items: [(exercise("Barbell_Curl"), 0)])

        let started = WorkoutStarter.discardAndStart(empty, then: .routine(r), in: context, session: session)

        XCTAssertNotEqual(started.id, emptyID)
        XCTAssertEqual(started.name, "Pull A")
        XCTAssertEqual(session.activeWorkoutID, started.id)
        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertEqual(session.activeWorkout(in: context)?.id, started.id)

        try await Task.sleep(for: .milliseconds(900))   // the discarded row goes after the cover animation
        let ids = ((try? context.fetch(FetchDescriptor<Workout>())) ?? []).map(\.id)
        XCTAssertEqual(ids, [started.id])
    }

    func testDiscardForNewStartDropsTheWorkoutAtOnce() async throws {
        let session = makeSession()
        let empty = WorkoutStarter.startEmpty(in: context, session: session)

        XCTAssertTrue(WorkoutStarter.discardForNewStart(empty, in: context, session: session))

        XCTAssertNil(session.activeWorkoutID, "the mini bar goes before the new workout starts")
        XCTAssertFalse(session.showsActiveWorkout)
        XCTAssertNil(session.activeWorkout(in: context), "the discarded workout is never adopted again")
        try await Task.sleep(for: .milliseconds(900))   // the row goes after the cover animation
        XCTAssertTrue(((try? context.fetch(FetchDescriptor<Workout>())) ?? []).isEmpty)
    }

    func testDiscardForNewStartRefusesAWorkoutWithDoneSets() {
        let session = makeSession()
        let running = WorkoutStarter.startEmpty(in: context, session: session)
        let set = WorkoutStarter.append(exercise("Pullups"), to: running, order: 0, setCount: 1, in: context).sortedSets.first
        set?.completedAt = .now
        try? context.save()
        session.collapse(context: context)

        XCTAssertFalse(WorkoutStarter.discardForNewStart(running, in: context, session: session))

        XCTAssertEqual(session.activeWorkoutID, running.id)
        XCTAssertTrue(session.showsActiveWorkout, "the running workout comes back instead")
    }

    // MARK: Start conflict (Train)

    func testRequestStartWithoutAWorkoutStartsAtOnce() {
        let session = makeSession()
        WorkoutStarter.requestStart(.empty, in: context, session: session)
        XCTAssertTrue(session.hasActiveWorkout)
        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertNil(session.startConflict)
    }

    func testRequestStartWhileAWorkoutRunsAsksTheShell() {
        let session = makeSession()
        let running = WorkoutStarter.startEmpty(in: context, session: session)
        session.collapse(context: context)
        let r = routine("Push A", items: [(exercise("Barbell_Curl"), 0)])

        WorkoutStarter.requestStart(.routine(r), in: context, session: session)

        let conflict = try? XCTUnwrap(session.startConflict)
        XCTAssertEqual(conflict?.active.id, running.id)
        XCTAssertEqual(conflict?.activeName, running.name)
        XCTAssertEqual(conflict?.canDiscard, true)
        XCTAssertEqual(session.activeWorkoutID, running.id, "nothing starts until the user decides")
        XCTAssertFalse(session.showsActiveWorkout)
    }

    func testResolveByDiscardingSwapsAtOnceAndHidesTheOldBar() async throws {
        let session = makeSession()
        let legs = WorkoutStarter.startEmpty(in: context, session: session)
        session.collapse(context: context)
        XCTAssertTrue(session.showsMiniBar)
        let r = routine("Push A", items: [(exercise("Barbell_Curl"), 0)])
        WorkoutStarter.requestStart(.routine(r), in: context, session: session)
        let conflict = try XCTUnwrap(session.startConflict)

        let started = WorkoutStarter.resolveByDiscarding(conflict, in: context, session: session)

        XCTAssertNil(session.startConflict, "the dialog goes")
        XCTAssertEqual(started.name, "Push A")
        XCTAssertEqual(session.activeWorkoutID, started.id)
        XCTAssertTrue(session.showsActiveWorkout, "the new workout's cover comes up right away")
        XCTAssertFalse(session.showsMiniBar, "the old bar leaves at once, and no new bar shows under the rising cover")
        XCTAssertNotEqual(session.activeWorkout(in: context)?.id, legs.id)

        try await Task.sleep(for: WorkoutSessionController.swapBarDelay + .milliseconds(200))
        XCTAssertTrue(session.showsMiniBar, "back (under the cover) once the swap is over")
        let ids = ((try? context.fetch(FetchDescriptor<Workout>())) ?? []).map(\.id)
        XCTAssertEqual(ids, [started.id], "the discarded workout is deleted")
    }

    func testCollapseRightAfterASwapShowsTheBar() {
        let session = makeSession()
        let empty = WorkoutStarter.startEmpty(in: context, session: session)
        session.collapse(context: context)
        WorkoutStarter.requestStart(.empty, in: context, session: session)
        let conflict = WorkoutStarter.Conflict(active: empty, canDiscard: true, request: .empty)

        WorkoutStarter.resolveByDiscarding(conflict, in: context, session: session)
        XCTAssertFalse(session.showsMiniBar)
        session.collapse(context: context)

        XCTAssertTrue(session.showsMiniBar)
    }

    func testResolveByDiscardingRefusesAWorkoutWithDoneSets() {
        let session = makeSession()
        let running = WorkoutStarter.startEmpty(in: context, session: session)
        let set = WorkoutStarter.append(exercise("Pullups"), to: running, order: 0, setCount: 1, in: context).sortedSets.first
        set?.completedAt = .now
        try? context.save()
        session.collapse(context: context)
        let conflict = WorkoutStarter.Conflict(active: running, canDiscard: false, request: .empty)

        let result = WorkoutStarter.resolveByDiscarding(conflict, in: context, session: session)

        XCTAssertEqual(result.id, running.id)
        XCTAssertEqual(session.activeWorkoutID, running.id)
        XCTAssertTrue(session.showsActiveWorkout, "the running workout comes back instead")
        XCTAssertTrue(session.showsMiniBar)
    }

    // MARK: Today's suggestion

    func testSuggestedRoutineFollowsTheLastRoutineDone() {
        let routines = [Routine(name: "Push A", order: 0), Routine(name: "Pull A", order: 1), Routine(name: "Legs", order: 2)]

        XCTAssertEqual(DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: [])?.name, "Push A")
        XCTAssertEqual(DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: ["Push A"])?.name, "Pull A")
        XCTAssertEqual(DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: ["Legs", "Pull A"])?.name, "Push A")
        XCTAssertEqual(DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: ["Trening", "Pull A"])?.name, "Legs",
                       "an ad-hoc workout does not shift the rotation")
        XCTAssertNil(DashboardModel.suggestedRoutine(routines: [], recentWorkoutNames: ["Push A"]))
    }
}
