import XCTest
import SwiftData
@testable import NoTomorrow

/// Fuel's day boundary and navigation: midnight rollover, the 30-minute snap-back, jumps from the History sheet,
/// the day query's time-zone window and the header's day title.
@MainActor
final class FuelDayNavigationTests: XCTestCase {
    private let calendar = Calendar.current

    /// Noon on 22 Sep 2026 in the test machine's zone: far from midnight and from DST changes.
    private var base: Date { calendar.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 12))! }

    private func day(_ offset: Int, from date: Date? = nil) -> Date {
        calendar.startOfDay(for: calendar.date(byAdding: .day, value: offset, to: date ?? base)!)
    }

    private func isoDay(_ iso: String) -> Date {
        let p = iso.split(separator: "-").map { Int($0)! }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))!
    }

    // MARK: - Shared vectors

    func testRolledDayVectors() {
        for v in FuelCalendarVectors.rolledDay {
            let rolled = FuelModel.rolledDay(selected: isoDay(v.selected), previousToday: isoDay(v.previousToday),
                                             today: isoDay(v.today))
            XCTAssertEqual(rolled, isoDay(v.expected), v.note)
        }
    }

    func testResumedDayVectors() {
        XCTAssertEqual(FuelModel.snapBackInterval, 30 * 60)
        for v in FuelCalendarVectors.resumedDay {
            let resumed = FuelModel.resumedDay(selected: isoDay(v.selected), today: isoDay(v.today), awayFor: v.awaySeconds)
            XCTAssertEqual(resumed, isoDay(v.expected), v.note)
        }
    }

    // MARK: - Midnight rollover

    func testShowingTodayFollowsMidnight() {
        let model = FuelModel(now: base)
        XCTAssertEqual(model.day, day(0))

        model.syncToday(now: day(1).addingTimeInterval(60))

        XCTAssertEqual(model.today, day(1))
        XCTAssertEqual(model.day, day(1))
    }

    func testBrowsingThePastStaysAcrossMidnight() {
        let model = FuelModel(now: base)
        model.goPreviousDay()
        model.goPreviousDay()

        model.syncToday(now: day(1).addingTimeInterval(60))

        XCTAssertEqual(model.today, day(1))
        XCTAssertEqual(model.day, day(-2))
    }

    func testSyncOnTheSameDayChangesNothing() {
        let model = FuelModel(now: base)
        model.goPreviousDay()

        model.syncToday(now: base.addingTimeInterval(3 * 3600))

        XCTAssertEqual(model.day, day(-1))
        XCTAssertEqual(model.today, day(0))
    }

    // MARK: - Snap-back after the background

    func testPastDaySnapsBackAfterMoreThan30Minutes() {
        let model = FuelModel(now: base)
        model.goPreviousDay()

        model.appDidEnterBackground(at: base)
        model.appWillEnterForeground(at: base.addingTimeInterval(31 * 60))

        XCTAssertEqual(model.day, day(0))
    }

    func testPastDayStaysAfterAShortBreak() {
        let model = FuelModel(now: base)
        model.goPreviousDay()

        model.appDidEnterBackground(at: base)
        model.appWillEnterForeground(at: base.addingTimeInterval(30 * 60))

        XCTAssertEqual(model.day, day(-1))
    }

    func testForegroundWithoutABackgroundStampKeepsThePastDay() {
        let model = FuelModel(now: base)
        model.goPreviousDay()
        model.appDidEnterBackground(at: base)
        model.appWillEnterForeground(at: base.addingTimeInterval(60))

        // The stamp is consumed: a second return, hours later, has no known time away.
        model.appWillEnterForeground(at: base.addingTimeInterval(5 * 3600))

        XCTAssertEqual(model.day, day(-1))
    }

    func testShortBreakAcrossMidnightStillFollowsToday() {
        let lateEvening = day(0).addingTimeInterval(23 * 3600 + 55 * 60)
        let model = FuelModel(now: lateEvening)

        model.appDidEnterBackground(at: lateEvening)
        model.appWillEnterForeground(at: lateEvening.addingTimeInterval(10 * 60))

        XCTAssertEqual(model.day, day(1, from: lateEvening))
        XCTAssertEqual(model.today, day(1, from: lateEvening))
    }

    // MARK: - Logging sheets

    func testLoggingSheetKeepsItsDayThroughASnapBack() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        let now = Date.now
        let model = FuelModel(now: now)
        model.goPreviousDay()
        let yesterday = model.day
        let sheets: [FuelHomeView.FuelSheet] = [.search(.lunch, day: model.day), .aiScan(.lunch, day: model.day),
                                                .barcode(.lunch, day: model.day), .quickAdd(.lunch, name: "Zupa", day: model.day)]

        // The app sat in the background with the sheet up: Fuel snaps back to today underneath it.
        model.appDidEnterBackground(at: now)
        model.appWillEnterForeground(at: now.addingTimeInterval(31 * 60))
        XCTAssertNotEqual(model.day, yesterday)

        XCTAssertEqual(sheets.map(\.logDay), Array(repeating: yesterday, count: sheets.count))
        XCTAssertNil(FuelHomeView.FuelSheet.calendar.logDay)
        let day = try XCTUnwrap(sheets[0].logDay)
        context.insert(MealEntry(day: day, slot: .lunch, customName: "Zupa", grams: 0, kcal: 300, proteinG: 10,
                                 carbsG: 30, fatG: 10))
        try context.save()
        model.go(to: yesterday)
        model.refresh(in: context)
        XCTAssertEqual(model.entries(for: .lunch).map(\.displayName), ["Zupa"], "the food landed on the day it was picked for")
    }

    // MARK: - Jumps

    func testGoToClampsTheFutureAndNormalisesToStartOfDay() {
        let model = FuelModel(now: base)

        model.go(to: day(-10).addingTimeInterval(15 * 3600), now: base)
        XCTAssertEqual(model.day, day(-10))

        model.go(to: day(3), now: base)
        XCTAssertEqual(model.day, day(0))

        model.go(to: day(-4), now: base)
        model.goToday(now: base)
        XCTAssertEqual(model.day, day(0))
    }

    // MARK: - Time-zone change

    private func zone(_ id: String) throws -> TimeZone { try XCTUnwrap(TimeZone(identifier: id)) }

    /// A model started in Warsaw on 22 Sep 2026, noon.
    private func warsawModel() throws -> (FuelModel, Date) {
        var warsaw = Calendar(identifier: .gregorian)
        warsaw.timeZone = try zone("Europe/Warsaw")
        let now = try XCTUnwrap(warsaw.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 12)))
        return (FuelModel(now: now, calendar: warsaw), now)
    }

    /// Flying east: a History cell is the new zone's midnight, and it must open that day, not the one before.
    func testGoToAfterFlyingEastOpensTheTappedDay() throws {
        let (model, now) = try warsawModel()
        model.calendar.timeZone = try zone("Asia/Tokyo")
        let tokyo = model.calendar
        let layout = FuelCalendar.layout(today: now, calendar: tokyo)
        let cell = try XCTUnwrap(tokyo.date(from: DateComponents(year: 2026, month: 9, day: 20)))
        XCTAssertTrue(layout.columns.joined().contains(cell), "the grid's cell for 20 Sep")

        model.go(to: cell, now: now)

        XCTAssertEqual(model.day, cell)
        XCTAssertEqual(tokyo.component(.day, from: model.day), 20)
    }

    /// Flying west while on today: still today (no Today pill), and a sync moves it onto the new zone's midnight.
    func testTodayStaysTodayAfterFlyingWest() throws {
        let (model, now) = try warsawModel()
        model.calendar.timeZone = try zone("America/New_York")
        let newYork = model.calendar
        let later = now.addingTimeInterval(3600)   // 07:00 on 22 Sep in New York

        XCTAssertTrue(model.isToday(now: later), "the old zone's midnight still reads as today")

        model.syncToday(now: later)

        XCTAssertEqual(model.day, newYork.startOfDay(for: later))
        XCTAssertEqual(model.today, newYork.startOfDay(for: later))
        XCTAssertTrue(model.isToday(now: later))
        model.goPreviousDay()
        XCTAssertEqual(newYork.component(.day, from: model.day), 21)
        XCTAssertFalse(model.isToday(now: later))
    }

    /// Browsing a past day across a zone change keeps its date, re-read in the new zone; the arrows step from it.
    func testBrowsedDayKeepsItsDateAcrossAZoneChange() throws {
        let (model, now) = try warsawModel()
        model.goPreviousDay()
        model.goPreviousDay()   // 20 Sep, Warsaw's midnight
        model.calendar.timeZone = try zone("Asia/Tokyo")
        let tokyo = model.calendar

        model.syncToday(now: now)

        XCTAssertEqual(model.day, try XCTUnwrap(tokyo.date(from: DateComponents(year: 2026, month: 9, day: 20))))
        XCTAssertEqual(model.today, tokyo.startOfDay(for: now))
        XCTAssertFalse(model.isToday(now: now))
        model.goNextDay()
        XCTAssertEqual(tokyo.component(.day, from: model.day), 21)
    }

    // MARK: - Data

    func testRefreshReadsGoalDirectionAndZoneShiftedEntries() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        context.insert(UserProfile(name: "Kuba", goal: .loseFat, calorieGoal: 2350))
        let today = calendar.startOfDay(for: .now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let local = MealEntry(day: today, slot: .breakfast, customName: "Owsianka", grams: 0,
                              kcal: 400, proteinG: 15, carbsG: 60, fatG: 8)
        // Logged an hour further east: stored midnight is an hour early but it is still today's meal.
        let travelled = MealEntry(day: today, slot: .lunch, customName: "Pierogi", grams: 0,
                                  kcal: 600, proteinG: 20, carbsG: 80, fatG: 20)
        travelled.day = today.addingTimeInterval(-3600)
        let other = MealEntry(day: yesterday, slot: .dinner, customName: "Zupa", grams: 0,
                              kcal: 300, proteinG: 10, carbsG: 30, fatG: 10)
        for entry in [local, travelled, other] { context.insert(entry) }
        try context.save()

        let model = FuelModel()
        model.refresh(in: context)

        XCTAssertEqual(model.trainingGoal, .loseFat)
        XCTAssertEqual(model.goals.kcal, 2350)
        XCTAssertEqual(model.kcalEaten, 1000, accuracy: 0.001)
        XCTAssertEqual(model.entries(for: .lunch).map(\.displayName), ["Pierogi"])
        XCTAssertTrue(model.entries(for: .dinner).isEmpty)
    }

    /// The Dashboard's Fuel row fetches with the same window as the Fuel day view, so a meal logged before a
    /// time-zone change counts on both.
    func testDashboardAndFuelDayShareTheWindow() throws {
        let container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                           configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        let context = container.mainContext
        let today = calendar.startOfDay(for: .now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today)!
        func insert(_ name: String, day: Date) {
            let entry = MealEntry(day: day, slot: .lunch, customName: name, grams: 0, kcal: 100, proteinG: 1, carbsG: 1, fatG: 1)
            entry.day = day
            context.insert(entry)
        }
        insert("Tu", day: today)
        insert("Na wschodzie", day: today.addingTimeInterval(-3 * 3600))
        insert("Na zachodzie", day: today.addingTimeInterval(5 * 3600))
        insert("Wczoraj", day: yesterday)
        insert("Jutro", day: tomorrow)
        try context.save()

        let dashboard = try context.fetch(FetchDescriptor<MealEntry>(predicate: FuelCalendar.entriesPredicate(for: today)))
        let model = FuelModel()
        model.refresh(in: context)

        XCTAssertEqual(Set(dashboard.map(\.displayName)), ["Tu", "Na wschodzie", "Na zachodzie"])
        XCTAssertEqual(Set(dashboard.map(\.id)), Set(model.entries.map(\.id)))
    }

    // MARK: - Day title

    func testDayTitle() {
        XCTAssertEqual(Fmt.dayTitle(base, now: base), Fmt.localized("day.today"))
        XCTAssertEqual(Fmt.dayTitle(day(-1), now: base), Fmt.localized("day.yesterday"))
        XCTAssertEqual(Fmt.dayTitle(day(-2), now: base), Fmt.shortDay(day(-2)))
        let lastYear = calendar.date(byAdding: .year, value: -1, to: base)!
        XCTAssertTrue(Fmt.dayTitle(lastYear, now: base).hasPrefix(Fmt.shortDay(lastYear)))
        XCTAssertTrue(Fmt.dayTitle(lastYear, now: base).hasSuffix("2025"))
        XCTAssertNotEqual(Fmt.localized("day.yesterday"), "day.yesterday", "the key is in the catalog")
    }

    func testPercentAndMonthShort() {
        XCTAssertTrue(Fmt.percent(0.934).hasPrefix("93"))
        XCTAssertTrue(Fmt.percent(1.1).hasPrefix("110"))
        XCTAssertFalse(Fmt.monthShort(base).isEmpty)
        XCTAssertFalse(Fmt.monthShort(base).contains("2026"))
    }
}
