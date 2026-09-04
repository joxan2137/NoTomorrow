import SwiftUI
import SwiftData

/// Train tab: resume banner, routines with Start, empty-workout ghost button, finished-workout history.
struct TrainView: View {
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext

    @Query(sort: \Routine.order) private var routines: [Routine]
    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt, order: .reverse)
    private var history: [Workout]
    @Query(filter: #Predicate<Workout> { $0.endedAt == nil }, sort: \Workout.startedAt, order: .reverse)
    private var unfinished: [Workout]
    @Query private var profiles: [UserProfile]

    @State private var selectedWorkout: Workout?

    private var unit: WeightUnit { profiles.first?.units ?? .kg }
    private var activeWorkout: Workout? { unfinished.first }

    var body: some View {
        @Bindable var session = session
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let active = activeWorkout {
                    ResumeWorkoutBanner(workout: active) { session.begin(active) }
                        .padding(.top, 18)
                }
                routinesSection
                historySection
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, NT.Spacing.section)
        }
        .ntScreenBackground()
        .sheet(item: $selectedWorkout) { workout in
            WorkoutDetailSheet(workout: workout, unit: unit)
        }
        .task {
            await ExerciseLibrary.shared.importIfNeeded(into: modelContext)
            RoutineSeeder.seedIfNeeded(context: modelContext)
        }
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
                    RoutineRow(routine: routine) {
                        WorkoutStarter.start(routine: routine, in: modelContext, session: session)
                    }
                    Hairline()
                }
            }
            GhostButton(title: "workout.startEmpty") {
                WorkoutStarter.startEmpty(in: modelContext, session: session)
            }
            .padding(.top, 16)
        }
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

// MARK: - Resume banner

/// The one card on this screen: an unfinished workout the user can jump back into.
struct ResumeWorkoutBanner: View {
    var workout: Workout
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            NTCard {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Circle().fill(NT.Colors.ember).frame(width: 8, height: 8)
                            Text("workout.inProgress").eyebrow(NT.Colors.ember)
                        }
                        Text("dashboard.resumeWorkout")
                            .font(NT.Fonts.headline)
                            .foregroundStyle(NT.Colors.ink)
                        TimelineView(.periodic(from: .now, by: 60)) { context in
                            Text(verbatim: "\(workout.name) · \(Fmt.duration(context.date.timeIntervalSince(workout.startedAt)))")
                                .font(NT.Fonts.footnote)
                                .foregroundStyle(NT.Colors.ink2)
                                .tabular()
                        }
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(NT.Colors.ink3)
                }
            }
        }
        .buttonStyle(PressScale())
    }
}
