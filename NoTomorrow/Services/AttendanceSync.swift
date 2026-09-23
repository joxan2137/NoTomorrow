import Foundation

/// Hands my attendance writes to the backend, paired or not: the server's reminder and 21:00 "did you skip?" jobs
/// read my status for the day, and a partner sees it (a finished workout is `attended`, not a stale `confirmed`).
/// Local SwiftData stays the source of truth; this is fire and forget, with `AttendanceOutbox` for retries.
@MainActor
enum AttendanceSync {
    typealias Reporter = @MainActor (_ day: Date, _ status: AttendanceStatus) -> Void

    /// Where reports go. The default sends through `BroShared.service`; tests swap in a spy. Unit tests never
    /// reach the network: the default does nothing inside an XCTest run (the host app may be signed in).
    static var reporter: Reporter = { day, status in
        guard !isRunningTests else { return }
        Task { await BroShared.service.reportAttendance(day: day, status: status) }
    }

    static func report(day: Date, status: AttendanceStatus) {
        reporter(day, status)
    }

    /// Reports the local writes a workout edit or delete made (see `AttendanceService.wireStatus`).
    static func report(_ changes: [AttendanceService.WorkoutDayChange], isGymDay: (Date) -> Bool) {
        for change in changes {
            guard let wire = AttendanceService.wireStatus(for: change, isGymDay: isGymDay) else { continue }
            reporter(wire.day, wire.status)
        }
    }

    private static let isRunningTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
}

/// My attendance writes the backend has not confirmed yet, one per day and account (the latest status wins), in
/// UserDefaults under `nt.attendance.outbox`, the same JSON array Android keeps:
/// `[{"day": "yyyy-MM-dd", "status": "cancelled", "reason": "sick", "note": "…", "makeUpDay": "yyyy-MM-dd",
/// "backend": "remote", "account": "<user id>"}]`. Every field after `status` is optional.
/// `BroService` puts a write here before sending it and removes it once the server took it, so a write made offline
/// (or cut off by the app being killed) goes out with a later refresh. Each entry carries the backend and account it
/// was made on (`Target`), and only the matching ones are sent: a write queued before a sign-out or a flip of the demo
/// switch waits for its own account instead of reaching another one.
struct AttendanceOutbox {
    /// The backend (the demo one or the real one) and the account a write was made on. The demo backend has no account.
    struct Target: Hashable {
        static let demoBackend = "demo"
        static let remoteBackend = "remote"

        var backend: String
        var account: String?

        static let demo = Target(backend: demoBackend, account: nil)

        static func remote(_ userId: String) -> Target {
            Target(backend: remoteBackend, account: userId)
        }
    }

    /// One queued write: my `status` for `day`; a cancellation also carries its reason, note and make-up day.
    struct Entry: Codable, Hashable {
        /// "yyyy-MM-dd" in the local calendar, the form the server's `/attendance/:day` takes.
        var day: String
        var status: AttendanceStatus
        var reason: String?
        var note: String?
        /// "yyyy-MM-dd", like `day`.
        var makeUpDay: String?
        /// `Target.backend`; nil only for an entry queued before entries were tagged: whoever is signed in sends it.
        var backend: String?
        var account: String?

        init(day: String, status: AttendanceStatus, reason: String? = nil, note: String? = nil,
             makeUpDay: String? = nil, target: Target? = nil) {
            self.day = day
            self.status = status
            self.reason = reason
            self.note = note
            self.makeUpDay = makeUpDay
            backend = target?.backend
            account = target?.account
        }

        var target: Target? { backend.map { Target(backend: $0, account: account) } }

        func belongs(to target: Target) -> Bool {
            self.target == nil || self.target == target
        }
    }

    static let key = "nt.attendance.outbox"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Oldest day first. Unreadable entries are dropped; the next write rewrites the list without them.
    var entries: [Entry] {
        guard let data = defaults.data(forKey: Self.key),
              let list = try? JSONDecoder().decode([Lossy].self, from: data) else { return [] }
        return list.compactMap(\.entry).sorted { $0.day < $1.day }
    }

    /// The entries `target` sends, oldest day first.
    func entries(for target: Target) -> [Entry] {
        entries.filter { $0.belongs(to: target) }
    }

    /// Queues `entry`, replacing anything queued for that day on the same backend and account.
    func put(_ entry: Entry) {
        var list = entries.filter { !($0.day == entry.day && ($0.target == entry.target || $0.target == nil)) }
        list.append(entry)
        write(list)
    }

    /// Queues `status` for `day` on `target`.
    func put(day: Date, status: AttendanceStatus, target: Target? = nil) {
        put(Entry(day: WireDay.string(day), status: status, target: target))
    }

    /// Drops `entry` once sent, unless a newer status for the same day replaced it in the meantime.
    func remove(_ entry: Entry) {
        let list = entries
        guard list.contains(entry) else { return }
        write(list.filter { $0 != entry })
    }

    /// Forgets everything queued for `target` (its account is gone), or everything when it is nil.
    func clear(target: Target? = nil) {
        guard let target else {
            defaults.removeObject(forKey: Self.key)
            return
        }
        write(entries.filter { $0.target != target })
    }

    /// A reply that retrying cannot fix (the server rejected the write itself), as opposed to no connection,
    /// a lapsed session, rate limiting or server trouble, which keep the write queued.
    static func isRejected(_ error: Error) -> Bool {
        guard case .http(let status, _, _) = BackendError.wrap(error) else { return false }
        return (400..<500).contains(status) && ![401, 408, 429].contains(status)
    }

    /// One element of the stored array; one that does not decode (an unknown status) is skipped, not fatal.
    private struct Lossy: Decodable {
        var entry: Entry?

        init(from decoder: Decoder) throws {
            entry = try? Entry(from: decoder)
        }
    }

    private func write(_ list: [Entry]) {
        if list.isEmpty {
            defaults.removeObject(forKey: Self.key)
        } else if let data = try? JSONEncoder().encode(list.sorted { $0.day < $1.day }) {
            defaults.set(data, forKey: Self.key)
        }
    }
}
