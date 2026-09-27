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

    func testStreakDaysStopReadingAtTheFirstGap() {
        let today = date(2026, 9, 26)
        // Newest first; 12 Sep has no completed set.
        let items: [(day: Date, counts: Bool)] = [
            (date(2026, 9, 21), true), (date(2026, 9, 16), true), (date(2026, 9, 12), false),
            (date(2026, 9, 8), true), (date(2026, 8, 20), true), (date(2026, 8, 1), true),
        ]
        var opened: [Date] = []
        let days = TrainingCalendar.streakDays(newestFirst: items, date: { $0.day },
                                               counts: { opened.append($0.day); return $0.counts },
                                               today: today, calendar: calendar)
        XCTAssertEqual(days, [date(2026, 9, 21), date(2026, 9, 16), date(2026, 9, 8)])
        XCTAssertEqual(opened.count, 4, "nothing past the gap before 7 Sep is opened")
        XCTAssertEqual(TrainingCalendar.weekStreak(workoutDays: days, today: today, calendar: calendar),
                       TrainingCalendar.weekStreak(workoutDays: items.filter { $0.counts }.map { $0.day }, today: today,
                                                   calendar: calendar))
        // Nothing this week or last: nothing to read.
        XCTAssertEqual(TrainingCalendar.streakDays(newestFirst: [date(2026, 9, 1)], date: { $0 }, counts: { _ in true },
                                                   today: today, calendar: calendar), [])
    }
}
