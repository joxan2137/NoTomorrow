import XCTest
import SwiftData
@testable import NoTomorrow

/// The exercise picker's equipment chips: every library value lands on a chip, and the filter combines with the
/// muscle chips and the search.
@MainActor
final class ExerciseEquipmentTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    func testEveryLibraryValueMapsToAChip() {
        typealias E = ExerciseLibrary.Equipment
        XCTAssertEqual(E.of("barbell"), .barbell)
        XCTAssertEqual(E.of("e-z curl bar"), .barbell)
        XCTAssertEqual(E.of("dumbbell"), .dumbbell)
        XCTAssertEqual(E.of("machine"), .machine)
        XCTAssertEqual(E.of("cable"), .cable)
        XCTAssertEqual(E.of("body only"), .bodyweight)
        XCTAssertEqual(E.of("kettlebells"), .kettlebell)
        XCTAssertEqual(E.of("bands"), .band)
        for raw in ["other", "medicine ball", "exercise ball", "foam roll", nil] {
            XCTAssertEqual(E.of(raw), .other, "\(raw ?? "nil")")
        }
        XCTAssertTrue(E.all.matches(nil))
        XCTAssertTrue(E.all.matches("barbell"))
        XCTAssertFalse(E.dumbbell.matches("barbell"))
        // A custom exercise created under a chip stays under it.
        for chip in E.allCases where chip != .all {
            XCTAssertEqual(E.of(chip.representative), chip)
        }
        XCTAssertNil(E.all.representative)
    }

    func testEquipmentCombinesWithMusclesAndSearch() {
        context.insert(Exercise(id: "bb-bench", name: "Barbell Bench Press", primaryMuscles: ["chest"], equipment: "barbell"))
        context.insert(Exercise(id: "db-bench", name: "Dumbbell Bench Press", primaryMuscles: ["chest"], equipment: "dumbbell"))
        context.insert(Exercise(id: "db-curl", name: "Dumbbell Curl", primaryMuscles: ["biceps"], equipment: "dumbbell"))
        context.insert(Exercise(id: "ez-curl", name: "EZ-Bar Curl", primaryMuscles: ["biceps"], equipment: "e-z curl bar"))
        context.insert(Exercise(id: "pushup", name: "Pushups", primaryMuscles: ["chest"], equipment: "body only"))
        context.insert(Exercise(id: "roll", name: "Chest Roll", primaryMuscles: ["chest"], equipment: nil))
        try? context.save()
        let model = ExercisePickerViewModel()
        model.load(context: context)

        func ids() -> Set<String> { Set(model.results.map(\.id)) }

        model.equipment = .dumbbell
        XCTAssertEqual(ids(), ["db-bench", "db-curl"])
        model.group = .chest
        XCTAssertEqual(ids(), ["db-bench"])
        model.equipment = .barbell
        XCTAssertEqual(ids(), ["bb-bench"])
        model.group = .arms
        XCTAssertEqual(ids(), ["ez-curl"], "the EZ bar counts as a barbell")
        model.group = .chest
        model.equipment = .other
        XCTAssertEqual(ids(), ["roll"], "no equipment is Other")
        model.equipment = .all
        XCTAssertEqual(ids(), ["bb-bench", "db-bench", "pushup", "roll"])

        model.query = "bench"
        model.equipment = .dumbbell   // filters at once instead of after the typing debounce
        XCTAssertEqual(ids(), ["db-bench"])
    }

    func testCustomExerciseTakesTheChipsEquipment() {
        let model = ExercisePickerViewModel()
        model.load(context: context)
        model.equipment = .kettlebell
        model.query = "Kettlebell Halo"
        let created = model.createExercise(context: context)
        XCTAssertEqual(created?.equipment, "kettlebells")
        XCTAssertEqual(model.results.map(\.id), [created?.id].compactMap { $0 })
    }
}
