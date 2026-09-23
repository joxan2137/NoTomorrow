import SwiftUI
import SwiftData

/// Train tab: routines with Start, empty-workout ghost button, finished-workout history.
/// A workout in progress lives in the mini bar above the tab bar; Start while one runs asks first (in the tab shell).
struct TrainView: View {
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext

    @Query(sort: \Routine.order) private var routines: [Routine]
    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt, order: .reverse)
    private var history: [Workout]
    @Query private var profiles: [UserProfile]

    @State private var selectedWorkout: Workout?

    private var unit: WeightUnit { profiles.first?.units ?? .kg }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                routinesSection
                historySection
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, NT.Spacing.section)
        }
        .ntScreenBackground()
        .workoutDetailSheet($selectedWorkout, unit: unit)
        // RootView imports the library once and seeds right after; this is a no-op once the routines exist.
        .task { RoutineSeeder.seedIfNeeded(context: modelContext) }
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Fmt.longDay(.now)).eyebrow()
            Text("tab.train").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
        }
    }

    // MARK: Routines

    private var routinesSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "workout.routines")
                .padding(.top, NT.Spacing.section)
                .padding(.bottom, 4)
            if routines.isEmpty {
                Text("workout.noRoutines")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .padding(.vertical, 12)
            } else {
                ForEach(routines) { routine in
                    RoutineRow(routine: routine) { requestStart(.routine(routine)) }
                    Hairline()
                }
            }
            GhostButton(title: "workout.startEmpty") { requestStart(.empty) }
            .padding(.top, 16)
        }
    }

    // MARK: Start

    /// Starts right away, or, while a workout is in progress (only one runs at a time), has the tab shell ask:
    /// Resume, Discard it and start new, Cancel (`WorkoutStartConflictDialog`).
    private func requestStart(_ request: WorkoutStarter.Request) {
        WorkoutStarter.requestStart(request, in: modelContext, session: session)
    }

    // MARK: History

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "workout.history",
                          trailing: history.isEmpty ? nil : Text(verbatim: "\(history.count)").monospacedDigit())
                .padding(.top, NT.Spacing.section)
                .padding(.bottom, 4)
            if history.isEmpty {
                Text("dashboard.noSessionsYet")
                    .font(NT.Fonts.subheadline)
                    .foregroundStyle(NT.Colors.ink2)
                    .padding(.vertical, 12)
            } else {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(history) { workout in
                        WorkoutHistoryRow(workout: workout, unit: unit) { selectedWorkout = workout }
                        Hairline()
                    }
                }
            }
        }
    }
}
