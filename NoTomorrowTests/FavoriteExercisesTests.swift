import XCTest
@testable import NoTomorrow

/// Starred exercises: the id set in `UserDefaults`, the picker's Favorites section, and the local wipe clearing it.
final class FavoriteExercisesTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUp() {
        suiteName = "FavoriteExercisesTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
    }

    func testToggleStarsAndUnstars() {
        XCTAssertEqual(FavoriteExercises.ids(defaults: defaults), [])
        XCTAssertEqual(FavoriteExercises.toggle("bench", defaults: defaults), ["bench"])
        FavoriteExercises.toggle("squat", defaults: defaults)
        XCTAssertTrue(FavoriteExercises.isFavorite("bench", defaults: defaults))
        // Survives a new handle on the same suite (a relaunch).
        XCTAssertEqual(FavoriteExercises.ids(defaults: UserDefaults(suiteName: suiteName)!), ["bench", "squat"])

        XCTAssertEqual(FavoriteExercises.toggle("bench", defaults: defaults), ["squat"])
        XCTAssertFalse(FavoriteExercises.isFavorite("bench", defaults: defaults))
        FavoriteExercises.toggle("squat", defaults: defaults)
        XCTAssertNil(defaults.object(forKey: FavoriteExercises.key), "the last unstar removes the key")
    }

    func testClearForgetsEverything() {
        FavoriteExercises.toggle("bench", defaults: defaults)
        FavoriteExercises.clear(defaults: defaults)
        XCTAssertEqual(FavoriteExercises.ids(defaults: defaults), [])
    }

    func testFavoritesGoOnTopInResultOrderWithoutSearch() {
        let results = ["a", "b", "c", "d"]
        let sections = FavoriteExercises.sections(results, favorites: ["d", "b", "zz"], isSearching: false, id: { $0 })
        XCTAssertEqual(sections.favorites, ["b", "d"], "results order; a favorite filtered out stays out")
        XCTAssertEqual(sections.others, ["a", "c"], "no row shows twice")
    }

    func testSearchingKeepsOneList() {
        let sections = FavoriteExercises.sections(["a", "b"], favorites: ["b"], isSearching: true, id: { $0 })
        XCTAssertEqual(sections.favorites, [])
        XCTAssertEqual(sections.others, ["a", "b"])
    }

    func testNoFavoritesKeepsOneList() {
        let sections = FavoriteExercises.sections(["a", "b"], favorites: [], isSearching: false, id: { $0 })
        XCTAssertEqual(sections.favorites, [])
        XCTAssertEqual(sections.others, ["a", "b"])
    }
}
