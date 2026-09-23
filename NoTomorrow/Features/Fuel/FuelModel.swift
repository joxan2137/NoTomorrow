import Foundation
import SwiftData
import Observation

/// Day-scoped totals for the Fuel home: goals from `UserProfile`, entries for the selected day, protein streak.
/// Refreshed from the model context on appear, on day change and after every sheet closes.
/// Also owns the day boundary: a model showing today follows midnight, and a past day left in the background for
/// more than `snapBackInterval` returns to today. Deletes and copies to today leave one `pendingUndo` for the toast.
@Observable
final class FuelModel {
    struct Goals {
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        static let fallback = Goals(kcal: 2600, protein: 180, carbs: 300, fat: 80)
    }

    var day: Date
    /// The calendar day this model last treated as today. `syncToday` moves it, and `day` with it when it was on today.
    private(set) var today: Date
    private(set) var entries: [MealEntry] = []
    private(set) var goals: Goals = .fallback
    private(set) var trainingGoal: TrainingGoal = .buildMuscle
    private(set) var proteinStreak: Int = 0
    /// The last delete or copy to today, undoable from the toast until `expiresAt` or the next one.
    private(set) var pendingUndo: Undo?

    struct Undo: Identifiable {
        enum Kind {
            case deleted(MealEntry.Snapshot)
            /// Ids of the entries a "Log again today" / "Copy to today" inserted.
            case added([UUID])
        }
        let id = UUID()
        let kind: Kind
        /// When the toast goes. A deadline rather than a timer, so a toast whose timer was cancelled (the Fuel tab was
        /// hidden) still ends on time instead of coming back later with a working Undo.
        var expiresAt: Date = .distantFuture

        /// Seconds left before `expiresAt`, zero once it has passed.
        func remaining(at now: Date = .now) -> TimeInterval { max(0, expiresAt.timeIntervalSince(now)) }

        /// "Entry deleted" / "Added to today"
        var messageKey: String.LocalizationValue {
            switch kind {
            case .deleted: "fuel.entryDeleted"
            case .added: "fuel.addedToToday"
            }
        }
    }

    /// How long the undo toast stays up (longer with VoiceOver, which needs time to reach the button).
    static let undoDuration: Duration = .seconds(4)
    static let undoDurationVoiceOver: Duration = .seconds(10)
    /// The lifetime a new undo gets; the view switches it with VoiceOver.
    @ObservationIgnored var undoLifetime: Duration = FuelModel.undoDuration
    /// The undo whose announcement was already posted, so a toast that reappears is not read out again.
    @ObservationIgnored private var announcedUndoID: UUID?

    /// When the app last went to the background; cleared when it comes back.
    @ObservationIgnored private var backgroundedAt: Date?

    /// A past day left in the background longer than this snaps back to today on return (30 min).
    static let snapBackInterval: TimeInterval = 30 * 60

    /// Every day computation of the Fuel tab, shared with the History sheet. Autoupdating, so after a time-zone
    /// change navigation, `isToday` and copies agree with the grid and the header (tests pin a zone).
    @ObservationIgnored var calendar: Calendar

    init(now: Date = .now, calendar: Calendar = .autoupdatingCurrent) {
        self.calendar = calendar
        let today = calendar.startOfDay(for: now)
        self.day = today
        self.today = today
    }

    // MARK: - Day navigation

    var isToday: Bool { isToday(now: .now) }
    var canGoForward: Bool { shownDay < calendar.startOfDay(for: .now) }

    /// `day` as this zone's calendar day (see `FuelCalendar.dayKey`).
    private var shownDay: Date { FuelCalendar.dayKey(day, calendar: calendar) }

    /// Whether `day` is the calendar day of `now`, read like a stored day (`FuelCalendar.dayKey`) so a midnight kept
    /// from before a time-zone change still counts as that day.
    func isToday(now: Date) -> Bool {
        shownDay == calendar.startOfDay(for: now)
    }

    func goPreviousDay() {
        day = calendar.date(byAdding: .day, value: -1, to: shownDay) ?? day
    }

    func goNextDay() {
        guard canGoForward else { return }
        day = calendar.date(byAdding: .day, value: 1, to: shownDay) ?? day
    }

    /// Jump straight to a day (the History sheet). Future days clamp to today.
    func go(to date: Date, now: Date = .now) {
        let target = min(calendar.startOfDay(for: date), calendar.startOfDay(for: now))
        if target != day { day = target }
    }

    func goToday(now: Date = .now) {
        go(to: now, now: now)
    }

    // MARK: - Day boundary

    /// Midnight rollover, on appear, on `.NSCalendarDayChanged` and on a time-zone change: when the calendar day has
    /// changed since the last call, a model that was showing today follows it; a past day being browsed stays put.
    /// After a zone change `day` and `today` still hold the old zone's midnights; they are re-read as this zone's
    /// calendar days first, so the day on screen keeps its date.
    func syncToday(now: Date = .now) {
        let newToday = calendar.startOfDay(for: now)
        let previousToday = FuelCalendar.dayKey(today, calendar: calendar)
        let rolled = Self.rolledDay(selected: shownDay, previousToday: previousToday, today: newToday)
        if newToday != today { today = newToday }
        if rolled != day { day = rolled }
    }

    func appDidEnterBackground(at now: Date = .now) {
        backgroundedAt = now
    }

    /// Back in the foreground: roll over, then a past day left for longer than `snapBackInterval` returns to today.
    func appWillEnterForeground(at now: Date = .now) {
        let away = backgroundedAt.map { now.timeIntervalSince($0) }
        backgroundedAt = nil
        syncToday(now: now)
        let resumed = Self.resumedDay(selected: day, today: today, awayFor: away)
        if resumed != day { day = resumed }
    }

    /// The day to show after the calendar day moved from `previousToday` to `today`.
    static func rolledDay(selected: Date, previousToday: Date, today: Date) -> Date {
        selected == previousToday || selected > today ? today : selected
    }

    /// The day to show on return from the background after `awayFor` seconds (nil when unknown).
    static func resumedDay(selected: Date, today: Date, awayFor: TimeInterval?) -> Date {
        guard let awayFor, awayFor > snapBackInterval else { return selected }
        return today
    }

    // MARK: - Totals

    var kcalEaten: Double { entries.reduce(0) { $0 + $1.kcal } }
    var proteinEaten: Double { entries.reduce(0) { $0 + $1.proteinG } }
    var carbsEaten: Double { entries.reduce(0) { $0 + $1.carbsG } }
    var fatEaten: Double { entries.reduce(0) { $0 + $1.fatG } }

    var kcalLeft: Double { max(0, goals.kcal - kcalEaten) }
    var ringProgress: Double { goals.kcal > 0 ? min(1, kcalEaten / goals.kcal) : 0 }
    var proteinRemaining: Double { max(0, goals.protein - proteinEaten) }

    func entries(for slot: MealSlot) -> [MealEntry] {
        entries.filter { $0.slot == slot }.sorted { $0.loggedAt < $1.loggedAt }
    }

    func kcal(for slot: MealSlot) -> Double {
        entries(for: slot).reduce(0) { $0 + $1.kcal }
    }

    // MARK: - Persistence

    func refresh(in context: ModelContext) {
        if let profile = try? context.fetch(FetchDescriptor<UserProfile>()).first {
            goals = Goals(kcal: Double(profile.calorieGoal), protein: Double(profile.proteinGoalG),
                          carbs: Double(profile.carbsGoalG), fat: Double(profile.fatGoalG))
            trainingGoal = profile.goal
        }
        // Same day key as the History grid, so a day it shows as logged never opens empty after a time-zone change.
        let dayDescriptor = FetchDescriptor<MealEntry>(
            predicate: FuelCalendar.entriesPredicate(for: day, calendar: calendar),
            sortBy: [SortDescriptor(\.loggedAt)]
        )
        entries = (try? context.fetch(dayDescriptor)) ?? []
        proteinStreak = computeStreak(in: context)
    }

    func delete(_ entry: MealEntry, in context: ModelContext, now: Date = .now) {
        let snapshot = entry.snapshot
        context.delete(entry)
        try? context.save()
        entries.removeAll { $0.id == snapshot.id }
        pendingUndo = makeUndo(.deleted(snapshot), now: now)
    }

    /// "Log again today" on a past day's entry: a copy in the same slot today (new id, logged now).
    func logAgainToday(_ entry: MealEntry, in context: ModelContext, now: Date = .now) {
        copyToToday([entry], in: context, now: now)
    }

    /// "Copy to today" on a past day's meal slot: all of its entries, in their order, into the same slot today.
    func copyToToday(_ slot: MealSlot, in context: ModelContext, now: Date = .now) {
        copyToToday(entries(for: slot), in: context, now: now)
    }

    /// Copies still undoable from the toast are kept: a second tap adds to the same undo, so Undo removes every copy
    /// the "Added to today" toast stands for, not only the last batch.
    private func copyToToday(_ source: [MealEntry], in context: ModelContext, now: Date) {
        guard !source.isEmpty else { return }
        var ids: [UUID] = []
        for (index, entry) in source.enumerated() {
            // A millisecond apart, so the copies keep the source order inside the slot. `now` itself, not a midnight
            // worked out here, so the day is taken once, from this calendar.
            let copy = entry.copy(to: now, loggedAt: now.addingTimeInterval(Double(index) / 1000), calendar: calendar)
            context.insert(copy)
            ids.append(copy.id)
            if let food = entry.food {
                food.useCount += 1
                food.lastUsedAt = now
            }
        }
        try? context.save()
        if let pending = pendingUndo, case .added(let earlier) = pending.kind, pending.remaining(at: now) > 0 {
            ids = earlier + ids
        }
        pendingUndo = makeUndo(.added(ids), now: now)
        refresh(in: context)
    }

    private func makeUndo(_ kind: Undo.Kind, now: Date) -> Undo {
        Undo(kind: kind, expiresAt: now.addingTimeInterval(Self.seconds(undoLifetime)))
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let parts = duration.components
        return TimeInterval(parts.seconds) + TimeInterval(parts.attoseconds) / 1e18
    }

    /// The toast's Undo: re-inserts a deleted entry as it was, or removes the copies just added.
    func undo(in context: ModelContext) {
        guard let undo = pendingUndo else { return }
        pendingUndo = nil
        switch undo.kind {
        case .deleted(let snapshot):
            context.insert(snapshot.restore())
        case .added(let ids):
            let descriptor = FetchDescriptor<MealEntry>(predicate: #Predicate { ids.contains($0.id) })
            for entry in (try? context.fetch(descriptor)) ?? [] { context.delete(entry) }
        }
        try? context.save()
        refresh(in: context)
    }

    /// The toast timed out: drops the undo unless a newer one replaced it meanwhile.
    func expireUndo(_ id: UUID) {
        if pendingUndo?.id == id { pendingUndo = nil }
    }

    /// Drops the undo once its deadline has passed (the toast's timer did not run, e.g. while the tab was hidden).
    func expireUndoIfDue(now: Date = .now) {
        if let undo = pendingUndo, undo.remaining(at: now) <= 0 { pendingUndo = nil }
    }

    /// True the first time it is asked about an undo: its VoiceOver announcement is posted once, not on every return.
    func shouldAnnounce(_ undo: Undo) -> Bool {
        guard announcedUndoID != undo.id else { return false }
        announcedUndoID = undo.id
        return true
    }

    /// Consecutive days (ending today, or yesterday if today is not there yet) with protein at or above goal.
    private func computeStreak(in context: ModelContext) -> Int {
        let today = calendar.startOfDay(for: .now)
        guard let from = calendar.date(byAdding: .day, value: -120, to: today) else { return 0 }
        let lower = FuelCalendar.storedDayBounds(for: from, calendar: calendar).lower
        var descriptor = FetchDescriptor<MealEntry>(predicate: #Predicate { $0.day >= lower })
        descriptor.propertiesToFetch = [\.day, \.proteinG]
        let recent = (try? context.fetch(descriptor)) ?? []
        var proteinByDay: [Date: Double] = [:]
        for entry in recent { proteinByDay[FuelCalendar.dayKey(entry.day, calendar: calendar), default: 0] += entry.proteinG }
        let goal = goals.protein
        guard goal > 0 else { return 0 }

        var cursor = today
        if (proteinByDay[cursor] ?? 0) < goal {
            cursor = calendar.date(byAdding: .day, value: -1, to: cursor) ?? cursor
        }
        var streak = 0
        while (proteinByDay[cursor] ?? 0) >= goal {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: cursor) else { break }
            cursor = previous
        }
        return streak
    }
}
