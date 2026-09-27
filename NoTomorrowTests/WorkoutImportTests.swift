import XCTest
import SwiftData
@testable import NoTomorrow

/// Importing Strong, Hevy and No Tomorrow CSV exports: format detection, grouping, units, set kinds, name matching,
/// and that importing twice adds nothing.
@MainActor
final class WorkoutImportTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private let strong = """
    Workout #;Date;Workout Name;Duration (sec);Exercise Name;Set Order;Weight (kg);Reps;RPE;Distance (meters);Seconds;Notes;Workout Notes
    1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);W;40;10;;;;;
    1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);1;80;8;8,5;;;Wide grip;
    1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);Rest Timer;;;;;90;;
    1;2026-09-20 18:00:00;"Push; heavy";3600;Cable Crossover Deluxe;1;20;12;;;;;
    2;2026-09-22 07:30:00;Legs;2700;Squat (Barbell);1;100;5;;;;;
    """

    func testStrongSemicolonExport() throws {
        let parsed = try WorkoutImport.parse(strong, unit: .kg)
        XCTAssertEqual(parsed.format, .strong)
        XCTAssertEqual(parsed.workouts.map(\.name), ["Push; heavy", "Legs"])
        let push = parsed.workouts[0]
        XCTAssertEqual(push.endedAt.timeIntervalSince(push.startedAt), 3600)
        XCTAssertEqual(push.exercises.map(\.name), ["Bench Press (Barbell)", "Cable Crossover Deluxe"])
        XCTAssertEqual(push.exercises[0].sets.map(\.kind), [.warmup, .normal])
        XCTAssertEqual(push.exercises[0].sets[1].rpe, 8.5)
        XCTAssertEqual(push.exercises[0].notes, "Wide grip")
    }

    func testHevyExportInPounds() throws {
        let hevy = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_lbs","reps","distance_miles","duration_seconds","rpe"
        "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Bench Press (Barbell)","","","0","warmup","95","10","","",""
        "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Bench Press (Barbell)","","","1","normal","225","5","","","9"
        "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Plank","","","0","normal","","","","60",""
        """
        let parsed = try WorkoutImport.parse(hevy, unit: .kg)
        XCTAssertEqual(parsed.format, .hevy)
        XCTAssertEqual(parsed.workouts.count, 1)
        XCTAssertEqual(parsed.workouts[0].setCount, 2)
        XCTAssertEqual(parsed.skippedRows, 1, "the timed plank has no reps")
        XCTAssertEqual(parsed.workouts[0].exercises[0].sets[1].weightKg, 225 / Fmt.lbPerKg, accuracy: 0.001)
        XCTAssertEqual(parsed.workouts[0].endedAt.timeIntervalSince(parsed.workouts[0].startedAt), 66 * 60)
    }

    func testOwnExportRoundTripsAndUnknownFilesFail() throws {
        let own = """
        workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps,completed_at,pr,set_record
        A,Push A,2026-09-20T16:00:00Z,2026-09-20T17:00:00Z,Barbell Bench Press - Medium Grip,1,normal,80,8,2026-09-20T16:10:00Z,1,0
        """
        let parsed = try WorkoutImport.parse(own, unit: .kg)
        XCTAssertEqual(parsed.format, .noTomorrow)
        XCTAssertEqual(parsed.workouts.first?.startedAt, try Date("2026-09-20T16:00:00Z", strategy: .iso8601))
        XCTAssertThrowsError(try WorkoutImport.parse("a,b\n1,2", unit: .kg))
        XCTAssertThrowsError(try WorkoutImport.parse("", unit: .kg))
    }

    func testMatchKeysMoveEquipmentToTheFront() {
        XCTAssertTrue(WorkoutImport.matchKeys("Bench Press (Barbell)").contains("barbell bench press"))
        XCTAssertEqual(WorkoutImport.matchKeys("Pull-Ups").first, "pull ups")
    }

    func testImportMatchesLibraryCreatesCustomAndSkipsDuplicates() throws {
        context.insert(Exercise(id: "Barbell_Bench_Press", name: "Barbell Bench Press", primaryMuscles: ["chest"]))
        context.insert(Exercise(id: "Barbell_Squat", name: "Barbell Squat", primaryMuscles: ["quadriceps"]))
        let parsed = try WorkoutImport.parse(strong, unit: .kg)

        let first = WorkoutImporter.importWorkouts(parsed.workouts, into: context)
        XCTAssertEqual(first.workouts, 2)
        XCTAssertEqual(first.sets, 4)
        XCTAssertEqual(first.newExercises, ["Cable Crossover Deluxe"])
        let workouts = try context.fetch(FetchDescriptor<Workout>())
        let push = try XCTUnwrap(workouts.first { $0.name == "Push; heavy" })
        XCTAssertEqual(push.sortedExercises.first?.exercise?.id, "Barbell_Bench_Press")
        XCTAssertTrue(push.sortedExercises.flatMap(\.sets).allSatisfy(\.isCompleted))

        let second = WorkoutImporter.importWorkouts(parsed.workouts, into: context)
        XCTAssertEqual(second.workouts, 0)
        XCTAssertEqual(second.duplicates, 2)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Workout>()), 2)
    }
}
