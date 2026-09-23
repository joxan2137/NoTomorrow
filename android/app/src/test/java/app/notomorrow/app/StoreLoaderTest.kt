package app.notomorrow.app

import app.notomorrow.data.db.NoTomorrowDatabase
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * `StoreLoaderTests.swift` on Android: a store that opens is used; one that cannot is reported,
 * never swapped for an empty stand-in, and its bytes stay untouched; Try again works once the cause
 * is gone; Start with empty data moves the files into a backup and deletes nothing.
 */
class StoreLoaderTest {

    private val root: File = Files.createTempDirectory("store-loader").toFile()
    private val databases = File(root, "databases").apply { mkdirs() }
    private val databaseFile = File(databases, NoTomorrowDatabase.NAME)
    private val shareDir = File(root, "cache/export/store")
    private val at = Instant.parse("2026-09-23T08:05:09Z")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun loader(open: () -> NoTomorrowDatabase) =
        StoreLoader(databaseFile, shareDir, open, clock = { at }, zone = ZoneOffset.UTC)

    private fun garbage() {
        databaseFile.writeText("not a database")
        File(databaseFile.path + "-wal").writeText("wal")
    }

    @Test
    fun `a store that opens is the one the app uses`() = runBlocking {
        val db = mockk<NoTomorrowDatabase>(relaxed = true)
        val store = loader { db }
        assertEquals(StoreLoader.State.Loading, store.state.value)

        assertTrue(store.open())

        assertEquals(StoreLoader.State.Open, store.state.value)
        assertSame(db, store.database())
        assertTrue(store.open(), "opening again keeps the same database")
    }

    @Test
    fun `a store that cannot be opened is reported and left untouched`() = runBlocking {
        garbage()
        val store = loader { throw IllegalStateException("file is not a database") }

        assertFalse(store.open())

        val state = store.state.value
        assertTrue(state is StoreLoader.State.Failed && "file is not a database" in state.message)
        assertFailsWith<IllegalStateException> { store.database() }
        assertEquals("not a database", databaseFile.readText(), "the bytes are untouched")
        assertTrue(store.hasFiles)
    }

    @Test
    fun `try again works once the cause is gone`() = runBlocking {
        var broken = true
        val db = mockk<NoTomorrowDatabase>(relaxed = true)
        val store = loader { if (broken) error("disk full") else db }

        assertFalse(store.open())
        broken = false
        assertTrue(store.open())
        assertEquals(StoreLoader.State.Open, store.state.value)
    }

    @Test
    fun `start with empty data moves the files into a backup and deletes nothing`() = runBlocking {
        garbage()
        File(databases, "Other.db").writeText("someone else's")
        val store = loader { error("file is not a database") }
        assertFalse(store.open())

        assertTrue(store.moveAside())

        val backup = assertNotNull(store.lastBackup)
        assertEquals(File(File(databases, "Store backups"), "2026-09-23 08.05.09").absolutePath, backup.absolutePath)
        assertEquals("not a database", File(backup, NoTomorrowDatabase.NAME).readText())
        assertEquals("wal", File(backup, "${NoTomorrowDatabase.NAME}-wal").readText())
        assertFalse(databaseFile.exists(), "the next open creates an empty store")
        assertTrue(File(databases, "Other.db").exists(), "only the store's own files move")

        // A second failure at the same second gets its own folder.
        garbage()
        assertTrue(store.moveAside())
        assertEquals("2026-09-23 08.05.09 2", store.lastBackup?.name)
    }

    @Test
    fun `an open store is never moved aside`() = runBlocking {
        databaseFile.writeText("real data")
        val store = loader { mockk(relaxed = true) }
        store.open()

        assertFalse(store.moveAside())
        assertEquals("real data", databaseFile.readText())
    }

    @Test
    fun `the store files are the database and its SQLite companions only`() {
        garbage()
        File(databaseFile.path + "-shm").writeText("shm")
        File(databases, "${NoTomorrowDatabase.NAME}.bak").writeText("x")
        File(databases, "Other").writeText("x")

        assertEquals(
            listOf(NoTomorrowDatabase.NAME, "${NoTomorrowDatabase.NAME}-wal", "${NoTomorrowDatabase.NAME}-shm"),
            StoreLoader.storeFiles(databaseFile).map { it.name },
        )
    }

    @Test
    fun `save a copy shares fresh copies and leaves the originals`() = runBlocking {
        garbage()
        val store = loader { error("broken") }

        val copies = store.shareableCopies()

        assertEquals(listOf(NoTomorrowDatabase.NAME, "${NoTomorrowDatabase.NAME}-wal"), copies.map { it.name })
        assertTrue(copies.all { it.parentFile == shareDir })
        assertEquals("not a database", copies.first().readText())
        assertTrue(databaseFile.exists())
    }

    @Test
    fun `the backup stamp sorts by time and is safe as a folder name`() {
        val first = StoreLoader.backupStamp(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)
        val later = StoreLoader.backupStamp(Instant.parse("2026-10-02T03:04:05Z"), ZoneOffset.UTC)
        assertEquals("2026-01-02 03.04.05", first)
        assertTrue(first < later)
        assertTrue(first.none { it in "/:\\" })
    }
}
