package app.notomorrow.app

import android.util.Log
import app.notomorrow.data.db.NoTomorrowDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Opens the database, and when it cannot, says so instead of carrying on with an empty stand-in —
 * the port of `StoreLoader.swift`. There is no in-memory fallback any more: a store that silently
 * ran in memory looked like the real one and lost everything logged into it at the next launch.
 *
 * While the store is not open, `RootScreen` shows [StoreErrorScreen] instead of the app, so
 * nothing reads [database]. Nothing is ever deleted: the files are moved aside only when the user
 * picks "Start with empty data" ([moveAside]), into `Store backups/<yyyy-MM-dd HH.mm.ss>/` next to
 * the database. A later build that fixes the cause opens the untouched store as if nothing
 * happened.
 */
class StoreLoader(
    /** `databases/NoTomorrow`; its `-wal`, `-shm` and `-journal` files sit next to it. */
    private val databaseFile: File,
    /** Where [shareableCopies] puts the copies the share sheet reads (`cache/export/store`). */
    private val shareDir: File,
    /** `DatabaseModule.build`: opens the file, runs the migrations, throws when it cannot. */
    private val openDatabase: () -> NoTomorrowDatabase,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    sealed interface State {
        /** Not tried yet (the splash screen is still up). */
        data object Loading : State

        data object Open : State

        /** [message] is the raw error, for a bug report: not translated, so the screen keeps it collapsed. */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    val isOpen: Boolean get() = _state.value == State.Open

    /** There is something on disk to share (the error screen hides "Save a copy" otherwise). */
    val hasFiles: Boolean get() = storeFiles(databaseFile).isNotEmpty()

    /** The folder the last [moveAside] filled, for the log and for a later restore. */
    var lastBackup: File? = null
        private set

    @Volatile
    private var opened: NoTomorrowDatabase? = null

    private val lock = Mutex()

    /**
     * The open database. The UI is gated on [state], so nothing asks while it is failed; if
     * something does, it gets an exception rather than a throwaway database that would swallow
     * whatever it writes.
     */
    fun database(): NoTomorrowDatabase =
        opened ?: throw IllegalStateException("The store is not open: ${_state.value}")

    /** Opens the store (Try again calls it again). `true` once it is open; the state says why not. */
    suspend fun open(): Boolean = lock.withLock {
        if (opened != null) return@withLock true
        try {
            opened = withContext(Dispatchers.IO) { openDatabase() }
            _state.value = State.Open
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "The store could not be opened", e)
            _state.value = State.Failed(e.toString())
            false
        }
    }

    /**
     * "Start with empty data": moves the files that cannot be opened into a new backup folder next
     * to the database, so the next [open] creates an empty one. Never deletes anything; `false`
     * (state unchanged) when a file could not be moved.
     */
    suspend fun moveAside(): Boolean = lock.withLock {
        if (opened != null) return@withLock false
        try {
            lastBackup = withContext(Dispatchers.IO) {
                moveAside(databaseFile, backupsDir(databaseFile), backupStamp(clock(), zone))
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "The store files could not be moved aside", e)
            false
        }
    }

    /**
     * "Save a copy of the data": fresh copies of the store files in [shareDir] (the share sheet
     * reads them through the `FileProvider`), so what is shared is a snapshot and the originals
     * stay untouched. Empty when there is nothing to copy.
     */
    suspend fun shareableCopies(): List<File> = withContext(Dispatchers.IO) {
        runCatching {
            shareDir.deleteRecursively()
            shareDir.mkdirs()
            storeFiles(databaseFile).map { source ->
                File(shareDir, source.name).also { copy ->
                    Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val TAG = "StoreLoader"

        /** The folder every backup goes in, next to the database. */
        const val BACKUPS_FOLDER = "Store backups"

        private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.ROOT)

        fun backupsDir(databaseFile: File): File = File(databaseFile.absoluteFile.parentFile, BACKUPS_FOLDER)

        /** `yyyy-MM-dd HH.mm.ss`: sorts by time and is safe as a folder name. */
        fun backupStamp(at: Instant, zone: ZoneId): String = stampFormat.format(at.atZone(zone))

        /** The database file and its SQLite companions that exist on disk, the database first. */
        fun storeFiles(databaseFile: File): List<File> =
            listOf("", "-wal", "-shm", "-journal")
                .map { File(databaseFile.path + it) }
                .filter { it.isFile }

        /**
         * Moves every file of [databaseFile] into `backups/<stamp>` (`<stamp> 2`, `<stamp> 3`… when
         * taken) and returns that folder. Throws before anything moves when the folder cannot be
         * made; a failed move leaves the rest where they are.
         */
        fun moveAside(databaseFile: File, backups: File, stamp: String): File {
            var target = File(backups, stamp)
            var n = 2
            while (target.exists()) target = File(backups, "$stamp ${n++}")
            if (!target.mkdirs()) throw IOException("Could not create $target")
            for (file in storeFiles(databaseFile)) {
                Files.move(file.toPath(), File(target, file.name).toPath())
            }
            return target
        }
    }
}
