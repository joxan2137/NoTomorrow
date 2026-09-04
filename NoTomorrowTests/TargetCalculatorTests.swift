import XCTest
@testable import NoTomorrow

final class TargetCalculatorTests: XCTestCase {
    func testBuildMuscleAt80kg() {
        // BMR = 10*80 + 6.25*178 - 5*30 + 5 = 1767.5; TDEE = 2739.6; +300 = 3039.6 → 3050
        let t = TargetCalculator.targets(bodyWeightKg: 80, goal: .buildMuscle)
        XCTAssertEqual(t.kcal, 3050)
        XCTAssertEqual(t.proteinG, 176)
        XCTAssertEqual(t.fatG, 72)
        // carbs = (3050 - 176*4 - 72*9) / 4 = (3050 - 704 - 648) / 4 = 424.5 → 425
        XCTAssertEqual(t.carbsG, 425)
    }

    func testLoseFatAt80kg() {
        // 2739.6 - 400 = 2339.6 → 2350
        let t = TargetCalculator.targets(bodyWeightKg: 80, goal: .loseFat)
        XCTAssertEqual(t.kcal, 2350)
        XCTAssertEqual(t.proteinG, 192)
        XCTAssertEqual(t.fatG, 72)
    }

    func testMaintainAt80kg() {
        let t = TargetCalculator.targets(bodyWeightKg: 80, goal: .maintain)
        XCTAssertEqual(t.kcal, 2750)
        XCTAssertEqual(t.proteinG, 144)
    }

    func testUnknownWeightUsesDefault() {
        let unknown = TargetCalculator.targets(bodyWeightKg: nil, goal: .maintain)
        let assumed = TargetCalculator.targets(bodyWeightKg: TargetCalculator.assumedBodyWeightKg, goal: .maintain)
        XCTAssertEqual(unknown, assumed)
    }

    func testKcalRoundedToFifty() {
        for w in stride(from: 50.0, through: 130.0, by: 2.5) {
            for goal in TrainingGoal.allCases {
                let t = TargetCalculator.targets(bodyWeightKg: w, goal: goal)
                XCTAssertEqual(t.kcal % 50, 0)
                XCTAssertGreaterThanOrEqual(t.carbsG, 0)
                XCTAssertGreaterThanOrEqual(t.proteinG, 0)
                XCTAssertGreaterThanOrEqual(t.fatG, 0)
            }
        }
    }

    func testNeverNegative() {
        let t = TargetCalculator.targets(bodyWeightKg: 0.1, goal: .loseFat)
        XCTAssertGreaterThanOrEqual(t.kcal, 0)
        XCTAssertGreaterThanOrEqual(t.carbsG, 0)
    }
}
