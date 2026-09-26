import SwiftUI
import SwiftData
import UIKit
import ImageIO

/// Progress photos: file naming, ordering, the compare default and adding / deleting a photo with its file.
/// Photos stay on this phone: they are not part of the CSV export and nothing syncs them.
enum ProgressPhotos {
    /// Long edge of the stored JPEG, in pixels, and its quality.
    static let maxLongEdge: CGFloat = 1600
    static let jpegQuality: CGFloat = 0.8

    /// "3f2c…-….jpg": the photo's id, lowercased, so a row and its file always pair up.
    static func fileName(for id: UUID) -> String {
        id.uuidString.lowercased() + ".jpg"
    }

    /// Newest first; photos taken at the same instant keep a stable order (by id).
    static func newestFirst<T>(_ items: [T], takenAt: KeyPath<T, Date>, id: KeyPath<T, UUID>) -> [T] {
        items.sorted { a, b in
            let dateA = a[keyPath: takenAt]
            let dateB = b[keyPath: takenAt]
            if dateA != dateB { return dateA > dateB }
            return a[keyPath: id].uuidString > b[keyPath: id].uuidString
        }
    }

    /// Compare opens on the first photo against the latest one; nil with fewer than two.
    /// `newestFirst` is the list as `newestFirst(_:takenAt:id:)` orders it.
    static func defaultComparison<T>(_ newestFirst: [T]) -> (before: T, after: T)? {
        guard newestFirst.count >= 2, let latest = newestFirst.first, let first = newestFirst.last else { return nil }
        return (before: first, after: latest)
    }

    /// Which photo the viewer shows after the one at `index` is deleted from `count`: the next one (older), else the
    /// one before it; nil when none is left.
    static func indexAfterDeleting(at index: Int, count: Int) -> Int? {
        let remaining = count - 1
        guard remaining > 0 else { return nil }
        return min(max(index, 0), remaining - 1)
    }

    static func poseKey(_ pose: ProgressPose) -> LocalizedStringKey {
        switch pose {
        case .front: "photos.pose.front"
        case .side: "photos.pose.side"
        case .back: "photos.pose.back"
        }
    }

    static func poseText(_ pose: ProgressPose) -> String {
        switch pose {
        case .front: Fmt.localized("photos.pose.front")
        case .side: Fmt.localized("photos.pose.side")
        case .back: Fmt.localized("photos.pose.back")
        }
    }

    /// "26 September 2026" / "26 września 2026".
    static func dateLabel(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.wide).year().locale(Fmt.locale))
    }

    /// "4 Sep 2026 · Front", for the compare menus.
    static func optionLabel(takenAt: Date, pose: ProgressPose?) -> String {
        let date = takenAt.formatted(.dateTime.day().month(.abbreviated).year().locale(Fmt.locale))
        guard let pose else { return date }
        return date + " · " + poseText(pose)
    }

    /// Writes the JPEG, then the row. When the save fails the file is removed again and the error thrown.
    @MainActor
    @discardableResult
    static func add(jpeg: Data, pose: ProgressPose?, takenAt: Date = .now, store: ProgressPhotoStore,
                    in context: ModelContext) throws -> ProgressPhoto {
        let id = UUID()
        let name = fileName(for: id)
        try store.write(jpeg, fileName: name)
        let photo = ProgressPhoto(id: id, takenAt: takenAt, fileName: name, pose: pose)
        context.insert(photo)
        do {
            try context.save()
        } catch {
            context.delete(photo)
            store.remove(fileName: name)
            throw error
        }
        return photo
    }

    /// Deletes the row and its file.
    @MainActor
    static func delete(_ photo: ProgressPhoto, store: ProgressPhotoStore, in context: ModelContext) {
        let name = photo.fileName
        context.delete(photo)
        try? context.save()
        store.remove(fileName: name)
    }
}

/// The folder the JPEGs live in: Application Support/ProgressPhotos/ (tests pass a temporary one).
struct ProgressPhotoStore {
    let directory: URL

    static var standard: ProgressPhotoStore { ProgressPhotoStore(directory: defaultDirectory) }

    static var defaultDirectory: URL {
        let fileManager = FileManager.default
        let base = (try? fileManager.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                         appropriateFor: nil, create: true))
            ?? fileManager.temporaryDirectory
        return base.appendingPathComponent("ProgressPhotos", isDirectory: true)
    }

    func url(for fileName: String) -> URL {
        directory.appendingPathComponent(fileName, isDirectory: false)
    }

    func write(_ data: Data, fileName: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        excludeFromBackup()
        try data.write(to: url(for: fileName), options: [.atomic, .completeFileProtection])
    }

    /// Application Support goes into iCloud and computer backups; the photos are "kept only on this phone", so the
    /// folder (and everything in it) is left out, as Android's `allowBackup="false"` leaves out `filesDir`.
    func excludeFromBackup() {
        var folder = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }

    /// Whether the folder is left out of backups (false when it does not exist yet).
    var isExcludedFromBackup: Bool {
        (try? directory.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup == true
    }

    func remove(fileName: String) {
        try? FileManager.default.removeItem(at: url(for: fileName))
    }

    /// Every photo file, gone (the account wipe).
    func removeAll() {
        try? FileManager.default.removeItem(at: directory)
    }

    /// File names in the folder, sorted; empty when there is none.
    func fileNames() -> [String] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.sorted()
    }
}

/// Decodes a stored photo at a display size off the main thread (ImageIO thumbnail, orientation applied).
enum ProgressPhotoLoader {
    static func image(at url: URL, maxPixel: CGFloat) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: Int(maxPixel),
            ]
            guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                return nil
            }
            return UIImage(cgImage: cgImage)
        }.value
    }
}
