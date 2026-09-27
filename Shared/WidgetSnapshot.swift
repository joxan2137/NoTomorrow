import Foundation

/// Everything the widgets show, published by the app (`WidgetSync`) as JSON in the App Group. The extension never
/// opens the SwiftData store: it renders this, and patches it when a quick-log tap adds food before the app has
/// seen it (`WidgetStore.logQuickFood`). Spec: `docs/widgets.md`.
struct WidgetSnapshot: Codable, Equatable {
    static let currentVersion = 1

    var version = WidgetSnapshot.currentVersion
    var generatedAt: Date
    /// `nt.language` ("en" / "pl"), nil for the system language.
    var languageOverride: String?
    /// False before onboarding: every widget then shows `widget.setup`.
    var hasProfile: Bool
    var fuel: Fuel
    var quickFoods: [QuickFood]
    var calendar: CalendarGrid
    var week: Week
    var defaultRestSeconds: Int
    /// The last quick-log tap, for the row's "Logged" check (`docs/widgets.md`, "Just logged").
    var lastLogged: LastLogged?

    struct Fuel: Codable, Equatable {
        /// Local midnight of the day the totals belong to; a snapshot read on a later day counts as nothing eaten.
        var day: Date
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        var kcalGoal: Double
    }

    struct CalendarGrid: Codable, Equatable {
        /// Consecutive local days, oldest first, ending on the snapshot's today.
        var days: [Day]
        /// Heat bands for the current goal (`FuelCalendar.tolerance`), permille.
        var under: [Int]
        var over: [Int]
        var kcalGoal: Double
        /// Finished workouts with a completed set that started in the last 30 days, today included.
        var sessions30: Int

        struct Day: Codable, Equatable {
            var date: Date
            /// Nil when nothing was logged (level 0); a logged day is non-nil even when it adds up to 0 kcal.
            var kcal: Double?
            var trained: Bool
        }
    }

    struct Week: Codable, Equatable {
        /// Mon…Sun of the snapshot's ISO week.
        var days: [Day]
        var isPaired: Bool
        /// ISO weekday (1 = Monday) → minute of day, for every gym day. The widget works out the next session from
        /// it, so the card moves on after a session without the app.
        var gymMinutes: [Int: Int]
        /// The routine Today suggests (`DashboardModel.suggestedRoutine`), nil without routines.
        var routineName: String?

        struct Day: Codable, Equatable {
            var date: Date
            var isGymDay: Bool
            var me: Status
            var partner: Status
        }

        enum Status: String, Codable, Equatable {
            case rest, planned, confirmed, attended, missed, cancelled

            var isMissedOrCancelled: Bool { self == .missed || self == .cancelled }
        }
    }

    struct LastLogged: Codable, Equatable {
        var key: String
        var at: Date
    }
}

/// A food the user logs over and over, logged again with one tap (`docs/widgets.md`, "Quick foods").
struct QuickFood: Codable, Equatable, Identifiable, Hashable {
    /// `food:<FoodItem.id>` or `name:<folded custom name>`.
    var key: String
    var foodID: String?
    var name: String
    var grams: Double
    var kcal: Double
    var protein: Double
    var carbs: Double
    var fat: Double
    var isAIEstimate: Bool

    var id: String { key }

    /// How many foods a snapshot carries; the medium widget shows 3, the small one 1.
    static let limit = 4
    /// Days of history the ranking looks at.
    static let windowDays = 60

    struct Candidate {
        var foodID: String?
        var name: String
        var grams: Double
        var kcal: Double
        var protein: Double
        var carbs: Double
        var fat: Double
        var isAIEstimate: Bool
        var loggedAt: Date
    }

    /// Groups by food id, or by the folded name for entries without a food; ranks by number of entries, then by the
    /// newest one; carries the newest entry's figures. Pure, so it is tested directly (and mirrored on Android).
    static func pick(_ candidates: [Candidate], limit: Int = QuickFood.limit) -> [QuickFood] {
        struct Group { var count: Int; var newest: Candidate }
        var groups: [String: Group] = [:]
        for candidate in candidates {
            let trimmed = candidate.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard candidate.foodID != nil || !trimmed.isEmpty else { continue }
            let key = candidate.foodID.map { "food:" + $0 } ?? "name:" + fold(trimmed)
            if var group = groups[key] {
                group.count += 1
                if candidate.loggedAt > group.newest.loggedAt { group.newest = candidate }
                groups[key] = group
            } else {
                groups[key] = Group(count: 1, newest: candidate)
            }
        }
        return groups
            .sorted { a, b in
                if a.value.count != b.value.count { return a.value.count > b.value.count }
                if a.value.newest.loggedAt != b.value.newest.loggedAt { return a.value.newest.loggedAt > b.value.newest.loggedAt }
                return a.key < b.key
            }
            .prefix(limit)
            .map { key, group in
                let c = group.newest
                return QuickFood(key: key, foodID: c.foodID, name: c.name.trimmingCharacters(in: .whitespacesAndNewlines),
                                 grams: c.grams, kcal: c.kcal, protein: c.protein, carbs: c.carbs, fat: c.fat,
                                 isAIEstimate: c.isAIEstimate)
            }
    }

    /// Case, diacritics and width folded, `ł` → `l`, whitespace runs collapsed: "Owsianka ", "owsianka" and
    /// "OWSIANKA" are one food.
    static func fold(_ name: String) -> String {
        let folded = name
            .folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: "ł", with: "l")
            .replacingOccurrences(of: "Ł", with: "l")
            .lowercased()
        return folded.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}

/// A quick-log tap waiting for the app to turn it into a `MealEntry` (`WidgetSync.ingestPendingLogs`).
struct PendingQuickLog: Codable, Equatable, Identifiable {
    /// Becomes the entry's id, so a drain that is interrupted and repeated never logs twice.
    var id: UUID
    var loggedAt: Date
    var food: QuickFood
}

/// Reads and writes the snapshot and the quick-log queue in the App Group. Used by both processes.
enum WidgetStore {
    private static let snapshotFile = "widget-snapshot.json"
    private static let pendingKey = "nt.widget.pendingLogs"

    private static var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }

    private static var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }

    // MARK: Snapshot

    static func readSnapshot() -> WidgetSnapshot? {
        guard let url = SharedStore.fileURL(snapshotFile), let data = try? Data(contentsOf: url),
              let snapshot = try? decoder.decode(WidgetSnapshot.self, from: data),
              snapshot.version == WidgetSnapshot.currentVersion else { return nil }
        return snapshot
    }

    static func writeSnapshot(_ snapshot: WidgetSnapshot) {
        guard let url = SharedStore.fileURL(snapshotFile), let data = try? encoder.encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
    }

    // MARK: Quick-log queue

    static func pendingLogs() -> [PendingQuickLog] {
        guard let data = SharedStore.defaults?.data(forKey: pendingKey) else { return [] }
        return (try? decoder.decode([PendingQuickLog].self, from: data)) ?? []
    }

    static func setPendingLogs(_ logs: [PendingQuickLog]) {
        guard let defaults = SharedStore.defaults else { return }
        if logs.isEmpty {
            defaults.removeObject(forKey: pendingKey)
        } else if let data = try? encoder.encode(logs) {
            defaults.set(data, forKey: pendingKey)
        }
    }

    /// The quick-log tap: queue the entry for the app and patch the snapshot so today's ring, the calendar's today
    /// cell and the "Logged" check show it at once. Returns false when there is nothing to log it with.
    @discardableResult
    static func logQuickFood(key: String, now: Date = .now, calendar: Calendar = .current) -> Bool {
        guard var snapshot = readSnapshot(), let food = snapshot.quickFoods.first(where: { $0.key == key }) else { return false }
        var queue = pendingLogs()
        queue.append(PendingQuickLog(id: UUID(), loggedAt: now, food: food))
        setPendingLogs(queue)

        let today = calendar.startOfDay(for: now)
        if snapshot.fuel.day != today {
            snapshot.fuel = .init(day: today, kcal: 0, protein: 0, carbs: 0, fat: 0, kcalGoal: snapshot.fuel.kcalGoal)
        }
        snapshot.fuel.kcal += food.kcal
        snapshot.fuel.protein += food.protein
        snapshot.fuel.carbs += food.carbs
        snapshot.fuel.fat += food.fat
        if let index = snapshot.calendar.days.lastIndex(where: { $0.date == today }) {
            snapshot.calendar.days[index].kcal = (snapshot.calendar.days[index].kcal ?? 0) + food.kcal
        } else if let last = snapshot.calendar.days.last, last.date < today {
            snapshot.calendar.days.append(.init(date: today, kcal: food.kcal, trained: false))
        }
        snapshot.lastLogged = .init(key: key, at: now)
        writeSnapshot(snapshot)
        return true
    }
}
