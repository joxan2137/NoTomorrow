import XCTest
import SwiftData
import UserNotifications
@testable import NoTomorrow

/// Finish stamps the end at once, "Edit sets" reopens, the mini bar's wording, and notification / deep-link routing.
@MainActor
final class ActiveWorkoutFlowTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        suiteName = "ActiveWorkoutFlowTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
    }

    /// A workout with one exercise per entry in `sets`; each inner array marks which rows are completed.
    private func workout(sets: [[Bool]]) -> Workout {
        let workout = Workout(name: "Push A", startedAt: .now.addingTimeInterval(-1800))
        context.insert(workout)
        for (order, rows) in sets.enumerated() {
            let exercise = Exercise(id: "ex-\(order)", name: "Exercise \(order)", primaryMuscles: ["chest"])
            context.insert(exercise)
            let entry = WorkoutExercise(order: order, exercise: exercise)
            context.insert(entry)
            entry.workout = workout
            for (index, done) in rows.enumerated() {
                let set = SetEntry(order: index, weightKg: 50, reps: 8)
                set.completedAt = done ? .now : nil
                context.insert(set)
                set.workoutExercise = entry
            }
        }
        try? context.save()
        return workout
    }

    // MARK: Finish

    func testFinishStampsTheEndImmediately() {
        let session = WorkoutSessionController(defaults: defaults)
        let w = workout(sets: [[true, false]])
        session.begin(w)
        let model = session.model(for: w, context: context)
        let finishedAt = Date.now.addingTimeInterval(-5)

        model.finish(now: finishedAt)

        XCTAssertEqual(w.endedAt, finishedAt, "the end must not wait for Done (a kill on the summary would stretch it)")
        XCTAssertTrue(model.showsSummary)

        model.reopen()
        XCTAssertNil(w.endedAt, "Edit sets puts the workout back in progress")
        XCTAssertFalse(model.showsSummary)
        XCTAssertTrue(session.isWorkoutInProgress)

        model.finish()
        model.commitFinish(session: session)
        XCTAssertNotNil(w.endedAt)
        XCTAssertNil(session.activeWorkoutID)
        XCTAssertFalse(session.showsActiveWorkout)
    }

    // MARK: Current exercise

    func testCurrentExerciseIsTheOpenOneElseFirstWithWorkLeft() {
        let w = workout(sets: [[true, true], [true, false], [false]])
        let model = ActiveWorkoutModel(workout: w, context: context)
        let list = model.exercises

        XCTAssertEqual(model.currentExercise?.order, 1, "opens on the first exercise with work left")
        model.toggleExpanded(list[2])
        XCTAssertEqual(model.currentExercise?.order, 2, "the open exercise wins")
        model.toggleExpanded(list[2])
        XCTAssertEqual(model.currentExercise?.order, 1, "nothing open: first with work left")

        let done = workout(sets: [[true], [true]])
        XCTAssertEqual(ActiveWorkoutModel.currentExercise(in: done.sortedExercises, expandedID: nil)?.order, 1,
                       "all done: the last one")
        XCTAssertNil(ActiveWorkoutModel.currentExercise(in: [], expandedID: nil))
    }

    // MARK: Mini bar

    func testMiniBarStateWhileTraining() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let state = WorkoutMiniBarState(startedAt: start, now: start.addingTimeInterval(42 * 60 + 10),
                                        restEnd: nil, restTotal: 90, upNextName: "Squat", currentName: "Bench")
        XCTAssertFalse(state.isResting)
        XCTAssertEqual(state.elapsed, "42:10")
        XCTAssertEqual(state.exerciseName, "Bench")
    }

    func testMiniBarStateWhileResting() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let now = start.addingTimeInterval(3723)
        let state = WorkoutMiniBarState(startedAt: start, now: now, restEnd: now.addingTimeInterval(45),
                                        restTotal: 90, upNextName: "Squat", currentName: "Bench")
        XCTAssertTrue(state.isResting)
        XCTAssertEqual(state.elapsed, "1:02:03")
        XCTAssertEqual(state.restRemaining ?? 0, 45, accuracy: 0.001)
        XCTAssertEqual(state.restFraction, 0.5, accuracy: 0.001)
        XCTAssertEqual(state.exerciseName, "Squat", "while resting the bar names what is up next")

        let over = WorkoutMiniBarState(startedAt: start, now: now, restEnd: now.addingTimeInterval(-1),
                                       restTotal: 90, upNextName: "Squat", currentName: "Bench")
        XCTAssertFalse(over.isResting, "an elapsed rest flips on time, before finishIfElapsed runs")
        XCTAssertEqual(over.exerciseName, "Bench")
    }

    // MARK: Routing

    func testDeepLinks() {
        XCTAssertEqual(AppState.Route(url: URL(string: "notomorrow://workout")!), .activeWorkout)
        XCTAssertEqual(AppState.Route(url: URL(string: "notomorrow://workout/rest")!), .restTimer)
        XCTAssertEqual(AppState.Route(url: RestTimerAttributes.deepLink), .restTimer)
        XCTAssertEqual(AppState.Route(url: URL(string: "NoTomorrow://Bro")!), .bro)
        XCTAssertEqual(AppState.Route(url: URL(string: "notomorrow://settings/")!), .settings)
        XCTAssertEqual(AppState.Route(url: URL(string: "notomorrow://fuel")!), .fuel)
        XCTAssertEqual(AppState.Route(url: URL(string: "notomorrow://today")!), .today)
        XCTAssertNil(AppState.Route(url: URL(string: "notomorrow://fuel/search")!))
        XCTAssertNil(AppState.Route(url: URL(string: "https://notomorrow.app/workout")!))
    }

    func testRestOverNotificationRouting() {
        let rest = RestTimerController.notificationID
        XCTAssertEqual(NotificationRouter.route(forNotification: rest), .activeWorkout)
        XCTAssertNil(NotificationRouter.route(forNotification: "nt.gym.reminder"))

        XCTAssertEqual(NotificationRouter.presentation(forNotification: rest, workoutOnScreen: false), [.banner, .list, .sound],
                       "collapsed: the banner is the only cue besides the haptic")
        XCTAssertEqual(NotificationRouter.presentation(forNotification: rest, workoutOnScreen: true), [],
                       "full workout on screen: haptic only, like before")
        XCTAssertEqual(NotificationRouter.presentation(forNotification: "nt.gym.reminder", workoutOnScreen: false), [])
    }

    func testRouterBuffersUntilConnected() {
        let router = NotificationRouter()
        router.deliver(.activeWorkout)
        var received: [AppState.Route] = []
        router.connect(isWorkoutOnScreen: { false }) { received.append($0) }
        router.deliver(.restTimer)
        XCTAssertEqual(received, [.activeWorkout, .restTimer])
    }
}
