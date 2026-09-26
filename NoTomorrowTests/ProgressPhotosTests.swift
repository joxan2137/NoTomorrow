import XCTest
import SwiftData
@testable import NoTomorrow

/// Progress photos: file names, the downscale size, newest-first order, the compare default, and adding / deleting
/// a photo together with its file.
@MainActor
final class ProgressPhotosTests: XCTestCase {
    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var store: ProgressPhotoStore!

    override func setUpWithError() throws {
        let schema = Schema(NoTomorrowSchema.models)
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
        store = ProgressPhotoStore(directory: FileManager.default.temporaryDirectory
            .appendingPathComponent("photos-\(UUID().uuidString)", isDirectory: true))
    }

    override func tearDown() {
        store.removeAll()
        store = nil
        container = nil
    }

    private struct Item {
        var id: UUID
        var takenAt: Date
    }

    private func uuid(_ n: Int) -> UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", n))!
    }

    func testFileNameIsTheLowercasedIdAsJPEG() {
        let id = UUID(uuidString: "3F2C0A1B-0000-4000-8000-00000000ABCD")!
        XCTAssertEqual(ProgressPhotos.fileName(for: id), "3f2c0a1b-0000-4000-8000-00000000abcd.jpg")
    }

    func testDownscaleBringsTheLongEdgeTo1600AndNeverUp() {
        XCTAssertEqual(ImageDownscaler.targetPixelSize(width: 4032, height: 3024, maxLongEdge: 1600),
                       CGSize(width: 1600, height: 1200))
        XCTAssertEqual(ImageDownscaler.targetPixelSize(width: 3024, height: 4032, maxLongEdge: 1600),
                       CGSize(width: 1200, height: 1600))
        XCTAssertEqual(ImageDownscaler.targetPixelSize(width: 1000, height: 3000, maxLongEdge: 1600),
                       CGSize(width: 533, height: 1600))
        XCTAssertEqual(ImageDownscaler.targetPixelSize(width: 800, height: 600, maxLongEdge: 1600),
                       CGSize(width: 800, height: 600))
        XCTAssertNil(ImageDownscaler.targetPixelSize(width: 0, height: 600, maxLongEdge: 1600))
        XCTAssertNil(ImageDownscaler.targetPixelSize(width: 1, height: 10_000, maxLongEdge: 1600))
    }

    func testNewestFirstWithAStableTieBreak() {
        let older = Item(id: uuid(1), takenAt: Date(timeIntervalSince1970: 100))
        let newer = Item(id: uuid(2), takenAt: Date(timeIntervalSince1970: 300))
        let sameA = Item(id: uuid(3), takenAt: Date(timeIntervalSince1970: 200))
        let sameB = Item(id: uuid(4), takenAt: Date(timeIntervalSince1970: 200))
        let ordered = ProgressPhotos.newestFirst([older, sameA, newer, sameB], takenAt: \.takenAt, id: \.id)
        XCTAssertEqual(ordered.map(\.id), [uuid(2), uuid(4), uuid(3), uuid(1)])
    }

    func testCompareDefaultsToFirstAgainstLatest() {
        XCTAssertNil(ProgressPhotos.defaultComparison([Int]()))
        XCTAssertNil(ProgressPhotos.defaultComparison([7]))
        let pair = ProgressPhotos.defaultComparison([30, 20, 10])
        XCTAssertEqual(pair?.before, 10)
        XCTAssertEqual(pair?.after, 30)
    }

    func testViewerMovesToTheNextPhotoAfterADelete() {
        XCTAssertEqual(ProgressPhotos.indexAfterDeleting(at: 0, count: 3), 0)
        XCTAssertEqual(ProgressPhotos.indexAfterDeleting(at: 1, count: 3), 1)
        XCTAssertEqual(ProgressPhotos.indexAfterDeleting(at: 2, count: 3), 1)
        XCTAssertNil(ProgressPhotos.indexAfterDeleting(at: 0, count: 1))
    }

    func testAddWritesTheFileAndTheRowAndDeleteRemovesBoth() throws {
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let photo = try ProgressPhotos.add(jpeg: jpeg, pose: .side, store: store, in: context)
        XCTAssertEqual(photo.fileName, ProgressPhotos.fileName(for: photo.id))
        XCTAssertEqual(photo.pose, .side)
        XCTAssertEqual(try Data(contentsOf: store.url(for: photo.fileName)), jpeg)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<ProgressPhoto>()), 1)

        ProgressPhotos.delete(photo, store: store, in: context)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<ProgressPhoto>()), 0)
        XCTAssertEqual(store.fileNames(), [])
    }

    func testPhotoFolderIsLeftOutOfBackups() throws {
        _ = try ProgressPhotos.add(jpeg: Data([1, 2, 3]), pose: nil, store: store, in: context)
        XCTAssertTrue(store.isExcludedFromBackup, "photos are kept only on this phone, not in iCloud backups")
    }

    func testPoseIsOptional() throws {
        let photo = try ProgressPhotos.add(jpeg: Data([1, 2, 3]), pose: nil, store: store, in: context)
        XCTAssertNil(photo.pose)
        XCTAssertNil(photo.poseRaw)
    }
}
