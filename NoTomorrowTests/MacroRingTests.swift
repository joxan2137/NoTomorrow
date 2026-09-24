import XCTest
@testable import NoTomorrow

/// `MacroRing.arcs` — parity with Android's `MacroRingArcsTest`.
final class MacroRingTests: XCTestCase {

    private func assertArc(_ arc: MacroRing.Arc, start: Double, end: Double, colorIndex: Int,
                           file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(arc.colorIndex, colorIndex, file: file, line: line)
        XCTAssertEqual(arc.start, start, accuracy: 1e-9, file: file, line: line)
        XCTAssertEqual(arc.end, end, accuracy: 1e-9, file: file, line: line)
    }

    func testArcsFollowEachMacroShareOfTheKcalGoal() {
        // 100 g protein = 400 kcal, 200 g carbs = 800 kcal, 40 g fat = 360 kcal of 2000.
        let arcs = MacroRing.arcs(protein: 100, carbs: 200, fat: 40, kcalGoal: 2000, gap: 0.01)
        XCTAssertEqual(arcs.count, 3)
        assertArc(arcs[0], start: 0, end: 0.19, colorIndex: 0)
        assertArc(arcs[1], start: 0.2, end: 0.59, colorIndex: 1)
        assertArc(arcs[2], start: 0.6, end: 0.77, colorIndex: 2)
    }

    func testPastTheGoalTheArcsScaleDownToAFullRing() {
        let arcs = MacroRing.arcs(protein: 250, carbs: 500, fat: 100, kcalGoal: 2000, gap: 0)
        XCTAssertEqual(arcs.last?.end ?? 0, 1, accuracy: 1e-9)
        // 1000 : 2000 : 900 kcal keep their proportions.
        XCTAssertEqual(arcs[0].end, 1000.0 / 3900.0, accuracy: 1e-9)
    }

    func testAMacroSmallerThanTheGapIsLeftOut() {
        let arcs = MacroRing.arcs(protein: 1, carbs: 100, fat: 0, kcalGoal: 2000, gap: 0.01)
        XCTAssertEqual(arcs.map(\.colorIndex), [1])
    }

    func testNoGoalOrNothingEatenDrawsNoArcs() {
        XCTAssertTrue(MacroRing.arcs(protein: 100, carbs: 100, fat: 10, kcalGoal: 0, gap: 0.01).isEmpty)
        XCTAssertTrue(MacroRing.arcs(protein: 0, carbs: 0, fat: 0, kcalGoal: 2000, gap: 0.01).isEmpty)
    }
}
