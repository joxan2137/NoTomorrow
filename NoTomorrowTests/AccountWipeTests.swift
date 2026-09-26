import XCTest
import SwiftData
@testable import NoTomorrow

/// "Delete account and data" on this phone: after the wipe every model type is empty (the bundled exercise library
/// stays, forgetting its use), in the context and in a fresh one on the same store, and a relaunch adopts no workout.
@MainActor
final class AccountWipeTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""
    private var storeURL: URL!

    override func setUpWithError() throws {
        // On disk, like the app's store: the batch delete this replaces behaved differently from an in-memory one.
        storeURL = FileManager.default.temporaryDirectory.appendingPathComponent("wipe-\(UUID().uuidString).store")
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, url: storeURL)])
        suiteName = "AccountWipeTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
        for suffix in ["", "-shm", "-wal"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: storeURL.path + suffix))
        }
    }

    /// One of everything, related the way the app relates them: finished and unfinished workouts with sets on a
    /// bundled and a custom exercise, a routine, meals on a saved food, weight, bro and attendance rows.
    @discardableResult
    private func seedEverything() throws -> (unfinished: Workout, bundled: Exercise) {
        context.insert(UserProfile(name: "Old", bodyWeightKg: 90))
        context.insert(GymSchedule())
        let bundled = Exercise(id: "Barbell_Squat", name: "Squat", primaryMuscles: ["quadriceps"])
        let custom = Exercise(id: "custom-1", name: "Sled push", primaryMuscles: ["quadriceps"], isCustom: true)
        context.insert(bundled)
        context.insert(custom)

        let routine = Routine(name: "Legs")
        context.insert(routine)
        let item = RoutineItem(order: 0, exercise: bundled)
        context.insert(item)
        item.routine = routine

        func workout(_ name: String, endedAt: Date?) -> Workout {
            let workout = Workout(name: name, startedAt: .now.addingTimeInterval(-3600))
            workout.endedAt = endedAt
            context.insert(workout)
            for (order, exercise) in [bundled, custom].enumerated() {
                let entry = WorkoutExercise(order: order, exercise: exercise)
                context.insert(entry)
                entry.workout = workout
                for index in 0..<3 {
                    let set = SetEntry(order: index, weightKg: 100, reps: 5)
                    set.completedAt = endedAt == nil && index > 0 ? nil : .now
                    set.isPR = index == 0
                    context.insert(set)
                    set.workoutExercise = entry
                }
            }
            return workout
        }
        _ = workout("Pull A", endedAt: .now.addingTimeInterval(-60))
        _ = workout("Legs", endedAt: .now.addingTimeInterval(-30))
        let unfinished = workout("Push A", endedAt: nil)
        bundled.lastUsedAt = .now

        let food = FoodItem(id: "off:5902003060560", name: "Zurek", source: .openFoodFacts,
                            kcalPer100: 43, proteinPer100: 1, carbsPer100: 5, fatPer100: 2)
        context.insert(food)
        context.insert(MealEntry(day: .now, slot: .snack, food: food, grams: 100, kcal: 43, proteinG: 1, carbsG: 5, fatG: 2))
        context.insert(MealEntry(day: .now, slot: .lunch, customName: "Obiad", grams: 300, kcal: 600,
                                 proteinG: 30, carbsG: 60, fatG: 20))
        context.insert(BodyWeightEntry(day: .now, kg: 90))
        context.insert(BodyMeasurement(day: .now, kind: .waist, value: 84))
        context.insert(BroPairing(partnerId: "p1", partnerName: "Bro", myCode: "ABC123"))
        context.insert(AttendanceRecord(day: .now, participant: .me, scheduledMinuteOfDay: 1080, status: .attended))
        context.insert(HeadsUp(fromMe: false, kind: .letsGo, text: "Let's go", sessionDay: .now))
        try context.save()
        return (unfinished, bundled)
    }

    func testSeedCoversEveryModelType() throws {
        try seedEverything()
        let seeded = LocalDataWipe.leftovers(in: context)
        XCTAssertEqual(Set(seeded.keys), Set(NoTomorrowSchema.models.map { String(describing: $0) }),
                       "every @Model type has rows before the wipe, so the wipe test covers them all")
    }

    func testWipeEmptiesEveryModelType() throws {
        let (_, bundled) = try seedEverything()

        try LocalDataWipe.run(in: context)

        XCTAssertEqual(LocalDataWipe.leftovers(in: context), [:])
        // Read back through a fresh context: the deletes were saved, not only staged.
        let fresh = ModelContext(container)
        XCTAssertEqual(LocalDataWipe.leftovers(in: fresh), [:])
        XCTAssertEqual(try fresh.fetchCount(FetchDescriptor<Workout>()), 0)
        XCTAssertEqual(try fresh.fetchCount(FetchDescriptor<WorkoutExercise>()), 0)
        XCTAssertEqual(try fresh.fetchCount(FetchDescriptor<SetEntry>()), 0)
        // The bundled library stays, without the old account's "last used".
        let library = try fresh.fetch(FetchDescriptor<Exercise>())
        XCTAssertEqual(library.map(\.id), [bundled.id])
        XCTAssertNil(library.first?.lastUsedAt)
        XCTAssertTrue(library.first?.usages.isEmpty ?? false)
    }

    func testWipeSurvivesAReopenedStore() throws {
        try seedEverything()
        try LocalDataWipe.run(in: context)
        container = nil

        let schema = Schema(NoTomorrowSchema.models)
        let reopened = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, url: storeURL)])
        XCTAssertEqual(LocalDataWipe.leftovers(in: reopened.mainContext), [:])
        container = reopened
    }

    func testRelaunchAfterWipeAdoptsNoWorkout() throws {
        let (unfinished, _) = try seedEverything()
        let session = WorkoutSessionController(defaults: defaults)
        session.begin(unfinished)
        _ = session.model(for: unfinished, context: context)
        session.collapse(context: context)
        // A discard still pending (its delayed delete not run yet) is persisted too.
        let empty = Workout(name: "Empty")
        context.insert(empty)
        try context.save()
        session.discard(empty, context: context).cancel()
        XCTAssertNotNil(defaults.stringArray(forKey: "nt.workout.discarding"))

        session.forgetAll()
        try LocalDataWipe.run(in: context)

        XCTAssertFalse(session.hasActiveWorkout)
        XCTAssertNil(session.model)
        XCTAssertNil(defaults.string(forKey: "nt.workout.active"))
        XCTAssertNil(defaults.stringArray(forKey: "nt.workout.discarding"))
        XCTAssertNil(session.activeWorkout(in: context), "nothing left to adopt")

        let relaunched = WorkoutSessionController(defaults: defaults)
        relaunched.restore(in: context)
        XCTAssertFalse(relaunched.hasActiveWorkout)
        XCTAssertFalse(relaunched.isWorkoutInProgress)
        XCTAssertNil(relaunched.activeWorkout(in: ModelContext(container)))
    }

    func testPendingWipeRunsOnceAndSurvivesARelaunch() throws {
        try seedEverything()
        XCTAssertFalse(LocalDataWipe.runPending(in: context, defaults: defaults), "nothing asked for")
        XCTAssertFalse(LocalDataWipe.leftovers(in: context).isEmpty)

        FavoriteExercises.toggle("Barbell_Squat", defaults: defaults)
        LocalDataWipe.markPending(defaults: defaults)
        // The app is killed before the wipe ran: the request is still there at the next launch.
        XCTAssertTrue(LocalDataWipe.isPending(defaults: UserDefaults(suiteName: suiteName)!))

        XCTAssertTrue(LocalDataWipe.runPending(in: context, defaults: defaults))
        XCTAssertEqual(LocalDataWipe.leftovers(in: context), [:])
        XCTAssertFalse(LocalDataWipe.isPending(defaults: defaults), "done once")
        XCTAssertEqual(FavoriteExercises.ids(defaults: defaults), [], "starred exercises go with the data")

        // A new account's data is never touched by a finished request.
        context.insert(UserProfile(name: "Tester", bodyWeightKg: 82))
        try context.save()
        XCTAssertFalse(LocalDataWipe.runPending(in: context, defaults: defaults))
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<UserProfile>()), 1)
    }

    func testDelayedDiscardAfterAWipeTouchesNothing() async throws {
        let session = WorkoutSessionController(defaults: defaults)
        let empty = Workout(name: "Empty")
        context.insert(empty)
        try context.save()
        let deletion = session.discard(empty, context: context)

        session.forgetAll()
        try LocalDataWipe.run(in: context)
        await deletion.value

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Workout>()), 0)
        XCTAssertNil(defaults.stringArray(forKey: "nt.workout.discarding"))
    }
}
