import XCTest
import SwiftUI
@testable import NoTomorrow

/// The body map (`muscle_model.json` parsed by `SVGPath`), its tap hit test, the exercise facts line and the form
/// cues file. Android mirrors these in `BodyMapTest`.
final class BodyMapTests: XCTestCase {

    func testEveryRegionParsesInsideTheBox() {
        XCTAssertFalse(BodyMap.regions.isEmpty)
        let box = CGRect(origin: .zero, size: BodyMap.size)
        for region in BodyMap.regions {
            let bounds = region.path.boundingRect
            XCTAssertGreaterThan(bounds.width, 0.5, region.muscle)
            XCTAssertGreaterThan(bounds.height, 0.5, region.muscle)
            XCTAssertTrue(box.contains(bounds), "\(region.muscle) \(bounds)")
            XCTAssertTrue(["front", "back"].contains(region.view), region.view)
        }
    }

    func testParserReadsMoveCurveAndClose() {
        let path = SVGPath.parse("M10 10C10 20 20 20 20 10L15 0Z")
        XCTAssertEqual(path.boundingRect.minX, 10, accuracy: 0.01)
        XCTAssertEqual(path.boundingRect.maxX, 20, accuracy: 0.01)
        XCTAssertEqual(path.boundingRect.minY, 0, accuracy: 0.01)
        XCTAssertEqual(SVGPath.parse("M-1-2L3,4Z").boundingRect, CGRect(x: -1, y: -2, width: 4, height: 6))
    }

    func testTapFindsTheMuscleUnderTheFinger() {
        // Drawn at 400 × 600, so the model's units double.
        let size = CGSize(width: 400, height: 600)
        XCTAssertEqual(BodyMap.muscle(at: CGPoint(x: 2 * 38.8, y: 2 * 68), in: size), "chest")
        XCTAssertEqual(BodyMap.muscle(at: CGPoint(x: 2 * 134.3, y: 2 * 100), in: size), "lats")
        XCTAssertEqual(BodyMap.muscle(at: CGPoint(x: 2 * 50, y: 2 * 20), in: size), nil, "the head is body, not muscle")
        XCTAssertNil(BodyMap.muscle(at: CGPoint(x: 2, y: 2), in: size))
    }

    func testMapShowsBothSidesOfTheBigMuscles() {
        for muscle in ["chest", "shoulders", "biceps", "abdominals", "quadriceps", "calves"] {
            XCTAssertTrue(BodyMap.regions.contains { $0.muscle == muscle && $0.view == "front" }, muscle)
        }
        for muscle in ["traps", "lats", "middle back", "lower back", "triceps", "glutes", "hamstrings", "calves"] {
            XCTAssertTrue(BodyMap.regions.contains { $0.muscle == muscle && $0.view == "back" }, muscle)
        }
    }

    func testExerciseFactsSkipUnknownValues() {
        let labels = ExerciseFacts.labels(equipment: "barbell", level: "beginner", mechanic: nil, force: "sideways")
        XCTAssertEqual(labels.count, 2)
    }

    func testFormCuesCoverKnownExercisesInBothLanguages() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "exercises", withExtension: "json"))
        let ids = Set(try JSONDecoder().decode([ExerciseLibrary.Record].self, from: Data(contentsOf: url)).map(\.id))
        XCTAssertGreaterThan(FormCues.all.count, 30)
        for (id, byLanguage) in FormCues.all {
            XCTAssertTrue(ids.contains(id), id)
            for language in ["en", "pl"] {
                let entry = try XCTUnwrap(byLanguage[language], "\(id) \(language)")
                XCTAssertFalse(entry.cues.isEmpty, "\(id) \(language)")
                XCTAssertEqual(entry.cues.count, byLanguage["en"]?.cues.count, "\(id) \(language)")
                XCTAssertEqual(entry.mistakes.count, byLanguage["en"]?.mistakes.count, "\(id) \(language)")
            }
        }
        XCTAssertEqual(FormCues.cues(for: "Barbell_Squat", language: "pl")?.cues.first?.hasPrefix("Przed"), true)
        XCTAssertNotNil(FormCues.cues(for: "Barbell_Squat", language: "de"), "falls back to English")
    }
}
