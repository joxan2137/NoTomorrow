import XCTest
@testable import NoTomorrow

/// Machine placard text → library exercise (docs/machine-scan.md). The same cases run on Android in
/// `MachineLabelMatcherTest.kt`, against the same bundled library.
final class MachineLabelMatcherTests: XCTestCase {

    private static let library: MachineLabelMatcher = {
        let bundled = ExerciseLibrary.loadBundled()
        let candidates = (bundled?.records ?? []).map {
            MachineLabelMatcher.Candidate(id: $0.id, name: $0.name, namePL: bundled?.polish[$0.id], equipment: $0.equipment)
        }
        return MachineLabelMatcher(candidates: candidates)
    }()

    private func best(_ lines: [(String, Double)], excluding: Set<String> = []) -> String? {
        Self.library.match(lines.map { .init(text: $0.0, weight: $0.1) }, excluding: excluding).first?.id
    }

    func testLibraryLoads() {
        XCTAssertNotNil(ExerciseLibrary.loadBundled())
    }

    func testPlacardNameWinsOverFinePrint() {
        XCTAssertEqual(best([("SEATED LEG CURL", 1), ("Adjust seat so knees align with pivot. Press legs down slowly", 0.35),
                             ("Technogym", 0.5)]), "Seated_Leg_Curl")
        XCTAssertEqual(best([("LEG EXTENSION", 1), ("Selection", 0.5), ("1. Adjust back pad 2. Extend legs", 0.3)]),
                       "Leg_Extensions")
        XCTAssertEqual(best([("TECHNOGYM", 1), ("Leg Press", 0.8)]), "Leg_Press")
    }

    func testBrandedMachine() {
        XCTAssertEqual(best([("HAMMER STRENGTH", 0.6), ("ISO-LATERAL ROW", 1)]), "nt_hs_iso_lateral_row")
    }

    func testSynonymsAndPlurals() {
        XCTAssertEqual(best([("Pectoral Machine", 1), ("Technogym", 0.5)]), "Butterfly")
        XCTAssertEqual(best([("TRICEPS PUSH DOWN", 1)]), "Triceps_Pushdown")
        XCTAssertEqual(best([("ABDOMINAL CRUNCH", 1)]), "Ab_Crunch_Machine")
        XCTAssertEqual(best([("ABDUCTOR", 1)]), "Thigh_Abductor")
        XCTAssertEqual(best([("CALF RAISE", 1), ("standing", 0.6)]), "Standing_Calf_Raises")
    }

    func testPrefersMachineOverFreeWeightVersion() {
        XCTAssertEqual(best([("BICEPS CURL", 1)]), "Machine_Bicep_Curl")
    }

    func testOCRMisreadStillMatches() {
        XCTAssertEqual(best([("LEG EXTENSIQN", 1)]), "Leg_Extensions")
    }

    func testSafetyTextAloneMatchesNothing() {
        XCTAssertNil(best([("Keep hands clear of moving parts. Max user weight 150 kg", 1)]))
        XCTAssertNil(best([]))
    }

    func testExcludedExerciseIsSkipped() {
        XCTAssertNotEqual(best([("LEG PRESS", 1)], excluding: ["Leg_Press"]), "Leg_Press")
    }

    func testAtMostThreeMatchesBestFirst() {
        let matches = Self.library.match([.init(text: "LEG CURL", weight: 1)])
        XCTAssertLessThanOrEqual(matches.count, MachineLabelMatcher.maxMatches)
        XCTAssertEqual(matches.map(\.score), matches.map(\.score).sorted(by: >))
        XCTAssertTrue(matches.allSatisfy { $0.score >= MachineLabelMatcher.minimumScore })
    }

    func testTokens() {
        XCTAssertEqual(MachineLabelMatcher.tokens("Lat Pull-Down"), ["lat", "pulldown"])
        XCTAssertEqual(MachineLabelMatcher.tokens("Leg Presses 2"), ["leg", "press"])
        XCTAssertEqual(MachineLabelMatcher.tokens("Rear Delt Flyes"), ["rear", "deltoid", "fly"])
        XCTAssertEqual(MachineLabelMatcher.tokens("Wyciskanie nóg"), ["wyciskanie", "nog"])
    }

    func testLineWeightsAreRelativeToTallestLine() {
        let lines = MachineLabelReader.weighted([("BIG", 40), ("small", 10), ("  ", 50)])
        XCTAssertEqual(lines, [.init(text: "BIG", weight: 1), .init(text: "small", weight: 0.25)])
    }
}
