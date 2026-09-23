package app.notomorrow.feature.workoutedit

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.PreviousRows
import app.notomorrow.feature.workout.SetSlot
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.WorkoutDraft
import app.notomorrow.feature.workout.WorkoutEditor
import app.notomorrow.feature.workout.previousValue
import app.notomorrow.feature.workout.toDraft
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.Participant
import app.notomorrow.model.SetKind
import app.notomorrow.service.AttendanceReporter
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.FakeAttendanceDao
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeScheduleDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.RecordService
import app.notomorrow.service.RoutineSeeder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `WorkoutEditor.save` / `delete` against in-memory tables (`WorkoutEditTests.swift`, save / delete /
 * attendance sections): the draft written back, set times kept consistent, the records rebuilt for
 * every workout of the touched exercises, and attendance corrected for the old and new day.
 */
class WorkoutEditorTest {

    private val zone = ZoneOffset.UTC
    /** Tuesday 22 Sep 2026. */
    private val today = LocalDate.of(2026, 9, 22)
    private val minute = 60_000L

    private val workouts = FakeWorkoutDao()
    private val exercises = FakeExerciseDao(
        listOf(
            ExerciseEntity(id = BENCH, name = "Bench", primaryMuscles = listOf("chest")),
            ExerciseEntity(id = SQUAT, name = "Squat", primaryMuscles = listOf("quadriceps")),
            ExerciseEntity(id = CURL, name = "Curl", primaryMuscles = listOf("biceps")),
        ),
    )
    private val attendance = FakeAttendanceDao()
    private val attendanceService = AttendanceService(
        attendance,
        FakeScheduleDao(GymScheduleEntity(weekdays = (1..7).toList())),
        FakeProfileDao(),
        zone,
    )
    private val records = RecordService(workouts)
    /** What the editor sent to the backend (`AttendanceSync.report`). */
    private val reported = mutableListOf<Pair<LocalDate, AttendanceStatus>>()
    private val stores = WorkoutEditor.Stores(
        workouts,
        exercises,
        records,
        attendanceService,
        reportAttendance = AttendanceReporter { day, status -> reported += day to status },
    )

    private fun daysAgo(n: Long, hour: Int = 18): Long =
        today.minusDays(n).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private var nextWorkout = 1

    /** A finished workout of one exercise; each set is (kg, reps) completed one minute apart from the start. */
    private suspend fun finished(
        start: Long,
        minutes: Double = 60.0,
        exerciseId: String = BENCH,
        name: String = "Push A",
        sets: List<Pair<Double, Int>>,
        kinds: List<SetKind>? = null,
    ): String {
        val id = "w${nextWorkout++}"
        workouts.insertWorkout(WorkoutEntity(id = id, name = name, startedAt = start, endedAt = start + (minutes * minute).toLong()))
        val entry = workouts.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = id, exerciseId = exerciseId, order = 0))
        sets.forEachIndexed { index, (kg, reps) ->
            workouts.insertSet(
                SetEntryEntity(
                    workoutExerciseId = entry,
                    order = index,
                    kind = kinds?.get(index) ?: SetKind.Normal,
                    weightKg = kg,
                    reps = reps,
                    completedAt = start + (index + 1) * minute,
                ),
            )
        }
        return id
    }

    private suspend fun draft(id: String): WorkoutDraft = workouts.workoutWithExercises(id)!!.toDraft(Locale.UK)

    private suspend fun save(id: String, draft: WorkoutDraft) =
        assertTrue(WorkoutEditor.save(id, draft, stores, zone, today))

    private suspend fun flags(id: String): List<Boolean> =
        workouts.workoutWithExercises(id)!!.sortedExercises.flatMap { it.sortedSets }.map { it.isPR }

    private suspend fun status(day: LocalDate) = attendanceService.record(day, Participant.Me)?.status

    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    // MARK: Save

    @Test
    fun `save writes the draft back`() = runBlocking {
        val start = daysAgo(2)
        val id = finished(start, sets = listOf(80.0 to 5, 80.0 to 5, 80.0 to 4))
        var d = draft(id)
        val ex = d.exercises[0]
        d = d.copy(name = "   ", notes = " Felt strong \n", durationMs = 45 * minute)
            .deletingSet(ex.sets[1].id, ex.id)
        d = d.togglingDone(ex.sets[2].id, ex.id)!!

        save(id, d)

        val saved = workouts.workoutWithExercises(id)!!
        assertEquals("Push A", saved.workout.name, "an empty name keeps the old one")
        assertEquals("Felt strong", saved.workout.notes)
        assertEquals(start + 45 * minute, saved.workout.endedAt)
        val sets = saved.sortedExercises[0].sortedSets
        assertEquals(listOf(0, 1), sets.map { it.order }, "rows re-indexed without gaps")
        assertEquals(start + 45_000, sets[0].completedAt, "60 → 45 min scales the offsets")
        assertNull(sets[1].completedAt)
        assertFalse(sets[1].isPR)
        assertEquals(2, workouts.sets.value.size)
    }

    @Test
    fun `an untouched save keeps every time exactly`() = runBlocking {
        val id = finished(daysAgo(1), minutes = 52.6, sets = listOf(80.0 to 5, 82.5 to 5))
        val before = workouts.sets.value.map { it.completedAt }

        save(id, draft(id).copy(name = "Push B"))

        assertEquals(before, workouts.sets.value.map { it.completedAt })
        assertEquals("Push B", workouts.workout(id)?.name)
    }

    @Test
    fun `a rename keeps a legacy 0-rep set logged, its time and the day`() = runBlocking {
        val start = daysAgo(2)
        val id = finished(start, sets = listOf(60.0 to 0)) // logged before the no-"0 × 0" rule
        attendanceService.markAttended(day(start))
        val before = workouts.sets.value.single().completedAt

        save(id, draft(id).copy(name = "Push B"))

        assertEquals(before, workouts.sets.value.single().completedAt, "still completed, at the same time")
        assertEquals(AttendanceStatus.Attended, status(day(start)), "the day keeps its workout")
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `sets added in the editor come after their neighbour for Previous`() = runBlocking {
        // Curl 30 × 10, then 30 × 8 and 30 × 7 added in the editor: all three share one time.
        val id = finished(daysAgo(1), exerciseId = CURL, name = "Arms", sets = listOf(30.0 to 10))
        var d = draft(id)
        val ex = d.exercises[0]
        d = d.addingSet(ex.id, newId = -1).updatingSet(-1, ex.id) { it.copy(reps = 8) }
        d = d.addingSet(ex.id, newId = -2).updatingSet(-2, ex.id) { it.copy(reps = 7) }
        save(id, d)
        assertEquals(1, workouts.sets.value.mapNotNull { it.completedAt }.toSet().size)

        val rows = workouts.completedSetsForExercise(CURL)
        val last = RecordService.lastSet(rows, excludingWorkoutId = "next")
        assertEquals(30.0 to 7, last?.let { it.weightKg to it.reps }, "the bottom row is the last one done")

        // The next workout's Previous: rows 1–3 from that session, a fourth past the end takes the last set.
        val previous = PreviousRows.of(ActiveWorkoutViewModel.latestEarlierWorkoutRows(rows, excludingWorkoutId = "next"))
        val lastValue = last?.let { SetValue(it.weightKg, it.reps) }
        assertEquals(
            listOf(SetValue(30.0, 10), SetValue(30.0, 8), SetValue(30.0, 7), SetValue(30.0, 7)),
            (0..3).map { previousValue(previous, lastValue, SetSlot(isWarmup = false, index = it)) },
        )
    }

    @Test
    fun `editing an old set rebuilds later records`() = runBlocking {
        val w1 = finished(daysAgo(3), sets = listOf(80.0 to 5))
        val w2 = finished(daysAgo(2), sets = listOf(85.0 to 5))
        val w3 = finished(daysAgo(1), sets = listOf(82.5 to 7))
        records.rebuild(setOf(BENCH))
        assertEquals(listOf(true, true, true), flags(w1) + flags(w2) + flags(w3))

        val d = draft(w1)
        val ex = d.exercises[0]
        save(w1, d.updatingSet(ex.sets[0].id, ex.id) { it.copy(weightKg = 90.0) })

        assertEquals(listOf(true, false, false), flags(w1) + flags(w2) + flags(w3))
    }

    @Test
    fun `moving a workout earlier reorders its records and its set times`() = runBlocking {
        val w1 = finished(daysAgo(4), sets = listOf(80.0 to 5))
        val w2 = finished(daysAgo(2), sets = listOf(100.0 to 5))
        records.rebuild(setOf(BENCH))
        assertEquals(listOf(true, true), flags(w1) + flags(w2))

        save(w2, draft(w2).withDay(today.minusDays(6), zone))

        assertEquals(listOf(true), flags(w2), "now the first record")
        assertEquals(listOf(false), flags(w1), "80 no longer beats 100")
        val moved = workouts.workoutWithExercises(w2)!!.sortedExercises[0].sortedSets[0]
        assertEquals(today.minusDays(6), day(moved.completedAt!!), "set times move with the workout")
    }

    @Test
    fun `an added exercise gets one logged row and its rest`() = runBlocking {
        val id = finished(daysAgo(1), sets = listOf(80.0 to 5))
        var d = draft(id).appendingExercise(
            id = -1,
            setId = -2,
            exerciseId = SQUAT,
            name = "Squat",
            primaryMuscle = "quadriceps",
            restSeconds = RoutineSeeder.restSeconds(SQUAT, 90),
            template = SetValue(100.0, 5),
        )
        d = d.movingExercise(-1, -1)

        save(id, d)

        val entries = workouts.workoutWithExercises(id)!!.sortedExercises
        assertEquals(listOf(SQUAT, BENCH), entries.map { it.workoutExercise.exerciseId })
        val added = entries[0].sortedSets
        assertEquals(listOf(100.0), added.map { it.weightKg })
        assertEquals(listOf(5), added.map { it.reps })
        assertNotNull(added[0].completedAt)
        assertTrue(added[0].isPR, "its first record")
        assertEquals(120, entries[0].workoutExercise.restSeconds, "heavy compound rest")
    }

    @Test
    fun `an exercise gone from the library is skipped`() = runBlocking {
        val id = finished(daysAgo(1), sets = listOf(80.0 to 5))
        val d = draft(id).appendingExercise(-1, -2, "Gone", "Gone", null, 90, SetValue(10.0, 10))

        save(id, d)

        assertEquals(1, workouts.exercises.value.size)
    }

    @Test
    fun `removing an exercise deletes its sets`() = runBlocking {
        val id = finished(daysAgo(1), sets = listOf(80.0 to 5, 80.0 to 5))
        val d = draft(id)

        save(id, d.removingExercise(d.exercises[0].id))

        assertTrue(workouts.exercises.value.isEmpty())
        assertTrue(workouts.sets.value.isEmpty())
    }

    // MARK: Delete

    @Test
    fun `delete rebuilds the records of later workouts`() = runBlocking {
        val w1 = finished(daysAgo(3), sets = listOf(90.0 to 5))
        val w2 = finished(daysAgo(2), sets = listOf(85.0 to 5))
        val w3 = finished(daysAgo(1), sets = listOf(82.5 to 7))
        records.rebuild(setOf(BENCH))
        assertEquals(listOf(true, false, false), flags(w1) + flags(w2) + flags(w3))

        WorkoutEditor.delete(w1, stores, zone, today)

        assertEquals(listOf(true, true), flags(w2) + flags(w3))
        assertEquals(2, workouts.workouts.value.size)
        assertEquals(2, workouts.sets.value.size)
    }

    // MARK: Attendance

    @Test
    fun `moving a workout moves attendance`() = runBlocking {
        val start = daysAgo(3)
        val id = finished(start, sets = listOf(80.0 to 5))
        attendanceService.markAttended(day(start))

        save(id, draft(id).withDay(today.minusDays(2), zone))

        assertEquals(AttendanceStatus.Missed, status(today.minusDays(3)))
        assertEquals(AttendanceStatus.Attended, status(today.minusDays(2)))
    }

    @Test
    fun `moving today's workout to yesterday clears today`() = runBlocking {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli() + 1_000
        val id = finished(start, minutes = 0.5, sets = listOf(80.0 to 5))
        attendanceService.markAttended(today)

        save(id, draft(id).withDay(today.minusDays(1), zone))

        assertNull(status(today), "today derives from the schedule again")
        assertEquals(AttendanceStatus.Attended, status(today.minusDays(1)))
    }

    @Test
    fun `unticking every set gives the day back`() = runBlocking {
        val id = finished(daysAgo(2), sets = listOf(80.0 to 5))
        attendanceService.markAttended(today.minusDays(2))
        val d = draft(id)

        save(id, d.togglingDone(d.exercises[0].sets[0].id, d.exercises[0].id)!!)

        assertEquals(AttendanceStatus.Missed, status(today.minusDays(2)))
    }

    @Test
    fun `deleting one of two workouts on a day keeps it attended`() = runBlocking {
        val morning = finished(daysAgo(2, hour = 8), sets = listOf(80.0 to 5))
        finished(daysAgo(2, hour = 18), sets = listOf(82.5 to 5))
        attendanceService.markAttended(today.minusDays(2))

        WorkoutEditor.delete(morning, stores, zone, today)

        assertEquals(AttendanceStatus.Attended, status(today.minusDays(2)))
    }

    @Test
    fun `an edit and a delete report what they changed to the backend`() = runBlocking {
        val id = finished(daysAgo(3), sets = listOf(80.0 to 5))
        attendanceService.markAttended(today.minusDays(3))

        save(id, draft(id).withDay(today.minusDays(2), zone))
        assertEquals(
            listOf(today.minusDays(2) to AttendanceStatus.Attended, today.minusDays(3) to AttendanceStatus.Missed),
            reported,
        )

        reported.clear()
        WorkoutEditor.delete(id, stores, zone, today)
        assertEquals(listOf(today.minusDays(2) to AttendanceStatus.Missed), reported)
    }

    @Test
    fun `today given back is reported planned, and an edit that changes no day reports nothing`() = runBlocking {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli() + 1_000
        val id = finished(start, minutes = 0.5, sets = listOf(80.0 to 5))
        attendanceService.markAttended(today)

        save(id, draft(id).copy(notes = "same day"))
        assertTrue(reported.isEmpty())

        save(id, draft(id).withDay(today.minusDays(1), zone))
        assertEquals(
            listOf(today.minusDays(1) to AttendanceStatus.Attended, today to AttendanceStatus.Planned),
            reported,
            "the server has no delete: today's gym day is planned again",
        )
    }

    private companion object {
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
        const val SQUAT = "Barbell_Squat"
        const val CURL = "Barbell_Curl"
    }
}
