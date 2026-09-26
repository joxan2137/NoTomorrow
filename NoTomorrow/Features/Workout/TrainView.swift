import SwiftUI
import SwiftData

/// Train tab: the "Up next" card (the routine Today suggests, with Start), the other routines, the empty-workout ghost
/// button, and finished-workout history grouped by week.
/// A workout in progress lives in the mini bar above the tab bar; Start while one runs asks first (in the tab shell).
struct TrainView: View {
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext

    @Query(sort: \Routine.order) private var routines: [Routine]
    @Query(filter: #Predicate<Workout> { $0.endedAt != nil }, sort: \Workout.startedAt, order: .reverse)
    private var history: [Workout]
    @Query private var profiles: [UserProfile]

    @State private var selectedWorkout: Workout?
    @State private var routineEdit: RoutineEditRequest?
    @State private var routineToDelete: Routine?

    private var unit: WeightUnit { profiles.first?.units ?? .kg }

    /// The routine the Today card suggests (`DashboardModel.suggestedRoutine`): the one after the last done.
    private var upNextRoutine: Routine? {
        DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: history.lazy.map(\.name))
    }

    var body: some View {
        let upNext = upNextRoutine
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let upNext {
                    upNextCard(upNext)
                        .padding(.top, NT.Spacing.section)
                }
                routinesSection(excluding: upNext)
                historySection
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, NT.Spacing.section)
        }
        .ntScreenBackground()
        .workoutDetailSheet($selectedWorkout, unit: unit)
        .sheet(item: $routineEdit) { RoutineEditorSheet(request: $0) }
        .alert("routine.deleteConfirm", isPresented: Binding(get: { routineToDelete != nil },
                                                             set: { if !$0 { routineToDelete = nil } })) {
            Button("routine.delete", role: .destructive) {
                if let routine = routineToDelete { RoutineStore.delete(routine, in: modelContext) }
                routineToDelete = nil
            }
            Button("common.cancel", role: .cancel) { routineToDelete = nil }
        }
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

    // MARK: Up next

    /// The screen's one focal card: the routine's exercises with their targets and Start, or, while a workout is in
    /// progress, Resume, which brings that workout back full screen (as the mini bar does).
    private func upNextCard(_ routine: Routine) -> some View {
        let items = routine.sortedItems.filter { $0.exercise != nil }
        let inProgress = session.isWorkoutInProgress
        let eyebrow: LocalizedStringKey = inProgress ? "workout.inProgress" : "train.upNext"
        return FocalCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(eyebrow).eyebrow(NT.Colors.ember)
                    Spacer(minLength: 8)
                    Text(WorkoutStrings.exercises(items.count))
                        .font(NT.Fonts.caption).foregroundStyle(NT.Colors.ink2).tabular()
                }
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(routine.name)
                        .font(NT.Fonts.title1).foregroundStyle(NT.Colors.ink).lineLimit(1)
                    Spacer(minLength: 8)
                    if !inProgress {
                        Button { routineEdit = .edit(routine) } label: {
                            Text("common.edit").font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink2)
                                .frame(minHeight: NT.Size.control)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 8)
                VStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        if index > 0 { Hairline() }
                        upNextLine(item)
                    }
                }
                .padding(.top, 6)
                PrimaryButton(title: inProgress ? "dashboard.resumeWorkout" : "workout.start",
                              height: NT.Size.cardButton) {
                    if inProgress { session.expand() } else { requestStart(.routine(routine)) }
                }
                .padding(.top, 16)
            }
        }
        .contextMenu { routineMenu(routine) }
    }

    /// Long-press actions on a routine: Edit, Duplicate, Move up / down, Delete.
    @ViewBuilder
    private func routineMenu(_ routine: Routine) -> some View {
        let index = routines.firstIndex { $0.persistentModelID == routine.persistentModelID } ?? 0
        Button("routine.edit", systemImage: "pencil") { routineEdit = .edit(routine) }
        Button("routine.duplicate", systemImage: "plus.square.on.square") {
            RoutineStore.duplicate(routine, in: modelContext)
        }
        if index > 0 {
            Button("workout.edit.moveUp", systemImage: "arrow.up") { RoutineStore.move(routine, by: -1, in: modelContext) }
        }
        if index < routines.count - 1 {
            Button("workout.edit.moveDown", systemImage: "arrow.down") { RoutineStore.move(routine, by: 1, in: modelContext) }
        }
        Button("routine.delete", systemImage: "trash", role: .destructive) { routineToDelete = routine }
    }

    /// "Bench Press ······ 3 × 8": the exercise and its target sets × reps.
    private func upNextLine(_ item: RoutineItem) -> some View {
        HStack(spacing: 12) {
            Text(item.exercise?.localizedName ?? "")
                .font(NT.Fonts.subheadline).foregroundStyle(NT.Colors.ink).lineLimit(1)
            Spacer(minLength: 8)
            Text(verbatim: "\(item.targetSets) × \(item.targetReps)")
                .font(NT.Fonts.footnote).foregroundStyle(NT.Colors.ink2).tabular()
        }
        .padding(.vertical, 10)
    }

    // MARK: Routines

    /// Every routine but the one on the Up next card.
    private func routinesSection(excluding upNext: Routine?) -> some View {
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
                ForEach(routines.filter { $0.persistentModelID != upNext?.persistentModelID }) { routine in
                    RoutineRow(routine: routine, onStart: { requestStart(.routine(routine)) },
                               onEdit: { routineEdit = .edit(routine) })
                        .contextMenu { routineMenu(routine) }
                    Hairline()
                }
            }
            VStack(spacing: 10) {
                GhostButton(title: "routine.new", systemImage: "plus") { routineEdit = .new() }
                GhostButton(title: "workout.startEmpty") { requestStart(.empty) }
            }
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

    /// This week, last week and earlier, newest first (weeks without a workout are left out).
    private var historyGroups: [(week: HistoryWeek, items: [Workout])] {
        HistoryWeek.grouped(history, date: { $0.startedAt }, now: .now)
    }

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
                    ForEach(historyGroups, id: \.week) { group in
                        Text(group.week.title).eyebrow()
                            .padding(.top, 12)
                        ForEach(group.items) { workout in
                            WorkoutHistoryRow(workout: workout, unit: unit) { selectedWorkout = workout }
                            Hairline()
                        }
                    }
                }
            }
        }
    }
}

/// The history group of a finished workout: this ISO week (Monday first), the one before, or earlier.
enum HistoryWeek: CaseIterable {
    case thisWeek, lastWeek, earlier

    var title: LocalizedStringKey {
        switch self {
        case .thisWeek: "workout.history.thisWeek"
        case .lastWeek: "workout.history.lastWeek"
        case .earlier: "workout.history.earlier"
        }
    }

    static func of(_ date: Date, now: Date, calendar: Calendar = .current) -> HistoryWeek {
        let thisWeek = calendar.startOfISOWeek(for: now)
        if date >= thisWeek { return .thisWeek }
        let lastWeek = calendar.date(byAdding: .day, value: -7, to: thisWeek) ?? thisWeek
        return date >= lastWeek ? .lastWeek : .earlier
    }

    /// `items` split by week, newest group first, each keeping the order it came in; empty weeks are left out.
    static func grouped<T>(_ items: [T], date: (T) -> Date, now: Date,
                           calendar: Calendar = .current) -> [(week: HistoryWeek, items: [T])] {
        let byWeek = Dictionary(grouping: items) { of(date($0), now: now, calendar: calendar) }
        return allCases.compactMap { week in byWeek[week].map { (week: week, items: $0) } }
    }
}
