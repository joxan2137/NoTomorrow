import XCTest
@testable import NoTomorrow

/// Plate calculator: plates per side, the closest load when the plates cannot make a weight, and the bar edge cases.
final class PlateMathTests: XCTestCase {

    func testLoadsHeaviestPlatesFirst() {
        let load = PlateMath.load(target: 142.5, bar: 20, plates: PlateMath.plates(for: .kg))
        XCTAssertEqual(load.perSide, [25, 25, 10, 1.25])
        XCTAssertEqual(load.total, 142.5)
        XCTAssertTrue(load.isExact)
        XCTAssertEqual(load.groups.map(\.plate), [25, 10, 1.25])
        XCTAssertEqual(load.groups.map(\.count), [2, 1, 1])
    }

    func testPoundPlates() {
        let load = PlateMath.load(target: 225, bar: 45, plates: PlateMath.plates(for: .lb))
        XCTAssertEqual(load.perSide, [45, 45])
        XCTAssertTrue(load.isExact)
    }

    func testClosestLoadStaysUnderTheTarget() {
        let load = PlateMath.load(target: 101, bar: 20, plates: PlateMath.plates(for: .kg))
        XCTAssertEqual(load.total, 100)
        XCTAssertEqual(load.shortBy, 1, accuracy: 0.001)
        XCTAssertFalse(load.isExact)
    }

    func testBarOnlyAndBelowTheBar() {
        let bare = PlateMath.load(target: 20, bar: 20, plates: PlateMath.plates(for: .kg))
        XCTAssertTrue(bare.perSide.isEmpty)
        XCTAssertTrue(bare.isExact)
        let light = PlateMath.load(target: 12.5, bar: 20, plates: PlateMath.plates(for: .kg))
        XCTAssertTrue(light.isBelowBar)
        XCTAssertFalse(light.isExact)
        XCTAssertEqual(light.total, 20)
    }

    // MARK: Warm-up ramp

    func testBarbellRampStartsWithTheBar() {
        let steps = WarmupPlan.steps(working: 100, unit: .kg, equipment: "barbell")
        XCTAssertEqual(steps, [.init(weight: 20, reps: 10), .init(weight: 50, reps: 5),
                               .init(weight: 70, reps: 3), .init(weight: 85, reps: 1)])
    }

    func testLightBarbellDropsStepsBelowTheBar() {
        let steps = WarmupPlan.steps(working: 40, unit: .kg, equipment: "barbell")
        XCTAssertEqual(steps.map(\.weight), [20, 27.5, 32.5])
        XCTAssertTrue(WarmupPlan.steps(working: 22.5, unit: .kg, equipment: "barbell").isEmpty)
    }

    func testDumbbellRampAndPounds() {
        XCTAssertEqual(WarmupPlan.steps(working: 30, unit: .kg, equipment: "dumbbell"),
                       [.init(weight: 15, reps: 8), .init(weight: 22.5, reps: 4)])
        XCTAssertEqual(WarmupPlan.steps(working: 225, unit: .lb, equipment: "barbell").map(\.weight), [45, 110, 155, 190])
        XCTAssertTrue(WarmupPlan.steps(working: 0, unit: .kg, equipment: "body only").isEmpty)
    }
}
