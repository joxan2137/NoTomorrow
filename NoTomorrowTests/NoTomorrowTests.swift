import XCTest
@testable import NoTomorrow

final class FormattersTests: XCTestCase {
    func testClock() {
        XCTAssertEqual(Fmt.clock(72), "1:12")
        XCTAssertEqual(Fmt.clock(0), "0:00")
    }

    func testEpley() {
        let set = SetEntry(order: 1, weightKg: 85, reps: 6)
        XCTAssertEqual(set.estimatedOneRepMax, 102, accuracy: 0.01)
    }
}
