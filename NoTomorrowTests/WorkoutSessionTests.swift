import XCTest
import SwiftData
@testable import NoTomorrow

/// The session is the single source of truth for the workout in progress: collapse keeps it, end drops it,
/// finished and discarded workouts are never adopted again, and launch repairs orphans.
@MainActor
final class WorkoutSessionTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        suiteName = "WorkoutSessionTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
    }

    private func makeSession() -> WorkoutSessionController { WorkoutSessionController(defaults: defaults) }

    @discardableResult
    private func workout(_ name: String, startedAt: Date = .now, completedAt: [Date?] = []) -> Workout {
        let workout = Workout(name: name, startedAt: startedAt)
        context.insert(workout)
        if !completedAt.isEmpty {
            let exercise = Exercise(id: "ex-\(UUID().uuidString)", name: "Bench", primaryMuscles: ["chest"])
            context.insert(exercise)
            let entry = WorkoutExercise(order: 0, exercise: exercise)
            context.insert(entry)
            entry.workout = workout
            for (index, date) in completedAt.enumerated() {
                let set = SetEntry(order: index, weightKg: 60, reps: 8)
                set.completedAt = date
                context.insert(set)
                set.workoutExercise = entry
            }
        }
        try? context.save()
        return workout
    }

    // MARK: Collapse / expand / end

    func testCollapseKeepsWorkoutAndModel() {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)
        let model = session.model(for: w, context: context)

        session.collapse(context: context)

        XCTAssertFalse(session.showsActiveWorkout)
        XCTAssertEqual(session.activeWorkoutID, w.id)
        XCTAssertTrue(session.isWorkoutInProgress)
        XCTAssertTrue(session.model === model, "the per-workout UI state must survive a collapse")

        session.expand(restSheet: true)
        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertTrue(session.wantsRestSheet)
    }

    func testModelIsCreatedOncePerWorkout() {
        let session = makeSession()
        let a = workout("A")
        session.begin(a)
        let first = session.model(for: a, context: context)
        XCTAssertTrue(session.model(for: a, context: context) === first)

        let b = workout("B")
        session.begin(b)
        XCTAssertNil(session.model, "a different workout drops the old model")
        XCTAssertFalse(session.model(for: b, context: context) === first)
    }

    func testEndClearsEverything() {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)
        _ = session.model(for: w, context: context)
        session.expand(restSheet: true)

        session.end()

        XCTAssertNil(session.activeWorkoutID)
        XCTAssertNil(session.model)
        XCTAssertFalse(session.showsActiveWorkout)
        XCTAssertFalse(session.wantsRestSheet)
        XCTAssertFalse(session.isWorkoutInProgress)
    }

    func testExpandReplacesAPresenterWhoseCoverNeverAppeared() {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)                 // a Fuel sheet was animating in: UIKit refused the cover
        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertFalse(session.isCoverOnScreen)
        let epoch = session.coverEpoch

        session.expand()                 // the mini bar tap

        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertEqual(session.coverEpoch, epoch + 1, "a fresh presenter presents the flag it finds set")
    }

    func testExpandWithTheCoverUpKeepsItsPresenter() {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)
        session.coverDidAppear()
        let epoch = session.coverEpoch

        session.expand(restSheet: true)

        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertTrue(session.wantsRestSheet)
        XCTAssertEqual(session.coverEpoch, epoch)
        session.coverDidDisappear()
        XCTAssertFalse(session.isCoverOnScreen)
    }

    func testCollapsedWorkoutExpandsWithoutANewPresenter() {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)
        session.coverDidAppear()
        session.collapse(context: context)
        session.coverDidDisappear()
        let epoch = session.coverEpoch

        session.expand()

        XCTAssertTrue(session.showsActiveWorkout)
        XCTAssertEqual(session.coverEpoch, epoch)
    }

    func testDeletedWorkoutIsNotServedFromTheModel() throws {
        let session = makeSession()
        let w = workout("Push A")
        session.begin(w)
        _ = session.model(for: w, context: context)

        context.delete(w)
        try context.save()

        XCTAssertNil(session.workout(in: context), "the cached model's row is gone")
        XCTAssertNil(session.activeWorkout(in: context))
        XCTAssertNil(session.activeWorkoutID, "the session lets go of a missing row")
        XCTAssertNil(session.model)
    }

    func testExpandWithoutWorkoutDoesNothing() {
        let session = makeSession()
        session.expand()
        XCTAssertFalse(session.showsActiveWorkout)
    }

    func testActiveWorkoutIdSurvivesRelaunch() {
        let w = workout("Legs")
        makeSession().begin(w)

        let relaunched = makeSession()
        XCTAssertEqual(relaunched.activeWorkoutID, w.id)
        XCTAssertFalse(relaunched.showsActiveWorkout, "a cold start shows the mini bar, not the full screen")
        XCTAssertEqual(relaunched.workout(in: context)?.id, w.id)
    }

    // MARK: Summary

    func testSummaryHidesMiniBarAndKeepsTheWorkout() {
        let session = makeSession()
        let w = workout("Push A", completedAt: [.now])
        session.begin(w)
        let model = session.model(for: w, context: context)

        model.finish()

        XCTAssertNotNil(w.endedAt)
        XCTAssertTrue(session.isShowingSummary)
        XCTAssertFalse(session.isWorkoutInProgress)
        XCTAssertEqual(session.workout(in: context)?.id, w.id, "the cover keeps the finished workout until Done")
        XCTAssertNil(session.activeWorkout(in: context), "a finished workout is not in progress")
        XCTAssertEqual(session.activeWorkoutID, w.id, "and the lookup must not drop it while its summary is up")
    }

    func testKilledOnSummaryIsLetGoOnRelaunch() {
        let w = workout("Push A", completedAt: [.now])
        let session = makeSession()
        session.begin(w)
        session.model(for: w, context: context).finish()

        let relaunched = makeSession()
        relaunched.restore(in: context)

        XCTAssertNil(relaunched.activeWorkoutID)
        XCTAssertNotNil(w.endedAt)
    }

    // MARK: Adoption

    func testAdoptsNewestUnfinishedWorkout() {
        let session = makeSession()
        workout("Old", startedAt: .now.addingTimeInterval(-7200))
        let newest = workout("New", startedAt: .now.addingTimeInterval(-600))

        XCTAssertEqual(session.activeWorkout(in: context)?.id, newest.id)
        XCTAssertEqual(session.activeWorkoutID, newest.id)
    }

    func testDiscardedWorkoutIsNeverAdoptedAndIsDeleted() async {
        let session = makeSession()
        let w = workout("Empty")
        session.begin(w)

        let deletion = session.discard(w, context: context)

        XCTAssertNil(session.activeWorkoutID)
        XCTAssertFalse(session.showsActiveWorkout)
        XCTAssertNil(session.activeWorkout(in: context), "a workout being discarded must not come back")

        await deletion.value
        let left = (try? context.fetch(FetchDescriptor<Workout>())) ?? []
        XCTAssertTrue(left.isEmpty)
    }

    func testInterruptedDiscardIsFinishedOnRelaunch() {
        let w = workout("Empty")
        let session = makeSession()
        session.begin(w)
        session.discard(w, context: context).cancel()   // the app dies before the delayed delete runs

        let relaunched = makeSession()
        relaunched.restore(in: context)

        XCTAssertNil(relaunched.activeWorkoutID)
        let left = (try? context.fetch(FetchDescriptor<Workout>())) ?? []
        XCTAssertTrue(left.isEmpty)
    }

    // MARK: Orphans

    func testRepairOrphansKeepsSessionWorkoutAndClosesTheRest() {
        let lastSet = Date.now.addingTimeInterval(-3 * 3600)
        let withSets = workout("Yesterday", startedAt: .now.addingTimeInterval(-4 * 3600),
                               completedAt: [lastSet.addingTimeInterval(-300), lastSet, nil])
        let emptyID = workout("Ghost", startedAt: .now.addingTimeInterval(-2 * 3600)).id
        let current = workout("Now", startedAt: .now.addingTimeInterval(-3600))
        let newerID = workout("Newer", startedAt: .now.addingTimeInterval(-60)).id
        let session = makeSession()
        session.begin(current)

        session.repairOrphans(in: context)

        XCTAssertNil(current.endedAt, "the session's workout stays in progress")
        XCTAssertEqual(withSets.endedAt, lastSet, "an orphan with sets ends at its last completed set")
        let ids = Set(((try? context.fetch(FetchDescriptor<Workout>())) ?? []).map(\.id))
        XCTAssertFalse(ids.contains(emptyID), "an orphan without completed sets is deleted")
        XCTAssertFalse(ids.contains(newerID), "only the session's workout is kept, even when a newer one exists")
    }

    func testRepairOrphansKeepsNewestWhenSessionHasNone() {
        let old = workout("Old", startedAt: .now.addingTimeInterval(-7200), completedAt: [.now.addingTimeInterval(-7000)])
        let newest = workout("New", startedAt: .now.addingTimeInterval(-600))
        let session = makeSession()

        session.restore(in: context)

        XCTAssertNotNil(old.endedAt)
        XCTAssertNil(newest.endedAt)
        XCTAssertEqual(session.activeWorkoutID, newest.id)
        XCTAssertFalse(session.showsActiveWorkout)
    }
}
