import XCTest
import SwiftUI
@testable import NoTomorrow

/// The mannequin demos (`motions.json`, `MotionLibrary`). Android mirrors these in `MotionDemoTest`, which also
/// checks the joint maths against `scripts/anatomy/motion_engine.js`.
final class MotionDemoTests: XCTestCase {

    func testLibraryLoadsAndMapsExercisesWithoutPhotos() throws {
        let file = try XCTUnwrap(MotionLibrary.file)
        XCTAssertGreaterThan(file.exercises.count, 90)
        for (id, pattern) in file.exercises {
            XCTAssertNotNil(file.patterns[pattern], "\(id) -> \(pattern)")
            XCTAssertEqual(ExerciseMedia.images[id] ?? [], [], id)
            XCTAssertEqual(file.patterns[pattern]?.frames.count, 2, pattern)
        }
    }

    func testFootAnchorKeepsTheNearAnklePlanted() throws {
        let file = try XCTUnwrap(MotionLibrary.file)
        for (name, pattern) in file.patterns where pattern.view != "front" && pattern.frames.first?.anchor == "foot" {
            for t in [0.0, 0.5, 1.0] {
                let pose = try XCTUnwrap(MotionLibrary.pose(pattern, at: t))
                let ankle = try XCTUnwrap(MotionLibrary.solve(pose, front: false)["anklen"])
                XCTAssertEqual(ankle.x, pose.at[0], accuracy: 0.01, name)
                XCTAssertEqual(ankle.y, pose.at[1], accuracy: 0.01, name)
            }
        }
    }

    /// Same numbers as `motion_joints.json` (Android's fixture from the reference engine), for one pattern.
    func testLungeMatchesTheReferenceEngine() throws {
        let pattern = try XCTUnwrap(MotionLibrary.file?.patterns["lunge"])
        let joints = MotionLibrary.solve(try XCTUnwrap(MotionLibrary.pose(pattern, at: 0.5)), front: false)
        XCTAssertEqual(try XCTUnwrap(joints["anklen"]).x, 122, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(joints["anklef"]).x, 62, accuracy: 0.01, "the far foot stays planted")
        let knee = try XCTUnwrap(joints["kneef"]), hip = try XCTUnwrap(joints["hipf"])
        XCTAssertEqual(hypot(knee.x - hip.x, knee.y - hip.y), MotionLibrary.length.thigh, accuracy: 0.01)
    }

    func testHotSegmentsFollowPrimaryMuscles() {
        XCTAssertEqual(MotionLibrary.hotSegments(for: ["quadriceps", "glutes"]), ["thigh"])
        XCTAssertEqual(MotionLibrary.hotSegments(for: ["chest", "triceps", "unknown"]), ["torso", "upper"])
    }

    func testPhaseEasesBetweenThePoses() {
        XCTAssertEqual(MotionDemoView.phase(0, period: 2), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionDemoView.phase(1, period: 2), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionDemoView.time(forPhase: 1, period: 2), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionDemoView.phase(MotionDemoView.time(forPhase: 0.3, period: 2.6), period: 2.6), 0.3, accuracy: 1e-9)
    }
}
