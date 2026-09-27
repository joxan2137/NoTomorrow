import XCTest
@testable import NoTomorrow

/// 1RM percentages: plate rounding, reps consistent with the rep-max table, and the calculator's Epley estimate.
final class OneRepMaxTests: XCTestCase {

    func testEstimateMatchesSetEntry() {
        XCTAssertEqual(OneRepMax.estimate(weight: 100, reps: 1), 100)
        XCTAssertEqual(OneRepMax.estimate(weight: 100, reps: 5), SetEntry(order: 0, weightKg: 100, reps: 5).estimatedOneRepMax)
        XCTAssertEqual(OneRepMax.estimate(weight: 90, reps: 10), 120, accuracy: 0.001)
        XCTAssertEqual(OneRepMax.estimate(weight: 0, reps: 5), 0)
        XCTAssertEqual(OneRepMax.estimate(weight: 100, reps: 0), 0)
    }

    func testRepsPerPercentage() {
        let reps = OneRepMax.percentages.map { OneRepMax.reps(atPercent: $0) }
        XCTAssertEqual(OneRepMax.percentages, [100, 95, 90, 85, 80, 75, 70, 65, 60, 50])
        XCTAssertEqual(reps, [1, 1, 3, 5, 7, 10, 12, 16, 20, 30])
    }

    /// The reps at a percentage are the most the rep-max table's estimate still allows there.
    func testRepsAgreeWithRepMax() {
        let e1RM = 120.0
        for percent in OneRepMax.percentages where percent < 100 {
            let reps = OneRepMax.reps(atPercent: percent)
            let target = e1RM * Double(percent) / 100
            XCTAssertGreaterThanOrEqual(RepMax.estimate(e1RM: e1RM, reps: reps), target - 1e-9, "\(percent) %")
            XCTAssertLessThan(RepMax.estimate(e1RM: e1RM, reps: reps + 1), target, "\(percent) %")
        }
    }

    func testRoundingToPlates() {
        XCTAssertEqual(OneRepMax.round(116.85, step: 2.5), 117.5)
        XCTAssertEqual(OneRepMax.round(110.7, step: 2.5), 110)
        XCTAssertEqual(OneRepMax.round(111.25, step: 2.5), 112.5, "halves round up")
        XCTAssertEqual(OneRepMax.round(191.25, step: 5), 190)
        XCTAssertEqual(OneRepMax.round(42, step: 0), 42)
    }

    func testRowsInKilograms() {
        let rows = OneRepMax.rows(e1RM: 123, step: WarmupPlan.increment(for: .kg))
        XCTAssertEqual(rows.map(\.percent), OneRepMax.percentages)
        XCTAssertEqual(rows.map(\.weight), [122.5, 117.5, 110, 105, 97.5, 92.5, 85, 80, 75, 62.5])
        XCTAssertEqual(rows.first?.reps, 1)
    }

    func testRowsInPounds() {
        let rows = OneRepMax.rows(e1RM: 225, step: WarmupPlan.increment(for: .lb))
        XCTAssertEqual(rows.map(\.weight), [225, 215, 205, 190, 180, 170, 160, 145, 135, 115])
    }

    func testNoRowsWithoutAnE1RM() {
        XCTAssertEqual(OneRepMax.rows(e1RM: 0, step: 2.5), [])
    }
}
