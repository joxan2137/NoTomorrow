import XCTest
@testable import NoTomorrow

/// All-time records of a lift: best e1RM, heaviest weight and best volume set, off the lift's working sets.
final class LiftRecordsTests: XCTestCase {

    private func set(_ kg: Double, _ reps: Int, day: Int = 1) -> LiftSet {
        let date = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 9, day: day))!
        return LiftSet(weightKg: kg, reps: reps, completedAt: date, date: date)
    }

    func testEachRecordPicksItsOwnSet() throws {
        let records = try XCTUnwrap(LiftRecords(sets: [set(100, 5, day: 1), set(120, 1, day: 2), set(80, 12, day: 3)]))
        // e1RM: 100 × 5 → 116.7, 120 × 1 → 120, 80 × 12 → 112.
        XCTAssertEqual(records.bestE1RM, set(120, 1, day: 2))
        XCTAssertEqual(records.bestE1RMKg, 120, accuracy: 0.001)
        XCTAssertEqual(records.heaviest, set(120, 1, day: 2))
        // Volume: 500, 120, 960.
        XCTAssertEqual(records.bestVolume, set(80, 12, day: 3))
    }

    func testBestE1RMMatchesSetEntryEpley() throws {
        let records = try XCTUnwrap(LiftRecords(sets: [set(100, 5)]))
        XCTAssertEqual(records.bestE1RMKg, SetEntry(order: 0, weightKg: 100, reps: 5).estimatedOneRepMax, accuracy: 0.0001)
    }

    func testTies() throws {
        // Same e1RM (60 × 15 and 90 × 1 are both 90): the heavier wins.
        let e1RMTie = try XCTUnwrap(LiftRecords(sets: [set(60, 15), set(90, 1)]))
        XCTAssertEqual(e1RMTie.bestE1RM.weightKg, 90)
        // Same weight: more reps wins.
        let weightTie = try XCTUnwrap(LiftRecords(sets: [set(100, 3), set(100, 5), set(100, 4)]))
        XCTAssertEqual(weightTie.heaviest.reps, 5)
        // Same volume: the heavier set.
        let volumeTie = try XCTUnwrap(LiftRecords(sets: [set(50, 20), set(100, 10)]))
        XCTAssertEqual(volumeTie.bestVolume.weightKg, 100)
    }

    func testNoCountingSetsNoRecords() {
        XCTAssertNil(LiftRecords(sets: []))
        XCTAssertNil(LiftRecords(sets: [set(0, 10), set(60, 0)]))
    }
}
