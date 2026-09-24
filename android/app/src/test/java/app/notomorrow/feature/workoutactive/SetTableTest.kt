package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.RoutineItemWithExercise
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.PreviousRows
import app.notomorrow.feature.workout.SetInput
import app.notomorrow.feature.workout.SetSlot
import app.notomorrow.feature.workout.SetValue
import app.notomorrow.feature.workout.WeightSuggestion
import app.notomorrow.feature.workout.prefilledValues
import app.notomorrow.feature.workout.previousValue
import app.notomorrow.feature.workout.routineTargetReps
import app.notomorrow.feature.workout.showsSuggestion
import app.notomorrow.feature.workout.slots
import app.notomorrow.feature.workout.valuesToLog
import app.notomorrow.feature.workout.weightSuggestion
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
import kotlin.test.assertNotNull
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
 * "0 × 0" sets, Delete set, Finish settling the records, pounds in and out (product-ux-12), and the
 * suggested weight (v2).
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

    // MARK: Suggested weight (v2)

    private fun sets(kg: Double, vararg reps: Int) = reps.map { SetValue(kg, it) }

    @Test
    fun `a suggestion adds a step when every set reached the target`() {
        assertEquals(
            WeightSuggestion(fromKg = 80.0, toKg = 82.5, reps = listOf(8, 8, 9)),
            weightSuggestion(sets(80.0, 8, 8, 9), targetReps = 8, unit = WeightUnit.Kg),
        )
        assertNull(weightSuggestion(sets(80.0, 8, 8, 7), targetReps = 8, unit = WeightUnit.Kg), "one set short of the target")
    }

    @Test
    fun `without a target no set may fall below the first`() {
        assertEquals(82.5, weightSuggestion(sets(80.0, 8, 8, 8), targetReps = null, unit = WeightUnit.Kg)?.toKg)
        assertEquals(
            82.5,
            weightSuggestion(sets(80.0, 8, 9), targetReps = 0, unit = WeightUnit.Kg)?.toKg,
            "a zero target is no target",
        )
        assertNull(weightSuggestion(sets(80.0, 8, 8, 7), targetReps = null, unit = WeightUnit.Kg))
    }

    @Test
    fun `no suggestion from one set, mixed weights or no weight`() {
        assertNull(weightSuggestion(sets(80.0, 8), targetReps = null, unit = WeightUnit.Kg))
        assertNull(weightSuggestion(sets(80.0, 8) + sets(82.5, 8), targetReps = null, unit = WeightUnit.Kg))
        assertNull(
            weightSuggestion(sets(0.0, 12, 12), targetReps = null, unit = WeightUnit.Kg),
            "bodyweight sets have nothing to add to",
        )
    }

    @Test
    fun `pound users step five pounds`() {
        val kg = SetInput.weightKg("135", WeightUnit.Lb)
        val suggestion = assertNotNull(weightSuggestion(sets(kg, 5, 5), targetReps = null, unit = WeightUnit.Lb))
        assertEquals("140", Fmt.weight(suggestion.toKg, WeightUnit.Lb, withUnit = false))
        assertEquals(kg, suggestion.fromKg)
    }

    @Test
    fun `the suggestion shows while an open set has the previous weight`() {
        val suggestion = WeightSuggestion(fromKg = 80.0, toKg = 82.5, reps = listOf(8, 8))
        assertTrue(showsSuggestion(suggestion, listOf(82.5, 80.0)))
        assertFalse(showsSuggestion(suggestion, listOf(82.5, 82.5)), "after Use")
        assertFalse(showsSuggestion(suggestion, emptyList()), "every set done")
    }

    @Test
    fun `previous normal rows leave out warm-ups and drop sets`() {
        val rows = PreviousRows.of(
            listOf(
                completed(0, SetKind.Warmup, 40.0, 10),
                completed(1, SetKind.Normal, 80.0, 8),
                completed(2, SetKind.Failure, 80.0, 6),
                completed(3, SetKind.Drop, 60.0, 6),
            ),
        )
        assertEquals(listOf(SetValue(80.0, 8), SetValue(80.0, 6)), rows.normal)
    }

    @Test
    fun `routine target reps come from the routine named like the workout`() {
        val routines = listOf(pushA(targetReps = 10))
        assertEquals(mapOf(BENCH to 10), routineTargetReps(routines, "Push A"))
        assertEquals(emptyMap(), routineTargetReps(routines, "Trening"), "an ad-hoc workout has no target")
    }

    @Test
    fun `use moves only the open working sets and the line goes`() = runTest {
        seedFinished("old", startedAt = now - 86_400_000L, rows = listOf(
            Row(SetKind.Warmup, 40.0, 10), Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 8), Row(SetKind.Drop, 60.0, 6),
        ))
        seedActive(rows = listOf(
            Row(SetKind.Warmup, 40.0, 10), Row(SetKind.Normal, 80.0, 8, done = true), Row(SetKind.Normal, 80.0, 8),
            Row(SetKind.Drop, 60.0, 6),
        ))
        val model = model(newSession())
        runCurrent()

        val section = model.state.value.exercises.single()
        assertEquals(82.5, section.suggestion?.toKg, "the drop set and the warm-up do not count")
        model.useSuggestion(section.id)
        runCurrent()

        assertEquals(
            listOf(40.0, 80.0, 82.5, 60.0),
            workouts.sets.value.filter { it.workoutExerciseId == section.id }.sortedBy { it.order }.map { it.weightKg },
            "done, warm-up and drop sets keep theirs",
        )
        assertNull(model.state.value.exercises.single().suggestion)
    }

    @Test
    fun `the routine target gates the suggestion`() = runTest {
        seedFinished("old", startedAt = now - 86_400_000L, rows = listOf(Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 8)))
        seedActive(rows = listOf(Row(SetKind.Normal, 80.0, 8), Row(SetKind.Normal, 80.0, 8)))
        val model = model(newSession(), routines = listOf(pushA(targetReps = 10)))
        runCurrent()

        assertNull(model.state.value.exercises.single().suggestion, "Push A asks for 10: 8 · 8 does not move the weight up")
    }

    // MARK: Fixtures

    private fun pushA(targetReps: Int) = RoutineWithItems(
        RoutineEntity(id = "push", name = "Push A"),
        listOf(
            RoutineItemWithExercise(
                RoutineItemEntity(routineId = "push", exerciseId = BENCH, order = 0, targetSets = 2, targetReps = targetReps),
                ExerciseEntity(id = BENCH, name = "Bench"),
            ),
        ),
    )

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

    private fun model(
        session: WorkoutSessionController,
        unit: WeightUnit = WeightUnit.Kg,
        routines: List<RoutineWithItems> = emptyList(),
    ) = ActiveWorkoutViewModel(
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
        routines = { routines },
    )

    private companion object {
        const val ACTIVE = "active"
        const val BENCH = "Barbell_Bench_Press_-_Medium_Grip"
    }
}
