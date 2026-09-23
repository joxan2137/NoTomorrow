package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.PreviousRows
import app.notomorrow.feature.workout.SetInput
import app.notomorrow.feature.workout.SetSlot
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.prefilledValues
import app.notomorrow.feature.workout.previousValue
import app.notomorrow.feature.workout.slots
import app.notomorrow.feature.workout.valuesToLog
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.rest.RestTimerController
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.FakeAttendanceDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeScheduleDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.RecordService
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The active set table (`SetTableTests.swift`): Previous lined up with warm-ups (product-ux-6), no
 * "0 × 0" sets, Delete set, Finish settling the records, and pounds in and out (product-ux-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetTableTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_790_000_000_000L

    private val workouts = FakeWorkoutDao()
    private val restTimer = mockk<RestTimerController>(relaxed = true) {
        every { state } returns MutableStateFlow(RestTimerState())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LocaleProvider.override = { Locale.UK }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LocaleProvider.override = null
    }

    // MARK: Previous (pure)

    @Test
    fun `previous lines up warm-ups with warm-ups and working sets by number`() {
        val last = PreviousRows.of(
            listOf(
                completed(0, SetKind.Warmup, 40.0, 10),
                completed(1, SetKind.Normal, 80.0, 8),
                completed(2, SetKind.Normal, 80.0, 8),
                completed(3, SetKind.Normal, 80.0, 7),
            ),
        )
        val table = slots(listOf(SetKind.Warmup, SetKind.Normal, SetKind.Normal, SetKind.Normal, SetKind.Normal))
        val fallback = SetValue(80.0, 7)

        val previous = table.map { previousValue(last, fallback, it) }

        assertEquals(SetValue(40.0, 10), previous[0], "warm-up against warm-up")
        assertEquals(SetValue(80.0, 8), previous[1], "set 1 against set 1, not the warm-up")
        assertEquals(SetValue(80.0, 7), previous[3])
        assertEquals(SetValue(80.0, 7), previous[4], "past the end: the most recent completed set")
        assertNull(previousValue(last, fallback, SetSlot(isWarmup = true, index = 1)), "a warm-up past the end gets none")
    }

    @Test
    fun `slots count warm-ups and the other sets apart`() {
        assertEquals(
            listOf(SetSlot(false, 0), SetSlot(true, 0), SetSlot(false, 1), SetSlot(true, 1)),
            slots(listOf(SetKind.Normal, SetKind.Warmup, SetKind.Drop, SetKind.Warmup)),
        )
    }

    @Test
    fun `a tick logs Previous for an empty row and nothing without reps`() {
        assertEquals(SetValue(80.0, 8), valuesToLog(0.0, 0, SetValue(80.0, 8)))
        assertEquals(SetValue(82.5, 6), valuesToLog(82.5, 6, SetValue(80.0, 8)), "typed numbers win")
        assertNull(valuesToLog(0.0, 0, null), "never done and nothing typed: no 0 × 0")
        assertNull(valuesToLog(80.0, 0, SetValue(80.0, 8)), "a weight alone is not a set")
        assertNull(valuesToLog(0.0, 0, SetValue(40.0, 0)))
    }

    @Test
    fun `the prefill fills only the empty cells of an open row`() {
        assertEquals(SetValue(80.0, 8), prefilledValues(0.0, 0, isCompleted = false, previous = SetValue(80.0, 8)))
        assertEquals(SetValue(85.0, 8), prefilledValues(85.0, 0, isCompleted = false, previous = SetValue(80.0, 8)))
        assertNull(prefilledValues(0.0, 0, isCompleted = true, previous = SetValue(80.0, 8)), "completed rows are never touched")
        assertNull(prefilledValues(85.0, 6, isCompleted = false, previous = SetValue(80.0, 8)))
        assertNull(prefilledValues(0.0, 0, isCompleted = false, previous = null))
        assertNull(prefilledValues(0.0, 5, isCompleted = false, previous = SetValue(0.0, 8)), "nothing to add")
    }

    // MARK: Units (product-ux-12)

    @Test
    fun `pounds round trip through the cells`() {
        val kg = SetInput.weightKg("135", WeightUnit.Lb)
        assertEquals(61.235, kg, 0.001, "stored in kg")
        assertEquals("135", SetInput.text(kg, WeightUnit.Lb), "shown as typed")
        assertEquals("135", Fmt.weight(kg, WeightUnit.Lb, withUnit = false))
        assertEquals(100.0, SetInput.weightKg("100", WeightUnit.Kg))
        // 60 kg shows as 132.28 lb, which would parse back to 60.0012 kg: the cell compares what is
        // typed with this text, so an untouched cell never writes the rounded number back.
        assertEquals("132.28", SetInput.text(60.0, WeightUnit.Lb))
        assertEquals(SetInput.number("132.28"), SetInput.number(SetInput.text(60.0, WeightUnit.Lb)))
    }

    @Test
    fun `set input parses leniently and never overflows`() {
        assertEquals(82.5, SetInput.number("82,5"))
        assertEquals(82.5, SetInput.number("82.5"))
        assertEquals(1000.0, SetInput.number("1 000"))
        assertEquals(0.0, SetInput.number(""))
        assertEquals(0.0, SetInput.number("abc"))
        assertEquals(0.0, SetInput.number("nan"))
        assertEquals(0.0, SetInput.number("-5"))
        assertEquals(8, SetInput.reps("8"))
        assertEquals(9, SetInput.reps("8,5"))
        assertEquals(9_999, SetInput.reps("1e30"))
        assertEquals("", SetInput.text(0.0, WeightUnit.Kg))
        assertEquals("", SetInput.text(0))
        assertEquals(81.25, SetInput.number(SetInput.text(81.25, WeightUnit.Kg)), "quarter plates survive")
        assertEquals("81,25", SetInput.text(81.25, WeightUnit.Kg, Locale.forLanguageTag("pl")))
        assertEquals("1000", SetInput.text(1000.0, WeightUnit.Kg, Locale.forLanguageTag("pl")), "no grouping in a cell")
    }

    @Test
    fun `volume in pounds`() {
        assertEquals("220", Fmt.volume(100.0, WeightUnit.Lb, withUnit = false))
        assertTrue(Fmt.volume(100.0, WeightUnit.Lb).endsWith("lb"))
        assertTrue(Fmt.volume(100.0).endsWith("kg"))
    }

    // MARK: The view model

    @Test
    fun `the table reads Previous from the last finished session, warm-ups apart`() = runTest {
        seedFinished("old", startedAt = now - 86_400_000L, rows = listOf(
            Row(SetKind.Warmup, 40.0, 10), Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 7),
        ))
        seedActive(rows = listOf(Row(SetKind.Warmup), Row(SetKind.Normal), Row(SetKind.Normal)))
        val model = model(newSession())
        runCurrent()

        val previous = model.state.value.exercises.single().sets.map { it.previous }
        assertEquals(listOf(SetValue(40.0, 10), SetValue(80.0, 8), SetValue(80.0, 8)), previous)

        val second = model.state.value.exercises.single().sets[1]
        model.prefillFromPrevious(second.id)
        runCurrent()
        assertEquals(80.0, workouts.set(second.id)?.weightKg)
        assertEquals(8, workouts.set(second.id)?.reps)
    }

    @Test
    fun `an empty row with nothing to copy is not logged`() = runTest {
        seedActive(rows = listOf(Row(SetKind.Normal)))
        val model = model(newSession())
        runCurrent()
        val set = model.state.value.exercises.single().sets.single()
        var logged: Boolean? = null

        model.complete(set.id) { logged = it }
        runCurrent()

        assertEquals(false, logged)
        assertNull(workouts.set(set.id)?.completedAt, "never done before and no reps: nothing to log")
        assertFalse(workouts.set(set.id)!!.isPR)
    }

    @Test
    fun `an empty row takes Previous on the tick`() = runTest {
        seedFinished("old", startedAt = now - 86_400_000L, rows = listOf(Row(SetKind.Normal, 80.0, 8)))
        seedActive(rows = listOf(Row(SetKind.Normal)))
        val model = model(newSession())
        runCurrent()
        val set = model.state.value.exercises.single().sets.single()
        var logged: Boolean? = null

        model.complete(set.id) { logged = it }
        runCurrent()

        assertEquals(true, logged)
        val saved = workouts.set(set.id)!!
        assertEquals(now, saved.completedAt)
        assertEquals(80.0, saved.weightKg)
        assertEquals(8, saved.reps)
    }

    @Test
    fun `delete set renumbers the rest`() = runTest {
        seedActive(rows = listOf(Row(SetKind.Normal, 80.0, 8, done = true), Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 8)))
        val model = model(newSession())
        runCurrent()
        val sets = model.state.value.exercises.single().sets

        model.removeSet(sets[1].id)
        runCurrent()

        val left = workouts.sets.value.sortedBy { it.order }
        assertEquals(listOf(sets[0].id, sets[2].id), left.map { it.id })
        assertEquals(listOf(0, 1), left.map { it.order })
        assertEquals(listOf(1, 2), model.state.value.exercises.single().sets.map { it.number })
    }

    @Test
    fun `finish settles stale record flags`() = runTest {
        seedActive(rows = listOf(Row(SetKind.Normal, 80.0, 8, done = true), Row(SetKind.Normal, 90.0, 8, done = true)))
        // Both flagged at their tick, then the second turned into a warm-up: no longer a record.
        workouts.sets.value = workouts.sets.value.map {
            if (it.order == 1) it.copy(isPR = true, kind = SetKind.Warmup) else it.copy(isPR = true)
        }
        val session = newSession()
        session.begin(ACTIVE)
        val model = model(session)

        model.finish()
        runCurrent()

        assertEquals(listOf(true, false), workouts.sets.value.sortedBy { it.order }.map { it.isPR })
    }

    @Test
    fun `the unit reaches the table state`() = runTest {
        seedActive(rows = listOf(Row(SetKind.Normal)))
        val model = model(newSession(), unit = WeightUnit.Lb)
        runCurrent()
        assertEquals(WeightUnit.Lb, model.state.value.unit)
    }

    // MARK: Fixtures

    private data class Row(val kind: SetKind, val kg: Double = 0.0, val reps: Int = 0, val done: Boolean = false)

    private suspend fun seedFinished(id: String, startedAt: Long, rows: List<Row>) {
        workouts.insertWorkout(WorkoutEntity(id = id, name = "Push A", startedAt = startedAt, endedAt = startedAt + 3_600_000))
        val entry = workouts.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = id, exerciseId = BENCH, order = 0))
        rows.forEachIndexed { index, row ->
            workouts.insertSet(
                SetEntryEntity(
                    workoutExerciseId = entry,
                    order = index,
                    kind = row.kind,
                    weightKg = row.kg,
                    reps = row.reps,
                    completedAt = startedAt + (index + 1) * 60_000L,
                ),
            )
        }
    }

    private suspend fun seedActive(rows: List<Row>) {
        val start = now - 600_000L
        workouts.insertWorkout(WorkoutEntity(id = ACTIVE, name = "Push A", startedAt = start))
        val entry = workouts.insertWorkoutExercise(WorkoutExerciseEntity(workoutId = ACTIVE, exerciseId = BENCH, order = 0))
        rows.forEachIndexed { index, row ->
            workouts.insertSet(
                SetEntryEntity(
                    workoutExerciseId = entry,
                    order = index,
                    kind = row.kind,
                    weightKg = row.kg,
                    reps = row.reps,
                    completedAt = if (row.done) start + (index + 1) * 60_000L else null,
                ),
            )
        }
    }

    private fun completed(order: Int, kind: SetKind, kg: Double, reps: Int) = CompletedSetRow(
        setId = order.toLong(),
        setOrder = order,
        kind = kind,
        weightKg = kg,
        reps = reps,
        completedAt = 1_000L + order,
        isPR = false,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = 1,
        workoutExerciseOrder = 0,
        exerciseId = BENCH,
        workoutId = "old",
        workoutName = "Push A",
        workoutStartedAt = 1_000,
        workoutEndedAt = 5_000,
    )

    private suspend fun TestScope.newSession() = WorkoutSessionController(
        object : WorkoutSessionController.Store {
            override suspend fun activeWorkoutId(): String? = null
            override suspend fun setActiveWorkoutId(id: String?) = Unit
            override suspend fun discarding(): Set<String> = emptySet()
            override suspend fun setDiscarding(ids: Set<String>) = Unit
        },
        workouts,
        backgroundScope,
    ).also { it.awaitRestored() }

    private fun model(session: WorkoutSessionController, unit: WeightUnit = WeightUnit.Kg) = ActiveWorkoutViewModel(
        workoutId = ACTIVE,
        workoutDao = workouts,
        recordService = RecordService(workouts),
        attendanceService = AttendanceService(
            FakeAttendanceDao(),
            FakeScheduleDao(GymScheduleEntity(weekdays = listOf(1, 3, 5))),
            FakeProfileDao(),
            zone,
        ),
        restTimer = restTimer,
        session = session,
        appPrefs = mockk<AppPrefs>(relaxed = true),
        strings = mockk<NtStrings>(relaxed = true),
        zone = zone,
        clock = { now },
        units = { unit },
    )

    private companion object {
        const val ACTIVE = "active"
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
    }
}
