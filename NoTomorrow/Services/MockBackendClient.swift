import Foundation

/// In-memory backend for offline demos. Partner "Tomek" shares your schedule, confirms 20 s after you do,
/// and answers a "can't make it" after 10 s. Pairing survives relaunch (UserDefaults) so the Bro tab stays coherent.
actor MockBackendClient: BackendClient {
    nonisolated let baseURL: URL

    static let partnerName = "Tomek"
    static let partnerId = "mock-tomek"
    static let pairCode = "NT-7K4Q"

    private var session: Session?
    private var schedule = ScheduleDTO(weekdays: [1, 3, 5], defaultMinuteOfDay: 18 * 60)
    private var partner: Partner? {
        didSet { UserDefaults.standard.set(partner != nil, forKey: Keys.paired) }
    }
    /// Explicit partner attendance, keyed by start of day. Anything not here is derived from the schedule.
    private var partnerOverrides: [Date: AttendanceDTO] = [:]
    private var myAttendance: [Date: AttendanceDTO] = [:]
    private var headsUps: [HeadsUpDTO] = []
    private var replyTask: Task<Void, Never>?
    private var confirmTask: Task<Void, Never>?

    private enum Keys { static let paired = "nt.mock.paired" }

    init(baseURL: URL = URL(string: "https://mock.notomorrow.app")!) {
        self.baseURL = baseURL
        if UserDefaults.standard.bool(forKey: Keys.paired) {
            partner = Partner(id: Self.partnerId, name: Self.partnerName, pairedAt: .now.addingTimeInterval(-21 * 86_400))
        }
    }

    // MARK: Auth

    func signIn(apple identityToken: String, authorizationCode: String) async throws -> Session { makeSession(userId: "mock-apple") }
    func signIn(google idToken: String) async throws -> Session { makeSession(userId: "mock-google") }
    func signIn(username: String, password: String) async throws -> Session { makeSession(userId: "mock-\(username)") }
    func register(username: String, password: String, email: String?) async throws -> Session { makeSession(userId: "mock-\(username)") }

    private func makeSession(userId: String) -> Session {
        let s = Session(accessToken: "mock-access-\(UUID().uuidString)", refreshToken: "mock-refresh-\(UUID().uuidString)", userId: userId)
        session = s
        return s
    }

    func logout(refreshToken: String) async throws { session = nil }

    func me() async throws -> Me {
        Me(id: session?.userId ?? "mock-user", username: "kuba", displayName: "Kuba", email: nil, partner: partner, pairCode: Self.pairCode)
    }

    func updateMe(locale: String?, timeZone: String?) async throws {}

    // MARK: Pairing

    func createPairCode() async throws -> String { Self.pairCode }

    func pair(withCode code: String) async throws -> Partner {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 7 else { throw BackendError.server("Invalid code") }
        let p = Partner(id: Self.partnerId, name: Self.partnerName, pairedAt: .now)
        partner = p
        return p
    }

    func unpair() async throws {
        partner = nil
        partnerOverrides = [:]
        headsUps = []
        confirmTask?.cancel()
        replyTask?.cancel()
    }

    func pushSchedule(_ s: ScheduleDTO) async throws { schedule = s }

    func partnerState() async throws -> PartnerState {
        guard let partner else { throw BackendError.server("Not paired") }
        return PartnerState(partnerName: partner.name, partnerSchedule: schedule,
                            attendance: partnerAttendance() + myAttendance.values.sorted { $0.day < $1.day },
                            headsUps: headsUps)
    }

    /// Tomek's record: every gym day in the last three weeks attended (one missed with a note for the log),
    /// today planned until he confirms, plus any explicit overrides.
    private func partnerAttendance() -> [AttendanceDTO] {
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        var rows: [AttendanceDTO] = []
        var pastGymDays: [Date] = []
        for offset in stride(from: 1, through: 21, by: 1) {
            guard let day = cal.date(byAdding: .day, value: -offset, to: today) else { continue }
            if schedule.isGymDay(cal.isoWeekday(for: day)) { pastGymDays.append(day) }
        }
        for (index, day) in pastGymDays.enumerated() {
            if let override = partnerOverrides[day] { rows.append(override); continue }
            // The third-most-recent gym day is a miss so the log has something to show; the last two are attended.
            if index == 2 {
                rows.append(AttendanceDTO(day: day, participant: .partner, status: .missed, reason: "sick",
                                          note: String(localized: "mock.partner.sickNote"), makeUpDay: nil))
            } else {
                rows.append(AttendanceDTO(day: day, participant: .partner, status: .attended, reason: nil, note: nil, makeUpDay: nil))
            }
        }
        if schedule.isGymDay(cal.isoWeekday(for: today)) {
            rows.append(partnerOverrides[today]
                        ?? AttendanceDTO(day: today, participant: .partner, status: .planned, reason: nil, note: nil, makeUpDay: nil))
        }
        return rows.sorted { $0.day < $1.day }
    }

    func setAttendance(day: Date, status: AttendanceStatus, reason: String?, note: String?, makeUpDay: Date?) async throws {
        let day = Calendar.current.startOfDay(for: day)
        myAttendance[day] = AttendanceDTO(day: day, participant: .me, status: status, reason: reason, note: note, makeUpDay: makeUpDay)
        guard partner != nil, status == .confirmed, Calendar.current.isDateInToday(day) else { return }
        confirmTask?.cancel()
        confirmTask = Task {
            try? await Task.sleep(for: .seconds(20))
            guard !Task.isCancelled else { return }
            partnerConfirms(day)
        }
    }

    private func partnerConfirms(_ day: Date) {
        guard partner != nil else { return }
        partnerOverrides[day] = AttendanceDTO(day: day, participant: .partner, status: .confirmed, reason: nil, note: nil, makeUpDay: nil)
    }

    func sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: Date) async throws {
        let day = Calendar.current.startOfDay(for: sessionDay)
        headsUps.append(HeadsUpDTO(id: UUID().uuidString, fromMe: true, kind: kind, text: text, sessionDay: day, sentAt: .now))
        guard partner != nil, kind == .cantMakeIt else { return }
        replyTask?.cancel()
        replyTask = Task {
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            partnerReplies(day)
        }
    }

    private func partnerReplies(_ day: Date) {
        guard partner != nil else { return }
        headsUps.append(HeadsUpDTO(id: UUID().uuidString, fromMe: false, kind: .custom,
                                   text: String(localized: "mock.partner.reply"), sessionDay: day, sentAt: .now))
    }

    func registerPushToken(_ token: Data) async throws {}

    // MARK: AI

    func estimate(imageJPEG: Data, meal: MealSlot, locale: String, anthropicKey: String?, notes: String) async throws -> AIEstimate {
        try await Task.sleep(for: .seconds(1.2))
        return AIEstimate(foods: [
            AIFood(name: String(localized: "mock.food.chicken"), grams: 180, kcal: 297, protein: 56, carbs: 0, fat: 6.5, confidence: 0.92),
            AIFood(name: String(localized: "mock.food.rice"), grams: 220, kcal: 286, protein: 5.9, carbs: 62, fat: 0.6, confidence: 0.84),
            AIFood(name: String(localized: "mock.food.broccoli"), grams: 90, kcal: 31, protein: 2.5, carbs: 6, fat: 0.4, confidence: 0.88),
            AIFood(name: String(localized: "mock.food.oliveOil"), grams: 14, kcal: 124, protein: 0, carbs: 0, fat: 14, confidence: 0.35, isGuess: true),
        ], overallConfidence: 0.78)
    }

    func deleteAccount() async throws {
        session = nil
        try await unpair()
        myAttendance = [:]
    }
}
