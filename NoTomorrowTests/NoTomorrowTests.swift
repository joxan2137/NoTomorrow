import XCTest
@testable import NoTomorrow

final class FormattersTests: XCTestCase {
    func testClock() {
        XCTAssertEqual(Fmt.clock(72), "1:12")
        XCTAssertEqual(Fmt.clock(0), "0:00")
    }

    func testElapsed() {
        XCTAssertEqual(Fmt.elapsed(0), "0:00")
        XCTAssertEqual(Fmt.elapsed(42 * 60 + 10.9), "42:10")
        XCTAssertEqual(Fmt.elapsed(3599), "59:59")
        XCTAssertEqual(Fmt.elapsed(3600), "1:00:00")
        XCTAssertEqual(Fmt.elapsed(2 * 3600 + 39 * 60 + 41), "2:39:41", "never 159:41")
        XCTAssertEqual(Fmt.elapsed(-5), "0:00")
    }

    func testEpley() {
        let set = SetEntry(order: 1, weightKg: 85, reps: 6)
        XCTAssertEqual(set.estimatedOneRepMax, 102, accuracy: 0.01)
    }
}
