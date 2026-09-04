import Foundation
import Observation
import SwiftData

/// The next scheduled gym session as the card needs it.
struct DashboardSession: Equatable {
    var date: Date
    var minuteOfDay: Int
    var isToday: Bool
}

/// Presentation flags and the write-side actions of the dashboard. Reads stay in the view's `@Query`s.
@Observable
@MainActor
final class DashboardModel {
    /// The process-wide `BroService` (`BroShared.service`) so the Dashboard, the Bro tab and `CantMakeItSheet`
    /// see the same partner, code and week; tests may inject their own.
    let bro: BroService

    var showsSettings = false
    var showsCantMakeIt = false
    var isConfirming = false

    init(bro: BroService? = nil) {
        self.bro = bro ?? BroShared.service
    }

    // MARK: Bro

    /// Refreshes the partner state now and then every 15 s while the dashboard is on screen
    /// (the mock partner confirms 20 s after you do). Cancelled with the view's `.task`.
    func runBroRefreshLoop(context: ModelContext) async {
        await bro.refresh(in: context)
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            guard !Task.isCancelled else { return }
            await bro.refresh(in: context)
        }
    }

    // MARK: Attendance

    /// "I'm in": writes my `.confirmed` record for today and, when paired, tells the partner.
    func confirmToday(isPaired: Bool, context: ModelContext) async {
        guard !isConfirming else { return }
        isConfirming = true
        defer { isConfirming = false }
        AttendanceService.markConfirmed(day: .now, context: context)
        if isPaired {
            await bro.confirmToday(in: context)
            await bro.refresh(in: context)
        }
    }

    // MARK: Workout

    /// Starts (or resumes) a workout. Builds a `Workout` from `routine` with one row per target set,
    /// weight prefilled from the last completed set of that exercise, then hands it to the session controller
    /// and switches to the Train tab where the active workout is presented.
    func startWorkout(routine: Routine?, context: ModelContext, session: WorkoutSessionController, appState: AppState) {
        if let active = session.activeWorkout(in: context) {
            session.begin(active)
            appState.selectedTab = .train
            return
        }
        let workout = Workout(name: routine?.name ?? String(localized: "workout.untitled"))
        context.insert(workout)
        for item in routine?.sortedItems ?? [] {
            guard let exercise = item.exercise else { continue }
            let entry = WorkoutExercise(order: item.order, exercise: exercise, restSeconds: item.restSeconds)
            entry.workout = workout
            context.insert(entry)
            let lastWeight = Self.lastCompletedWeight(for: exercise) ?? 0
            for index in 0..<max(1, item.targetSets) {
                let set = SetEntry(order: index, weightKg: lastWeight, reps: item.targetReps)
                set.workoutExercise = entry
                context.insert(set)
            }
            exercise.lastUsedAt = .now
        }
        try? context.save()
        session.begin(workout)
        appState.selectedTab = .train
    }

    /// Rotates through the routines by how many workouts have been finished: 0 → first, 1 → second, …
    static func suggestedRoutine(routines: [Routine], finishedCount: Int) -> Routine? {
        guard !routines.isEmpty else { return nil }
        return routines[finishedCount % routines.count]
    }

    /// Weight of the most recently completed working set of `exercise`, in kg.
    static func lastCompletedWeight(for exercise: Exercise) -> Double? {
        exercise.usages
            .flatMap(\.sets)
            .filter { $0.isCompleted && $0.kind != .warmup }
            .max { ($0.completedAt ?? .distantPast) < ($1.completedAt ?? .distantPast) }?
            .weightKg
    }
}
