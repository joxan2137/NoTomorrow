import XCTest
@testable import NoTomorrow

/// Progress weekly stats: eight weeks on the calendar's first weekday, every metric summed per week.
final class WeeklyStatsTests: XCTestCase {

    private func calendar(firstWeekday: Int) -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Warsaw")!
        cal.firstWeekday = firstWeekday
        return cal
    }

    private func date(_ y: Int, _ m: Int, _ d: Int, hour: Int = 18, in cal: Calendar) -> Date {
        cal.date(from: DateComponents(year: y, month: m, day: d, hour: hour))!
    }

    private func session(_ start: Date, minutes: Double = 60, volume: Double = 1000, sets: Int = 10) -> WeeklyStats.Session {
        WeeklyStats.Session(startedAt: start, duration: minutes * 60, volumeKg: volume, sets: sets)
    }

    func testEightWeeksOldestFirstEndingThisWeekOnMonday() {
        let cal = calendar(firstWeekday: 2)
        let now = date(2026, 9, 26, in: cal)   // Saturday
        let weeks = WeeklyStats.weeks(sessions: [], now: now, calendar: cal)
        XCTAssertEqual(weeks.count, 8)
        XCTAssertEqual(weeks.last?.weekStart, cal.startOfDay(for: date(2026, 9, 21, in: cal)))
        XCTAssertEqual(weeks.first?.weekStart, cal.startOfDay(for: date(2026, 8, 3, in: cal)))
        XCTAssertEqual(weeks.filter(\.isCurrent).count, 1)
        XCTAssertTrue(weeks.last?.isCurrent ?? false)
        XCTAssertTrue(weeks.allSatisfy { $0.workouts == 0 && $0.volumeKg == 0 && $0.duration == 0 && $0.sets == 0 })
    }

    func testWeeksFollowTheCalendarsFirstWeekday() {
        let cal = calendar(firstWeekday: 1)   // Sunday first
        let now = date(2026, 9, 26, in: cal)
        let sunday = date(2026, 9, 20, in: cal)
        let saturdayBefore = date(2026, 9, 19, in: cal)
        let weeks = WeeklyStats.weeks(sessions: [session(sunday), session(saturdayBefore)], now: now, calendar: cal)
        XCTAssertEqual(weeks.last?.weekStart, cal.startOfDay(for: sunday))
        XCTAssertEqual(weeks.last?.workouts, 1, "Sunday opens the week")
        XCTAssertEqual(weeks[6].workouts, 1, "Saturday closes the one before")

        let monday = calendar(firstWeekday: 2)
        let mondayWeeks = WeeklyStats.weeks(sessions: [session(sunday), session(saturdayBefore)],
                                            now: now, calendar: monday)
        XCTAssertEqual(mondayWeeks.last?.workouts, 0)
        XCTAssertEqual(mondayWeeks[6].workouts, 2, "Monday-first: both belong to last week")
    }

    func testSumsEveryMetricPerWeek() {
        let cal = calendar(firstWeekday: 2)
        let now = date(2026, 9, 26, in: cal)
        let weeks = WeeklyStats.weeks(sessions: [
            session(date(2026, 9, 22, in: cal), minutes: 50, volume: 4000, sets: 18),
            session(date(2026, 9, 24, in: cal), minutes: 70, volume: 6000, sets: 22),
            session(date(2026, 9, 15, in: cal), minutes: 45, volume: 2500, sets: 12),
            session(date(2026, 7, 1, in: cal)),   // older than eight weeks
        ], now: now, calendar: cal)
        let this = weeks[7]
        XCTAssertEqual(this.workouts, 2)
        XCTAssertEqual(this.volumeKg, 10_000)
        XCTAssertEqual(this.duration, 120 * 60)
        XCTAssertEqual(this.sets, 40)
        XCTAssertEqual(weeks[6].value(.workouts), 1)
        XCTAssertEqual(weeks[6].value(.volume), 2500)
        XCTAssertEqual(weeks[6].value(.duration), 45 * 60)
        XCTAssertEqual(weeks[6].value(.sets), 12)
        XCTAssertEqual(weeks.map(\.workouts).reduce(0, +), 3)
    }

    func testChangeAgainstLastWeek() {
        let cal = calendar(firstWeekday: 2)
        let now = date(2026, 9, 26, in: cal)
        let weeks = WeeklyStats.weeks(sessions: [
            session(date(2026, 9, 22, in: cal), volume: 5000),
            session(date(2026, 9, 15, in: cal), volume: 4000),
        ], now: now, calendar: cal)
        XCTAssertEqual(WeeklyStats.change(weeks, metric: .volume)!, 0.25, accuracy: 0.0001)
        XCTAssertEqual(WeeklyStats.change(weeks, metric: .workouts)!, 0, accuracy: 0.0001)
        let lonely = WeeklyStats.weeks(sessions: [session(date(2026, 9, 22, in: cal))], now: now, calendar: cal)
        XCTAssertNil(WeeklyStats.change(lonely, metric: .sets), "nothing to compare against")
    }
}
