import XCTest
import SwiftData
@testable import NoTomorrow

/// The Fuel home's undoable actions: delete (restored with the same id, day and order), "Log again today" on an entry
/// and "Copy to today" on a past day's meal slot (new entries today, same slot, removable with Undo).
@MainActor
final class FuelUndoCopyTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private let calendar = Calendar.current

    override func setUpWithError() throws {
        container = try ModelContainer(for: Schema(NoTomorrowSchema.models),
                                       configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private var today: Date { calendar.startOfDay(for: .now) }
    private func day(_ offset: Int) -> Date { calendar.date(byAdding: .day, value: offset, to: today)! }

    private func allEntries() throws -> [MealEntry] {
        try context.fetch(FetchDescriptor<MealEntry>(sortBy: [SortDescriptor(\.loggedAt)]))
    }

    @discardableResult
    private func log(_ name: String, day: Date, slot: MealSlot, kcal: Double, food: FoodItem? = nil,
                     loggedAt: Date, ai: Bool = false) -> MealEntry {
        let entry = MealEntry(day: day, slot: slot, food: food, customName: food == nil ? name : nil, grams: 100,
                              kcal: kcal, proteinG: 10, carbsG: 20, fatG: 5,
                              isAIEstimate: ai, confidence: ai ? 0.6 : nil)
        entry.loggedAt = loggedAt
        context.insert(entry)
        return entry
    }

    private func fuelModel(on day: Date) -> FuelModel {
        let model = FuelModel()
        model.go(to: day)
        model.refresh(in: context)
        return model
    }

    // MARK: - Delete + undo

    func testDeleteThenUndoRestoresTheSameEntry() throws {
        let food = FoodItem(id: "off:5900259000002", name: "Serek wiejski", source: .openFoodFacts,
                            kcalPer100: 97, proteinPer100: 11, carbsPer100: 2, fatPer100: 5)
        context.insert(food)
        let first = log("", day: day(-2), slot: .breakfast, kcal: 97, food: food, loggedAt: day(-2).addingTimeInterval(8 * 3600))
        let second = log("Kawa", day: day(-2), slot: .breakfast, kcal: 20, loggedAt: day(-2).addingTimeInterval(9 * 3600))
        try context.save()
        let id = first.id
        let loggedAt = first.loggedAt
        let model = fuelModel(on: day(-2))

        model.delete(first, in: context)

        XCTAssertEqual(model.entries(for: .breakfast).map(\.id), [second.id])
        XCTAssertEqual(try allEntries().count, 1)
        guard case .deleted? = model.pendingUndo?.kind else { return XCTFail("delete leaves an undo") }

        model.undo(in: context)

        XCTAssertNil(model.pendingUndo)
        let restored = try XCTUnwrap(try allEntries().first { $0.id == id })
        XCTAssertEqual(restored.day, day(-2))
        XCTAssertEqual(restored.loggedAt, loggedAt)
        XCTAssertTrue(restored.food === food)
        XCTAssertEqual(restored.kcal, 97)
        XCTAssertEqual(model.entries(for: .breakfast).map(\.id), [id, second.id], "back in its place, before the coffee")
    }

    func testUndoRestoresAnAIRowWithItsBadge() throws {
        let entry = log("Leczo", day: day(-1), slot: .dinner, kcal: 412.6, loggedAt: day(-1).addingTimeInterval(19 * 3600), ai: true)
        try context.save()
        let model = fuelModel(on: day(-1))

        model.delete(entry, in: context)
        model.undo(in: context)

        let restored = try XCTUnwrap(try allEntries().first)
        XCTAssertEqual(restored.displayName, "Leczo")
        XCTAssertTrue(restored.isAIEstimate)
        XCTAssertEqual(restored.confidence, 0.6)
        XCTAssertEqual(restored.kcal, 412.6)
    }

    func testOnlyTheLastDeleteIsUndoable() throws {
        let a = log("A", day: today, slot: .lunch, kcal: 100, loggedAt: today.addingTimeInterval(12 * 3600))
        let b = log("B", day: today, slot: .lunch, kcal: 200, loggedAt: today.addingTimeInterval(13 * 3600))
        try context.save()
        let model = fuelModel(on: today)

        model.delete(a, in: context)
        let firstUndo = try XCTUnwrap(model.pendingUndo)
        model.delete(b, in: context)
        XCTAssertNotEqual(model.pendingUndo?.id, firstUndo.id)

        model.expireUndo(firstUndo.id)
        XCTAssertNotNil(model.pendingUndo, "an old toast's timer must not end the newer undo")

        model.undo(in: context)
        XCTAssertEqual(model.entries.map(\.displayName), ["B"])
    }

    func testExpiredUndoDoesNothing() throws {
        let a = log("A", day: today, slot: .snack, kcal: 100, loggedAt: .now)
        try context.save()
        let model = fuelModel(on: today)
        model.delete(a, in: context)

        model.expireUndo(try XCTUnwrap(model.pendingUndo).id)
        model.undo(in: context)

        XCTAssertNil(model.pendingUndo)
        XCTAssertTrue(try allEntries().isEmpty)
    }

    // MARK: - Log again today

    func testLogAgainTodayCopiesIntoTheSameSlotToday() throws {
        let food = FoodItem(id: "custom:owsianka", name: "Owsianka", source: .custom,
                            kcalPer100: 380, proteinPer100: 13, carbsPer100: 66, fatPer100: 7)
        food.useCount = 3
        context.insert(food)
        let source = log("", day: day(-3), slot: .breakfast, kcal: 380, food: food, loggedAt: day(-3).addingTimeInterval(7 * 3600))
        let ai = log("Kanapka", day: day(-3), slot: .snack, kcal: 311.7, loggedAt: day(-3).addingTimeInterval(16 * 3600), ai: true)
        try context.save()
        let model = fuelModel(on: day(-3))
        let now = Date()

        model.logAgainToday(source, in: context, now: now)
        model.logAgainToday(ai, in: context, now: now)

        let todays = try allEntries().filter { $0.day == today }
        XCTAssertEqual(todays.count, 2)
        let copy = try XCTUnwrap(todays.first { $0.food != nil })
        XCTAssertNotEqual(copy.id, source.id)
        XCTAssertEqual(copy.slot, .breakfast)
        XCTAssertEqual(copy.loggedAt, now)
        XCTAssertEqual(copy.kcal, 380)
        XCTAssertTrue(copy.food === food)
        XCTAssertEqual(food.useCount, 4)
        XCTAssertEqual(food.lastUsedAt, now)
        let aiCopy = try XCTUnwrap(todays.first { $0.food == nil })
        XCTAssertEqual(aiCopy.displayName, "Kanapka")
        XCTAssertEqual(aiCopy.slot, .snack)
        XCTAssertTrue(aiCopy.isAIEstimate, "an unchanged copy is still the same estimate")
        XCTAssertEqual(aiCopy.confidence, 0.6)

        XCTAssertEqual(source.day, day(-3), "the source stays on its day")
        XCTAssertEqual(model.entries.count, 2, "the past day on screen is unchanged")
        guard case .added(let ids)? = model.pendingUndo?.kind else { return XCTFail("a copy leaves an undo") }
        XCTAssertEqual(ids, [copy.id, aiCopy.id], "one toast, one Undo for both copies")
    }

    // MARK: - Copy a meal slot to today

    func testCopyToTodayCopiesTheWholeSlotInOrderAndUndoRemovesIt() throws {
        let base = day(-1)
        log("Jajecznica", day: base, slot: .breakfast, kcal: 320, loggedAt: base.addingTimeInterval(8 * 3600))
        log("Chleb", day: base, slot: .breakfast, kcal: 160, loggedAt: base.addingTimeInterval(8 * 3600 + 60))
        log("Pomidor", day: base, slot: .breakfast, kcal: 20, loggedAt: base.addingTimeInterval(8 * 3600 + 120))
        log("Pierogi", day: base, slot: .dinner, kcal: 600, loggedAt: base.addingTimeInterval(19 * 3600))
        log("Banan", day: today, slot: .breakfast, kcal: 105, loggedAt: today.addingTimeInterval(60))
        try context.save()
        let model = fuelModel(on: base)

        model.copyToToday(.breakfast, in: context, now: .now)

        let todayModel = fuelModel(on: today)
        XCTAssertEqual(todayModel.entries(for: .breakfast).map(\.displayName), ["Banan", "Jajecznica", "Chleb", "Pomidor"],
                       "copies land after what today already has, in the source order")
        XCTAssertTrue(todayModel.entries(for: .dinner).isEmpty, "only the chosen slot is copied")
        XCTAssertEqual(model.entries.count, 4, "yesterday is untouched")

        model.undo(in: context)

        todayModel.refresh(in: context)
        XCTAssertEqual(todayModel.entries.map(\.displayName), ["Banan"])
        XCTAssertEqual(try allEntries().count, 5)
        XCTAssertNil(model.pendingUndo)
    }

    /// A double tap on "Copy to today": two batches, and Undo takes both back, not only the second.
    func testRepeatedCopiesShareOneUndo() throws {
        let base = day(-1)
        log("Jajecznica", day: base, slot: .breakfast, kcal: 320, loggedAt: base.addingTimeInterval(8 * 3600))
        log("Chleb", day: base, slot: .breakfast, kcal: 160, loggedAt: base.addingTimeInterval(8 * 3600 + 60))
        try context.save()
        let model = fuelModel(on: base)
        let now = Date.now

        model.copyToToday(.breakfast, in: context, now: now)
        let firstUndo = try XCTUnwrap(model.pendingUndo)
        model.copyToToday(.breakfast, in: context, now: now.addingTimeInterval(0.3))

        let todayModel = fuelModel(on: today)
        XCTAssertEqual(todayModel.entries(for: .breakfast).count, 4, "a deliberate repeat still copies")
        let secondUndo = try XCTUnwrap(model.pendingUndo)
        XCTAssertNotEqual(secondUndo.id, firstUndo.id, "the toast restarts")
        XCTAssertEqual(secondUndo.expiresAt.timeIntervalSince(now), 4.3, accuracy: 0.001)

        model.undo(in: context)

        todayModel.refresh(in: context)
        XCTAssertTrue(todayModel.entries.isEmpty, "both batches are gone")
        XCTAssertEqual(try allEntries().count, 2)
    }

    func testACopyAfterTheToastExpiredStartsItsOwnUndo() throws {
        let base = day(-1)
        log("Zupa", day: base, slot: .lunch, kcal: 250, loggedAt: base.addingTimeInterval(13 * 3600))
        let deleted = log("Kawa", day: base, slot: .snack, kcal: 20, loggedAt: base.addingTimeInterval(15 * 3600))
        try context.save()
        let model = fuelModel(on: base)
        let now = Date.now

        model.copyToToday(.lunch, in: context, now: now)
        // Expired while the tab was hidden, never cleared: it must not grow.
        model.copyToToday(.lunch, in: context, now: now.addingTimeInterval(5))
        guard case .added(let ids)? = model.pendingUndo?.kind else { return XCTFail("a copy leaves an undo") }
        XCTAssertEqual(ids.count, 1)

        // A delete in between is its own undo; the next copy replaces it rather than adding to it.
        model.delete(deleted, in: context, now: now.addingTimeInterval(6))
        model.copyToToday(.lunch, in: context, now: now.addingTimeInterval(7))
        guard case .added(let latest)? = model.pendingUndo?.kind else { return XCTFail("a copy leaves an undo") }
        XCTAssertEqual(latest.count, 1)
    }

    // MARK: - Undo deadline

    func testUndoExpiresAtItsDeadline() throws {
        let a = log("A", day: today, slot: .snack, kcal: 100, loggedAt: .now)
        try context.save()
        let model = fuelModel(on: today)
        let now = Date.now

        model.delete(a, in: context, now: now)
        let undo = try XCTUnwrap(model.pendingUndo)
        XCTAssertEqual(undo.expiresAt, now.addingTimeInterval(4))
        XCTAssertEqual(undo.remaining(at: now.addingTimeInterval(1)), 3, accuracy: 0.001)
        XCTAssertEqual(undo.remaining(at: now.addingTimeInterval(9)), 0)

        model.expireUndoIfDue(now: now.addingTimeInterval(3.9))
        XCTAssertNotNil(model.pendingUndo, "still inside the toast's time")
        model.expireUndoIfDue(now: now.addingTimeInterval(4))
        XCTAssertNil(model.pendingUndo)
    }

    /// The toast's timer was cancelled by a tab switch 2 s in; back 10 s later the undo is over, with nothing to undo.
    func testUndoLeftBehindByATabSwitchDoesNotComeBack() throws {
        let a = log("A", day: today, slot: .lunch, kcal: 300, loggedAt: .now)
        try context.save()
        let model = fuelModel(on: today)
        let now = Date.now
        model.delete(a, in: context, now: now)

        model.expireUndoIfDue(now: now.addingTimeInterval(12))
        model.undo(in: context)

        XCTAssertNil(model.pendingUndo)
        XCTAssertTrue(try allEntries().isEmpty, "Undo no longer works once the toast's time is up")
    }

    func testVoiceOverUndoLastsLongerAndIsAnnouncedOnce() throws {
        let a = log("A", day: today, slot: .dinner, kcal: 500, loggedAt: .now)
        let b = log("B", day: today, slot: .dinner, kcal: 200, loggedAt: .now)
        try context.save()
        let model = fuelModel(on: today)
        model.undoLifetime = FuelModel.undoDurationVoiceOver
        let now = Date.now

        model.delete(a, in: context, now: now)
        let first = try XCTUnwrap(model.pendingUndo)
        XCTAssertEqual(first.expiresAt, now.addingTimeInterval(10))
        XCTAssertTrue(model.shouldAnnounce(first))
        XCTAssertFalse(model.shouldAnnounce(first), "a toast shown again is not read out again")

        model.delete(b, in: context, now: now)
        XCTAssertTrue(model.shouldAnnounce(try XCTUnwrap(model.pendingUndo)), "a new undo is announced")
    }

    // MARK: - Time-zone change

    /// Flying west after launch: "Copy to today" lands on the new zone's today, not on a stale midnight that reads as
    /// yesterday there.
    func testCopyToTodayUsesTheCurrentZone() throws {
        var warsaw = Calendar(identifier: .gregorian)
        warsaw.timeZone = try XCTUnwrap(TimeZone(identifier: "Europe/Warsaw"))
        let launch = try XCTUnwrap(warsaw.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 8)))
        let yesterday = try XCTUnwrap(warsaw.date(byAdding: .day, value: -1, to: warsaw.startOfDay(for: launch)))
        let source = log("Owsianka", day: yesterday, slot: .breakfast, kcal: 380,
                         loggedAt: yesterday.addingTimeInterval(7 * 3600))
        source.day = yesterday   // Warsaw's midnight, whatever zone the test machine is in
        try context.save()
        let model = FuelModel(now: launch, calendar: warsaw)
        model.go(to: yesterday, now: launch)
        model.refresh(in: context)
        XCTAssertEqual(model.entries.count, 1)

        model.calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let newYork = model.calendar
        let now = launch.addingTimeInterval(3 * 3600)   // 05:00 on 22 Sep in New York
        model.copyToToday(.breakfast, in: context, now: now)

        let copy = try XCTUnwrap(try allEntries().first { $0.day != yesterday })
        XCTAssertEqual(copy.day, newYork.startOfDay(for: now))
        XCTAssertEqual(newYork.component(.day, from: copy.day), 22)
        model.goToday(now: now)
        model.refresh(in: context)
        XCTAssertEqual(model.entries.map(\.id), [copy.id], "today's list shows the copy")
    }

    func testCopyingAnEmptySlotDoesNothing() throws {
        let model = fuelModel(on: day(-1))

        model.copyToToday(.lunch, in: context, now: .now)

        XCTAssertNil(model.pendingUndo)
        XCTAssertTrue(try allEntries().isEmpty)
    }

    func testUndoMessages() {
        let added = FuelModel.Undo(kind: .added([]))
        XCTAssertNotEqual(Fmt.localized(added.messageKey), "fuel.addedToToday", "the key is in the catalog")
        XCTAssertNotEqual(Fmt.localized("fuel.entryDeleted"), "fuel.entryDeleted")
        XCTAssertNotEqual(Fmt.localized("fuel.copyToToday"), "fuel.copyToToday")
        XCTAssertEqual(FuelModel.undoDuration, .seconds(4))
    }
}
