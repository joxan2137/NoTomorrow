package app.notomorrow.feature.settings

import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.service.Days
import app.notomorrow.util.Csv
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * "Export my data": `workouts.csv` (one row per completed set) and `meals.csv` — the port of
 * `ExportCSV` in `Features/Settings/SettingsExport.swift`.
 *
 * The bytes come from [Csv] (headers, escaping, ISO dates, dot decimals); this object only walks
 * Room and writes the two files. iOS uses the temp directory; Android writes to
 * `cacheDir/export/<dir>` because that is what `res/xml/file_paths.xml` shares through the
 * `FileProvider`.
 */
object SettingsExportFiles {

    /** `ExportCSV.Counts`. */
    data class Counts(val workouts: Int = 0, val sets: Int = 0, val meals: Int = 0)

    data class Result(val files: List<File>, val counts: Counts)

    /** The share-sheet MIME type for a pair of CSVs. */
    const val MIME_TYPE: String = "text/csv"

    suspend fun build(
        cacheDir: File,
        workoutDao: WorkoutDao,
        mealDao: MealDao,
        today: LocalDate = LocalDate.now(),
    ): Result {
        val dir = File(File(cacheDir, "export"), Csv.exportDirectoryName(today))
        dir.mkdirs()
        // A previous export of the same day would otherwise be shared alongside the new one.
        dir.listFiles()?.forEach { it.delete() }

        val files = mutableListOf<File>()
        var setCount = 0

        val workouts = workoutDao.finishedWorkoutsWithExercisesAsc()
        val workoutRows = mutableListOf(Csv.WORKOUTS_HEADER)
        for (w in workouts) {
            for (ex in w.sortedExercises) {
                for (s in ex.sortedSets) {
                    if (!s.isCompleted) continue
                    setCount += 1
                    workoutRows += Csv.workoutSetRow(
                        workoutId = w.workout.id,
                        workoutName = w.workout.name,
                        startedAt = Instant.ofEpochMilli(w.workout.startedAt),
                        endedAt = w.workout.endedAt?.let(Instant::ofEpochMilli),
                        exerciseName = ex.exercise?.name.orEmpty(),
                        order = s.order,
                        kind = s.kind.raw,
                        weightKg = s.weightKg,
                        reps = s.reps,
                        completedAt = s.completedAt?.let(Instant::ofEpochMilli),
                        isPR = s.isPR,
                        isSetRecord = s.isSetRecord,
                    )
                }
            }
        }
        write(File(dir, Csv.WORKOUTS_FILE), workoutRows)?.let { files += it }

        val meals = mealDao.allEntriesWithFood()
        val mealRows = mutableListOf(Csv.MEALS_HEADER)
        for (m in meals) {
            mealRows += Csv.mealRow(
                // The `day` column is local midnight, so it is turned back into a local date —
                // formatting the instant in UTC would slide east-of-Greenwich days back one.
                day = Csv.day(Days.date(m.entry.day)),
                slot = m.entry.slot.raw,
                food = m.displayName,
                brand = m.food?.brand.orEmpty(),
                grams = m.entry.grams,
                kcal = m.entry.kcal,
                proteinG = m.entry.proteinG,
                carbsG = m.entry.carbsG,
                fatG = m.entry.fatG,
                isAIEstimate = m.entry.isAIEstimate,
                loggedAt = Instant.ofEpochMilli(m.entry.loggedAt),
            )
        }
        write(File(dir, Csv.MEALS_FILE), mealRows)?.let { files += it }

        return Result(
            files = files,
            counts = Counts(workouts = workouts.size, sets = setCount, meals = meals.size),
        )
    }

    /** iOS drops a file it could not write and shares whatever is left. */
    private fun write(file: File, rows: List<String>): File? = runCatching {
        file.writeText(Csv.file(rows))
        file
    }.getOrNull()
}
