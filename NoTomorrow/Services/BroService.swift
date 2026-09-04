import Foundation
import Observation
import SwiftData

/// Everything the Bro tab and the Dashboard bro row need: pairing, the partner's week, heads-ups.
/// Talks to a `BackendClient`; after every refresh it mirrors the partner's attendance and incoming heads-ups
/// into SwiftData so the week strip and the log work offline.
@Observable
@MainActor
final class BroService {
    let client: any BackendClient

    private(set) var partner: Partner?
    private(set) var partnerState: PartnerState?
    private(set) var myCode: String?
    private(set) var isLoading = false
    var lastError: BackendError?

    var isPaired: Bool { partner != nil }

    init(client: any BackendClient = AppConfig.shared.makeBackendClient()) {
        self.client = client
    }

    // MARK: Refresh

    /// Pulls `me()` (who am I paired with) and, when paired, the partner's state; then syncs into `context`.
    func refresh(in context: ModelContext) async {
        isLoading = true
        defer { isLoading = false }
        do {
            let me = try await client.me()
            partner = me.partner
            if let code = me.pairCode { myCode = code }
            if partner == nil {
                partnerState = nil
                return
            }
            let state = try await client.partnerState()
            partnerState = state
            sync(state, into: context)
            upsertPairing(in: context)
            lastError = nil
        } catch {
            lastError = BackendError.wrap(error)
        }
    }

    // MARK: Pairing

    func createCode() async -> String? {
        do {
            let code = try await client.createPairCode()
            myCode = code
            lastError = nil
            return code
        } catch {
            lastError = BackendError.wrap(error)
            return nil
        }
    }

    /// "abcd", "nt-abcd", "NTABCD " → "NT-ABCD". Anything that does not reduce to four code characters is returned as-is (uppercased).
    static func normalizedCode(_ raw: String) -> String {
        var body = raw.uppercased().filter { $0.isLetter || $0.isNumber }
        if body.hasPrefix("NT"), body.count == 6 { body.removeFirst(2) }
        return body.count == 4 ? "NT-\(body)" : raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    @discardableResult
    func pair(code: String, in context: ModelContext) async -> Bool {
        let trimmed = Self.normalizedCode(code)
        guard trimmed.count == 7 else { return false }
        isLoading = true
        do {
            let p = try await client.pair(withCode: trimmed)
            partner = p
            isLoading = false
            lastError = nil
            if myCode == nil { _ = await createCode() }
            if let schedule = try? context.fetch(FetchDescriptor<GymSchedule>()).first {
                try? await client.pushSchedule(ScheduleDTO(schedule))
            }
            await refresh(in: context)
            return true
        } catch {
            isLoading = false
            lastError = BackendError.wrap(error)
            return false
        }
    }

    func unpair(in context: ModelContext) async {
        do {
            try await client.unpair()
            partner = nil
            partnerState = nil
            lastError = nil
            for pairing in (try? context.fetch(FetchDescriptor<BroPairing>())) ?? [] { context.delete(pairing) }
        } catch {
            lastError = BackendError.wrap(error)
        }
    }

    // MARK: Attendance & heads-ups

    /// "I'm in" for today. Writes my `AttendanceRecord(.confirmed)` locally and tells the partner.
    func confirmToday(in context: ModelContext) async {
        let today = Calendar.current.startOfDay(for: .now)
        upsertAttendance(day: today, participant: .me, status: .confirmed, reason: nil, note: nil, makeUpDay: nil, in: context)
        do {
            try await client.setAttendance(day: today, status: .confirmed, reason: nil, note: nil, makeUpDay: nil)
            lastError = nil
        } catch {
            lastError = BackendError.wrap(error)
        }
    }

    /// Cancels today's session: local `.cancelled` record plus backend attendance and a `.cantMakeIt` heads-up.
    /// The caller (`CantMakeItSheet`) inserts its own `HeadsUp(fromMe: true)` row.
    func cantMakeIt(reason: String?, note: String?, makeUpDay: Date?, sessionDay: Date = .now, in context: ModelContext) async {
        let day = Calendar.current.startOfDay(for: sessionDay)
        upsertAttendance(day: day, participant: .me, status: .cancelled, reason: reason, note: note, makeUpDay: makeUpDay, in: context)
        do {
            try await client.setAttendance(day: day, status: .cancelled, reason: reason, note: note, makeUpDay: makeUpDay)
            if partner != nil {
                let text = (note?.isEmpty == false ? note : reason) ?? ""
                try await client.sendHeadsUp(kind: .cantMakeIt, text: text, sessionDay: day)
            }
            lastError = nil
        } catch {
            lastError = BackendError.wrap(error)
        }
    }

    /// Sends a heads-up (running late, let's go, custom) for the given session day. Backend only; the caller owns the local row.
    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date = .now) async {
        guard partner != nil else { return }
        do {
            try await client.sendHeadsUp(kind: kind, text: text, sessionDay: Calendar.current.startOfDay(for: sessionDay))
            lastError = nil
        } catch {
            lastError = BackendError.wrap(error)
        }
    }

    // MARK: Derived

    /// Partner's record for a given day, if the backend reported one.
    func partnerAttendance(on day: Date) -> AttendanceDTO? {
        let d = Calendar.current.startOfDay(for: day)
        return partnerState?.attendance.first { $0.participant == .partner && Calendar.current.isDate($0.day, inSameDayAs: d) }
    }

    // MARK: SwiftData sync

    private func sync(_ state: PartnerState, into context: ModelContext) {
        let cal = Calendar.current
        let partnerRows = state.attendance.filter { $0.participant == .partner }
        if let earliest = partnerRows.map(\.day).min() {
            let floor = cal.startOfDay(for: earliest)
            let descriptor = FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.day >= floor })
            let existing = ((try? context.fetch(descriptor)) ?? []).filter { $0.participant == .partner }
            for dto in partnerRows {
                let minute = state.partnerSchedule.minuteOfDay(for: cal.isoWeekday(for: dto.day))
                if let row = existing.first(where: { cal.isDate($0.day, inSameDayAs: dto.day) }) {
                    guard row.status != dto.status || row.reason != dto.reason || row.note != dto.note || row.makeUpDay != dto.makeUpDay else { continue }
                    row.status = dto.status
                    row.reason = dto.reason
                    row.note = dto.note
                    row.makeUpDay = dto.makeUpDay
                    row.scheduledMinuteOfDay = minute
                    row.updatedAt = .now
                } else {
                    let row = AttendanceRecord(day: dto.day, participant: .partner, scheduledMinuteOfDay: minute, status: dto.status)
                    row.reason = dto.reason
                    row.note = dto.note
                    row.makeUpDay = dto.makeUpDay
                    context.insert(row)
                }
            }
        }

        let incoming = state.headsUps.filter { !$0.fromMe }
        if let earliest = incoming.map(\.sentAt).min() {
            let descriptor = FetchDescriptor<HeadsUp>(predicate: #Predicate { $0.sentAt >= earliest })
            let existing = ((try? context.fetch(descriptor)) ?? []).filter { !$0.fromMe }
            for dto in incoming where !existing.contains(where: { abs($0.sentAt.timeIntervalSince(dto.sentAt)) < 1 && $0.text == dto.text }) {
                let row = HeadsUp(fromMe: false, kind: dto.kind, text: dto.text, sessionDay: dto.sessionDay)
                row.sentAt = dto.sentAt
                context.insert(row)
            }
        }
        try? context.save()
    }

    private func upsertAttendance(day: Date, participant: Participant, status: AttendanceStatus,
                                  reason: String?, note: String?, makeUpDay: Date?, in context: ModelContext) {
        let cal = Calendar.current
        let start = cal.startOfDay(for: day)
        let end = cal.date(byAdding: .day, value: 1, to: start) ?? start
        let descriptor = FetchDescriptor<AttendanceRecord>(predicate: #Predicate { $0.day >= start && $0.day < end })
        let rows = ((try? context.fetch(descriptor)) ?? []).filter { $0.participant == participant }
        let minute = scheduledMinute(for: start, in: context)
        if let row = rows.first {
            row.status = status
            row.reason = reason
            row.note = note
            row.makeUpDay = makeUpDay
            row.updatedAt = .now
        } else {
            let row = AttendanceRecord(day: start, participant: participant, scheduledMinuteOfDay: minute, status: status)
            row.reason = reason
            row.note = note
            row.makeUpDay = makeUpDay
            context.insert(row)
        }
        try? context.save()
    }

    private func scheduledMinute(for day: Date, in context: ModelContext) -> Int {
        let schedule = try? context.fetch(FetchDescriptor<GymSchedule>()).first
        return schedule?.minuteOfDay(for: Calendar.current.isoWeekday(for: day)) ?? 18 * 60
    }

    private func upsertPairing(in context: ModelContext) {
        guard let partner else { return }
        let existing = (try? context.fetch(FetchDescriptor<BroPairing>())) ?? []
        if let row = existing.first {
            row.partnerId = partner.id
            row.partnerName = partner.name
            if let myCode { row.myCode = myCode }
        } else {
            context.insert(BroPairing(partnerId: partner.id, partnerName: partner.name, myCode: myCode ?? ""))
        }
        try? context.save()
    }
}
