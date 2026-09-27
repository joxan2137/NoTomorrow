import SwiftUI
import SwiftData
import WidgetKit

/// Publishes the `WidgetSnapshot` the home-screen widgets render, and turns their quick-log taps into meal entries.
/// Spec: `docs/widgets.md`, "Data flow". Driven by `WidgetSyncTask` (RootView): after every SwiftData save, on
/// foreground / background, at midnight.
@MainActor
enum WidgetSync {

    // MARK: Quick-log queue

    /// Inserts the entries the Quick log widget queued while the app was away. Each keeps the tap's id and time, so a
    /// drain that runs twice (or is interrupted before the queue is cleared) never logs one twice.
    @discardableResult
    static func ingestPendingLogs(into context: ModelContext) -> Int {
        let pending = WidgetStore.pendingLogs()
        guard !pending.isEmpty else { return 0 }
        var inserted = 0
        for log in pending {
            let id = log.id
            let existing = FetchDescriptor<MealEntry>(predicate: #Predicate { $0.id == id })
            if ((try? context.fetchCount(existing)) ?? 0) > 0 { continue }
            var food: FoodItem?
            if let foodID = log.food.foodID {
                food = try? context.fetch(FetchDescriptor<FoodItem>(predicate: #Predicate { $0.id == foodID })).first
            }
            let entry = MealEntry(day: log.loggedAt, slot: MealSlot.suggested(at: log.loggedAt), food: food,
                                  customName: food == nil ? log.food.name : nil, grams: log.food.grams,
                                  kcal: log.food.kcal, proteinG: log.food.protein, carbsG: log.food.carbs,
                                  fatG: log.food.fat, isAIEstimate: log.food.isAIEstimate)
            entry.id = log.id
            entry.loggedAt = log.loggedAt
            if let food {
                food.useCount += 1
                food.lastUsedAt = max(food.lastUsedAt ?? .distantPast, log.loggedAt)
            }
            context.insert(entry)
            inserted += 1
        }
        do {
            try context.save()
        } catch {
            return 0   // the queue stays; the next foreground tries again
        }
        // Only what was drained: a tap that landed meanwhile stays queued.
        let drained = Set(pending.map(\.id))
        WidgetStore.setPendingLogs(WidgetStore.pendingLogs().filter { !drained.contains($0.id) })
        return inserted
    }

    // MARK: Snapshot

    /// Rebuilds the snapshot from the store and reloads the widgets when anything they show changed.
    static func publish(from context: ModelContext, now: Date = .now) {
        guard SharedStore.isAvailable else { return }
        var snapshot = makeSnapshot(from: context, now: now)
        let previous = WidgetStore.readSnapshot()
        // A "Logged" check that is still showing survives a rebuild.
        if let last = previous?.lastLogged, now.timeIntervalSince(last.at) < 10 { snapshot.lastLogged = last }
        WidgetStore.writeSnapshot(snapshot)
        var current = snapshot
        var before = previous
        current.generatedAt = .distantPast
        before?.generatedAt = .distantPast
        if current != before { WidgetCenter.shared.reloadAllTimelines() }
    }

    static func makeSnapshot(from context: ModelContext, now: Date = .now) -> WidgetSnapshot {
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        let profile = try? context.fetch(FetchDescriptor<UserProfile>()).first
        let goal = profile?.goal ?? .buildMuscle
        let kcalGoal = Double(profile?.calorieGoal ?? 0)
        let pending = WidgetStore.pendingLogs()

        // Today's totals, plus taps not drained yet (a rebuild in the background must not undo them).
        let todays = (try? context.fetch(FetchDescriptor<MealEntry>(predicate: FuelCalendar.entriesPredicate(for: today)))) ?? []
        let pendingToday = pending.filter { cal.isDate($0.loggedAt, inSameDayAs: today) }.map(\.food)
        var fuel = WidgetSnapshot.Fuel(day: today, kcal: 0, protein: 0, carbs: 0, fat: 0, kcalGoal: kcalGoal)
        for entry in todays {
            fuel.kcal += entry.kcal; fuel.protein += entry.proteinG; fuel.carbs += entry.carbsG; fuel.fat += entry.fatG
        }
        for food in pendingToday {
            fuel.kcal += food.kcal; fuel.protein += food.protein; fuel.carbs += food.carbs; fuel.fat += food.fat
        }

        return WidgetSnapshot(
            generatedAt: now,
            languageOverride: UserDefaults.standard.string(forKey: "nt.language"),
            hasProfile: profile != nil && UserDefaults.standard.bool(forKey: "nt.hasOnboarded"),
            fuel: fuel,
            quickFoods: quickFoods(in: context, now: now),
            calendar: calendarGrid(in: context, today: today, now: now, goal: goal, kcalGoal: kcalGoal,
                                   pendingTodayKcal: pendingToday.reduce(0) { $0 + $1.kcal }),
            week: week(in: context, now: now),
            defaultRestSeconds: profile?.defaultRestSeconds ?? 90,
            lastLogged: nil
        )
    }

    // MARK: Parts

    private static func quickFoods(in context: ModelContext, now: Date) -> [QuickFood] {
        let cal = Calendar.current
        let from = cal.date(byAdding: .day, value: -QuickFood.windowDays, to: cal.startOfDay(for: now)) ?? now
        let lower = FuelCalendar.storedDayBounds(for: from).lower
        let entries = (try? context.fetch(FetchDescriptor<MealEntry>(predicate: #Predicate { $0.day >= lower }))) ?? []
        let candidates = entries.map { entry in
            QuickFood.Candidate(foodID: entry.food?.id, name: entry.displayName, grams: entry.grams, kcal: entry.kcal,
                                protein: entry.proteinG, carbs: entry.carbsG, fat: entry.fatG,
                                isAIEstimate: entry.isAIEstimate, loggedAt: entry.loggedAt)
        }
        return QuickFood.pick(candidates)
    }

    private static func calendarGrid(in context: ModelContext, today: Date, now: Date, goal: TrainingGoal,
                                     kcalGoal: Double, pendingTodayKcal: Double) -> WidgetSnapshot.CalendarGrid {
        let cal = Calendar.current
        let layout = FuelCalendar.layout(today: today)
        var kcalByDay = FuelCalendar.kcalByDay(from: layout.start, in: context)
        if pendingTodayKcal > 0 { kcalByDay[today, default: 0] += pendingTodayKcal }

        let windowStart = min(layout.start, cal.date(byAdding: .day, value: -29, to: today) ?? today)
        let finished = (try? context.fetch(FetchDescriptor<Workout>(
            predicate: #Predicate { $0.endedAt != nil && $0.startedAt >= windowStart }))) ?? []
        let counted = finished.filter { $0.completedSetCount > 0 }
        let trainedDays = Set(counted.map { cal.startOfDay(for: $0.startedAt) })
        let thirtyDaysAgo = cal.date(byAdding: .day, value: -29, to: today) ?? today
        let sessions30 = counted.filter { $0.startedAt >= thirtyDaysAgo && $0.startedAt <= now }.count

        var days: [WidgetSnapshot.CalendarGrid.Day] = []
        var day = layout.start
        while day <= today {
            days.append(.init(date: day, kcal: kcalByDay[day], trained: trainedDays.contains(day)))
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = cal.startOfDay(for: next)
        }
        let tolerance = FuelCalendar.tolerance(for: goal)
        return .init(days: days, under: tolerance.under, over: tolerance.over, kcalGoal: kcalGoal, sessions30: sessions30)
    }

    private static func week(in context: ModelContext, now: Date) -> WidgetSnapshot.Week {
        let schedule = AttendanceService.schedule(in: context)
        let records = AttendanceService.weekRecords(today: now, context: context)
        let days = AttendanceService.currentWeek(schedule: schedule, records: records, today: now).map { day in
            WidgetSnapshot.Week.Day(date: day.date, isGymDay: day.isGymDay,
                                    me: status(day.myState), partner: status(day.partnerState))
        }
        let pairings = (try? context.fetchCount(FetchDescriptor<BroPairing>())) ?? 0
        let isPaired = !AuthStore.shared.needsSignIn && pairings > 0

        var gymMinutes: [Int: Int] = [:]
        for iso in schedule?.weekdays ?? [] { gymMinutes[iso] = schedule?.minuteOfDay(for: iso) }

        let routines = (try? context.fetch(FetchDescriptor<Routine>(sortBy: [SortDescriptor(\.order)]))) ?? []
        var finished = FetchDescriptor<Workout>(predicate: #Predicate { $0.endedAt != nil },
                                                sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        finished.fetchLimit = 50
        let names = ((try? context.fetch(finished)) ?? []).map(\.name)
        let routine = DashboardModel.suggestedRoutine(routines: routines, recentWorkoutNames: names)

        return .init(days: days, isPaired: isPaired, gymMinutes: gymMinutes, routineName: routine?.name)
    }

    private static func status(_ state: DayState) -> WidgetSnapshot.Week.Status {
        switch state {
        case .rest: .rest
        case .planned: .planned
        case .confirmed: .confirmed
        case .attended: .attended
        case .missed: .missed
        case .cancelled: .cancelled
        }
    }
}

// MARK: - Driver

/// Keeps the widgets current from the app shell (`RootView`): drains the quick-log queue and republishes when the app
/// comes forward, republishes on the way to the background, after every save (debounced) and when the day changes.
struct WidgetSyncTask: ViewModifier {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @Environment(StoreLoader.self) private var store
    @Environment(RestTimerController.self) private var restTimer
    @State private var debounce: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .task(id: store.generation) { drainAndPublish() }
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .active:
                    restTimer.syncFromStore()
                    drainAndPublish()
                case .background:
                    publishNow()
                default:
                    break
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: ModelContext.didSave)) { _ in publishSoon() }
            .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in publishSoon() }
            .onReceive(NotificationCenter.default.publisher(for: .widgetQuickLogQueued)) { _ in drainAndPublish() }
    }

    private func drainAndPublish() {
        guard store.isOpen else { return }
        WidgetSync.ingestPendingLogs(into: modelContext)
        publishNow()
    }

    private func publishNow() {
        debounce?.cancel()
        guard store.isOpen else { return }
        WidgetSync.publish(from: modelContext)
    }

    /// Saves come in bursts (a set ticked, a meal moved); one rebuild half a second after the last is enough.
    private func publishSoon() {
        debounce?.cancel()
        debounce = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            publishNow()
        }
    }
}

extension View {
    func widgetSync() -> some View { modifier(WidgetSyncTask()) }
}
