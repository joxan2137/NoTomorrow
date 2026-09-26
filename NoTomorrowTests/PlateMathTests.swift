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
}
