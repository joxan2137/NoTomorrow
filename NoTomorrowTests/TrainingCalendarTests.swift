import XCTest
@testable import NoTomorrow

/// Progress training calendar: month grid (Monday first), day levels and the weekly streak.
final class TrainingCalendarTests: XCTestCase {
    private var calendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Warsaw")!
        return cal
    }()

    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))!
    }

    func testSeptember2026StartsOnTuesday() {
        let weeks = TrainingCalendar.weeks(of: date(2026, 9, 15), calendar: calendar)
        XCTAssertEqual(weeks.count, 5)
        XCTAssertNil(weeks[0][0], "Monday 31 Aug is outside the month")
        XCTAssertEqual(calendar.component(.day, from: weeks[0][1]!), 1)
        XCTAssertEqual(calendar.component(.day, from: weeks[4][2]!), 30)
        XCTAssertNil(weeks[4][3])
        XCTAssertTrue(weeks.allSatisfy { $0.count == 7 })
    }

    func testLevels() {
        XCTAssertEqual([0, 1, 9, 10, 17, 18, 25, 26, 60].map(TrainingCalendar.level(sets:)), [0, 1, 1, 2, 2, 3, 3, 4, 4])
    }

    func testWeekStreakCountsBackFromThisOrLastWeek() {
        let today = date(2026, 9, 26)   // Saturday
        let days = [date(2026, 9, 21), date(2026, 9, 16), date(2026, 9, 8), date(2026, 8, 20)]
        XCTAssertEqual(TrainingCalendar.weekStreak(workoutDays: days, today: today, calendar: calendar), 3)
        let lastWeekOnly = [date(2026, 9, 16), date(2026, 9, 9)]
        XCTAssertEqual(TrainingCalendar.weekStreak(workoutDays: lastWeekOnly, today: today, calendar: calendar), 2)
        XCTAssertEqual(TrainingCalendar.weekStreak(workoutDays: [date(2026, 9, 1)], today: today, calendar: calendar), 0)
    }
}
