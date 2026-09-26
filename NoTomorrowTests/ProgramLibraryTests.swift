import XCTest
import SwiftData
@testable import NoTomorrow

/// The built-in programs ("Browse programs"): the shared `programs.json` decodes, every exercise id is in the bundled
/// library, every line fits the routine editor, every string is in the catalog, and "Add N routines" writes the
/// routines with their lines and unique names.
@MainActor
final class ProgramLibraryTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    private var programs: [TrainingProgram] { ProgramLibrary.all }

    private func program(_ id: String) throws -> TrainingProgram {
        try XCTUnwrap(programs.first { $0.id == id })
    }

    private func routines() -> [Routine] {
        (try? context.fetch(FetchDescriptor<Routine>(sortBy: [SortDescriptor(\.order)]))) ?? []
    }

    /// Every library exercise the program uses, as the import would insert it.
    private func insertExercises(of program: TrainingProgram) {
        for id in program.exerciseIDs.sorted() {
            context.insert(Exercise(id: id, name: id, primaryMuscles: ["chest"]))
        }
        try? context.save()
    }

    // MARK: Data

    func testBundledProgramsDecode() {
        XCTAssertEqual(programs.map(\.id), ["fullBody", "fiveByFive", "ppl", "upperLower", "fiveThreeOne", "dumbbellHome"])
        for program in programs {
            XCTAssertTrue((2...6).contains(program.routines.count), program.id)
            XCTAssertTrue((1...7).contains(program.daysPerWeek), program.id)
            XCTAssertEqual(Set(program.routines.map(\.id)).count, program.routines.count, program.id)
        }
    }

    func testEveryProgramExerciseIdResolvesInTheBundledLibrary() throws {
        let library = try XCTUnwrap(ExerciseLibrary.loadBundled())
        let ids = Set(library.records.map(\.id))
        for program in programs {
            for day in program.routines {
                XCTAssertFalse(day.items.isEmpty, "\(program.id)/\(day.id)")
                for line in day.items {
                    XCTAssertTrue(ids.contains(line.exercise), "\(program.id)/\(day.id): \(line.exercise)")
                }
            }
        }
    }

    func testEveryLineFitsTheRoutineEditor() {
        for program in programs {
            for day in program.routines {
                XCTAssertEqual(Set(day.items.map(\.exercise)).count, day.items.count, "\(program.id)/\(day.id) repeats")
                for line in day.items {
                    let where_ = "\(program.id)/\(day.id)/\(line.exercise)"
                    XCTAssertTrue(RoutineDraft.setRange.contains(line.sets), where_)
                    XCTAssertTrue(RoutineDraft.repRange.contains(line.reps), where_)
                    XCTAssertTrue(RoutineDraft.restOptions.contains(line.rest), where_)
                }
            }
        }
    }

    func testEveryProgramStringIsInTheCatalogInBothLanguages() throws {
        let missing = "\u{1}missing"
        let keys = programs.flatMap(\.localizationKeys) + programs.map(\.level.titleKey)
        let bundles = try ["en", "pl"].map { language -> Bundle in
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), language)
            return try XCTUnwrap(Bundle(path: path), language)
        }
        for bundle in bundles {
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: missing, table: nil)
                XCTAssertNotEqual(value, missing, "\(bundle.bundlePath): \(key)")
                XCTAssertNotEqual(value, key, "\(bundle.bundlePath): \(key)")
            }
        }
    }

    // MARK: Drafts

    func testDraftsLocalizeNamesKeepTargetsAndSkipUnknownExercises() {
        let program = TrainingProgram(
            id: "p", name: "p.name", summary: "p.summary", level: .beginner, daysPerWeek: 2,
            routines: [
                .init(id: "a", name: "p.a", items: [.init(exercise: "squat", sets: 5, reps: 5, rest: 180),
                                                    .init(exercise: "gone", sets: 3, reps: 8, rest: 90)]),
                .init(id: "b", name: "p.b", items: [.init(exercise: "press", sets: 3, reps: 10, rest: 90)]),
            ])
        let known = ["squat": "Squat", "press": "Press"]
        let drafts = ProgramLibrary.drafts(
            for: program, taken: ["day a"],
            exercise: { id in known[id].map { ProgramLibrary.ExerciseInfo(name: $0, primaryMuscle: "quadriceps") } },
            localize: { $0 == "p.a" ? "Day A" : "Day B" })
        XCTAssertEqual(drafts.map(\.name), ["Day A 2", "Day B"])
        XCTAssertEqual(drafts[0].items.map(\.exerciseID), ["squat"])
        XCTAssertEqual(drafts[0].items[0].name, "Squat")
        XCTAssertEqual(drafts[0].items[0].sets, 5)
        XCTAssertEqual(drafts[0].items[0].reps, 5)
        XCTAssertEqual(drafts[0].items[0].restSeconds, 180)
        XCTAssertEqual(drafts[1].items.map(\.exerciseID), ["press"])
    }

    // MARK: Add

    func testAddingAProgramCreatesItsRoutinesInOrder() throws {
        let ppl = try program("ppl")
        insertExercises(of: ppl)
        context.insert(Routine(name: "Mine", order: 0))
        try? context.save()

        let added = RoutineStore.add(ppl, in: context)

        XCTAssertEqual(added.count, ppl.routines.count)
        let all = routines()
        XCTAssertEqual(all.map(\.name), ["Mine"] + ppl.routines.map { TrainingProgram.text($0.name) })
        XCTAssertEqual(all.map(\.order), Array(0..<all.count))
        for (routine, day) in zip(all.dropFirst(), ppl.routines) {
            let items = routine.sortedItems
            XCTAssertEqual(items.compactMap { $0.exercise?.id }, day.items.map(\.exercise), day.id)
            XCTAssertEqual(items.map(\.targetSets), day.items.map(\.sets), day.id)
            XCTAssertEqual(items.map(\.targetReps), day.items.map(\.reps), day.id)
            XCTAssertEqual(items.map(\.restSeconds), day.items.map(\.rest), day.id)
        }
    }

    func testAddingAProgramTwiceGivesUniqueNames() throws {
        let fiveByFive = try program("fiveByFive")
        insertExercises(of: fiveByFive)

        RoutineStore.add(fiveByFive, in: context)
        RoutineStore.add(fiveByFive, in: context)

        let names = routines().map(\.name)
        let base = fiveByFive.routines.map { TrainingProgram.text($0.name) }
        XCTAssertEqual(names, base + base.map { $0 + " 2" })
        XCTAssertEqual(Set(names.map { $0.lowercased() }).count, names.count)
    }

    func testAddingWithoutTheLibrarySkipsEmptyRoutines() throws {
        let added = RoutineStore.add(try program("upperLower"), in: context)
        XCTAssertTrue(added.isEmpty)
        XCTAssertTrue(routines().isEmpty)
    }
}
