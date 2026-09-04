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

    /// Records that I am not coming. `status` is `.cancelled` for an announced skip (Can't make it sheet);
    /// pass `.missed` for a silent no-show.
    @discardableResult
    static func markMissed(day: Date, reason: String?, note: String?, makeUp: Date?,
                           status: AttendanceStatus = .cancelled, context: ModelContext) -> AttendanceRecord {
        let record = upsert(day: day, participant: .me, context: context)
        record.status = status
        record.reason = reason
        record.note = note.flatMap { $0.isEmpty ? nil : $0 }
        record.makeUpDay = makeUp.map { Calendar.current.startOfDay(for: $0) }
        record.updatedAt = .now
        try? context.save()
        return record
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

    /// Turns every past gym day (since the profile was created, before today) that has no attended/cancelled record into `.missed`.
    /// Call from the dashboard on appear.
    static func markPastPlannedAsMissed(schedule: GymSchedule?, context: ModelContext, today: Date = .now) {
        guard let schedule, !schedule.weekdays.isEmpty else { return }
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: today)
        let lookback = cal.date(byAdding: .day, value: -30, to: todayStart) ?? todayStart
        let createdAt = profileCreatedAt(in: context).map { cal.startOfDay(for: $0) } ?? lookback
        let from = max(lookback, createdAt)
        guard from < todayStart else { return }

        let existing = records(from: from, to: todayStart, context: context)
        var changed = false
        var day = from
        while day < todayStart {
            let iso = cal.isoWeekday(for: day)
            if schedule.isGymDay(iso) {
                let mine = existing.first { $0.participant == .me && cal.isDate($0.day, inSameDayAs: day) }
                if let mine {
                    if mine.status == .planned || mine.status == .confirmed {
                        mine.status = .missed
                        mine.updatedAt = .now
                        changed = true
                    }
                } else {
                    let record = AttendanceRecord(day: day, participant: .me,
                                                  scheduledMinuteOfDay: schedule.minuteOfDay(for: iso), status: .missed)
                    context.insert(record)
                    changed = true
                }
            }
            guard let next = cal.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        if changed { try? context.save() }
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
