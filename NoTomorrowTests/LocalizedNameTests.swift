import XCTest
@testable import NoTomorrow

/// Names looked up from a catalog key built at run time. The key must be a plain string: an interpolated
/// `String.LocalizationValue("goal.\(x)")` looks up "goal.%@" and the raw key reaches the screen.
final class LocalizedNameTests: XCTestCase {
    func testTrainingGoalNamesComeFromTheCatalog() {
        let names = TrainingGoal.allCases.map(\.localizedName)
        for (goal, name) in zip(TrainingGoal.allCases, names) {
            XCTAssertFalse(name.hasPrefix("goal."), "\(goal) shows its raw key")
            XCTAssertEqual(name, String(localized: String.LocalizationValue(SetupYouView.goalKey(goal))))
        }
        XCTAssertEqual(Set(names).count, TrainingGoal.allCases.count)
    }

    func testCantMakeItReasonsComeFromTheCatalog() {
        for reason in CantMakeItSheet.CantReason.allCases {
            XCTAssertFalse(reason.label.hasPrefix("cant.reason."), "\(reason) shows its raw key")
            XCTAssertEqual(BroDerived.reasonLabel(reason.rawValue), reason.label)
        }
        XCTAssertEqual(BroDerived.reasonLabel("Traffic"), "Traffic", "a typed reason is shown as it is")
    }
}
