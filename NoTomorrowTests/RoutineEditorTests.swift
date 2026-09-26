import XCTest
import SwiftData
@testable import NoTomorrow

/// The routine builder: draft edits, names, "Save as routine", and the store writes behind Save / Duplicate / Move.
@MainActor
final class RoutineEditorTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private func exercise(_ id: String) -> Exercise {
        let exercise = Exercise(id: id, name: id, primaryMuscles: ["chest"])
        context.insert(exercise)
        return exercise
    }

    private func line(_ id: String, sets: Int = 3, reps: Int = 8, rest: Int = 0) -> RoutineItemDraft {
        RoutineItemDraft(exerciseID: id, name: id, sets: sets, reps: reps, restSeconds: rest)
    }

    private func routines() -> [Routine] {
        (try? context.fetch(FetchDescriptor<Routine>(sortBy: [SortDescriptor(\.order)]))) ?? []
    }

    // MARK: Draft

    func testStepsClampToTheirRanges() {
        var draft = RoutineDraft(name: "A", items: [line("bench", sets: 1, reps: 50)])
        let id = draft.items[0].id
        draft.stepSets(id, by: -1)
        draft.stepReps(id, by: 1)
        XCTAssertEqual(draft.items[0].sets, 1)
        XCTAssertEqual(draft.items[0].reps, 50)
        draft.stepSets(id, by: 1)
        draft.stepReps(id, by: -1)
        XCTAssertEqual(draft.items[0].sets, 2)
        XCTAssertEqual(draft.items[0].reps, 49)
    }

    func testAppendSkipsAnExerciseAlreadyInTheRoutine() {
        var draft = RoutineDraft(name: "A", items: [line("bench")])
        draft.append(line("bench"))
        draft.append(line("row"))
        XCTAssertEqual(draft.items.map(\.exerciseID), ["bench", "row"])
    }

    func testMoveStaysInBounds() {
        var draft = RoutineDraft(name: "A", items: [line("a"), line("b"), line("c")])
        draft.move(draft.items[0].id, by: -1)
        XCTAssertEqual(draft.items.map(\.exerciseID), ["a", "b", "c"])
        draft.move(draft.items[0].id, by: 1)
        XCTAssertEqual(draft.items.map(\.exerciseID), ["b", "a", "c"])
    }

    func testSaveNeedsAFreeNameAndAnExercise() {
        var draft = RoutineDraft(name: "  ", items: [line("a")])
        XCTAssertFalse(draft.canSave(otherNames: []))
        draft.name = "push a"
        XCTAssertFalse(draft.canSave(otherNames: ["Push A"]))
        XCTAssertTrue(draft.canSave(otherNames: ["Pull A"]))
        draft.items = []
        XCTAssertFalse(draft.canSave(otherNames: []))
    }

    func testUniqueNameCountsUp() {
        XCTAssertEqual(RoutineDraft.uniqueName("Legs", taken: ["Push A"]), "Legs")
        XCTAssertEqual(RoutineDraft.uniqueName("Legs", taken: ["legs", "Legs 2"]), "Legs 3")
    }

    func testFromWorkoutKeepsWorkingSetsAndCustomRest() {
        let draft = RoutineDraft.from(workoutName: "Push A", exercises: [
            .init(exerciseID: "bench", name: "Bench", primaryMuscle: "chest", workingReps: [8, 6, 6],
                  restSeconds: 120, usesDefaultRest: true),
            .init(exerciseID: "fly", name: "Fly", primaryMuscle: "chest", workingReps: [12, 12],
                  restSeconds: 45, usesDefaultRest: false),
            .init(exerciseID: "skipped", name: "Skipped", primaryMuscle: nil, workingReps: [],
                  restSeconds: 90, usesDefaultRest: true),
        ], takenNames: ["Push A"])
        XCTAssertEqual(draft.name, "Push A 2")
        XCTAssertEqual(draft.items.map(\.exerciseID), ["bench", "fly"])
        XCTAssertEqual(draft.items.map(\.sets), [3, 2])
        XCTAssertEqual(draft.items.map(\.reps), [8, 12])
        XCTAssertEqual(draft.items.map(\.restSeconds), [RoutineDraft.inheritRest, 45])
    }

    // MARK: Store

    func testSaveCreatesThenReplacesLines() {
        _ = exercise("bench"); _ = exercise("row"); _ = exercise("curl")
        let first = RoutineStore.save(RoutineDraft(name: " Upper ", items: [line("bench"), line("row", sets: 4, reps: 10, rest: 60)]),
                                      into: nil, in: context)
        XCTAssertEqual(first.name, "Upper")
        XCTAssertEqual(first.sortedItems.compactMap { $0.exercise?.id }, ["bench", "row"])
        XCTAssertEqual(first.sortedItems.last?.targetSets, 4)
        XCTAssertEqual(first.sortedItems.last?.restSeconds, 60)

        var edit = RoutineStore.draft(of: first)
        edit.name = "Upper B"
        edit.remove(edit.items[0].id)
        edit.append(line("curl"))
        RoutineStore.save(edit, into: first, in: context)
        XCTAssertEqual(routines().count, 1)
        XCTAssertEqual(first.name, "Upper B")
        XCTAssertEqual(first.sortedItems.compactMap { $0.exercise?.id }, ["row", "curl"])
        XCTAssertEqual((try? context.fetchCount(FetchDescriptor<RoutineItem>())) ?? -1, 2)
    }

    func testNewRoutinesGoToTheEndAndDuplicateSitsAfterItsOriginal() {
        _ = exercise("bench")
        let a = RoutineStore.save(RoutineDraft(name: "A", items: [line("bench")]), into: nil, in: context)
        _ = RoutineStore.save(RoutineDraft(name: "B", items: [line("bench")]), into: nil, in: context)
        RoutineStore.duplicate(a, in: context)
        XCTAssertEqual(routines().map(\.name), ["A", "A 2", "B"])
        XCTAssertEqual(routines().map(\.order), [0, 1, 2])
    }

    func testMoveSwapsNeighboursAndDeleteRemovesItems() {
        _ = exercise("bench")
        let a = RoutineStore.save(RoutineDraft(name: "A", items: [line("bench")]), into: nil, in: context)
        let b = RoutineStore.save(RoutineDraft(name: "B", items: [line("bench")]), into: nil, in: context)
        RoutineStore.move(b, by: -1, in: context)
        XCTAssertEqual(routines().map(\.name), ["B", "A"])
        RoutineStore.move(b, by: -1, in: context)
        XCTAssertEqual(routines().map(\.name), ["B", "A"])
        RoutineStore.delete(a, in: context)
        XCTAssertEqual(routines().map(\.name), ["B"])
        XCTAssertEqual((try? context.fetchCount(FetchDescriptor<RoutineItem>())) ?? -1, 1)
    }

    func testSaveAsRoutineReadsTheFinishedWorkout() {
        let bench = exercise("bench")
        let workout = Workout(name: "Push A")
        context.insert(workout)
        workout.endedAt = .now
        let item = WorkoutExercise(order: 0, exercise: bench, restSeconds: 45)
        context.insert(item)
        item.workout = workout
        for (index, kind) in [SetKind.warmup, .normal, .normal].enumerated() {
            let set = SetEntry(order: index, kind: kind, weightKg: 60, reps: 10 - index)
            set.completedAt = .now
            context.insert(set)
            set.workoutExercise = item
        }
        let draft = RoutineStore.draft(from: workout, in: context)
        XCTAssertEqual(draft.name, "Push A")
        XCTAssertEqual(draft.items.count, 1)
        XCTAssertEqual(draft.items[0].sets, 2)
        XCTAssertEqual(draft.items[0].reps, 9)
        XCTAssertEqual(draft.items[0].restSeconds, 45)
    }

    func testSeederRunsOnceSoDeletedRoutinesStayDeleted() throws {
        let suite = "RoutineEditorTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        for id in Set(RoutineSeeder.templates.flatMap(\.exerciseIds)) { _ = exercise(id) }
        RoutineSeeder.seedIfNeeded(context: context, defaults: defaults)
        XCTAssertEqual(routines().count, RoutineSeeder.templates.count)
        routines().forEach { RoutineStore.delete($0, in: context) }
        RoutineSeeder.seedIfNeeded(context: context, defaults: defaults)
        XCTAssertTrue(routines().isEmpty)
    }
}
