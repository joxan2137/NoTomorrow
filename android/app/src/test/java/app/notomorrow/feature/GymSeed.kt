package app.notomorrow.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import app.notomorrow.data.entity.BodyMeasurementEntity
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.ProgressPhotoEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.files.ProgressPhotoFiles
import app.notomorrow.di.AppContainer
import app.notomorrow.model.MeasurementKind
import app.notomorrow.model.SetKind
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Realistic training history for [GymScreenshots]: ten weeks of a Push / Pull / Legs split with
 * steadily climbing loads, a body-weight trend, tape measurements, three progress photos and a
 * routine with a superset — written straight into the container's Room database.
 */
internal class GymSeed(private val container: AppContainer) {

    val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)

    companion object {
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
        const val OHP = "Barbell_Shoulder_Press"
        const val INCLINE = "Incline_Dumbbell_Press"
        const val PUSHDOWN = "Triceps_Pushdown"
        const val LATERAL = "Side_Lateral_Raise"
        const val DEADLIFT = "Barbell_Deadlift"
        const val ROW = "Bent_Over_Barbell_Row"
        const val PULLUP = "Pullups"
        const val CURL = "Barbell_Curl"
        const val SQUAT = "Barbell_Squat"
        const val RDL = "Romanian_Deadlift"
        const val LEG_PRESS = "Leg_Press"

        const val ROUTINE_ID = "shots-upper"
        const val LAST_WORKOUT = "shots-w-last"
        const val ACTIVE_WORKOUT = "shots-active"
    }

    private data class Plan(val name: String, val lifts: List<Triple<String, Double, Double>>)

    // exercise, starting kg, kg gained over ten weeks
    private val plans = listOf(
        Plan("Push A", listOf(Triple(BENCH, 70.0, 17.5), Triple(OHP, 40.0, 10.0), Triple(INCLINE, 24.0, 6.0), Triple(PUSHDOWN, 25.0, 7.5))),
        Plan("Pull A", listOf(Triple(DEADLIFT, 120.0, 30.0), Triple(ROW, 60.0, 12.5), Triple(PULLUP, 0.0, 0.0), Triple(CURL, 30.0, 5.0))),
        Plan("Legs", listOf(Triple(SQUAT, 90.0, 25.0), Triple(RDL, 80.0, 15.0), Triple(LEG_PRESS, 140.0, 40.0))),
    )

    private fun millis(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    suspend fun seed() {
        val db = container.db
        db.profileDao().upsert(UserProfileEntity(name = "Alex", bodyWeightKg = 82.4, defaultRestSeconds = 120))
        seedWorkouts()
        seedRoutine()
        seedBody()
    }

    private suspend fun seedWorkouts() {
        val dao = container.db.workoutDao()
        val best = mutableMapOf<String, Double>()
        // Mon / Wed / Fri, 10 weeks back, skipping a few days so the calendar has gaps.
        val start = today.minusWeeks(10).with(java.time.DayOfWeek.MONDAY)
        var index = 0
        var day = start
        while (day.isBefore(today.minusDays(1))) {
            val dow = day.dayOfWeek.value
            // A missed Wednesday every third week and a bonus Saturday every fourth: gaps and extras in the calendar.
            val week = (day.toEpochDay() - start.toEpochDay()) / 7
            val trains = (dow in setOf(1, 5) || (dow == 3 && week % 3 != 1L)) || (dow == 6 && week % 4 == 2L)
            if (trains) {
                val plan = plans[index % plans.size]
                val progress = (day.toEpochDay() - start.toEpochDay()).toDouble() / (today.toEpochDay() - start.toEpochDay())
                val id = "shots-w-$index"
                val startedAt = millis(day, 18, 5)
                dao.insertWorkout(WorkoutEntity(id = id, name = plan.name, startedAt = startedAt, endedAt = startedAt + 64 * 60_000L))
                plan.lifts.forEachIndexed { order, (exercise, base, gain) ->
                    val top = ((base + gain * progress) / 2.5).roundToInt() * 2.5
                    val reps = listOf(8, 8, 6, 6)
                    // The top set (row 2) is a PR whenever it beats every earlier session.
                    val topE1 = top * (1 + reps[2] / 30.0)
                    val pr = top > 0 && topE1 > (best[exercise] ?: 0.0) + 0.01
                    if (pr) best[exercise] = topE1
                    val sets = reps.mapIndexed { row, r ->
                        val kg = if (row >= 2) top else top - 5.0
                        SetEntryEntity(
                            workoutExerciseId = 0,
                            order = row,
                            weightKg = kg.coerceAtLeast(0.0),
                            reps = if (exercise == PULLUP) 8 + (progress * 4).toInt() - row else r,
                            completedAt = startedAt + (order * 4 + row + 1) * 150_000L,
                            isPR = pr && row == 2,
                            rpe = if (row == 3) 8.5 else null,
                        )
                    }
                    dao.insertWorkoutExerciseWithSets(
                        WorkoutExerciseEntity(workoutId = id, exerciseId = exercise, order = order),
                        sets,
                    )
                }
                index++
            }
            day = day.plusDays(1)
        }
        seedLastWorkout()
        // What the app stamps when an exercise goes into a workout: the picker lists these first.
        val used = millis(today.minusDays(1), 18, 5)
        plans.flatMap { it.lifts }.forEachIndexed { i, (exercise, _, _) ->
            container.db.exerciseDao().markUsed(exercise, used - i * 86_400_000L)
        }
    }

    /**
     * Yesterday's Pull A: a deadlift PR at 167.5 kg — past 2× body weight for the first time, so
     * the finish screen has a milestone to show — plus a row PR.
     */
    private suspend fun seedLastWorkout() {
        val dao = container.db.workoutDao()
        val startedAt = millis(today.minusDays(1), 18, 5)
        dao.insertWorkout(WorkoutEntity(id = LAST_WORKOUT, name = "Pull A", startedAt = startedAt, endedAt = startedAt + 71 * 60_000L))
        val lifts = listOf(
            DEADLIFT to listOf(140.0 to 5, 155.0 to 3, 167.5 to 3, 150.0 to 5),
            ROW to listOf(65.0 to 8, 70.0 to 8, 75.0 to 6, 75.0 to 6),
            PULLUP to listOf(0.0 to 12, 0.0 to 10, 0.0 to 9),
            CURL to listOf(32.5 to 8, 35.0 to 6, 35.0 to 6),
        )
        lifts.forEachIndexed { order, (exercise, sets) ->
            dao.insertWorkoutExerciseWithSets(
                WorkoutExerciseEntity(workoutId = LAST_WORKOUT, exerciseId = exercise, order = order),
                sets.mapIndexed { row, (kg, reps) ->
                    SetEntryEntity(
                        workoutExerciseId = 0,
                        order = row,
                        weightKg = kg,
                        reps = reps,
                        completedAt = startedAt + (order * 5 + row + 1) * 180_000L,
                        isPR = (exercise == DEADLIFT && kg == 167.5) || (exercise == ROW && row == 2),
                        rpe = if (row == 2) 9.0 else null,
                    )
                },
            )
        }
    }

    private suspend fun seedRoutine() {
        container.db.routineDao().insertRoutineWithItems(
            RoutineEntity(id = ROUTINE_ID, name = "Upper strength", order = 99),
            listOf(
                RoutineItemEntity(routineId = ROUTINE_ID, exerciseId = BENCH, order = 0, targetSets = 4, targetReps = 6, restSeconds = 180),
                RoutineItemEntity(routineId = ROUTINE_ID, exerciseId = ROW, order = 1, targetSets = 4, targetReps = 8, restSeconds = 150),
                RoutineItemEntity(routineId = ROUTINE_ID, exerciseId = INCLINE, order = 2, targetSets = 3, targetReps = 10, restSeconds = 60, supersetGroup = 1),
                RoutineItemEntity(routineId = ROUTINE_ID, exerciseId = PULLUP, order = 3, targetSets = 3, targetReps = 8, restSeconds = 90, supersetGroup = 1),
                RoutineItemEntity(routineId = ROUTINE_ID, exerciseId = LATERAL, order = 4, targetSets = 3, targetReps = 15, restSeconds = 0),
            ),
        )
    }

    /** A running workout: bench done for two sets (RPE on one), a note, then a superset. */
    suspend fun seedActiveWorkout(): String {
        val dao = container.db.workoutDao()
        val startedAt = System.currentTimeMillis() - 23 * 60_000L
        dao.insertWorkout(WorkoutEntity(id = ACTIVE_WORKOUT, name = "Upper strength", startedAt = startedAt))
        val bench = listOf(
            SetEntryEntity(workoutExerciseId = 0, order = 0, kind = SetKind.Warmup, weightKg = 40.0, reps = 10, completedAt = startedAt + 60_000),
            SetEntryEntity(workoutExerciseId = 0, order = 1, weightKg = 85.0, reps = 6, completedAt = startedAt + 300_000, rpe = 7.5),
            SetEntryEntity(workoutExerciseId = 0, order = 2, weightKg = 87.5, reps = 6, completedAt = startedAt + 520_000, rpe = 9.0),
            SetEntryEntity(workoutExerciseId = 0, order = 3, weightKg = 87.5, reps = 6),
        )
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = ACTIVE_WORKOUT, exerciseId = BENCH, order = 0, restSeconds = 180, notes = "Pause the first rep on the chest. Elbows at 45°."),
            bench,
        )
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = ACTIVE_WORKOUT, exerciseId = INCLINE, order = 1, restSeconds = 60, supersetGroup = 1),
            (0 until 3).map { SetEntryEntity(workoutExerciseId = 0, order = it, weightKg = 30.0, reps = 10) },
        )
        dao.insertWorkoutExerciseWithSets(
            WorkoutExerciseEntity(workoutId = ACTIVE_WORKOUT, exerciseId = PULLUP, order = 2, restSeconds = 90, supersetGroup = 1),
            (0 until 3).map { SetEntryEntity(workoutExerciseId = 0, order = it, weightKg = 0.0, reps = 10) },
        )
        return ACTIVE_WORKOUT
    }

    private suspend fun seedBody() {
        val db = container.db
        for (week in 0..11) {
            val day = today.minusWeeks((11 - week).toLong())
            val dayStart = millis(day, 0)
            db.bodyWeightDao().upsert(BodyWeightEntryEntity(day = dayStart, kg = 85.6 - week * 0.29 + (week % 3) * 0.2))
        }
        val tape = mapOf(
            MeasurementKind.Waist to (88.0 to -3.5),
            MeasurementKind.Chest to (104.0 to 2.0),
            MeasurementKind.Arm to (37.5 to 1.5),
            MeasurementKind.Thigh to (60.0 to 1.0),
            MeasurementKind.BodyFat to (19.5 to -2.8),
        )
        for (step in 0..3) {
            val day = today.minusWeeks((9 - step * 3).toLong())
            tape.forEach { (kind, pair) ->
                val value = pair.first + pair.second * step / 3.0
                db.bodyMeasurementDao().insert(
                    BodyMeasurementEntity(id = "m-${kind.raw}-$step", day = millis(day, 0), kind = kind.raw, value = (value * 10).roundToInt() / 10.0),
                )
            }
        }
        PHOTOS.forEachIndexed { i, (daysAgo, pose) ->
            val name = "shot-$i.jpg"
            db.progressPhotoDao().insert(
                ProgressPhotoEntity(id = "p$i", takenAt = millis(today.minusDays(daysAgo), 8, 30), fileName = name, pose = pose),
            )
        }
    }

    /**
     * The photo files. On the main thread: Robolectric's native graphics aborts the JVM when a
     * worker thread is the first to touch it.
     */
    fun writePhotos() {
        val files = ProgressPhotoFiles.inFilesDir(container.app.filesDir)
        PHOTOS.indices.forEach { i -> files.write(placeholderPhoto(i), "shot-$i.jpg") }
    }

    private val PHOTOS = listOf(84L to "front", 42L to "side", 3L to "front")

    /** A 3:4 gradient with a pale figure silhouette — stands in for a real photo. */
    private fun placeholderPhoto(seed: Int): ByteArray {
        val w = 600
        val h = 800
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val tops = intArrayOf(0xFF3A4A5C.toInt(), 0xFF4B3F57.toInt(), 0xFF3F5448.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), tops[seed % 3], 0xFF16181C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFD8C3A8.toInt()
        val waist = 110f - seed * 8f
        canvas.drawCircle(w / 2f, 170f, 62f, paint)
        canvas.drawRoundRect(w / 2f - 150f, 250f, w / 2f + 150f, 420f, 60f, 60f, paint)
        canvas.drawRoundRect(w / 2f - waist, 400f, w / 2f + waist, 560f, 40f, 40f, paint)
        canvas.drawRoundRect(w / 2f - 110f, 540f, w / 2f - 10f, 800f, 40f, 40f, paint)
        canvas.drawRoundRect(w / 2f + 10f, 540f, w / 2f + 110f, 800f, 40f, 40f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
