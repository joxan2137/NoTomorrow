import Foundation
import Observation
import SwiftData

/// Opens the SwiftData store and holds the container the app runs on.
/// A store that fails to open is never swapped for an empty one behind the user's back (an in-memory fallback looks
/// like the real app and loses everything logged into it on the next launch). `failure` is set instead and RootView
/// shows `StoreErrorView`: try again, save a copy of the files, or start fresh, which first moves every store file
/// into a timestamped backup folder next to the store (`moveAside`) and then opens a new one (`retry`). Nothing is ever deleted, and a later build that fixes the
/// cause opens the untouched store as if nothing had happened.
@Observable
@MainActor
final class StoreLoader {
    /// The open store, or an empty in-memory placeholder while `failure` is set (no screen reads it then).
    private(set) var container: ModelContainer
    /// Why the store did not open (the raw error, shown in small print); nil while it is open.
    private(set) var failure: String?
    /// Bumped every time a store opens, so the launch work (library import, seeding) reruns after a recovery.
    private(set) var generation = 0
    /// Where the last "start fresh" moved the old files.
    private(set) var lastBackup: URL?

    let configuration: ModelConfiguration
    private let schema: Schema
    private let fileManager: FileManager

    /// Folder next to the store that "start fresh" moves the old files into, one timestamped subfolder per move.
    static let backupFolderName = "Store backups"

    init(schema: Schema = Schema(NoTomorrowSchema.models), configuration: ModelConfiguration? = nil,
         fileManager: FileManager = .default) {
        let configuration = configuration ?? ModelConfiguration("NoTomorrow", schema: schema, isStoredInMemoryOnly: false)
        self.schema = schema
        self.configuration = configuration
        self.fileManager = fileManager
        do {
            container = try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            container = Self.placeholder(schema)
            failure = String(describing: error)
        }
    }

    var isOpen: Bool { failure == nil }

    /// Tries to open the store again (a full disk or a locked device can clear up). True when it opened.
    @discardableResult
    func retry() -> Bool {
        do {
            container = try ModelContainer(for: schema, configurations: [configuration])
            failure = nil
            generation += 1
            return true
        } catch {
            failure = String(describing: error)
            return false
        }
    }

    /// First half of "start fresh": moves the store's files into `Store backups/<timestamp>/` (never deletes them).
    /// True when nothing of the old store is left in place. The caller then resets whatever belonged to the old data
    /// (`hasOnboarded`) before `retry()` opens the new, empty store, so a reopen that fails can't leave the app pointing
    /// at data that is gone. Refused while the store is open.
    @discardableResult
    func moveAside(now: Date = .now) -> Bool {
        guard !isOpen else { return false }
        let files = storeFiles(includingSupport: true)
        guard !files.isEmpty else { return true }
        let stamp = Self.backupStamp(now)
        var backup = backupsFolder.appendingPathComponent(stamp, isDirectory: true)
        var n = 2
        while fileManager.fileExists(atPath: backup.path) {
            backup = backupsFolder.appendingPathComponent("\(stamp) \(n)", isDirectory: true)
            n += 1
        }
        do {
            try fileManager.createDirectory(at: backup, withIntermediateDirectories: true)
            for file in files {
                try fileManager.moveItem(at: file, to: backup.appendingPathComponent(file.lastPathComponent))
            }
            lastBackup = backup
            return true
        } catch {
            // Whatever did move is in `backup`; the rest stays where it was.
            if fileManager.fileExists(atPath: backup.path) { lastBackup = backup }
            failure = String(describing: error)
            return false
        }
    }

    // MARK: Files

    /// The SQLite file and its `-wal` / `-shm` companions, for "save a copy".
    var shareableFiles: [URL] { storeFiles(includingSupport: false) }

    var backupsFolder: URL {
        configuration.url.deletingLastPathComponent().appendingPathComponent(Self.backupFolderName, isDirectory: true)
    }

    /// Every file that belongs to the store: `NoTomorrow.store`, `-wal`, `-shm`, plus Core Data's hidden
    /// `.NoTomorrow_SUPPORT` folder (external blobs) when asked and present.
    func storeFiles(includingSupport: Bool) -> [URL] {
        let url = configuration.url
        let folder = url.deletingLastPathComponent()
        let name = url.lastPathComponent
        let support = "." + url.deletingPathExtension().lastPathComponent + "_SUPPORT"
        let items = (try? fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        return items
            .filter { item in
                let n = item.lastPathComponent
                return n == name || n.hasPrefix(name + "-") || (includingSupport && n == support)
            }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// "2026-09-22 10.15.00": sortable, and safe as a folder name.
    static func backupStamp(_ date: Date) -> String {
        let c = Calendar(identifier: .gregorian).dateComponents(in: .current, from: date)
        return String(format: "%04d-%02d-%02d %02d.%02d.%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0,
                      c.hour ?? 0, c.minute ?? 0, c.second ?? 0)
    }

    private static func placeholder(_ schema: Schema) -> ModelContainer {
        // An in-memory store with this schema cannot fail short of the process being out of memory.
        try! ModelContainer(for: schema, configurations: [ModelConfiguration(isStoredInMemoryOnly: true)])
    }
}
