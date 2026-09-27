import XCTest
import SwiftData
@testable import NoTomorrow

/// Exercise search folding (Polish typed without diacritics, ł included), the versioned library import, and the
/// short Polish duration copy.
@MainActor
final class ExerciseLibraryTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        suiteName = "ExerciseLibraryTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        container = nil
    }

    // MARK: Search (l10n-a11y-1)

    func testFoldFlattensEveryPolishLetter() {
        XCTAssertEqual(WorkoutStrings.fold("Ławka"), WorkoutStrings.fold("lawka"))
        XCTAssertEqual(WorkoutStrings.fold("Wiosłowanie sztangą"), "wioslowanie sztanga")
        XCTAssertEqual(WorkoutStrings.fold("ŁYDKI"), "lydki")
        XCTAssertEqual(WorkoutStrings.fold("Zażółć gęślą jaźń"), "zazolc gesla jazn")
    }

    func testPickerFindsPolishNamesTypedWithoutDiacritics() {
        context.insert(Exercise(id: "row", name: "Alternating Kettlebell Row",
                                namePL: "Naprzemienne wiosłowanie kettlebell", primaryMuscles: ["middle back"]))
        context.insert(Exercise(id: "bench", name: "Barbell Bench Press - Medium Grip",
                                namePL: "Wyciskanie sztangi na ławce płaskiej", primaryMuscles: ["chest"]))
        try? context.save()
        let model = ExercisePickerViewModel()
        model.load(context: context)

        func search(_ query: String) -> [String] {
            model.query = query
            model.group = .all   // filters at once instead of after the typing debounce
            return model.results.map(\.id)
        }

        XCTAssertEqual(search("wioslowanie"), ["row"])
        XCTAssertEqual(search("lawce plaskiej"), ["bench"])
        XCTAssertEqual(search("WYCISKANIE ławce"), ["bench"])
    }

    func testSearchingDropsTheBodyMapMuscleAndDoesNotOfferADuplicate() {
        context.insert(Exercise(id: "belt", name: "Belt Squat", namePL: "Przysiad z pasem", primaryMuscles: ["quadriceps"]))
        context.insert(Exercise(id: "pull", name: "Pullups", primaryMuscles: ["lats"]))
        try? context.save()
        let model = ExercisePickerViewModel()
        model.load(context: context)
        model.muscle = "lats"
        XCTAssertEqual(model.results.map(\.id), ["pull"])

        model.query = "belt squat"
        XCTAssertNil(model.muscle, "typing a search clears the body-map muscle")
        XCTAssertFalse(model.showsCreateRow, "the library already has it")
        model.query = "przysiad z pasem"
        XCTAssertFalse(model.showsCreateRow)
        model.query = "belt squat 2"
        XCTAssertTrue(model.showsCreateRow)
    }

    func testDefaultWorkoutNameFollowsTheCurrentLanguage() {
        let current = String(localized: "workout.defaultName")
        XCTAssertEqual(WorkoutStrings.displayName("Workout"), current)
        XCTAssertEqual(WorkoutStrings.displayName("Trening"), current)
        XCTAssertEqual(WorkoutStrings.displayName("Push A"), "Push A")
    }

    // MARK: Library import (reliability-perf-4, ios-correctness-3)

    private final class LoadCounter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        var count: Int { lock.withLock { value } }
        func increment() { lock.withLock { value += 1 } }
    }

    private func bundled() -> ExerciseLibrary.Bundled {
        let json = """
        [{"id": "Barbell_Squat", "name": "Barbell Squat", "primaryMuscles": ["quadriceps"], "secondaryMuscles": [],
          "instructions": [], "category": "strength"},
         {"id": "Pullups", "name": "Pullups", "primaryMuscles": ["lats"], "secondaryMuscles": ["biceps"],
          "instructions": [], "category": "strength", "equipment": "body only"}]
        """
        let records = (try? JSONDecoder().decode([ExerciseLibrary.Record].self, from: Data(json.utf8))) ?? []
        return ExerciseLibrary.Bundled(records: records, polish: ["Barbell_Squat": "Przysiad ze sztangą"])
    }

    private func libraryNames() -> [String: String?] {
        let rows = (try? context.fetch(FetchDescriptor<Exercise>())) ?? []
        return Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0.namePL) })
    }

    func testImportRunsOncePerLibraryVersion() async {
        let counter = LoadCounter()
        let data = bundled()
        let load: @Sendable () -> ExerciseLibrary.Bundled? = { counter.increment(); return data }

        await ExerciseLibrary.importIfNeeded(into: context, defaults: defaults, load: load)
        XCTAssertEqual(counter.count, 1)
        XCTAssertEqual(libraryNames(), ["Barbell_Squat": "Przysiad ze sztangą", "Pullups": nil])
        XCTAssertEqual(defaults.integer(forKey: ExerciseLibrary.versionKey), ExerciseLibrary.libraryVersion)

        await ExerciseLibrary.importIfNeeded(into: context, defaults: defaults, load: load)
        XCTAssertEqual(counter.count, 1, "a cold launch with the same library does no work")

        // A new bundled version fills in what is missing without duplicating rows.
        defaults.set(ExerciseLibrary.libraryVersion - 1, forKey: ExerciseLibrary.versionKey)
        let squat = (try? context.fetch(FetchDescriptor<Exercise>()))?.first { $0.id == "Barbell_Squat" }
        squat?.namePL = nil
        await ExerciseLibrary.importIfNeeded(into: context, defaults: defaults, load: load)
        XCTAssertEqual(counter.count, 2)
        XCTAssertEqual(libraryNames(), ["Barbell_Squat": "Przysiad ze sztangą", "Pullups": nil])
    }

    func testEmptyStoreImportsEvenWithTheStamp() async {
        defaults.set(ExerciseLibrary.libraryVersion, forKey: ExerciseLibrary.versionKey)
        context.insert(Exercise(id: "custom-1", name: "My press", primaryMuscles: ["chest"], isCustom: true))
        try? context.save()

        let data = bundled()
        await ExerciseLibrary.importIfNeeded(into: context, defaults: defaults, load: { data })

        XCTAssertEqual(libraryNames().count, 3, "custom exercises do not count as the library")
    }

    func testNeedsImport() {
        let current = ExerciseLibrary.libraryVersion
        XCTAssertTrue(ExerciseLibrary.needsImport(storedVersion: nil, libraryRows: 876))
        XCTAssertTrue(ExerciseLibrary.needsImport(storedVersion: current - 1, libraryRows: 876))
        XCTAssertTrue(ExerciseLibrary.needsImport(storedVersion: current, libraryRows: 0))
        XCTAssertFalse(ExerciseLibrary.needsImport(storedVersion: current, libraryRows: 876))
    }

    // MARK: Duration copy (l10n-a11y-2)

    func testPolishDurationsUseTheShortHourSymbol() {
        let pl = Locale(identifier: "pl_PL")
        var duration = LocalizedStringResource("\(1) h \(12) min")
        duration.locale = pl
        XCTAssertEqual(String(localized: duration), "1 h 12 min")
        var countdown = LocalizedStringResource("in \(2) h \(5) min")
        countdown.locale = pl
        XCTAssertEqual(String(localized: countdown), "za 2 h 5 min")
    }
}
