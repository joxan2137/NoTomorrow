import Foundation
import SwiftData
import os

/// The on-device half of "Delete account and data": every object the user made, gone from the store.
///
/// Objects are fetched and deleted one by one, not with `ModelContext.delete(model:)`. That one is a store-level
/// batch delete, and Core Data refuses it for an entity whose inverse it would have to nullify on the way: "Batch
/// delete failed due to mandatory OTO nullify inverse on SetEntry/workoutExercise" (and on WorkoutExercise/exercise
/// for workout exercises and workouts), NSCocoaErrorDomain 134050. Under `try?` that failed without a word, and the
/// workouts and their sets outlived the account, came back as the next account's history and, unfinished, as its
/// running workout.
///
/// Account deletion only asks for the wipe (`markPending`); it runs once the screens that showed the data are gone
/// (`RootView`, a moment after the switch to onboarding) or, at the latest, before onboarding writes anything. A
/// deleted object is detached from its context, and a screen still reading one (the Fuel tab's entries, re-rendered
/// by the deletion itself) crashes. The request is persisted, so an app killed in between wipes at the next launch.
@MainActor
enum LocalDataWipe {
    static let pendingKey = "nt.localWipe.pending"
    /// Long enough for the tab shell to go and the Settings sheet to finish sliding away.
    static let settleDelay: Duration = .milliseconds(800)
    private static let log = Logger(subsystem: "app.notomorrow.ios", category: "wipe")

    static func markPending(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: pendingKey)
    }

    static func isPending(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: pendingKey)
    }

    /// Runs the wipe `markPending` asked for, if any; true when one ran. The request is dropped only once the wipe
    /// is saved, so a failed one is tried again.
    @discardableResult
    static func runPending(in context: ModelContext, defaults: UserDefaults = .standard) -> Bool {
        guard isPending(defaults: defaults) else { return false }
        do {
            try run(in: context)
            defaults.removeObject(forKey: pendingKey)
            // Routines were wiped: the next onboarding seeds the starter ones again.
            defaults.removeObject(forKey: RoutineSeeder.seededKey)
            return true
        } catch {
            log.error("Local data wipe failed: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// Children before parents, so no cascade walks into an object already deleted, then one save.
    /// The bundled exercise library stays (it is the app's, not the user's) but forgets when each was last used;
    /// custom exercises go. Throws when the save fails, with nothing deleted.
    static func run(in context: ModelContext) throws {
        try deleteAll(SetEntry.self, in: context)
        try deleteAll(WorkoutExercise.self, in: context)
        try deleteAll(Workout.self, in: context)
        try deleteAll(RoutineItem.self, in: context)
        try deleteAll(Routine.self, in: context)
        try deleteAll(MealEntry.self, in: context)
        try deleteAll(FoodItem.self, in: context)
        try deleteAll(BodyWeightEntry.self, in: context)
        try deleteAll(HeadsUp.self, in: context)
        try deleteAll(AttendanceRecord.self, in: context)
        try deleteAll(BroPairing.self, in: context)
        try deleteAll(Exercise.self, where: #Predicate<Exercise> { $0.isCustom }, in: context)
        let used = try context.fetch(FetchDescriptor<Exercise>(predicate: #Predicate { $0.lastUsedAt != nil }))
        used.forEach { $0.lastUsedAt = nil }
        try deleteAll(GymSchedule.self, in: context)
        try deleteAll(UserProfile.self, in: context)
        do {
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    /// Rows still in the store per model type (by name), leaving out the bundled exercises that are meant to stay.
    /// Empty after a successful `run`.
    static func leftovers(in context: ModelContext) -> [String: Int] {
        var counts: [String: Int] = [:]
        for type in NoTomorrowSchema.models {
            let count = rowCount(type, in: context)
            if count != 0 { counts[String(describing: type)] = count }
        }
        return counts
    }

    private static func rowCount<T: PersistentModel>(_ type: T.Type, in context: ModelContext) -> Int {
        if T.self == Exercise.self {
            return (try? context.fetchCount(FetchDescriptor<Exercise>(predicate: #Predicate { $0.isCustom }))) ?? -1
        }
        return (try? context.fetchCount(FetchDescriptor<T>())) ?? -1
    }

    private static func deleteAll<T: PersistentModel>(_ type: T.Type, where predicate: Predicate<T>? = nil,
                                                      in context: ModelContext) throws {
        for row in try context.fetch(FetchDescriptor<T>(predicate: predicate)) {
            context.delete(row)
        }
    }
}
