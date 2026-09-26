import XCTest
import SwiftData
@testable import NoTomorrow

/// Sharing a routine as text (`RoutineShare`): the text a routine writes, reading it back from the `nt1:` line or the
/// numbered lines, the editor's limits, matching to the library, and "Add routine". The fixture is shared with the
/// Android tests (`RoutineShareTest.kt`), so both apps read the same text the same way.
@MainActor
final class RoutineShareTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }

    override func tearDown() {
        container = nil
    }

    // MARK: Fixture (identical in android/.../RoutineShareTest.kt)

    private static let fixture = """
    No Tomorrow routine: Push A · Pchnięcia
    1. Barbell Bench Press - Medium Grip — 4 × 8, rest 2:00
    2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]
    3. Dumbbell Flyes — 3 × 12 [Superset A]
    4. Żuraw "nordycki" — 3 × 15, rest 1:00
    nt1:eyJ2IjoxLCJuYW1lIjoiUHVzaCBBIMK3IFBjaG5pxJljaWEiLCJpdGVtcyI6W3siaWQiOiJCYXJiZWxsX0JlbmNoX1ByZXNzXy1fTWVkaXVtX0dyaXAiLCJzZXRzIjo0LCJyZXBzIjo4LCJyZXN0IjoxMjB9LHsiaWQiOiJJbmNsaW5lX0R1bWJiZWxsX1ByZXNzIiwic2V0cyI6MywicmVwcyI6MTAsInJlc3QiOjkwLCJzcyI6MX0seyJpZCI6IkR1bWJiZWxsX0ZseWVzIiwic2V0cyI6MywicmVwcyI6MTIsInJlc3QiOjAsInNzIjoxfSx7Im5hbWUiOiLFu3VyYXcgXCJub3JkeWNraVwiIiwic2V0cyI6MywicmVwcyI6MTUsInJlc3QiOjYwfV19
    """

    private static let fixtureDraft = RoutineDraft(name: "Push A · Pchnięcia", items: [
        RoutineItemDraft(exerciseID: "Barbell_Bench_Press_-_Medium_Grip", name: "Barbell Bench Press - Medium Grip",
                         sets: 4, reps: 8, restSeconds: 120),
        RoutineItemDraft(exerciseID: "Incline_Dumbbell_Press", name: "Incline Dumbbell Press",
                         sets: 3, reps: 10, restSeconds: 90, supersetGroup: 1),
        RoutineItemDraft(exerciseID: "Dumbbell_Flyes", name: "Dumbbell Flyes", sets: 3, reps: 12, restSeconds: 0,
                         supersetGroup: 1),
        RoutineItemDraft(exerciseID: "custom-1234", name: "Żuraw \"nordycki\"", sets: 3, reps: 15, restSeconds: 60),
    ])

    private static let fixtureShared = RoutineShare.Shared(name: "Push A · Pchnięcia", lines: [
        .init(exerciseID: "Barbell_Bench_Press_-_Medium_Grip", name: "Barbell Bench Press - Medium Grip",
              sets: 4, reps: 8, restSeconds: 120, supersetGroup: nil),
        .init(exerciseID: "Incline_Dumbbell_Press", name: "Incline Dumbbell Press",
              sets: 3, reps: 10, restSeconds: 90, supersetGroup: 1),
        .init(exerciseID: "Dumbbell_Flyes", name: "Dumbbell Flyes", sets: 3, reps: 12, restSeconds: 0, supersetGroup: 1),
        .init(exerciseID: nil, name: "Żuraw \"nordycki\"", sets: 3, reps: 15, restSeconds: 60, supersetGroup: nil),
    ])

    // MARK: Writing

    func testWritingTheFixtureRoutineGivesTheFixtureText() {
        XCTAssertEqual(RoutineShare.text(Self.fixtureDraft, labels: .english), Self.fixture)
    }

    func testCustomExercisesAreSharedByNameAndLibraryOnesById() {
        let shared = RoutineShare.shared(from: Self.fixtureDraft)
        XCTAssertEqual(shared.lines.map(\.exerciseID),
                       ["Barbell_Bench_Press_-_Medium_Grip", "Incline_Dumbbell_Press", "Dumbbell_Flyes", nil])
        XCTAssertEqual(shared.lines.map(\.name), [nil, nil, nil, "Żuraw \"nordycki\""])
    }

    func testBase64URLUsesTheUrlAlphabetWithoutPadding() {
        XCTAssertEqual(RoutineShare.base64URL(Data([0xfb, 0xff])), "-_8")
        XCTAssertEqual(RoutineShare.base64URLDecoded("-_8"), Data([0xfb, 0xff]))
        XCTAssertEqual(RoutineShare.base64URLDecoded("+/8="), Data([0xfb, 0xff]))
        XCTAssertNil(RoutineShare.base64URLDecoded("abcde"))
    }

    // MARK: Reading

    func testTheFixtureDecodes() {
        XCTAssertEqual(RoutineShare.decode(Self.fixture), Self.fixtureShared)
    }

    func testRoundTripKeepsEveryLine() throws {
        let draft = RoutineDraft(name: "Legs\nday", items: [
            RoutineItemDraft(exerciseID: "Barbell_Squat", name: "Barbell Squat", sets: 5, reps: 5, restSeconds: 180),
            RoutineItemDraft(exerciseID: "custom-x", name: "Sled \\ push", sets: 2, reps: 20, restSeconds: 45,
                             supersetGroup: 3),
            RoutineItemDraft(exerciseID: "Lunges", name: "Lunges", sets: 3, reps: 12, supersetGroup: 3),
        ])
        let shared = try XCTUnwrap(RoutineShare.decodePayload(in: RoutineShare.text(draft)))
        XCTAssertEqual(shared.name, "Legs day")
        XCTAssertEqual(shared.lines.map(\.exerciseID), ["Barbell_Squat", nil, "Lunges"])
        XCTAssertEqual(shared.lines[1].name, "Sled \\ push")
        XCTAssertEqual(shared.lines.map(\.sets), [5, 2, 3])
        XCTAssertEqual(shared.lines.map(\.reps), [5, 20, 12])
        XCTAssertEqual(shared.lines.map(\.restSeconds), [180, 45, 0])
        XCTAssertEqual(shared.lines.map(\.supersetGroup), [nil, 1, 1])
    }

    func testWithoutThePayloadTheNumberedLinesAreRead() throws {
        let text = Self.fixture.split(separator: "\n").dropLast().joined(separator: "\n")
        let shared = try XCTUnwrap(RoutineShare.decode(text))
        XCTAssertEqual(shared.name, "Push A · Pchnięcia")
        XCTAssertEqual(shared.lines, Self.fixtureShared.lines.map { line in
            var copy = line
            copy.exerciseID = nil
            return copy
        })
    }

    func testADamagedPayloadFallsBackToTheNumberedLines() throws {
        let text = Self.fixture.replacingOccurrences(of: "nt1:eyJ2", with: "nt1:eyJ3")
        let shared = try XCTUnwrap(RoutineShare.decode(text))
        XCTAssertEqual(shared.lines.map(\.exerciseID), [nil, nil, nil, nil])
        XCTAssertEqual(shared.lines.count, 4)
    }

    func testAPayloadWrappedOverLinesStillDecodes() throws {
        let payload = RoutineShare.payload(RoutineShare.shared(from: Self.fixtureDraft))
        let cut = payload.index(payload.startIndex, offsetBy: 60)
        let wrapped = "look at this\n" + String(payload[..<cut]) + "\n  " + String(payload[cut...])
        let shared = try XCTUnwrap(RoutineShare.decode(wrapped))
        XCTAssertEqual(shared.lines.map(\.exerciseID), Self.fixtureShared.lines.map(\.exerciseID))
    }

    func testAnotherLanguagesLinesAreReadByShape() throws {
        let text = """
        Plan No Tomorrow: Góra
        1. Wyciskanie sztangi na ławce — 4 × 6, przerwa 3:00
        2) Podciąganie - 3x8 [Superseria B]
        3. Wiosłowanie – 3 * 10, przerwa 1:30 [Superseria B]
        4. Plank
        """
        let shared = try XCTUnwrap(RoutineShare.decode(text))
        XCTAssertEqual(shared.name, "Góra")
        XCTAssertEqual(shared.lines.map(\.name), ["Wyciskanie sztangi na ławce", "Podciąganie", "Wiosłowanie", "Plank"])
        XCTAssertEqual(shared.lines.map(\.sets), [4, 3, 3, RoutineDraft.defaultSets])
        XCTAssertEqual(shared.lines.map(\.reps), [6, 8, 10, RoutineDraft.defaultReps])
        XCTAssertEqual(shared.lines.map(\.restSeconds), [180, 0, 90, 0])
        XCTAssertEqual(shared.lines.map(\.supersetGroup), [nil, 1, 1, nil])
    }

    func testValuesAreClampedToTheEditorsLimits() throws {
        let json = #"{"v":1,"name":"X","items":[{"id":"a","sets":40,"reps":0,"rest":100},{"id":"b","sets":-2,"reps":400,"rest":9999},{"id":"","name":"  "},{"name":"c","rest":-5,"ss":0}]}"#
        let shared = try XCTUnwrap(RoutineShare.decode(RoutineShare.marker + RoutineShare.base64URL(Data(json.utf8))))
        XCTAssertEqual(shared.lines.map(\.exerciseID), ["a", "b", nil])
        XCTAssertEqual(shared.lines.map(\.sets), [10, 1, RoutineDraft.defaultSets])
        XCTAssertEqual(shared.lines.map(\.reps), [1, 50, RoutineDraft.defaultReps])
        XCTAssertEqual(shared.lines.map(\.restSeconds), [90, 300, 0])
        XCTAssertEqual(shared.lines.map(\.supersetGroup), [nil, nil, nil])
    }

    func testTextWithoutARoutineGivesNothing() {
        XCTAssertNil(RoutineShare.decode(""))
        XCTAssertNil(RoutineShare.decode("hey, lunch tomorrow?"))
        XCTAssertNil(RoutineShare.decode("nt1:!!!"))
        let future = #"{"v":2,"name":"X","items":[{"id":"a","sets":3,"reps":8,"rest":0}]}"#
        XCTAssertNil(RoutineShare.decode(RoutineShare.marker + RoutineShare.base64URL(Data(future.utf8))))
    }

    func testRestSnapsToTheNearestMenuLength() {
        XCTAssertEqual(RoutineShare.snapRest(0), 0)
        XCTAssertEqual(RoutineShare.snapRest(-10), 0)
        XCTAssertEqual(RoutineShare.snapRest(1), 30)
        XCTAssertEqual(RoutineShare.snapRest(100), 90)
        XCTAssertEqual(RoutineShare.snapRest(105), 90)
        XCTAssertEqual(RoutineShare.snapRest(106), 120)
        XCTAssertEqual(RoutineShare.snapRest(1000), 300)
    }

    // MARK: Matching

    func testPlanMatchesByIdThenByNameAndMarksNewExercises() {
        var catalog = RoutineShare.Catalog()
        catalog.add(.init(id: "Barbell_Bench_Press_-_Medium_Grip", name: "Wyciskanie", primaryMuscle: "chest"),
                    names: ["Barbell Bench Press - Medium Grip"])
        catalog.add(.init(id: "Dumbbell_Flyes", name: "Dumbbell Flyes", primaryMuscle: "chest"),
                    names: ["Dumbbell Flyes", "Rozpiętki"])
        let shared = RoutineShare.Shared(name: "X", lines: [
            .init(exerciseID: "Barbell_Bench_Press_-_Medium_Grip", name: nil, sets: 4, reps: 8, restSeconds: 120),
            .init(exerciseID: "Unknown_Id", name: "rozpietki", sets: 3, reps: 12, restSeconds: 0, supersetGroup: 1),
            .init(exerciseID: nil, name: "Żuraw", sets: 3, reps: 15, restSeconds: 60, supersetGroup: 1),
            .init(exerciseID: nil, name: "zuraw", sets: 2, reps: 5, restSeconds: 0),
            .init(exerciseID: "Gone", name: nil, sets: 2, reps: 5, restSeconds: 0),
        ])
        let planned = RoutineShare.plan(shared, catalog: catalog)
        XCTAssertEqual(planned.map(\.exerciseID), ["Barbell_Bench_Press_-_Medium_Grip", "Dumbbell_Flyes", nil])
        XCTAssertEqual(planned.map(\.name), ["Wyciskanie", "Dumbbell Flyes", "Żuraw"])
        XCTAssertEqual(planned.map(\.isNew), [false, false, true])
        XCTAssertEqual(planned.map(\.supersetGroup), [nil, 1, 1])
        XCTAssertEqual(planned.map(\.index), [0, 1, 2])
    }

    // MARK: Store

    func testAddingASharedRoutineCreatesCustomExercisesAndAUniqueName() throws {
        context.insert(Exercise(id: "Barbell_Bench_Press_-_Medium_Grip", name: "Barbell Bench Press - Medium Grip",
                                primaryMuscles: ["chest"]))
        context.insert(Exercise(id: "Incline_Dumbbell_Press", name: "Incline Dumbbell Press", primaryMuscles: ["chest"]))
        context.insert(Exercise(id: "Dumbbell_Flyes", name: "Dumbbell Flyes", primaryMuscles: ["chest"]))
        context.insert(Routine(name: "push a · pchnięcia", order: 0))
        try context.save()

        let shared = try XCTUnwrap(RoutineShare.decode(Self.fixture))
        let planned = RoutineShare.plan(shared, catalog: RoutineStore.shareCatalog(in: context))
        let routine = try XCTUnwrap(RoutineStore.addShared(name: shared.name, items: planned, fallbackName: "Shared",
                                                           in: context))

        XCTAssertEqual(routine.name, "Push A · Pchnięcia 2")
        XCTAssertEqual(routine.order, 1)
        let items = routine.sortedItems
        XCTAssertEqual(items.count, 4)
        XCTAssertEqual(items.prefix(3).compactMap { $0.exercise?.id },
                       ["Barbell_Bench_Press_-_Medium_Grip", "Incline_Dumbbell_Press", "Dumbbell_Flyes"])
        let custom = try XCTUnwrap(items[3].exercise)
        XCTAssertTrue(custom.isCustom)
        XCTAssertTrue(custom.id.hasPrefix("custom-"))
        XCTAssertEqual(custom.name, "Żuraw \"nordycki\"")
        XCTAssertEqual(items.map(\.targetSets), [4, 3, 3, 3])
        XCTAssertEqual(items.map(\.targetReps), [8, 10, 12, 15])
        XCTAssertEqual(items.map(\.restSeconds), [120, 90, 0, 60])
        XCTAssertEqual(items.map(\.supersetGroup), [nil, 1, 1, nil])

        // The same text again matches the custom exercise it created instead of adding another.
        let again = RoutineShare.plan(shared, catalog: RoutineStore.shareCatalog(in: context))
        XCTAssertEqual(again.map(\.isNew), [false, false, false, false])
    }

    func testSharingARoutineWritesItsLines() throws {
        let bench = Exercise(id: "Barbell_Bench_Press_-_Medium_Grip", name: "Barbell Bench Press - Medium Grip",
                             primaryMuscles: ["chest"])
        context.insert(bench)
        let routine = Routine(name: "Push", order: 0)
        context.insert(routine)
        let item = RoutineItem(order: 0, exercise: bench, targetSets: 4, targetReps: 8, restSeconds: 120)
        context.insert(item)
        item.routine = routine
        try context.save()

        let shared = try XCTUnwrap(RoutineShare.decode(RoutineStore.shareText(for: routine)))
        XCTAssertEqual(shared.name, "Push")
        XCTAssertEqual(shared.lines.map(\.exerciseID), ["Barbell_Bench_Press_-_Medium_Grip"])
        XCTAssertEqual(shared.lines.map(\.sets), [4])
        XCTAssertEqual(shared.lines.map(\.restSeconds), [120])
    }
}
