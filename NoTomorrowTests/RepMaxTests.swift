import XCTest
@testable import NoTomorrow

/// Rep-max table: best set for at least N reps, and the Epley estimate from the best e1RM.
final class RepMaxTests: XCTestCase {

    private func set(_ kg: Double, _ reps: Int) -> LiftSet {
        LiftSet(weightKg: kg, reps: reps, completedAt: nil, date: .now)
    }

    func testBestSetNeedsAtLeastThatManyReps() {
        let rows = RepMax.rows(sets: [set(100, 3), set(90, 5), set(95, 5), set(60, 12)], e1RM: 110)
        XCTAssertEqual(rows.map(\.reps), RepMax.repCounts)
        XCTAssertEqual(rows[0].best?.weightKg, 100)   // 1 rep: 100 × 3 counts
        XCTAssertEqual(rows[1].best?.weightKg, 100)   // 3 reps
        XCTAssertEqual(rows[2].best?.weightKg, 95)    // 5 reps
        XCTAssertEqual(rows[3].best?.weightKg, 60)    // 8 reps: only the 12-rep set qualifies
        XCTAssertEqual(rows[5].best?.weightKg, 60)
    }

    func testEstimateInvertsEpley() {
        XCTAssertEqual(RepMax.estimate(e1RM: 120, reps: 1), 120)
        XCTAssertEqual(RepMax.estimate(e1RM: 120, reps: 10), 90, accuracy: 0.001)
        XCTAssertEqual(RepMax.estimate(e1RM: 0, reps: 5), 0)
        let e1RM = SetEntry(order: 0, weightKg: 100, reps: 5).estimatedOneRepMax
        XCTAssertEqual(RepMax.estimate(e1RM: e1RM, reps: 5), 100, accuracy: 0.001, "round-trips a set's own e1RM")
    }
}
