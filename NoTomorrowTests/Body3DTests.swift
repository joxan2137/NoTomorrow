import XCTest
import SwiftUI
@testable import NoTomorrow

/// The 3D muscle view (`Body3D`), its tap hit test, the 3D demo clips, the exercise facts line and the form cues file.
/// Android mirrors these in `Body3DTest`.
final class Body3DTests: XCTestCase {

    func testEveryLayerFitsTheRenderAndLoads() throws {
        let meta = try XCTUnwrap(Body3D.meta)
        XCTAssertEqual(Body3D.size, CGSize(width: 520, height: 1000))
        let box = CGRect(origin: .zero, size: Body3D.size)
        for (muscle, views) in meta.layers {
            XCTAssertTrue(meta.muscles.contains(muscle), muscle)
            for view in views.keys {
                let frame = try XCTUnwrap(Body3D.frame(of: muscle, view: view))
                XCTAssertTrue(box.contains(frame), "\(muscle) \(view) \(frame)")
                let image = try XCTUnwrap(Body3D.image("\(view)-\(muscle)"), "\(view)-\(muscle)")
                XCTAssertEqual(image.size.width * image.scale, frame.width, "\(view)-\(muscle)")
            }
        }
        for view in Body3D.views { XCTAssertNotNil(Body3D.image(view), view) }
    }

    func testBodyShowsEveryMuscleInTheLibrary() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "exercises", withExtension: "json"))
        let records = try JSONDecoder().decode([ExerciseLibrary.Record].self, from: Data(contentsOf: url))
        for record in records {
            for muscle in record.primaryMuscles + record.secondaryMuscles {
                XCTAssertFalse(Body3D.meta?.layers[muscle]?.isEmpty ?? true, "\(record.id) \(muscle)")
            }
        }
        for muscle in ["chest", "shoulders", "biceps", "abdominals", "quadriceps", "forearms"] {
            XCTAssertNotNil(Body3D.frame(of: muscle, view: "front"), muscle)
        }
        for muscle in ["traps", "lats", "middle back", "lower back", "triceps", "glutes", "hamstrings", "calves"] {
            XCTAssertNotNil(Body3D.frame(of: muscle, view: "back"), muscle)
        }
    }

    func testTapFindsTheMuscleUnderTheFinger() {
        // Drawn at half size, so the render's pixels halve.
        let size = CGSize(width: 260, height: 500)
        func at(_ view: String, _ x: CGFloat, _ y: CGFloat) -> String? {
            Body3D.muscle(at: CGPoint(x: x / 2, y: y / 2), view: view, in: size)
        }
        XCTAssertEqual(at("front", 205, 290), "chest")
        XCTAssertEqual(at("front", 205, 610), "quadriceps")
        XCTAssertEqual(at("back", 190, 350), "lats")
        XCTAssertEqual(at("back", 300, 500), "glutes")
        XCTAssertNil(at("front", 260, 120), "the head is body, not muscle")
        XCTAssertNil(at("front", 8, 8))
    }

    func testLabelMapRoundsToTheNearestStep() {
        let map = Body3D.LabelMap(width: 4, height: 1, bytes: [0, 12, 17, 13 * 12 + 5])
        XCTAssertNil(map.index(x: 0, y: 0, step: 12))
        XCTAssertEqual(map.index(x: 1, y: 0, step: 12), 0)
        XCTAssertEqual(map.index(x: 2, y: 0, step: 12), 0)
        XCTAssertEqual(map.index(x: 3, y: 0, step: 12), 12)
        XCTAssertNil(map.index(x: 4, y: 0, step: 12))
    }

    func testDemosCoverExercisesWithoutPhotos() {
        XCTAssertGreaterThan(ExerciseDemos.clips.count, 90)
        for (id, clip) in ExerciseDemos.clips {
            XCTAssertEqual(ExerciseMedia.images[id] ?? [], [], id)
            XCTAssertNotNil(ExerciseDemos.video(clip), clip)
            XCTAssertNotNil(ExerciseDemos.poster(clip), clip)
        }
    }

    func testMuscleStrengthsFollowTheirRoles() {
        XCTAssertEqual(MuscleModelView.strength(for: "chest", primary: ["chest"], secondary: ["triceps"]), 1)
        XCTAssertEqual(MuscleModelView.strength(for: "triceps", primary: ["chest"], secondary: ["triceps"]), 0.45)
        XCTAssertEqual(MuscleModelView.strength(for: "calves", primary: ["chest"], secondary: ["triceps"]), 0)
        XCTAssertEqual((0...4).map(MuscleHeatView.strength(level:)), [0, 0.3, 0.52, 0.76, 1])
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
