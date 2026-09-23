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

    /// Starts the suggested routine through the shared `WorkoutStarter` path, or brings back the workout already in
    /// progress. Either way the full screen opens over the current tab (no tab switch).
    func startWorkout(routine: Routine?, context: ModelContext, session: WorkoutSessionController) {
        if session.activeWorkout(in: context) != nil {
            session.expand()
            return
        }
        WorkoutStarter.start(routine.map { .routine($0) } ?? .empty, in: context, session: session)
    }

    /// The routine after the one done most recently, wrapping around; the first routine when no finished workout
    /// came from a routine. `recentWorkoutNames` is newest first; ad-hoc workouts (no matching routine) are skipped,
    /// so they do not shift the rotation.
    static func suggestedRoutine(routines: [Routine], recentWorkoutNames: some Sequence<String>) -> Routine? {
        guard !routines.isEmpty else { return nil }
        for name in recentWorkoutNames {
            if let index = routines.firstIndex(where: { $0.name == name }) {
                return routines[(index + 1) % routines.count]
            }
        }
        return routines.first
    }
}
