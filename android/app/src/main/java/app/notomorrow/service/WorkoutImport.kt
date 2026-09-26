package app.notomorrow.service

import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.first

/**
 * Reads workout history exported from Strong, Hevy or NoTomorrow itself (Settings › Import workouts)
 * into plain values; [WorkoutImporter] writes them — port of `WorkoutImport.swift`. The parsing uses
 * no store, so it is unit-tested on plain text.
 *
 * Formats (the header row decides; comma or semicolon separated):
 *   Strong      Date, Workout Name, Duration, Exercise Name, Set Order (1, 2… or W / D / F), Weight, Reps, RPE, Notes…
 *   Hevy        title, start_time, end_time, exercise_title, exercise_notes, set_type, weight_kg / weight_lbs, reps, rpe…
 *   NoTomorrow  workout_id, workout_name, started_at, ended_at, exercise, set, kind, weight_kg, reps, completed_at…
 */
object WorkoutImport {

    enum class Format { Strong, Hevy, NoTomorrow }

    data class ImportedSet(
        val kind: SetKind,
        val weightKg: Double,
        val reps: Int,
        val rpe: Double?,
    )

    data class ImportedExercise(
        val name: String,
        val notes: String = "",
        val sets: List<ImportedSet> = emptyList(),
    )

    /** Times are epoch milliseconds. */
    data class ImportedWorkout(
        val name: String,
        val startedAt: Long,
        val endedAt: Long,
        val notes: String = "",
        val exercises: List<ImportedExercise> = emptyList(),
    ) {
        val setCount: Int get() = exercises.sumOf { it.sets.size }
    }

    data class Parsed(
        val format: Format,
        val workouts: List<ImportedWorkout>,
        /** Rows that could not be read (no date, no reps…), skipped. */
        val skippedRows: Int,
    )

    sealed class Failure(message: String) : Exception(message) {
        data object Empty : Failure("empty")
        data object UnknownFormat : Failure("unknownFormat")
    }

    /**
     * [unit] is what a weight column without a unit in its name is in (Strong writes the app's unit).
     * Local times (Strong, Hevy) are read in [zone]. Throws [Failure].
     */
    fun parse(text: String, unit: WeightUnit, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val rows = csvRows(text)
        val header = rows.firstOrNull()
        if (header == null || rows.size <= 1) throw Failure.Empty
        val columns = Columns(header)
        val body = rows.drop(1).filterNot { it.size == 1 && it[0].isEmpty() }
        val dates = Dates(zone)
        if (columns.has("exercise name") && columns.has("set order")) return parseStrong(body, columns, unit, dates)
        if (columns.has("exercise_title") && columns.has("start_time")) return parseHevy(body, columns, dates)
        if (columns.has("workout_id") && columns.has("exercise")) return parseNoTomorrow(body, columns, dates)
        throw Failure.UnknownFormat
    }

    // MARK: Strong

    private fun parseStrong(rows: List<List<String>>, c: Columns, unit: WeightUnit, dates: Dates): Parsed {
        val builder = Builder()
        val weightColumn = c.first(listOf("weight (kg)", "weight (lbs)", "weight")) ?: "weight"
        val weightUnit = when {
            weightColumn.contains("lbs") -> WeightUnit.Lb
            weightColumn.contains("kg") -> WeightUnit.Kg
            else -> unit
        }
        val durationColumn = c.first(listOf("duration (sec)", "duration")) ?: "duration"
        for (row in rows) {
            val order = c.value("set order", row).trim()
            val start = dates.parse(c.value("date", row))
            if (start == null) {
                builder.skipped += 1
                continue
            }
            // Strong writes rest timers and notes as rows of their own; only rows with reps are sets.
            val reps = (Numbers.parse(c.value("reps", row)) ?: 0.0).toInt()
            if (order.lowercase(Locale.ROOT) == "rest timer") continue
            if (reps <= 0) {
                builder.skipped += 1
                continue
            }
            val kind = when (order.uppercase(Locale.ROOT)) {
                "W" -> SetKind.Warmup
                "D" -> SetKind.Drop
                "F" -> SetKind.Failure
                else -> SetKind.Normal
            }
            val weight = Numbers.parse(c.value(weightColumn, row)) ?: 0.0
            // Older exports name the unit per row.
            val rowUnit = when (c.value("weight unit", row).lowercase(Locale.ROOT)) {
                "lbs", "lb" -> WeightUnit.Lb
                "kg" -> WeightUnit.Kg
                else -> weightUnit
            }
            val duration = Durations.seconds(c.value(durationColumn, row))
            builder.add(
                workoutName = c.value("workout name", row),
                start = start,
                end = start + ((duration ?: 3600.0) * 1000).toLong(),
                workoutNotes = c.value("workout notes", row),
                exercise = c.value("exercise name", row),
                exerciseNotes = c.value("notes", row),
                set = ImportedSet(kind, kg(weight, rowUnit), reps, Numbers.parse(c.value("rpe", row))),
            )
        }
        return Parsed(Format.Strong, builder.workouts(), builder.skipped)
    }

    // MARK: Hevy

    private fun parseHevy(rows: List<List<String>>, c: Columns, dates: Dates): Parsed {
        val builder = Builder()
        val usesPounds = !c.has("weight_kg") && c.has("weight_lbs")
        for (row in rows) {
            val start = dates.parse(c.value("start_time", row))
            if (start == null) {
                builder.skipped += 1
                continue
            }
            val reps = (Numbers.parse(c.value("reps", row)) ?: 0.0).toInt()
            if (reps <= 0) {
                builder.skipped += 1
                continue
            }
            val kind = when (c.value("set_type", row).lowercase(Locale.ROOT)) {
                "warmup" -> SetKind.Warmup
                "dropset" -> SetKind.Drop
                "failure" -> SetKind.Failure
                else -> SetKind.Normal
            }
            val weight = Numbers.parse(c.value(if (usesPounds) "weight_lbs" else "weight_kg", row)) ?: 0.0
            val end = dates.parse(c.value("end_time", row)) ?: (start + HOUR_MS)
            builder.add(
                workoutName = c.value("title", row),
                start = start,
                end = max(end, start),
                workoutNotes = c.value("description", row),
                exercise = c.value("exercise_title", row),
                exerciseNotes = c.value("exercise_notes", row),
                set = ImportedSet(
                    kind,
                    kg(weight, if (usesPounds) WeightUnit.Lb else WeightUnit.Kg),
                    reps,
                    Numbers.parse(c.value("rpe", row)),
                ),
            )
        }
        return Parsed(Format.Hevy, builder.workouts(), builder.skipped)
    }

    // MARK: NoTomorrow (Settings › Export)

    private fun parseNoTomorrow(rows: List<List<String>>, c: Columns, dates: Dates): Parsed {
        val builder = Builder()
        for (row in rows) {
            val start = dates.parse(c.value("started_at", row))
            if (start == null) {
                builder.skipped += 1
                continue
            }
            val reps = (Numbers.parse(c.value("reps", row)) ?: 0.0).toInt()
            if (reps <= 0) {
                builder.skipped += 1
                continue
            }
            val end = dates.parse(c.value("ended_at", row)) ?: (start + HOUR_MS)
            builder.add(
                workoutName = c.value("workout_name", row),
                start = start,
                end = max(end, start),
                workoutNotes = "",
                exercise = c.value("exercise", row),
                exerciseNotes = "",
                set = ImportedSet(
                    kind = SetKind.from(c.value("kind", row)) ?: SetKind.Normal,
                    weightKg = Numbers.parse(c.value("weight_kg", row)) ?: 0.0,
                    reps = reps,
                    rpe = null,
                ),
            )
        }
        return Parsed(Format.NoTomorrow, builder.workouts(), builder.skipped)
    }

    private fun kg(value: Double, unit: WeightUnit): Double = if (unit == WeightUnit.Kg) value else value / Fmt.LB_PER_KG

    private const val HOUR_MS = 3_600_000L

    // MARK: Grouping

    /** Rows → workouts (same start and name), exercises in the order they first appear. */
    private class Builder {
        private val list = mutableListOf<MutableWorkout>()
        private val index = mutableMapOf<String, Int>()
        var skipped = 0

        private class MutableWorkout(val workout: ImportedWorkout, val exercises: MutableList<ImportedExercise>)

        fun add(
            workoutName: String,
            start: Long,
            end: Long,
            workoutNotes: String,
            exercise: String,
            exerciseNotes: String,
            set: ImportedSet,
        ) {
            val exerciseName = exercise.trim()
            if (exerciseName.isEmpty()) {
                skipped += 1
                return
            }
            val name = workoutName.trim()
            val key = "${Math.floorDiv(start, 1000L)}|$name"
            val w = index.getOrPut(key) {
                list += MutableWorkout(
                    ImportedWorkout(
                        name = name.ifEmpty { "Workout" },
                        startedAt = start,
                        endedAt = end,
                        notes = workoutNotes.trim(),
                    ),
                    mutableListOf(),
                )
                list.size - 1
            }
            val exercises = list[w].exercises
            val e = exercises.indexOfLast { it.name == exerciseName }
            if (e >= 0) {
                val current = exercises[e]
                // A note on a later row (Strong writes notes per set) fills an exercise that has none yet.
                val notes = current.notes.ifEmpty { exerciseNotes.trim() }
                exercises[e] = current.copy(notes = notes, sets = current.sets + set)
            } else {
                exercises += ImportedExercise(name = exerciseName, notes = exerciseNotes.trim(), sets = listOf(set))
            }
        }

        fun workouts(): List<ImportedWorkout> = list.map { it.workout.copy(exercises = it.exercises.toList()) }
    }

    // MARK: Exercise names

    /**
     * Keys a name is matched on: folded, "(Barbell)" style suffixes moved to the front
     * ("Bench Press (Barbell)" → "barbell bench press"), punctuation dropped.
     */
    fun matchKeys(name: String): List<String> {
        val folded = ExerciseLibrary.fold(name)
        val keys = mutableListOf(clean(folded))
        val open = folded.indexOf('(')
        if (open >= 0) {
            val close = folded.indexOf(')', open)
            if (close >= 0) {
                val inside = folded.substring(open + 1, close)
                val outside = folded.substring(0, open) + folded.substring(close + 1)
                keys += clean("$inside $outside")
                keys += clean(outside)
            }
        }
        return keys.filter { it.isNotEmpty() }
    }

    private fun clean(text: String): String =
        text.lowercase(Locale.ROOT)
            .map { if (it.isLetter() || it.isDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    // MARK: CSV

    /**
     * RFC 4180-ish reader: quoted fields with doubled quotes and line breaks inside, comma or
     * semicolon (whichever the header uses more), CRLF or LF, a leading byte-order mark.
     */
    fun csvRows(text: String): List<List<String>> {
        val input = if (text.startsWith('﻿')) text.substring(1) else text
        val headerLine = input.takeWhile { !isNewline(it) }
        val delimiter = if (headerLine.count { it == ';' } > headerLine.count { it == ',' }) ';' else ','

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < input.length) {
            val char = input[i]
            // Swift reads "\r\n" as one character.
            val newlineLength = if (char == '\r' && i + 1 < input.length && input[i + 1] == '\n') 2 else 1
            if (inQuotes) {
                if (char == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                } else {
                    field.append(input, i, i + newlineLength)
                    i += newlineLength
                    continue
                }
            } else if (char == '"') {
                inQuotes = true
            } else if (char == delimiter) {
                row += field.toString()
                field.clear()
            } else if (isNewline(char)) {
                row += field.toString()
                rows += row
                row = mutableListOf()
                field.clear()
                i += newlineLength
                continue
            } else {
                field.append(char)
            }
            i += 1
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows.filterNot { it.size == 1 && it[0].isEmpty() }
    }

    /** `Character.isNewline`. */
    private fun isNewline(char: Char): Boolean =
        char == '\n' || char == '\r' || char == '\u000B' || char == '\u000C' ||
            char == '\u0085' || char == ' ' || char == ' '

    /** Header name → column index, matched case-insensitively. */
    private class Columns(header: List<String>) {
        private val index = mutableMapOf<String, Int>()

        init {
            header.forEachIndexed { i, name ->
                val key = name.trim().lowercase(Locale.ROOT)
                if (key !in index) index[key] = i
            }
        }

        fun has(name: String): Boolean = name in index

        fun first(names: List<String>): String? = names.firstOrNull(::has)

        fun value(name: String, row: List<String>): String {
            val i = index[name] ?: return ""
            return row.getOrNull(i)?.trim().orEmpty()
        }
    }

    private object Numbers {
        private val PLAIN = Regex("[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?|[0-9]+\\.")

        /** "82.5", "82,5" (a semicolon file's decimal comma), empty → null. */
        fun parse(text: String): Double? {
            val cleaned = text.replace(',', '.').trim()
            if (cleaned.isEmpty() || !PLAIN.matches(cleaned)) return null
            val value = cleaned.toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() && it >= 0 }
        }
    }

    private object Durations {
        /** "3600", "1h 5m", "45m", "1:05:00" → seconds. */
        fun seconds(text: String): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            trimmed.toDoubleOrNull()?.let { return it }
            if (trimmed.contains(':')) {
                return trimmed.split(':').mapNotNull { it.toDoubleOrNull() }.fold(0.0) { acc, part -> acc * 60 + part }
            }
            var total = 0.0
            var number = ""
            for (char in trimmed) {
                if (char.isDigit()) {
                    number += char
                    continue
                }
                val value = number.toDoubleOrNull() ?: 0.0
                when (char) {
                    'h' -> total += value * 3600
                    'm' -> total += value * 60
                    's' -> total += value
                }
                if (char.isLetter()) number = ""
            }
            return total.takeIf { it > 0 }
        }
    }

    private class Dates(private val zone: ZoneId) {
        private val dateTimes = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "d MMM yyyy, HH:mm", "d MMM yyyy HH:mm",
            "MMM d, yyyy, h:mm a", "dd.MM.yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
        ).map { DateTimeFormatter.ofPattern(it, Locale.US) }
        private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

        /** ISO 8601 with a zone first (NoTomorrow's own export), then the local-time formats Strong and Hevy write. */
        fun parse(text: String): Long? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(trimmed).toEpochMilli() }
            for (formatter in dateTimes) {
                runCatching { return LocalDateTime.parse(trimmed, formatter).atZone(zone).toInstant().toEpochMilli() }
            }
            runCatching { return LocalDate.parse(trimmed, day).atStartOfDay(zone).toInstant().toEpochMilli() }
            return null
        }
    }
}

/**
 * Writes parsed workouts into the store — port of `WorkoutImporter`: finished workouts with
 * completed sets, exercises matched to the library by name (English or Polish, "Bench Press
 * (Barbell)" style included) or created as custom exercises. A workout that already exists (same
 * name, start within a minute) is skipped, so importing the same file twice adds nothing. The
 * records of every touched exercise are rebuilt, as after a history edit.
 *
 * [transaction] is `db.withTransaction` in the app, a plain call in tests.
 */
class WorkoutImporter(
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val recordService: RecordService,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {

    data class Summary(
        val workouts: Int = 0,
        val sets: Int = 0,
        val duplicates: Int = 0,
        val newExercises: List<String> = emptyList(),
    )

    suspend fun importWorkouts(workouts: List<WorkoutImport.ImportedWorkout>): Summary {
        var summary = Summary()
        transaction {
            val byKey = mutableMapOf<String, ExerciseEntity>()
            for (exercise in exerciseDao.allByName()) {
                for (name in listOfNotNull(exercise.name, exercise.namePL)) {
                    for (key in WorkoutImport.matchKeys(name)) byKey.putIfAbsent(key, exercise)
                }
            }
            val existing = workoutDao.observeFinishedWorkouts().first()
            val lastUsed = mutableMapOf<String, Long>()
            val newExercises = mutableListOf<String>()
            var count = 0
            var sets = 0
            var duplicates = 0

            for (imported in workouts) {
                if (imported.setCount <= 0) continue
                if (existing.any { it.name == imported.name && abs(it.startedAt - imported.startedAt) < 60_000 }) {
                    duplicates += 1
                    continue
                }
                val workoutId = UUID.randomUUID().toString()
                workoutDao.insertWorkout(
                    WorkoutEntity(
                        id = workoutId,
                        name = imported.name,
                        startedAt = imported.startedAt,
                        endedAt = imported.endedAt,
                        notes = imported.notes,
                    ),
                )
                val span = max(60_000L, imported.endedAt - imported.startedAt).toDouble()
                val step = span / max(1, imported.setCount)
                var tick = 0
                imported.exercises.forEachIndexed { order, item ->
                    val exercise = match(item.name, byKey, newExercises)
                    val entryId = workoutDao.insertWorkoutExercise(
                        WorkoutExerciseEntity(
                            workoutId = workoutId,
                            exerciseId = exercise.id,
                            order = order,
                            restSeconds = RoutineSeeder.restSeconds(exercise.id),
                            notes = item.notes,
                        ),
                    )
                    val rows = item.sets.mapIndexed { index, value ->
                        // Spread over the workout in row order, so the records timeline reads them in the order done.
                        tick += 1
                        SetEntryEntity(
                            workoutExerciseId = entryId,
                            order = index,
                            kind = value.kind,
                            weightKg = value.weightKg,
                            reps = value.reps,
                            completedAt = imported.startedAt + (step * tick).toLong(),
                            rpe = value.rpe,
                        )
                    }
                    if (rows.isNotEmpty()) workoutDao.insertSets(rows)
                    sets += rows.size
                    val used = lastUsed[exercise.id] ?: exercise.lastUsedAt ?: Long.MIN_VALUE
                    lastUsed[exercise.id] = max(used, imported.startedAt)
                }
                count += 1
            }
            for ((id, at) in lastUsed) exerciseDao.markUsed(id, at)
            recordService.rebuild(lastUsed.keys)
            summary = Summary(count, sets, duplicates, newExercises.toList())
        }
        return summary
    }

    private suspend fun match(
        name: String,
        keys: MutableMap<String, ExerciseEntity>,
        newExercises: MutableList<String>,
    ): ExerciseEntity {
        val candidates = WorkoutImport.matchKeys(name)
        for (key in candidates) keys[key]?.let { return it }
        val exercise = ExerciseEntity(
            id = "custom-" + UUID.randomUUID().toString().lowercase(Locale.ROOT),
            name = name,
            primaryMuscles = emptyList(),
            isCustom = true,
        )
        exerciseDao.upsert(exercise)
        for (key in candidates) keys.putIfAbsent(key, exercise)
        newExercises += name
        return exercise
    }
}
