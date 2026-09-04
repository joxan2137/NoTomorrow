import SwiftUI
import SwiftData

/// "Today" tab. Owns the day boundary: the inner screen is rebuilt (and its day-scoped queries re-created)
/// whenever the calendar day changes, so a dashboard left open overnight does not keep yesterday's data.
@MainActor
struct DashboardView: View {
    @State private var day = Calendar.current.startOfDay(for: .now)
    @State private var model = DashboardModel()

    var body: some View {
        DashboardScreen(day: day, model: model)
            .id(day)
            .onAppear { refreshDay() }
            .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in refreshDay() }
    }

    private func refreshDay() {
        let now = Calendar.current.startOfDay(for: .now)
        if now != day { day = now }
    }
}

/// The actual scrolling screen for one calendar day.
@MainActor
struct DashboardScreen: View {
    let day: Date
    @Bindable var model: DashboardModel

    @Environment(AppState.self) private var appState
    @Environment(WorkoutSessionController.self) private var session
    @Environment(\.modelContext) private var modelContext

    @Query private var profiles: [UserProfile]
    @Query(sort: \GymSchedule.updatedAt, order: .reverse) private var schedules: [GymSchedule]
    @Query private var pairings: [BroPairing]
    @Query(sort: \Routine.order) private var routines: [Routine]
    @Query private var weekRecords: [AttendanceRecord]
    @Query private var todayMeals: [MealEntry]
    @Query private var finishedWorkouts: [Workout]
    @Query private var activeWorkouts: [Workout]

    init(day: Date, model: DashboardModel) {
        self.day = day
        self.model = model
        let cal = Calendar.current
        let weekStart = cal.startOfISOWeek(for: day)
        let weekEnd = cal.date(byAdding: .day, value: 7, to: weekStart) ?? weekStart
        _weekRecords = Query(filter: #Predicate<AttendanceRecord> { $0.day >= weekStart && $0.day < weekEnd },
                             sort: \AttendanceRecord.day)
        _todayMeals = Query(filter: #Predicate<MealEntry> { $0.day == day }, sort: \MealEntry.loggedAt)
        _finishedWorkouts = Query(filter: #Predicate<Workout> { $0.endedAt != nil },
                                  sort: \Workout.startedAt, order: .reverse)
        _activeWorkouts = Query(filter: #Predicate<Workout> { $0.endedAt == nil })
    }

    // MARK: Derived

    private var profile: UserProfile? { profiles.first }
    private var schedule: GymSchedule? { schedules.first }
    private var isPaired: Bool { model.bro.isPaired || !pairings.isEmpty }
    private var partnerName: String? { model.bro.partner?.name ?? pairings.first?.partnerName }

    private var week: [WeekDay] {
        AttendanceService.currentWeek(schedule: schedule, records: weekRecords, today: day)
    }

    private var today: WeekDay? { week.first { $0.isToday } }

    private var nextSession: DashboardSession? {
        guard let next = AttendanceService.nextSession(schedule: schedule, today: .now) else { return nil }
        return DashboardSession(date: next.date, minuteOfDay: next.minuteOfDay,
                                isToday: Calendar.current.isDate(next.date, inSameDayAs: day))
    }

    private var suggestedRoutine: Routine? {
        DashboardModel.suggestedRoutine(routines: routines, finishedCount: finishedWorkouts.count)
    }

    private func record(_ participant: Participant) -> AttendanceRecord? {
        weekRecords.first { $0.participant == participant && Calendar.current.isDate($0.day, inSameDayAs: day) }
    }

    // MARK: Body

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                WeekStripView(days: week)
                    .padding(.top, NT.Spacing.section)
                NextSessionCard(
                    session: nextSession,
                    routineName: suggestedRoutine?.name,
                    isPaired: isPaired,
                    partnerName: partnerName,
                    myState: today?.myState ?? .rest,
                    myTime: record(.me)?.updatedAt,
                    partnerState: today?.partnerState ?? .rest,
                    partnerTime: record(.partner)?.updatedAt,
                    hasActiveWorkout: !activeWorkouts.isEmpty,
                    isConfirming: model.isConfirming,
                    onConfirm: confirm,
                    onStart: startWorkout,
                    onCantMakeIt: { model.showsCantMakeIt = true }
                )
                .padding(.top, NT.Spacing.section)
                FuelSummaryRow(totals: FuelTotals(entries: todayMeals), goals: FuelGoals(profile: profile)) {
                    appState.selectedTab = .fuel
                }
                .padding(.top, 26)
                Hairline()
                    .padding(.top, NT.Spacing.section)
                LastSessionRow(workout: finishedWorkouts.first, units: profile?.units ?? .kg) {
                    appState.selectedTab = .train
                }
                .padding(.top, 18)
            }
            .padding(.horizontal, NT.Spacing.screenH)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
        .ntScreenBackground()
        .sheet(isPresented: $model.showsSettings) { SettingsView() }
        .sheet(isPresented: $model.showsCantMakeIt) {
            CantMakeItSheet(sessionDay: nextSession?.date ?? day,
                            minuteOfDay: nextSession?.minuteOfDay ?? (schedule?.defaultMinuteOfDay ?? 0),
                            routineName: suggestedRoutine?.name) {
                Task { await model.bro.refresh(in: modelContext) }
            }
        }
        .onAppear {
            AttendanceService.markPastPlannedAsMissed(schedule: schedule, context: modelContext, today: .now)
            RoutineSeeder.seedIfNeeded(context: modelContext)
        }
        .task { await model.runBroRefreshLoop(context: modelContext) }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Fmt.longDay(day)).eyebrow()
                Text("tab.today").font(NT.Fonts.largeTitle).foregroundStyle(NT.Colors.ink)
            }
            Spacer(minLength: 0)
            Button { model.showsSettings = true } label: {
                Avatar(initial: avatarInitial)
                    .frame(width: NT.Size.control, height: NT.Size.control)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("dashboard.settings"))
            .padding(.bottom, 4)
        }
    }

    private var avatarInitial: String {
        let name = profile?.name.trimmingCharacters(in: .whitespaces) ?? ""
        return name.isEmpty ? "–" : name
    }

    // MARK: Actions

    private func confirm() {
        Task { await model.confirmToday(isPaired: isPaired, context: modelContext) }
    }

    private func startWorkout() {
        model.startWorkout(routine: suggestedRoutine, context: modelContext, session: session, appState: appState)
    }
}
