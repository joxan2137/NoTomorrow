package app.notomorrow.feature.progress

import app.notomorrow.data.dao.ProgressPhotoDao
import app.notomorrow.data.entity.ProgressPhotoEntity
import app.notomorrow.data.files.ProgressPhotoFiles
import app.notomorrow.model.ProgressPose
import app.notomorrow.util.ImageDownscaler
import java.io.File
import java.util.Locale
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Progress photos (`ProgressPhotosTests.swift`): file names, the downscale size, newest-first order,
 * the compare default, and adding / deleting a photo together with its file.
 */
class ProgressPhotosTest {

    private val dir: File = kotlin.io.path.createTempDirectory("photos").toFile()
    private val files = ProgressPhotoFiles(File(dir, ProgressPhotoFiles.DIRECTORY))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun row(id: String, takenAt: Long, pose: String? = null) =
        ProgressPhotoEntity(id = id, takenAt = takenAt, fileName = ProgressPhotos.fileName(id), pose = pose)

    @Test
    fun `file name is the lowercased id as jpeg`() {
        assertEquals(
            "3f2c0a1b-0000-4000-8000-00000000abcd.jpg",
            ProgressPhotos.fileName("3F2C0A1B-0000-4000-8000-00000000ABCD"),
        )
    }

    @Test
    fun `downscale brings the long edge to 1600 and never up`() {
        val max = ProgressPhotos.MAX_LONG_EDGE
        assertEquals(1600 to 1200, ImageDownscaler.targetSize(4032, 3024, max))
        assertEquals(1200 to 1600, ImageDownscaler.targetSize(3024, 4032, max))
        assertEquals(533 to 1600, ImageDownscaler.targetSize(1000, 3000, max))
        assertEquals(800 to 600, ImageDownscaler.targetSize(800, 600, max))
        assertNull(ImageDownscaler.targetSize(0, 600, max))
        assertNull(ImageDownscaler.targetSize(1, 10_000, max))
        assertEquals(80, ProgressPhotos.QUALITY)
    }

    @Test
    fun `newest first with a stable tie break`() {
        val ordered = ProgressPhotos.newestFirst(
            listOf(row("a", 100), row("c", 200), row("b", 300), row("d", 200)),
        )
        assertEquals(listOf("b", "d", "c", "a"), ordered.map { it.id })
    }

    @Test
    fun `compare defaults to the first against the latest`() {
        assertNull(ProgressPhotos.defaultComparison(emptyList<Int>()))
        assertNull(ProgressPhotos.defaultComparison(listOf(7)))
        assertEquals(10 to 30, ProgressPhotos.defaultComparison(listOf(30, 20, 10)))
    }

    @Test
    fun `viewer moves to the next photo after a delete`() {
        assertEquals(0, ProgressPhotos.indexAfterDeleting(0, 3))
        assertEquals(1, ProgressPhotos.indexAfterDeleting(1, 3))
        assertEquals(1, ProgressPhotos.indexAfterDeleting(2, 3))
        assertNull(ProgressPhotos.indexAfterDeleting(0, 1))
    }

    @Test
    fun `pose is read from the raw value and optional`() {
        assertEquals(ProgressPose.Back, ProgressPhotos.pose(row("a", 1, "back")))
        assertNull(ProgressPhotos.pose(row("a", 1)))
        assertNull(ProgressPhotos.pose(row("a", 1, "upside-down")))
    }

    @Test
    fun `date labels carry the year in the viewer`() {
        val takenAt = 1_790_000_000_000L // 2026-09-21 UTC
        assertEquals("21 wrz 2026", ProgressPhotos.dateLabel(takenAt, Locale.forLanguageTag("pl-PL"), ZoneOffset.UTC))
        assertEquals("Sep 21, 2026", ProgressPhotos.dateLabel(takenAt, Locale.US, ZoneOffset.UTC))
    }

    @Test
    fun `add writes the file and the row, delete removes both`() = runBlocking {
        val dao = FakePhotoDao()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val photo = ProgressPhotos.add(jpeg, ProgressPose.Side, dao, files, now = 42)
        assertEquals(ProgressPhotos.fileName(photo.id), photo.fileName)
        assertEquals("side", photo.pose)
        assertEquals(42, photo.takenAt)
        assertContentEquals(jpeg, files.file(photo.fileName).readBytes())
        assertEquals(listOf(photo), dao.rows.value)
        assertEquals(listOf(photo.fileName), files.fileNames())

        ProgressPhotos.delete(photo, dao, files)
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(emptyList(), files.fileNames())
    }

    @Test
    fun `a failed insert leaves no file behind`() = runBlocking {
        val dao = FakePhotoDao(failInsert = true)
        assertFailsWith<IllegalStateException> { ProgressPhotos.add(byteArrayOf(1), null, dao, files) }
        assertEquals(emptyList(), files.fileNames())
    }

    private class FakePhotoDao(private val failInsert: Boolean = false) : ProgressPhotoDao {
        val rows = MutableStateFlow<List<ProgressPhotoEntity>>(emptyList())
        override fun observeAll(): Flow<List<ProgressPhotoEntity>> = rows
        override suspend fun insert(row: ProgressPhotoEntity) {
            if (failInsert) error("disk full")
            rows.value = rows.value + row
        }
        override suspend fun delete(id: String) {
            rows.value = rows.value.filter { it.id != id }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }
}
