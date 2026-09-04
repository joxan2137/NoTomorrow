import Foundation

/// One row of the Bro log: a past gym day with what each of us did.
struct BroLogRow: Identifiable, Equatable {
    var day: Date
    var routineName: String?
    var myState: DayState
    var partnerState: DayState
    /// Already-localized second line: a quoted heads-up / note, a reason, or "No heads-up sent". `nil` hides the line.
    var note: String?

    var id: Date { day }

    var anyoneMissed: Bool { myState.isMissedOrCancelled || partnerState.isMissedOrCancelled }
}

/// The "Today · 18:00 · Push A" block under the shared-week rows.
struct BroSessionLine: Equatable {
    var day: Date
    var minuteOfDay: Int
    var routineName: String?
    var myState: DayState
    var partnerState: DayState
    var myConfirmedAt: Date?
    var partnerConfirmedAt: Date?

    var bothIn: Bool {
        isIn(myState) && isIn(partnerState)
    }

    private func isIn(_ s: DayState) -> Bool { s == .confirmed || s == .attended }
}

/// Pure derivations for the Bro tab (kept out of the views so the bodies stay cheap to type-check).
enum BroDerived {

    // MARK: Routine names

    /// Name of the routine for `day`: the workout done that day if any, otherwise the next one in rotation
    /// after the most recent completed workout, otherwise the first routine.
    static func routineName(for day: Date, routines: [Routine], workouts: [Workout]) -> String? {
        let cal = Calendar.current
        let completed = workouts.filter { $0.endedAt != nil }.sorted { $0.startedAt > $1.startedAt }
        if let done = completed.first(where: { cal.isDate($0.startedAt, inSameDayAs: day) }) { return done.name }
        let ordered = routines.sorted { $0.order < $1.order }
        guard !ordered.isEmpty else { return nil }
        if let last = completed.first, let index = ordered.firstIndex(where: { $0.name == last.name }) {
            return ordered[(index + 1) % ordered.count].name
        }
        return ordered.first?.name
    }

    /// Completed workout name on `day`, if one happened.
    static func workoutName(on day: Date, workouts: [Workout]) -> String? {
        let cal = Calendar.current
        return workouts.first { $0.endedAt != nil && cal.isDate($0.startedAt, inSameDayAs: day) }?.name
    }

    // MARK: Session line

    static func sessionLine(schedule: GymSchedule?, records: [AttendanceRecord], routines: [Routine],
                            workouts: [Workout], today: Date = .now) -> BroSessionLine? {
        guard let next = AttendanceService.nextSession(schedule: schedule, today: today) else { return nil }
        let cal = Calendar.current
        let day = cal.startOfDay(for: next.date)
        let isGymDay = schedule?.isGymDay(cal.isoWeekday(for: day)) ?? false
        let mine = records.first { $0.participant == .me && cal.isDate($0.day, inSameDayAs: day) }
        let theirs = records.first { $0.participant == .partner && cal.isDate($0.day, inSameDayAs: day) }
        return BroSessionLine(
            day: day,
            minuteOfDay: next.minuteOfDay,
            routineName: routineName(for: day, routines: routines, workouts: workouts),
            myState: AttendanceService.state(for: day, participant: .me, isGymDay: isGymDay, records: records, today: today),
            partnerState: AttendanceService.state(for: day, participant: .partner, isGymDay: isGymDay, records: records, today: today),
            myConfirmedAt: confirmedAt(mine),
            partnerConfirmedAt: confirmedAt(theirs)
        )
    }

    private static func confirmedAt(_ record: AttendanceRecord?) -> Date? {
        guard let record, record.status == .confirmed || record.status == .attended else { return nil }
        return record.updatedAt
    }

    // MARK: Log

    /// The last `limit` gym days (most recent first) since we both existed: gym days per the schedule, plus any
    /// day that has a terminal record for me. Days before the profile or the pairing are skipped.
    static func logRows(schedule: GymSchedule?, records: [AttendanceRecord], workouts: [Workout], headsUps: [HeadsUp],
                        profileCreatedAt: Date?, pairedAt: Date?, today: Date = .now, limit: Int = 10) -> [BroLogRow] {
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: today)
        let lookback = cal.date(byAdding: .day, value: -60, to: todayStart) ?? todayStart
        let floor = [lookback, profileCreatedAt, pairedAt].compactMap { $0.map { cal.startOfDay(for: $0) } }.max() ?? lookback

        var rows: [BroLogRow] = []
        var day = todayStart
        while day >= floor, rows.count < limit {
            let iso = cal.isoWeekday(for: day)
            let isGymDay = schedule?.isGymDay(iso) ?? false
            let mine = records.first { $0.participant == .me && cal.isDate($0.day, inSameDayAs: day) }
            let theirs = records.first { $0.participant == .partner && cal.isDate($0.day, inSameDayAs: day) }
            let isPast = day < todayStart
            let terminal: (AttendanceRecord?) -> Bool = { r in
                guard let r else { return false }
                return r.status == .attended || r.status == .missed || r.status == .cancelled
            }
            let include = (isPast && (isGymDay || mine != nil)) || (!isPast && (terminal(mine) || terminal(theirs)))
            if include {
                let myState = AttendanceService.state(for: day, participant: .me, isGymDay: isGymDay, records: records, today: today)
                let partnerState = AttendanceService.state(for: day, participant: .partner, isGymDay: isGymDay, records: records, today: today)
                // Today with only one terminal record: leave the other side as planned, not missed.
                let row = BroLogRow(
                    day: day,
                    routineName: workoutName(on: day, workouts: workouts),
                    myState: isPast ? myState : (terminal(mine) ? myState : .planned),
                    partnerState: isPast ? partnerState : (terminal(theirs) ? partnerState : .planned),
                    note: noteLine(day: day, mine: mine, theirs: theirs, headsUps: headsUps,
                                   anyoneMissed: myState.isMissedOrCancelled || partnerState.isMissedOrCancelled)
                )
                rows.append(row)
            }
            guard let previous = cal.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return rows
    }

    /// Quoted heads-up text for the day, else the note or reason of whoever bailed, else "No heads-up sent" when someone missed.
    static func noteLine(day: Date, mine: AttendanceRecord?, theirs: AttendanceRecord?, headsUps: [HeadsUp], anyoneMissed: Bool) -> String? {
        let cal = Calendar.current
        let dayHeadsUps = headsUps
            .filter { cal.isDate($0.sessionDay, inSameDayAs: day) && $0.kind == .cantMakeIt && !$0.text.isEmpty }
            .sorted { $0.sentAt > $1.sentAt }
        if let text = dayHeadsUps.first?.text { return quoted(text) }
        for record in [theirs, mine].compactMap({ $0 }) where record.status == .missed || record.status == .cancelled {
            if let note = record.note, !note.isEmpty { return quoted(note) }
            if let reason = record.reason, let label = reasonLabel(reason) { return label }
        }
        return anyoneMissed ? String(localized: "bro.noHeadsUp") : nil
    }

    private static func quoted(_ text: String) -> String { "\u{201C}\(text)\u{201D}" }

    /// "sick" → "Sick" via the `cant.reason.*` keys; unknown reasons are shown as-is.
    static func reasonLabel(_ reason: String) -> String? {
        let known = ["sick", "work", "tired", "family", "none"]
        let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return nil }
        if known.contains(trimmed) { return String(localized: String.LocalizationValue("cant.reason.\(trimmed)")) }
        return reason
    }

    // MARK: Labels

    /// "Wed 2" — abbreviated weekday plus day of month, for the log column and make-up chips.
    static func weekdayDay(_ date: Date) -> String {
        let day = Calendar.current.component(.day, from: date).formatted()
        return "\(Fmt.weekdayShort(date)) \(day)"
    }

    /// Next `count` days after `day` that are not gym days (within two weeks).
    static func nonGymDays(after day: Date, schedule: GymSchedule?, count: Int = 2) -> [Date] {
        let cal = Calendar.current
        let start = cal.startOfDay(for: day)
        var result: [Date] = []
        for offset in 1...14 where result.count < count {
            guard let d = cal.date(byAdding: .day, value: offset, to: start) else { continue }
            if schedule?.isGymDay(cal.isoWeekday(for: d)) == true { continue }
            result.append(d)
        }
        return result
    }
}
