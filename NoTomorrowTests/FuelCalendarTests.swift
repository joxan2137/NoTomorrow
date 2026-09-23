import XCTest
import SwiftData
@testable import NoTomorrow

/// FuelCalendar: scoring, layout, stats and day keys. The vector tables at the bottom are shared 1:1 with Android's
/// FuelCalendarTest (scratchpad contract fuel-calendar-vectors.json); dates in them are calendar days in Warsaw.
@MainActor
final class FuelCalendarTests: XCTestCase {
    private typealias V = FuelCalendarVectors

    private static let warsaw: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Warsaw")!
        return calendar
    }()

    private func day(_ iso: String, _ calendar: Calendar = FuelCalendarTests.warsaw) -> Date {
        let parts = iso.split(separator: "-").map { Int($0)! }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts.count > 2 ? parts[2] : 1))!
    }

    private func iso(_ date: Date?, _ calendar: Calendar = FuelCalendarTests.warsaw) -> String? {
        guard let date else { return nil }
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    // MARK: - Scoring

    func testToleranceBands() {
        XCTAssertEqual(FuelCalendar.tolerance(for: .loseFat), .init(under: [100, 200, 350], over: [30, 80, 150]))
        XCTAssertEqual(FuelCalendar.tolerance(for: .buildMuscle), .init(under: [30, 80, 150], over: [100, 200, 350]))
        XCTAssertEqual(FuelCalendar.tolerance(for: .maintain), .init(under: [50, 100, 200], over: [50, 100, 200]))
    }

    func testPermilleVectors() {
        for v in V.permille {
            XCTAssertEqual(FuelCalendar.permille(kcalEaten: v.kcalEaten, kcalGoal: v.kcalGoal), v.expected,
                           "\(v.kcalEaten) / \(v.kcalGoal)")
        }
        XCTAssertEqual(FuelCalendar.permille(kcalEaten: .nan, kcalGoal: 2000), 0)
        XCTAssertEqual(FuelCalendar.permille(kcalEaten: .infinity, kcalGoal: 2000), 10_000)
        XCTAssertEqual(FuelCalendar.permille(kcalEaten: 2000, kcalGoal: .nan), 0)
    }

    func testLevelVectors() {
        XCTAssertEqual(V.level.count, 141)
        for v in V.level {
            let level = FuelCalendar.level(kcalEaten: v.eaten, kcalGoal: v.kcalGoal, goal: v.goal,
                                           hasEntries: v.has, isToday: v.today)
            XCTAssertEqual(level, v.expected,
                           "\(v.goal) goal \(v.kcalGoal) eaten \(v.eaten) has \(v.has) today \(v.today) (\(v.note))")
        }
    }

    /// Every permille from 0 to 3 000: brighter towards the goal, dimmer past it, never outside 1…4 when logged.
    func testPastDaysBrightenTowardsGoalAndDimPastIt() {
        for goal in TrainingGoal.allCases {
            var previous = 0
            for p in 0...3000 {
                let level = FuelCalendar.level(kcalEaten: Double(p), kcalGoal: 1000, goal: goal, hasEntries: true, isToday: false)
                XCTAssertTrue((1...4).contains(level), "\(goal) \(p)")
                if p <= 1000 {
                    XCTAssertGreaterThanOrEqual(level, previous, "\(goal) \(p)")
                } else {
                    XCTAssertLessThanOrEqual(level, previous, "\(goal) \(p)")
                }
                previous = level
            }
            XCTAssertEqual(FuelCalendar.level(kcalEaten: 1000, kcalGoal: 1000, goal: goal, hasEntries: true, isToday: false), 4)
        }
    }

    /// Lose fat forgives a short day more than a long one; build muscle is the mirror; maintain is symmetric.
    func testGoalDirectionDecidesWhichSideIsStrict() {
        func level(_ goal: TrainingGoal, _ p: Int) -> Int {
            FuelCalendar.level(kcalEaten: Double(p), kcalGoal: 1000, goal: goal, hasEntries: true, isToday: false)
        }
        for d in 1...500 {
            XCTAssertGreaterThanOrEqual(level(.loseFat, 1000 - d), level(.loseFat, 1000 + d), "loseFat ±\(d)")
            XCTAssertGreaterThanOrEqual(level(.buildMuscle, 1000 + d), level(.buildMuscle, 1000 - d), "buildMuscle ±\(d)")
            XCTAssertEqual(level(.maintain, 1000 + d), level(.maintain, 1000 - d), "maintain ±\(d)")
            XCTAssertEqual(level(.loseFat, 1000 - d), level(.buildMuscle, 1000 + d), "mirror ±\(d)")
        }
    }

    /// Today fills up 1 → 2 → 3 below its best band, then scores exactly like a finished day.
    func testTodayReadsAsProgressUntilItReachesTheBand() {
        for goal in TrainingGoal.allCases {
            let band = 1000 - FuelCalendar.tolerance(for: goal).under[0]
            var previous = 1
            for p in 0...3000 {
                let today = FuelCalendar.level(kcalEaten: Double(p), kcalGoal: 1000, goal: goal, hasEntries: true, isToday: true)
                let past = FuelCalendar.level(kcalEaten: Double(p), kcalGoal: 1000, goal: goal, hasEntries: true, isToday: false)
                if p < band {
                    XCTAssertEqual(today, min(3, max(1, p / 250)), "\(goal) \(p)")
                    XCTAssertGreaterThanOrEqual(today, previous, "\(goal) \(p)")
                    previous = today
                } else {
                    XCTAssertEqual(today, past, "\(goal) \(p)")
                }
            }
            XCTAssertEqual(FuelCalendar.level(kcalEaten: Double(band), kcalGoal: 1000, goal: goal, hasEntries: true, isToday: true), 4)
        }
    }

    // MARK: - Layout

    func testLayoutVectors() {
        let calendar = Self.warsaw
        for v in V.layout {
            let layout = FuelCalendar.layout(today: day(v.today), calendar: calendar)
            XCTAssertEqual(iso(layout.start), v.start, v.today)
            XCTAssertEqual(iso(layout.today), v.today)
            XCTAssertEqual(layout.columns.count, v.columnCount, v.today)
            XCTAssertEqual(layout.columns.first?.map { iso($0) }, v.firstColumn, v.today)
            XCTAssertEqual(layout.columns.last?.map { iso($0) }, v.lastColumn, v.today)
            XCTAssertEqual(layout.monthLabels.map { $0.column }, v.monthLabels.map { $0.column }, v.today)
            XCTAssertEqual(layout.monthLabels.map { String(iso($0.month)!.prefix(7)) }, v.monthLabels.map { $0.month }, v.today)
            XCTAssertTrue(layout.monthLabels.allSatisfy { calendar.component(.day, from: $0.month) == 1 }, v.today)
            for probe in v.columnOf {
                XCTAssertEqual(layout.column(of: day(probe.date), calendar: calendar), probe.expected, "\(v.today) \(probe.date)")
            }
        }
    }

    /// Across both DST changes every cell is a local midnight, one calendar day after the previous one.
    func testLayoutCellsAreConsecutiveMidnightsAcrossDST() {
        let calendar = Self.warsaw
        for today in ["2026-03-29", "2026-04-02", "2026-10-25", "2026-11-03"] {
            let layout = FuelCalendar.layout(today: day(today), calendar: calendar)
            let days = layout.columns.flatMap { $0 }.compactMap { $0 }
            XCTAssertEqual(days.last, day(today))
            XCTAssertEqual(calendar.component(.weekday, from: layout.start), 2, "starts on a Monday")
            for (a, b) in zip(days, days.dropFirst()) {
                XCTAssertEqual(calendar.startOfDay(for: b), b)
                XCTAssertEqual(calendar.dateComponents([.day], from: a, to: b).day, 1, "\(today): \(iso(a)!) → \(iso(b)!)")
            }
            XCTAssertEqual(days.count, 7 * 25 + calendar.isoWeekday(for: day(today)))
        }
    }

    func testFirstVisibleColumnVectors() {
        for v in V.firstVisibleColumn {
            XCTAssertEqual(FuelCalendar.firstVisibleColumn(selected: v.selected, visible: v.visible, count: v.count), v.expected,
                           "selected \(String(describing: v.selected)) visible \(v.visible) count \(v.count)")
        }
    }

    // MARK: - Stats

    func testStatsVectors() {
        let calendar = Self.warsaw
        for v in V.stats {
            let kcal = Dictionary(uniqueKeysWithValues: v.kcalByDay.map { (day($0.key), $0.value) })
            let stats = FuelCalendar.stats(kcalByDay: kcal, today: day(v.today), kcalGoal: v.kcalGoal, goal: v.goal,
                                           calendar: calendar)
            assertEqual(stats.avg7, v.avg7, v.note)
            assertEqual(stats.avg30, v.avg30, v.note)
            XCTAssertEqual(stats.onTarget30, v.onTarget30, v.note)
        }
    }

    private func assertEqual(_ actual: Double?, _ expected: Double?, _ note: String, line: UInt = #line) {
        switch (actual, expected) {
        case (nil, nil): break
        case let (a?, e?): XCTAssertEqual(a, e, accuracy: 1e-9, note, line: line)
        default: XCTFail("\(String(describing: actual)) != \(String(describing: expected)) (\(note))", line: line)
        }
    }

    // MARK: - Day keys

    /// A day logged at Warsaw midnight stays on its calendar day when read in London, New York or Tokyo.
    func testDayKeySurvivesTimeZoneChange() {
        for zone in ["Europe/London", "America/New_York", "Asia/Tokyo", "Europe/Warsaw"] {
            var abroad = Calendar(identifier: .gregorian)
            abroad.timeZone = TimeZone(identifier: zone)!
            for date in ["2026-03-29", "2026-07-15", "2026-10-25", "2026-12-31"] {
                let stored = day(date)   // Warsaw midnight
                XCTAssertEqual(FuelCalendar.dayKey(stored, calendar: abroad), day(date, abroad), "\(zone) \(date)")
                if zone == "America/New_York" {
                    XCTAssertNotEqual(abroad.startOfDay(for: stored), day(date, abroad), "plain startOfDay moves it a day back")
                }
            }
        }
    }

    func testDayKeyVectors() {
        let utc = ISO8601DateFormatter()
        for v in V.dayKey {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: v.zone)!
            XCTAssertEqual(iso(FuelCalendar.dayKey(utc.date(from: v.storedUtc)!, calendar: calendar), calendar), v.expected,
                           "\(v.storedUtc) in \(v.zone)")
        }
    }

    func testStoredDayBoundsVectors() {
        let utc = ISO8601DateFormatter()
        for v in V.storedDayBounds {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: v.zone)!
            let bounds = FuelCalendar.storedDayBounds(for: day(v.day, calendar), calendar: calendar)
            XCTAssertEqual(bounds.lower, utc.date(from: v.lowerUtc), v.day)
            XCTAssertEqual(bounds.upper, utc.date(from: v.upperUtc), v.day)
        }
    }

    /// `storedDayBounds` is exactly the set of stored values whose `dayKey` is that day, DST days included.
    func testStoredDayBoundsMatchDayKey() {
        let calendar = Self.warsaw
        for date in ["2026-03-28", "2026-03-29", "2026-03-30", "2026-10-24", "2026-10-25", "2026-10-26"] {
            let target = day(date)
            let (lower, upper) = FuelCalendar.storedDayBounds(for: target, calendar: calendar)
            var probe = target.addingTimeInterval(-36 * 3600)
            while probe < target.addingTimeInterval(48 * 3600) {
                let inBounds = probe >= lower && probe < upper
                XCTAssertEqual(FuelCalendar.dayKey(probe, calendar: calendar) == target, inBounds, "\(date) \(probe)")
                probe.addTimeInterval(15 * 60)
            }
        }
    }

    // MARK: - Data

    func testKcalByDaySumsEntriesPerDay() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        func daysAgo(_ n: Int) -> Date { calendar.date(byAdding: .day, value: -n, to: today)! }
        func log(_ kcal: Double, on day: Date) -> MealEntry {
            let entry = MealEntry(day: day, slot: .lunch, customName: "x", grams: 0, kcal: kcal, proteinG: 0, carbsG: 0, fatG: 0)
            context.insert(entry)
            return entry
        }
        _ = log(500, on: today)
        _ = log(700, on: daysAgo(1))
        _ = log(800, on: daysAgo(1))
        _ = log(0, on: daysAgo(3))
        _ = log(900, on: daysAgo(40))
        _ = log(999, on: daysAgo(41))
        // Logged an hour further east: its stored midnight is an hour early, but it still belongs to that day.
        log(300, on: daysAgo(2)).day = daysAgo(2).addingTimeInterval(-3600)
        try context.save()

        let totals = FuelCalendar.kcalByDay(from: daysAgo(40), in: context)

        XCTAssertEqual(totals[today], 500)
        XCTAssertEqual(totals[daysAgo(1)], 1500)
        XCTAssertEqual(totals[daysAgo(2)], 300)
        XCTAssertEqual(totals[daysAgo(3)], 0, "a logged day with 0 kcal is still logged")
        XCTAssertEqual(totals[daysAgo(40)], 900)
        XCTAssertNil(totals[daysAgo(41)], "before the window")
        XCTAssertNil(totals[daysAgo(4)])
        XCTAssertEqual(totals.count, 5)
    }
}

// MARK: - Shared vectors

/// Tables shared with Android; the generated part below mirrors the JSON contract field by field.
enum FuelCalendarVectors {
    struct LevelVector {
        let goal: TrainingGoal
        let kcalGoal: Double
        let eaten: Double
        let has: Bool
        let today: Bool
        let expected: Int
        let note: String

        init(_ goal: TrainingGoal, kcalGoal: Double, eaten: Double, has: Bool, today: Bool, expected: Int, note: String) {
            self.goal = goal
            self.kcalGoal = kcalGoal
            self.eaten = eaten
            self.has = has
            self.today = today
            self.expected = expected
            self.note = note
        }
    }

    struct LayoutVector {
        let today: String
        let start: String
        let columnCount: Int
        let firstColumn: [String?]
        let lastColumn: [String?]
        let monthLabels: [(column: Int, month: String)]
        let columnOf: [(date: String, expected: Int?)]
    }

    struct StatsVector {
        let today: String
        let kcalGoal: Double
        let goal: TrainingGoal
        let kcalByDay: [String: Double]
        let avg7: Double?
        let avg30: Double?
        let onTarget30: Int
        let note: String
    }

    // Generated from contracts/fuel-calendar-vectors.json (shared with Android FuelCalendarTest). Do not edit by hand.
    static let permille: [(kcalEaten: Double, kcalGoal: Double, expected: Int)] = [
        (2000, 2000, 1000),
        (2200, 2000, 1100),
        (1799, 2000, 900),
        (1939, 2000, 970),
        (2061, 2000, 1031),
        (1299, 2000, 650),
        (1, 2000, 1),
        (3, 2000, 2),
        (0, 2000, 0),
        (-50, 2000, 0),
        (1000000000000.0, 2000, 10000),
        (5, 1e-09, 10000),
        (2100, 2350, 894),
        (2900, 3050, 951),
        (2000, 0, 0),
        (2000, -1, 0),
        (20000, 2000, 10000),
        (20001, 2000, 10000),
    ]

    static let level: [LevelVector] = [
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1000, has: true, today: false, expected: 4, note: "exactly on goal"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 900, has: true, today: false, expected: 4, note: "under edge for level 4"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 899, has: true, today: false, expected: 3, note: "just past the under edge for level 4"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 800, has: true, today: false, expected: 3, note: "under edge for level 3"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 799, has: true, today: false, expected: 2, note: "just past the under edge for level 3"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 650, has: true, today: false, expected: 2, note: "under edge for level 2"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 649, has: true, today: false, expected: 1, note: "just past the under edge for level 2"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1030, has: true, today: false, expected: 4, note: "over edge for level 4"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1031, has: true, today: false, expected: 3, note: "just past the over edge for level 4"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1080, has: true, today: false, expected: 3, note: "over edge for level 3"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1081, has: true, today: false, expected: 2, note: "just past the over edge for level 3"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1150, has: true, today: false, expected: 2, note: "over edge for level 2"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1151, has: true, today: false, expected: 1, note: "just past the over edge for level 2"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 0, has: true, today: false, expected: 1, note: "logged but 0 kcal"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 10000, has: true, today: false, expected: 1, note: "10x goal"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1000, has: true, today: false, expected: 4, note: "exactly on goal"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 970, has: true, today: false, expected: 4, note: "under edge for level 4"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 969, has: true, today: false, expected: 3, note: "just past the under edge for level 4"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 920, has: true, today: false, expected: 3, note: "under edge for level 3"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 919, has: true, today: false, expected: 2, note: "just past the under edge for level 3"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 850, has: true, today: false, expected: 2, note: "under edge for level 2"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 849, has: true, today: false, expected: 1, note: "just past the under edge for level 2"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1100, has: true, today: false, expected: 4, note: "over edge for level 4"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1101, has: true, today: false, expected: 3, note: "just past the over edge for level 4"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1200, has: true, today: false, expected: 3, note: "over edge for level 3"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1201, has: true, today: false, expected: 2, note: "just past the over edge for level 3"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1350, has: true, today: false, expected: 2, note: "over edge for level 2"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1351, has: true, today: false, expected: 1, note: "just past the over edge for level 2"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 0, has: true, today: false, expected: 1, note: "logged but 0 kcal"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 10000, has: true, today: false, expected: 1, note: "10x goal"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1000, has: true, today: false, expected: 4, note: "exactly on goal"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 950, has: true, today: false, expected: 4, note: "under edge for level 4"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 949, has: true, today: false, expected: 3, note: "just past the under edge for level 4"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 900, has: true, today: false, expected: 3, note: "under edge for level 3"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 899, has: true, today: false, expected: 2, note: "just past the under edge for level 3"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 800, has: true, today: false, expected: 2, note: "under edge for level 2"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 799, has: true, today: false, expected: 1, note: "just past the under edge for level 2"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1050, has: true, today: false, expected: 4, note: "over edge for level 4"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1051, has: true, today: false, expected: 3, note: "just past the over edge for level 4"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1100, has: true, today: false, expected: 3, note: "over edge for level 3"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1101, has: true, today: false, expected: 2, note: "just past the over edge for level 3"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1200, has: true, today: false, expected: 2, note: "over edge for level 2"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1201, has: true, today: false, expected: 1, note: "just past the over edge for level 2"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 0, has: true, today: false, expected: 1, note: "logged but 0 kcal"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 10000, has: true, today: false, expected: 1, note: "10x goal"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2000, has: true, today: false, expected: 4, note: ""),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1800, has: true, today: false, expected: 4, note: "900 permille edge"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1799, has: true, today: false, expected: 4, note: "899.5 rounds up to 900"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1600, has: true, today: false, expected: 3, note: ""),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1300, has: true, today: false, expected: 2, note: "650 permille edge"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1299, has: true, today: false, expected: 2, note: "649.5 rounds up to 650"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1200, has: true, today: false, expected: 1, note: ""),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2060, has: true, today: false, expected: 4, note: "1030 permille edge"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2061, has: true, today: false, expected: 3, note: "1030.5 rounds up to 1031"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2160, has: true, today: false, expected: 3, note: ""),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2300, has: true, today: false, expected: 2, note: "1150 permille edge"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2301, has: true, today: false, expected: 1, note: ""),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2200, has: true, today: false, expected: 2, note: "2200/2000 is 1.1000000000000001 as a Double"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1940, has: true, today: false, expected: 4, note: "970 permille edge"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1939, has: true, today: false, expected: 4, note: "969.5 rounds up to 970"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1840, has: true, today: false, expected: 3, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1700, has: true, today: false, expected: 2, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1600, has: true, today: false, expected: 1, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 2200, has: true, today: false, expected: 4, note: "1100 permille edge"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 2400, has: true, today: false, expected: 3, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 2700, has: true, today: false, expected: 2, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 2701, has: true, today: false, expected: 1, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 2100, has: true, today: false, expected: 4, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 1900, has: true, today: false, expected: 4, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 1800, has: true, today: false, expected: 3, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 2400, has: true, today: false, expected: 2, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 2401, has: true, today: false, expected: 1, note: ""),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 1599, has: true, today: false, expected: 2, note: ""),
        LevelVector(.loseFat, kcalGoal: 2350, eaten: 2100, has: true, today: false, expected: 3, note: "cut goal, 89.4 %"),
        LevelVector(.loseFat, kcalGoal: 2350, eaten: 2700, has: true, today: false, expected: 2, note: "cut goal, 114.9 %"),
        LevelVector(.buildMuscle, kcalGoal: 3050, eaten: 2900, has: true, today: false, expected: 3, note: "bulk goal, 95.1 %"),
        LevelVector(.buildMuscle, kcalGoal: 3050, eaten: 3350, has: true, today: false, expected: 4, note: "bulk goal, 109.8 %"),
        LevelVector(.maintain, kcalGoal: 2750, eaten: 2600, has: true, today: false, expected: 3, note: "maintenance, 94.5 %"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 0, has: true, today: true, expected: 1, note: "today, 0 %"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1, has: true, today: true, expected: 1, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 249, has: true, today: true, expected: 1, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 250, has: true, today: true, expected: 1, note: "progress threshold 25 %"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 499, has: true, today: true, expected: 1, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 500, has: true, today: true, expected: 2, note: "progress threshold 50 %"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 749, has: true, today: true, expected: 2, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 750, has: true, today: true, expected: 3, note: "progress threshold 75 %"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 899, has: true, today: true, expected: 3, note: "just below the best band"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 900, has: true, today: true, expected: 4, note: "reaches the best band"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1000, has: true, today: true, expected: 4, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1030, has: true, today: true, expected: 4, note: ""),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1031, has: true, today: true, expected: 3, note: "over the goal: normal scoring"),
        LevelVector(.loseFat, kcalGoal: 1000, eaten: 1151, has: true, today: true, expected: 1, note: "far over: normal scoring"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 0, has: true, today: true, expected: 1, note: "today, 0 %"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1, has: true, today: true, expected: 1, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 249, has: true, today: true, expected: 1, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 250, has: true, today: true, expected: 1, note: "progress threshold 25 %"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 499, has: true, today: true, expected: 1, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 500, has: true, today: true, expected: 2, note: "progress threshold 50 %"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 749, has: true, today: true, expected: 2, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 750, has: true, today: true, expected: 3, note: "progress threshold 75 %"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 969, has: true, today: true, expected: 3, note: "just below the best band"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 970, has: true, today: true, expected: 4, note: "reaches the best band"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1000, has: true, today: true, expected: 4, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1100, has: true, today: true, expected: 4, note: ""),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1101, has: true, today: true, expected: 3, note: "over the goal: normal scoring"),
        LevelVector(.buildMuscle, kcalGoal: 1000, eaten: 1351, has: true, today: true, expected: 1, note: "far over: normal scoring"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 0, has: true, today: true, expected: 1, note: "today, 0 %"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1, has: true, today: true, expected: 1, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 249, has: true, today: true, expected: 1, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 250, has: true, today: true, expected: 1, note: "progress threshold 25 %"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 499, has: true, today: true, expected: 1, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 500, has: true, today: true, expected: 2, note: "progress threshold 50 %"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 749, has: true, today: true, expected: 2, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 750, has: true, today: true, expected: 3, note: "progress threshold 75 %"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 949, has: true, today: true, expected: 3, note: "just below the best band"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 950, has: true, today: true, expected: 4, note: "reaches the best band"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1000, has: true, today: true, expected: 4, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1050, has: true, today: true, expected: 4, note: ""),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1051, has: true, today: true, expected: 3, note: "over the goal: normal scoring"),
        LevelVector(.maintain, kcalGoal: 1000, eaten: 1201, has: true, today: true, expected: 1, note: "far over: normal scoring"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 300, has: true, today: true, expected: 1, note: "diagnosis table, today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1000, has: true, today: true, expected: 2, note: "diagnosis table, today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1500, has: true, today: true, expected: 3, note: "diagnosis table, today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1800, has: true, today: true, expected: 4, note: "diagnosis table, today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2200, has: true, today: true, expected: 2, note: "diagnosis table, today"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1900, has: true, today: true, expected: 3, note: "diagnosis table, today"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 1940, has: true, today: true, expected: 4, note: "diagnosis table, today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 0, has: false, today: false, expected: 0, note: "nothing logged"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 0, has: false, today: true, expected: 0, note: "nothing logged today"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 0, has: false, today: false, expected: 0, note: "nothing logged"),
        LevelVector(.buildMuscle, kcalGoal: 2000, eaten: 0, has: false, today: true, expected: 0, note: "nothing logged today"),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 0, has: false, today: false, expected: 0, note: "nothing logged"),
        LevelVector(.maintain, kcalGoal: 2000, eaten: 0, has: false, today: true, expected: 0, note: "nothing logged today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 2000, has: false, today: false, expected: 0, note: "kcal without entries still reads as nothing logged"),
        LevelVector(.maintain, kcalGoal: 0, eaten: 1500, has: true, today: false, expected: 1, note: "goal 0: logged but unscorable"),
        LevelVector(.maintain, kcalGoal: -100, eaten: 1500, has: true, today: true, expected: 1, note: "negative goal: logged but unscorable"),
        LevelVector(.buildMuscle, kcalGoal: 0, eaten: 0, has: false, today: false, expected: 0, note: "goal 0 and nothing logged"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: -50, has: true, today: false, expected: 1, note: "negative kcal clamps to 0 %"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: -50, has: true, today: true, expected: 1, note: "negative kcal today"),
        LevelVector(.loseFat, kcalGoal: 2000, eaten: 1000000000000.0, has: true, today: false, expected: 1, note: "huge kcal clamps to 10 000 permille"),
        LevelVector(.maintain, kcalGoal: 1e-09, eaten: 5, has: true, today: false, expected: 1, note: "tiny goal clamps to 10 000 permille"),
    ]

    static let layout: [LayoutVector] = [
        LayoutVector(today: "2026-09-22", start: "2026-03-30", columnCount: 26,
                     firstColumn: ["2026-03-30", "2026-03-31", "2026-04-01", "2026-04-02", "2026-04-03", "2026-04-04", "2026-04-05"],
                     lastColumn: ["2026-09-21", "2026-09-22", nil, nil, nil, nil, nil],
                     monthLabels: [(0, "2026-04"), (4, "2026-05"), (9, "2026-06"), (13, "2026-07"), (17, "2026-08"), (22, "2026-09")],
                     columnOf: [("2026-03-30", 0), ("2026-03-29", nil), ("2026-09-22", 25), ("2026-09-23", nil), ("2026-04-12", 1), ("2026-04-13", 2), ("2026-09-20", 24)]),
        LayoutVector(today: "2026-09-27", start: "2026-03-30", columnCount: 26,
                     firstColumn: ["2026-03-30", "2026-03-31", "2026-04-01", "2026-04-02", "2026-04-03", "2026-04-04", "2026-04-05"],
                     lastColumn: ["2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27"],
                     monthLabels: [(0, "2026-04"), (4, "2026-05"), (9, "2026-06"), (13, "2026-07"), (17, "2026-08"), (22, "2026-09")],
                     columnOf: [("2026-03-30", 0), ("2026-03-29", nil), ("2026-09-27", 25), ("2026-09-28", nil), ("2026-04-12", 1), ("2026-04-13", 2), ("2026-09-20", 24)]),
        LayoutVector(today: "2026-09-21", start: "2026-03-30", columnCount: 26,
                     firstColumn: ["2026-03-30", "2026-03-31", "2026-04-01", "2026-04-02", "2026-04-03", "2026-04-04", "2026-04-05"],
                     lastColumn: ["2026-09-21", nil, nil, nil, nil, nil, nil],
                     monthLabels: [(0, "2026-04"), (4, "2026-05"), (9, "2026-06"), (13, "2026-07"), (17, "2026-08"), (22, "2026-09")],
                     columnOf: [("2026-03-30", 0), ("2026-03-29", nil), ("2026-09-21", 25), ("2026-09-22", nil), ("2026-04-12", 1), ("2026-04-13", 2), ("2026-09-20", 24)]),
        LayoutVector(today: "2026-01-05", start: "2025-07-14", columnCount: 26,
                     firstColumn: ["2025-07-14", "2025-07-15", "2025-07-16", "2025-07-17", "2025-07-18", "2025-07-19", "2025-07-20"],
                     lastColumn: ["2026-01-05", nil, nil, nil, nil, nil, nil],
                     monthLabels: [(0, "2025-07"), (2, "2025-08"), (7, "2025-09"), (11, "2025-10"), (15, "2025-11"), (20, "2025-12"), (24, "2026-01")],
                     columnOf: [("2025-07-14", 0), ("2025-07-13", nil), ("2026-01-05", 25), ("2026-01-06", nil), ("2025-07-27", 1), ("2025-07-28", 2), ("2026-01-04", 24)]),
        LayoutVector(today: "2026-06-01", start: "2025-12-08", columnCount: 26,
                     firstColumn: ["2025-12-08", "2025-12-09", "2025-12-10", "2025-12-11", "2025-12-12", "2025-12-13", "2025-12-14"],
                     lastColumn: ["2026-06-01", nil, nil, nil, nil, nil, nil],
                     monthLabels: [(0, "2025-12"), (3, "2026-01"), (7, "2026-02"), (11, "2026-03"), (16, "2026-04"), (20, "2026-05"), (25, "2026-06")],
                     columnOf: [("2025-12-08", 0), ("2025-12-07", nil), ("2026-06-01", 25), ("2026-06-02", nil), ("2025-12-21", 1), ("2025-12-22", 2), ("2026-05-31", 24)]),
        LayoutVector(today: "2026-03-29", start: "2025-09-29", columnCount: 26,
                     firstColumn: ["2025-09-29", "2025-09-30", "2025-10-01", "2025-10-02", "2025-10-03", "2025-10-04", "2025-10-05"],
                     lastColumn: ["2026-03-23", "2026-03-24", "2026-03-25", "2026-03-26", "2026-03-27", "2026-03-28", "2026-03-29"],
                     monthLabels: [(0, "2025-10"), (4, "2025-11"), (9, "2025-12"), (13, "2026-01"), (17, "2026-02"), (21, "2026-03")],
                     columnOf: [("2025-09-29", 0), ("2025-09-28", nil), ("2026-03-29", 25), ("2026-03-30", nil), ("2025-10-12", 1), ("2025-10-13", 2), ("2026-03-22", 24)]),
        LayoutVector(today: "2026-10-25", start: "2026-04-27", columnCount: 26,
                     firstColumn: ["2026-04-27", "2026-04-28", "2026-04-29", "2026-04-30", "2026-05-01", "2026-05-02", "2026-05-03"],
                     lastColumn: ["2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25"],
                     monthLabels: [(0, "2026-05"), (5, "2026-06"), (9, "2026-07"), (13, "2026-08"), (18, "2026-09"), (22, "2026-10")],
                     columnOf: [("2026-04-27", 0), ("2026-04-26", nil), ("2026-10-25", 25), ("2026-10-26", nil), ("2026-05-10", 1), ("2026-05-11", 2), ("2026-10-18", 24)]),
        LayoutVector(today: "2027-01-01", start: "2026-07-06", columnCount: 26,
                     firstColumn: ["2026-07-06", "2026-07-07", "2026-07-08", "2026-07-09", "2026-07-10", "2026-07-11", "2026-07-12"],
                     lastColumn: ["2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01", nil, nil],
                     monthLabels: [(0, "2026-07"), (3, "2026-08"), (8, "2026-09"), (12, "2026-10"), (16, "2026-11"), (21, "2026-12"), (25, "2027-01")],
                     columnOf: [("2026-07-06", 0), ("2026-07-05", nil), ("2027-01-01", 25), ("2027-01-02", nil), ("2026-07-19", 1), ("2026-07-20", 2), ("2026-12-27", 24)]),
    ]

    static let firstVisibleColumn: [(selected: Int?, visible: Int, count: Int, expected: Int)] = [
        (nil, 11, 26, 15),
        (25, 11, 26, 15),
        (15, 11, 26, 15),
        (14, 11, 26, 9),
        (10, 11, 26, 5),
        (5, 11, 26, 0),
        (3, 11, 26, 0),
        (0, 11, 26, 0),
        (20, 12, 26, 14),
        (8, 12, 26, 2),
        (5, 30, 26, 0),
        (nil, 30, 26, 0),
        (0, 1, 26, 0),
        (24, 1, 26, 24),
        (25, 1, 26, 25),
    ]

    static let stats: [StatsVector] = [
        StatsVector(today: "2026-09-22", kcalGoal: 2000, goal: .loseFat,
                    kcalByDay: ["2026-09-22": 500, "2026-09-21": 2000, "2026-09-20": 2500, "2026-09-14": 1900, "2026-08-23": 1000, "2026-08-22": 3000],
                    avg7: 2250.0, avg30: 1850.0, onTarget30: 2, note: "diagnosis vector: today and day 31 are outside every window"),
        StatsVector(today: "2026-09-22", kcalGoal: 2000, goal: .loseFat,
                    kcalByDay: [:],
                    avg7: nil, avg30: nil, onTarget30: 0, note: "nothing logged"),
        StatsVector(today: "2026-09-22", kcalGoal: 2000, goal: .loseFat,
                    kcalByDay: ["2026-09-22": 2000],
                    avg7: nil, avg30: nil, onTarget30: 0, note: "only today logged: no averages"),
        StatsVector(today: "2026-03-30", kcalGoal: 3050, goal: .buildMuscle,
                    kcalByDay: ["2026-03-29": 3050, "2026-03-28": 3350, "2026-03-27": 2958, "2026-03-23": 2900, "2026-03-22": 3100, "2026-03-01": 3000, "2026-02-28": 3200],
                    avg7: 3064.5, avg30: 3079.714285714286, onTarget30: 6, note: "window across the DST change (29 Mar 2026)"),
        StatsVector(today: "2026-09-22", kcalGoal: 2750, goal: .maintain,
                    kcalByDay: ["2026-09-21": 2750, "2026-09-20": 2750, "2026-09-19": 2750, "2026-09-18": 2750, "2026-09-17": 2750, "2026-09-16": 2750, "2026-09-15": 2750, "2026-09-14": 2750, "2026-09-13": 2750, "2026-09-12": 2750, "2026-09-11": 2750, "2026-09-10": 2750, "2026-09-09": 2750, "2026-09-08": 2750, "2026-09-07": 2750, "2026-09-06": 2750, "2026-09-05": 2750, "2026-09-04": 2750, "2026-09-03": 2750, "2026-09-02": 2750, "2026-09-01": 2750, "2026-08-31": 2750, "2026-08-30": 2750, "2026-08-29": 2750, "2026-08-28": 2750, "2026-08-27": 2750, "2026-08-26": 2750, "2026-08-25": 2750, "2026-08-24": 2750, "2026-08-23": 2750],
                    avg7: 2750.0, avg30: 2750.0, onTarget30: 30, note: "every day on target"),
    ]

    static let rolledDay: [(selected: String, previousToday: String, today: String, expected: String, note: String)] = [
        ("2026-09-22", "2026-09-22", "2026-09-23", "2026-09-23", "on today: follows midnight"),
        ("2026-09-19", "2026-09-22", "2026-09-23", "2026-09-19", "browsing the past: stays"),
        ("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-21", "yesterday stays (it is now two days ago)"),
        ("2026-09-22", "2026-09-22", "2026-09-25", "2026-09-25", "on today: jumps several days"),
        ("2026-09-22", "2026-09-22", "2026-09-21", "2026-09-21", "clock moved back a day: follows"),
        ("2026-09-23", "2026-09-23", "2026-09-22", "2026-09-22", "clock moved back: today follows"),
        ("2026-09-24", "2026-09-22", "2026-09-23", "2026-09-23", "ahead of the new today: clamps"),
    ]

    static let resumedDay: [(selected: String, today: String, awaySeconds: Double?, expected: String, note: String)] = [
        ("2026-09-19", "2026-09-22", 1740, "2026-09-19", "past day, 29 min away: stays"),
        ("2026-09-19", "2026-09-22", 1800, "2026-09-19", "past day, exactly 30 min away: stays"),
        ("2026-09-19", "2026-09-22", 1801, "2026-09-22", "past day, just over 30 min away: back to today"),
        ("2026-09-19", "2026-09-22", 28800, "2026-09-22", "past day, overnight: back to today"),
        ("2026-09-22", "2026-09-22", 28800, "2026-09-22", "already on today"),
        ("2026-09-19", "2026-09-22", nil, "2026-09-19", "unknown away time (never saw the app leave): stays"),
    ]

    static let dayKey: [(storedUtc: String, zone: String, expected: String)] = [
        ("2026-03-28T23:00:00Z", "Europe/Warsaw", "2026-03-29"),
        ("2026-03-28T23:00:00Z", "Europe/London", "2026-03-29"),
        ("2026-03-28T23:00:00Z", "America/New_York", "2026-03-29"),
        ("2026-03-28T23:00:00Z", "Asia/Tokyo", "2026-03-29"),
        ("2026-07-14T22:00:00Z", "Europe/Warsaw", "2026-07-15"),
        ("2026-07-14T22:00:00Z", "Europe/London", "2026-07-15"),
        ("2026-07-14T22:00:00Z", "America/New_York", "2026-07-15"),
        ("2026-07-14T22:00:00Z", "Asia/Tokyo", "2026-07-15"),
        ("2026-10-24T22:00:00Z", "Europe/Warsaw", "2026-10-25"),
        ("2026-10-24T22:00:00Z", "Europe/London", "2026-10-25"),
        ("2026-10-24T22:00:00Z", "America/New_York", "2026-10-25"),
        ("2026-10-24T22:00:00Z", "Asia/Tokyo", "2026-10-25"),
        ("2026-12-30T23:00:00Z", "Europe/Warsaw", "2026-12-31"),
        ("2026-12-30T23:00:00Z", "Europe/London", "2026-12-31"),
        ("2026-12-30T23:00:00Z", "America/New_York", "2026-12-31"),
        ("2026-12-30T23:00:00Z", "Asia/Tokyo", "2026-12-31"),
    ]

    static let storedDayBounds: [(day: String, zone: String, lowerUtc: String, upperUtc: String)] = [
        ("2026-03-28", "Europe/Warsaw", "2026-03-27T11:00:00Z", "2026-03-28T11:00:00Z"),
        ("2026-03-29", "Europe/Warsaw", "2026-03-28T11:00:00Z", "2026-03-29T10:00:00Z"),
        ("2026-03-30", "Europe/Warsaw", "2026-03-29T10:00:00Z", "2026-03-30T10:00:00Z"),
        ("2026-10-24", "Europe/Warsaw", "2026-10-23T10:00:00Z", "2026-10-24T10:00:00Z"),
        ("2026-10-25", "Europe/Warsaw", "2026-10-24T10:00:00Z", "2026-10-25T11:00:00Z"),
        ("2026-10-26", "Europe/Warsaw", "2026-10-25T11:00:00Z", "2026-10-26T11:00:00Z"),
    ]
}
