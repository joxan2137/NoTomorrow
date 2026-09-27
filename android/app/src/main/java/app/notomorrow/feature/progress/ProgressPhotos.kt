package app.notomorrow.feature.progress

import app.notomorrow.data.dao.ProgressPhotoDao
import app.notomorrow.data.entity.ProgressPhotoEntity
import app.notomorrow.data.files.ProgressPhotoFiles
import app.notomorrow.model.ProgressPose
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Progress photos — port of `ProgressPhotos` (`Features/Progress/ProgressPhotos.swift`): file
 * naming, ordering, the compare default, and adding / deleting a photo with its file. Photos stay
 * on this phone: they are not part of the CSV export and nothing syncs them.
 */
object ProgressPhotos {

    /** Long edge of the stored JPEG, in pixels, and its quality (`ImageDownscaler`, 0.8). */
    const val MAX_LONG_EDGE = 1600
    const val QUALITY = 80

    /** "3f2c…-….jpg": the photo's id, lowercased, so a row and its file always pair up. */
    fun fileName(id: String): String = id.lowercase(Locale.ROOT) + ".jpg"

    /** Newest first; photos taken at the same instant keep a stable order (by id). */
    fun newestFirst(rows: List<ProgressPhotoEntity>): List<ProgressPhotoEntity> =
        rows.sortedWith(compareByDescending<ProgressPhotoEntity> { it.takenAt }.thenByDescending { it.id })

    /**
     * Compare opens on the first photo against the latest one; null with fewer than two.
     * [newestFirst] is the list as [newestFirst] orders it.
     */
    fun <T> defaultComparison(newestFirst: List<T>): Pair<T, T>? {
        if (newestFirst.size < 2) return null
        return newestFirst.last() to newestFirst.first()
    }

    /**
     * Which photo the viewer shows after the one at [index] is deleted from [count]: the next one
     * (older), else the one before it; null when none is left.
     */
    fun indexAfterDeleting(index: Int, count: Int): Int? {
        val remaining = count - 1
        if (remaining <= 0) return null
        return index.coerceIn(0, remaining - 1)
    }

    fun pose(row: ProgressPhotoEntity): ProgressPose? = ProgressPose.from(row.pose)

    /** "26 Sep 2026" / "26 wrz 2026". */
    fun dateLabel(
        takenAt: Long,
        locale: Locale = LocaleProvider.current(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = Fmt.mediumDate(Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDate(), locale)

    /** "26 Sep" / "26 wrz" — the thumbnail badge. */
    fun shortDateLabel(
        takenAt: Long,
        locale: Locale = LocaleProvider.current(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = Fmt.dayMonth(Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDate(), locale)

    /** Writes the JPEG, then the row. When the insert fails the file is removed again and the error thrown. */
    suspend fun add(
        jpeg: ByteArray,
        pose: ProgressPose?,
        dao: ProgressPhotoDao,
        files: ProgressPhotoFiles,
        now: Long = System.currentTimeMillis(),
    ): ProgressPhotoEntity {
        val id = UUID.randomUUID().toString()
        val name = fileName(id)
        withContext(Dispatchers.IO) { files.write(jpeg, name) }
        val row = ProgressPhotoEntity(id = id, takenAt = now, fileName = name, pose = pose?.raw)
        try {
            dao.insert(row)
        } catch (e: Throwable) {
            files.remove(name)
            throw e
        }
        return row
    }

    /** Deletes the row and its file. */
    suspend fun delete(row: ProgressPhotoEntity, dao: ProgressPhotoDao, files: ProgressPhotoFiles) {
        dao.delete(row.id)
        withContext(Dispatchers.IO) { files.remove(row.fileName) }
    }
}
