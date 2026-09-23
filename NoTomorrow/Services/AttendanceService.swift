import Foundation
import SwiftData

/// What a single day looks like on the week strip / shared-week card, for one participant.
enum DayState: Equatable {
    case rest
    case planned
    case confirmed
    case attended
    case missed
    case cancelled(reason: String?)

    var isMissedOrCancelled: Bool {
        switch self {
        case .missed, .cancelled: return true
        default: return false
        }
    }
}

/// One column of the Mon…Sun strip.
struct WeekDay: Identifiable, Equatable {
    var date: Date
    var isoWeekday: Int
    var isToday: Bool
    var isGymDay: Bool
    var myState: DayState
    var partnerState: DayState

    var id: Date { date }

    /// Catalog key for the single-letter label ("weekday.mon.short" …).
    var shortLabelKey: String { AttendanceService.shortLabelKey(isoWeekday: isoWeekday) }
}

/// Derives strip/card states from `GymSchedule` + `AttendanceRecord`s and writes my attendance.
/// Pure functions take explicit `today`, so they are testable; the `mark…` functions upsert into SwiftData.
enum AttendanceService {

    // MARK: - Week

    /// Mon…Sun of the ISO week containing `today`.
    static func currentWeek(schedule: GymSchedule?, records: [AttendanceRecord], today: Date = .now) -> [WeekDay] {
        let cal = Calendar.current
        let start = cal.startOfISOWeek(for: today)
        let todayStart = cal.startOfDay(for: today)
        return (0..<7).compactMap { offset in
            guard let date = cal.date(byAdding: .day, value: offset, to: start) else { return nil }
            let iso = offset + 1
            let isGymDay = schedule?.isGymDay(iso) ?? false
            return WeekDay(
                date: date,
                isoWeekday: iso,
                isToday: cal.isDate(date, inSameDayAs: todayStart),
                isGymDay: isGymDay,
                myState: state(for: date, participant: .me, isGymDay: isGymDay, records: records, today: todayStart),
                partnerState: state(for: date, participant: .partner, isGymDay: isGymDay, records: records, today: todayStart)
            )
        }
    }

    /// State of one day for one participant, from the record if any, else from the schedule.
    static func state(for day: Date, participant: Participant, isGymDay: Bool,
                      records: [AttendanceRecord], today: Date = .now) -> DayState {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: day)
        let todayStart = cal.startOfDay(for: today)
        if let record = records.first(where: { $0.participant == participant && cal.isDate($0.day, inSameDayAs: dayStart) }) {
            switch record.status {
            case .attended: return .attended
            case .missed: return .missed
            case .cancelled: return .cancelled(reason: record.reason)
            case .confirmed: return dayStart < todayStart ? .missed : .confirmed
            case .planned: return dayStart < todayStart ? .missed : .planned
            }
        }
        guard isGymDay else { return .rest }
        // No record for a past gym day: the sweep (which respects the account creation date) writes explicit
        // .missed rows, so an absent record means "nothing to show", not a miss.
        return dayStart < todayStart ? .rest : .planned
    }

    // MARK: - Next session

    /// Today's session while it is at most 2 h in the past, otherwise the next gym day (within a week).
    static func nextSession(schedule: GymSchedule?, today: Date = .now) -> (date: Date, minuteOfDay: Int)? {
        guard let schedule, !schedule.weekdays.isEmpty else { return nil }
        let cal = Calendar.current
        let grace: TimeInterval = 2 * 3600
        for offset in 0...7 {
            guard let day = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: today)) else { continue }
            let iso = cal.isoWeekday(for: day)
            guard schedule.isGymDay(iso) else { continue }
            let minute = schedule.minuteOfDay(for: iso)
            let at = sessionDate(day: day, minuteOfDay: minute)
            if offset == 0, at.addingTimeInterval(grace) < today { continue }
            return (at, minute)
        }
        return nil
    }

    /// Start of `day` + `minuteOfDay`.
    static func sessionDate(day: Date, minuteOfDay: Int) -> Date {
        let cal = Calendar.current
        return cal.date(byAdding: .minute, value: minuteOfDay, to: cal.startOfDay(for: day)) ?? day
    }

    // MARK: - Writes (me)

    @discardableResult
    static func markConfirmed(day: Date, context: ModelContext) -> AttendanceRecord {
        let record = upsert(day: day, participant: .me, context: context)
        record.status = .confirmed
        record.updatedAt = .now
        try? context.save()
        return record
    }

    @discardableResult
    static func markAttended(day: Date, context: ModelContext) -> AttendanceRecord {
        let record = upsert(day: day, participant: .me, context: context)
        record.status = .attended
        record.updatedAt = .now
        try? context.save()
        return record
    }

    /// A make-up session on `day` (picked in the Can't make it sheet): the day becomes planned for me, the server's
    /// rule, so both sides agree: an empty day, or one that was cancelled or missed, turns planned; a planned,
    /// confirmed or attended day is left alone. A make-up day that passes untrained is swept to missed.
    @discardableResult
    static func markPlanned(day: Date, context: ModelContext) -> AttendanceRecord {
        let isNew = record(for: day, participant: .me, context: context) == nil
        let record = upsert(day: day, participant: .me, context: context)
        if isNew || record.status == .cancelled || record.status == .missed {
            record.status = .planned
            record.reason = nil
            record.note = nil
            record.makeUpDay = nil
            record.updatedAt = .now
            try? context.save()
        }
        return record
    }

    /// Records that I am not coming. `status` is `.cancelled` for an announced skip (Can't make it sheet);
    /// pass `.missed` for a silent no-show. A day I already trained is never cancelled (the server's rule): the
    /// attended record comes back unchanged. Only a workout edit or delete turns attended into missed.
    @discardableResult
    static func markMissed(day: Date, reason: String?, note: String?, makeUp: Date?,
                           status: AttendanceStatus = .cancelled, context: ModelContext) -> AttendanceRecord {
        let record = upsert(day: day, participant: .me, context: context)
        if status == .cancelled && record.status == .attended { return record }
        record.status = status
        record.reason = reason
        record.note = note.flatMap { $0.isEmpty ? nil : $0 }
        record.makeUpDay = makeUp.map { Calendar.current.startOfDay(for: $0) }
        record.updatedAt = .now
        try? context.save()
        return record
    }

    /// Drops my record for `day`, so the day derives from the schedule again.
    static func clearMine(day: Date, context: ModelContext) {
        guard let record = record(for: day, participant: .me, context: context) else { return }
        context.delete(record)
        try? context.save()
    }

    /// Upsert for either participant (BroService uses it for the partner).
    @discardableResult
    static func upsert(day: Date, participant: Participant, context: ModelContext,
                       scheduledMinuteOfDay: Int? = nil) -> AttendanceRecord {
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: day)
        if let existing = record(for: dayStart, participant: participant, context: context) { return existing }
        let minute = scheduledMinuteOfDay
            ?? schedule(in: context)?.minuteOfDay(for: cal.isoWeekday(for: dayStart))
            ?? 18 * 60
        let record = AttendanceRecord(day: dayStart, participant: participant, scheduledMinuteOfDay: minute)
        context.insert(record)
        return record
    }

    /// Judges the days that ended since the last sweep: a gym day with no record of mine, and any day whose record is
    /// still planned or confirmed (a make-up day included), becomes `.missed`. Each day is judged once, by the
    /// schedule in force the first time the app opened after it (`cursor`), so changing the gym days never turns
    /// past rest days into misses. Bounded by the profile's creation and 30 days back. Call from the dashboard on appear.
    /// The first sweep with a cursor judges yesterday alone, by the schedule in force now: earlier builds already
    /// judged the days before it on every appear (never the day they ran on), and today's schedule must not re-judge
    /// them. The cursor only moves forward, and it moves even without a schedule.
    static func markPastPlannedAsMissed(schedule: GymSchedule?, context: ModelContext, today: Date = .now,
                                        cursor: SweepCursor = SweepCursor()) {
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: today)
        guard let yesterday = cal.date(byAdding: .day, value: -1, to: todayStart) else { return }
        // No cursor yet: as if everything up to the day before yesterday had been judged.
        let sweptThrough = cursor.sweptThrough ?? cal.date(byAdding: .day, value: -1, to: yesterday) ?? yesterday
        defer { if sweptThrough < yesterday { cursor.sweptThrough = yesterday } }

        let lookback = cal.date(byAdding: .day, value: -30, to: todayStart) ?? todayStart
        let createdAt = profileCreatedAt(in: context).map { cal.startOfDay(for: $0) } ?? lookback
        let firstUnswept = cal.date(byAdding: .day, value: 1, to: sweptThrough) ?? todayStart
        let from = max(lookback, createdAt, firstUnswept)
        guard from < todayStart else { return }

        let existing = records(from: from, to: todayStart, context: context)
        var changed = false
        var day = from
        while day < todayStart {
            let iso = cal.isoWeekday(for: day)
            let mine = existing.first { $0.participant == .me && cal.isDate($0.day, inSameDayAs: day) }
            if let mine {
                if mine.status == .planned || mine.status == .confirmed {
                    mine.status = .missed
                    mine.updatedAt = .now
                    changed = true
                }
            } else if let schedule, schedule.isGymDay(iso) {
                let record = AttendanceRecord(day: day, participant: .me,
                                              scheduledMinuteOfDay: schedule.minuteOfDay(for: iso), status: .missed)
                context.insert(record)
                changed = true
            }
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        if changed { try? context.save() }
    }

    /// The last day the sweep has judged, in UserDefaults as a calendar day ("yyyy-MM-dd", `WireDay`), not an instant:
    /// read back in another time zone it is still the same day, so flying west never has a day judged twice (Android
    /// keeps an epoch day). Tests pass their own suite.
    struct SweepCursor {
        static let key = "nt.attendance.sweptThrough"
        let defaults: UserDefaults
        let calendar: Calendar

        init(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
            self.defaults = defaults
            self.calendar = calendar
        }

        /// Start of that day in `calendar`'s time zone.
        var sweptThrough: Date? {
            get {
                // Not `string(forKey:)`: it would turn the legacy number below into a string.
                let stored = defaults.object(forKey: Self.key)
                if let day = stored as? String { return WireDay.date(day, calendar: calendar) }
                // Written as seconds since 2001 by an earlier build of this branch: the day it was in, here.
                guard let seconds = stored as? Double else { return nil }
                return calendar.startOfDay(for: Date(timeIntervalSinceReferenceDate: seconds))
            }
            nonmutating set {
                if let newValue {
                    defaults.set(WireDay.string(newValue, calendar: calendar), forKey: Self.key)
                } else {
                    defaults.removeObject(forKey: Self.key)
                }
            }
        }
    }

    // MARK: - Workout edited or deleted

    /// One write to my attendance after a finished workout moved to another day, was deleted, or stopped counting.
    enum WorkoutDayChange: Equatable {
        case markAttended(Date)
        case markMissed(Date)
        /// A make-up day that is today again goes back to the plan the Can't make it sheet made.
        case markPlanned(Date)
        case clear(Date)
    }

    /// What a workout that counted on `oldDay` and now counts on `newDay` does to my attendance. A workout counts on
    /// its start day when it has a completed set; nil = it did not count / no longer counts (deleted, all unticked).
    /// The new day is marked attended with Finish's rule (any day, scheduled or not, that is not in the future).
    /// Only `.attended` is ever reverted, and only when no other finished workout keeps the old day: a make-up day
    /// (one of my cancellations points at it) returns to its plan, planned today or later and missed once past (what
    /// the sweep would write); a past gym day becomes missed; today or a rest day loses the record and derives from
    /// the schedule again.
    static func workoutDayChanges(oldDay: Date?, newDay: Date?, oldDayStillAttended: Bool,
                                  isGymDay: (Date) -> Bool, myStatus: (Date) -> AttendanceStatus?,
                                  isMakeUpDay: (Date) -> Bool = { _ in false },
                                  today: Date = .now) -> [WorkoutDayChange] {
        let cal = Calendar.current
        let old = oldDay.map { cal.startOfDay(for: $0) }
        let new = newDay.map { cal.startOfDay(for: $0) }
        let todayStart = cal.startOfDay(for: today)
        guard old != new else { return [] }
        var changes: [WorkoutDayChange] = []
        if let new, new <= todayStart, myStatus(new) != .attended {
            changes.append(.markAttended(new))
        }
        if let old, !oldDayStillAttended, myStatus(old) == .attended {
            if isMakeUpDay(old) {
                changes.append(old < todayStart ? .markMissed(old) : .markPlanned(old))
            } else {
                changes.append(old < todayStart && isGymDay(old) ? .markMissed(old) : .clear(old))
            }
        }
        return changes
    }

    /// Applies `workoutDayChanges` for workout `workoutID` (already saved in its new state, or deleted) and returns
    /// them, so the caller can report them to the backend (`AttendanceSync`).
    @discardableResult
    static func applyWorkoutDayChange(from oldDay: Date?, to newDay: Date?, excluding workoutID: UUID,
                                      context: ModelContext, today: Date = .now) -> [WorkoutDayChange] {
        let cal = Calendar.current
        let schedule = schedule(in: context)
        let stillAttended = oldDay.map { hasOtherFinishedWorkout(on: $0, excluding: workoutID, context: context) } ?? false
        let changes = workoutDayChanges(
            oldDay: oldDay, newDay: newDay, oldDayStillAttended: stillAttended,
            isGymDay: { schedule?.isGymDay(cal.isoWeekday(for: $0)) ?? false },
            myStatus: { record(for: $0, participant: .me, context: context)?.status },
            isMakeUpDay: { isMakeUpDay($0, context: context) },
            today: today)
        for change in changes {
            switch change {
            case .markAttended(let day): markAttended(day: day, context: context)
            case .markMissed(let day): markMissed(day: day, reason: nil, note: nil, makeUp: nil, status: .missed, context: context)
            case .markPlanned(let day): restorePlanned(day: day, context: context)
            case .clear(let day): clearMine(day: day, context: context)
            }
        }
        return changes
    }

    /// The status the server should hold after a local change (it has no delete): attended / missed / planned as
    /// written; a cleared gym day is planned again (so the 21:00 skip check can still ask). A cleared rest day has no
    /// server equivalent and is not reported.
    static func wireStatus(for change: WorkoutDayChange, isGymDay: (Date) -> Bool) -> (day: Date, status: AttendanceStatus)? {
        switch change {
        case .markAttended(let day): return (day, .attended)
        case .markMissed(let day): return (day, .missed)
        case .markPlanned(let day): return (day, .planned)
        case .clear(let day): return isGymDay(day) ? (day, .planned) : nil
        }
    }

    /// One of my records (a cancellation with a make-up day) names `day` as its make-up day.
    static func isMakeUpDay(_ day: Date, context: ModelContext) -> Bool {
        let target: Date? = Calendar.current.startOfDay(for: day)
        let d = FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.makeUpDay == target })
        return ((try? context.fetch(d)) ?? []).contains { $0.participant == .me }
    }

    /// My record for `day` back to planned, whatever it held (`markPlanned` leaves an attended day alone).
    private static func restorePlanned(day: Date, context: ModelContext) {
        let record = upsert(day: day, participant: .me, context: context)
        record.status = .planned
        record.reason = nil
        record.note = nil
        record.makeUpDay = nil
        record.updatedAt = .now
        try? context.save()
    }

    /// A finished workout other than `workoutID`, with a completed set, started on `day`.
    private static func hasOtherFinishedWorkout(on day: Date, excluding workoutID: UUID, context: ModelContext) -> Bool {
        let cal = Calendar.current
        let start = cal.startOfDay(for: day)
        guard let end = cal.date(byAdding: .day, value: 1, to: start) else { return false }
        let d = FetchDescriptor<Workout>(predicate: #Predicate {
            $0.endedAt != nil && $0.startedAt >= start && $0.startedAt < end && $0.id != workoutID
        })
        return ((try? context.fetch(d)) ?? []).contains { $0.completedSetCount > 0 }
    }

    // MARK: - Streaks & counts

    /// Days where both of us showed up, out of the days at least one of us did.
    static func sessionsTogether(records: [AttendanceRecord]) -> (together: Int, total: Int) {
        let cal = Calendar.current
        var byDay: [Date: (me: Bool, partner: Bool)] = [:]
        for r in records where r.status == .attended {
            let key = cal.startOfDay(for: r.day)
            var entry = byDay[key] ?? (false, false)
            if r.participant == .me { entry.me = true } else { entry.partner = true }
            byDay[key] = entry
        }
        let together = byDay.values.filter { $0.me && $0.partner }.count
        return (together, byDay.count)
    }

    /// My attended sessions in the last 30 days.
    static func attendedCount(records: [AttendanceRecord], participant: Participant = .me, today: Date = .now) -> Int {
        recent(records, participant: participant, today: today).filter { $0.status == .attended }.count
    }

    /// My missed + cancelled sessions in the last 30 days.
    static func missedCount(records: [AttendanceRecord], participant: Participant = .me, today: Date = .now) -> Int {
        recent(records, participant: participant, today: today).filter { $0.status == .missed || $0.status == .cancelled }.count
    }

    /// Consecutive attended gym days counting back from the most recent one that has passed (planned days ahead are skipped).
    static func currentStreak(records: [AttendanceRecord], participant: Participant = .me, today: Date = .now) -> Int {
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: today)
        let past = records
            .filter { $0.participant == participant && cal.startOfDay(for: $0.day) <= todayStart && $0.status != .planned && $0.status != .confirmed }
            .sorted { $0.day > $1.day }
        var streak = 0
        for r in past {
            if r.status == .attended { streak += 1 } else { break }
        }
        return streak
    }

    private static func recent(_ records: [AttendanceRecord], participant: Participant, today: Date) -> [AttendanceRecord] {
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: today)
        guard let from = cal.date(byAdding: .day, value: -30, to: todayStart) else { return [] }
        return records.filter { $0.participant == participant && $0.day >= from && $0.day <= todayStart }
    }

    // MARK: - Fetching

    static func schedule(in context: ModelContext) -> GymSchedule? {
        var d = FetchDescriptor<GymSchedule>(sortBy: [SortDescriptor(\.updatedAt, order: .reverse)])
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    static func record(for day: Date, participant: Participant, context: ModelContext) -> AttendanceRecord? {
        let dayStart = Calendar.current.startOfDay(for: day)
        let d = FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.day == dayStart })
        return (try? context.fetch(d))?.first { $0.participant == participant }
    }

    /// Records with `from <= day < to` (both participants).
    static func records(from: Date, to: Date, context: ModelContext) -> [AttendanceRecord] {
        let d = FetchDescriptor<AttendanceRecord>(
            predicate: #Predicate { $0.day >= from && $0.day < to },
            sortBy: [SortDescriptor(\.day)]
        )
        return (try? context.fetch(d)) ?? []
    }

    /// Records for the ISO week containing `today`.
    static func weekRecords(today: Date = .now, context: ModelContext) -> [AttendanceRecord] {
        let cal = Calendar.current
        let start = cal.startOfISOWeek(for: today)
        let end = cal.date(byAdding: .day, value: 7, to: start) ?? start
        return records(from: start, to: end, context: context)
    }

    static func allRecords(context: ModelContext) -> [AttendanceRecord] {
        let d = FetchDescriptor<AttendanceRecord>(sortBy: [SortDescriptor(\.day, order: .reverse)])
        return (try? context.fetch(d)) ?? []
    }

    private static func profileCreatedAt(in context: ModelContext) -> Date? {
        var d = FetchDescriptor<UserProfile>(sortBy: [SortDescriptor(\.createdAt)])
        d.fetchLimit = 1
        return try? context.fetch(d).first?.createdAt
    }

    // MARK: - Labels

    /// "weekday.mon.short" … "weekday.sun.short" for ISO weekday 1…7.
    static func shortLabelKey(isoWeekday: Int) -> String {
        let names = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
        let index = min(max(isoWeekday, 1), 7) - 1
        return "weekday.\(names[index]).short"
    }

    /// "weekday.mon" … "weekday.sun" for ISO weekday 1…7.
    static func labelKey(isoWeekday: Int) -> String {
        let names = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
        let index = min(max(isoWeekday, 1), 7) - 1
        return "weekday.\(names[index])"
    }
}
