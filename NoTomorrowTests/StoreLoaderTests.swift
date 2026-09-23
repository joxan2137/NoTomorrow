import XCTest
import SwiftData
@testable import NoTomorrow

/// A store that cannot be opened is reported and left alone; "start fresh" moves its files into a backup first.
@MainActor
final class StoreLoaderTests: XCTestCase {
    private var folder: URL!
    private var storeURL: URL { folder.appendingPathComponent("NoTomorrow.store") }
    private let schema = Schema(NoTomorrowSchema.models)
    private let garbage = Data("definitely not a SQLite database".utf8)

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("StoreLoaderTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: folder)
        folder = nil
    }

    private func makeLoader() -> StoreLoader {
        StoreLoader(schema: schema, configuration: ModelConfiguration(schema: schema, url: storeURL))
    }

    private func names(in url: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: url.path)) ?? []).sorted()
    }

    func testOpensANewStore() throws {
        let loader = makeLoader()

        XCTAssertTrue(loader.isOpen)
        XCTAssertNil(loader.failure)
        loader.container.mainContext.insert(BodyWeightEntry(day: .now, kg: 80, source: .manual))
        try loader.container.mainContext.save()
    }

    func testUnreadableStoreIsReportedAndLeftAlone() throws {
        try garbage.write(to: storeURL)

        let loader = makeLoader()

        XCTAssertFalse(loader.isOpen, "no silent switch to an empty store")
        XCTAssertNotNil(loader.failure)
        XCTAssertEqual(try Data(contentsOf: storeURL), garbage, "the file is not touched")
        XCTAssertTrue(loader.shareableFiles.contains { $0.lastPathComponent == "NoTomorrow.store" })
        XCTAssertEqual(try loader.container.mainContext.fetchCount(FetchDescriptor<Workout>()), 0,
                       "the placeholder is empty and in memory")
    }

    func testRetryOpensOnceTheCauseIsGone() throws {
        try garbage.write(to: storeURL)
        let loader = makeLoader()
        XCTAssertFalse(loader.retry())
        XCTAssertEqual(loader.generation, 0)

        for file in loader.storeFiles(includingSupport: true) { try FileManager.default.removeItem(at: file) }

        XCTAssertTrue(loader.retry())
        XCTAssertTrue(loader.isOpen)
        XCTAssertEqual(loader.generation, 1, "the launch work reruns for the recovered store")
    }

    func testStartFreshMovesTheFilesIntoABackupAndOpensAnEmptyStore() throws {
        try garbage.write(to: storeURL)
        try Data("wal".utf8).write(to: folder.appendingPathComponent("NoTomorrow.store-wal"))
        let loader = makeLoader()
        XCTAssertFalse(loader.isOpen)
        let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

        XCTAssertTrue(loader.moveAside(now: now))
        XCTAssertTrue(loader.storeFiles(includingSupport: true).isEmpty, "nothing of the old store is left in place")
        XCTAssertFalse(loader.isOpen, "moving is not opening")
        XCTAssertTrue(loader.retry())

        XCTAssertTrue(loader.isOpen)
        XCTAssertEqual(loader.generation, 1)
        let backup = folder.appendingPathComponent(StoreLoader.backupFolderName)
            .appendingPathComponent(StoreLoader.backupStamp(now))
        XCTAssertEqual(loader.lastBackup?.standardizedFileURL, backup.standardizedFileURL)
        XCTAssertEqual(try Data(contentsOf: backup.appendingPathComponent("NoTomorrow.store")), garbage,
                       "moved, never deleted")
        XCTAssertTrue(names(in: backup).contains("NoTomorrow.store-wal"))
        XCTAssertEqual(try loader.container.mainContext.fetchCount(FetchDescriptor<Workout>()), 0)
        XCTAssertFalse(loader.storeFiles(includingSupport: true).contains { $0.lastPathComponent == StoreLoader.backupFolderName },
                       "the backup folder is never taken for part of the store")
    }

    /// The files are gone even when the new store then fails to open: `StoreErrorView` resets setup on the move, not on
    /// the reopen, so a later Try again lands in setup rather than in an app with no profile.
    func testMoveAsideSucceedsEvenWhenTheReopenFails() throws {
        try garbage.write(to: storeURL)
        let loader = makeLoader()
        let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

        XCTAssertTrue(loader.moveAside(now: now))
        // Something in the store's place that SQLite cannot open.
        try FileManager.default.createDirectory(at: storeURL, withIntermediateDirectories: true)
        XCTAssertFalse(loader.retry())

        XCTAssertFalse(loader.isOpen)
        let backup = try XCTUnwrap(loader.lastBackup)
        XCTAssertEqual(try Data(contentsOf: backup.appendingPathComponent("NoTomorrow.store")), garbage)

        // A second start fresh in the same second gets its own folder.
        XCTAssertTrue(loader.moveAside(now: now))
        XCTAssertNotEqual(loader.lastBackup?.standardizedFileURL, backup.standardizedFileURL)
        XCTAssertEqual(try Data(contentsOf: backup.appendingPathComponent("NoTomorrow.store")), garbage,
                       "the first backup is untouched")
        XCTAssertTrue(loader.retry())
    }

    func testMoveAsideLeavesAnOpenStoreAlone() throws {
        let loader = makeLoader()
        XCTAssertTrue(loader.isOpen)

        XCTAssertFalse(loader.moveAside())

        XCTAssertTrue(loader.storeFiles(includingSupport: false).contains { $0.lastPathComponent == "NoTomorrow.store" })
        XCTAssertNil(loader.lastBackup)
    }

    func testStoreFilesAreOnlyTheStoresOwn() throws {
        let loader = makeLoader()
        XCTAssertTrue(loader.isOpen)
        try Data().write(to: folder.appendingPathComponent("Other.store"))
        try FileManager.default.createDirectory(at: folder.appendingPathComponent(".NoTomorrow_SUPPORT"),
                                                withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: loader.backupsFolder, withIntermediateDirectories: true)

        let plain = loader.storeFiles(includingSupport: false).map(\.lastPathComponent)
        let all = loader.storeFiles(includingSupport: true).map(\.lastPathComponent)

        XCTAssertTrue(plain.contains("NoTomorrow.store"))
        XCTAssertTrue(plain.allSatisfy { $0 == "NoTomorrow.store" || $0.hasPrefix("NoTomorrow.store-") })
        XCTAssertEqual(Set(all), Set(plain + [".NoTomorrow_SUPPORT"]))
    }

    func testBackupStampSortsByTime() {
        let earlier = StoreLoader.backupStamp(Date(timeIntervalSinceReferenceDate: 800_000_000))
        let later = StoreLoader.backupStamp(Date(timeIntervalSinceReferenceDate: 800_000_061))
        XCTAssertLessThan(earlier, later)
        XCTAssertFalse(earlier.contains("/"))
        XCTAssertFalse(earlier.contains(":"))
    }
}
