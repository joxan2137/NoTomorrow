package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.rest.RestTimerController
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.AttendanceReporter
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.FakeAttendanceDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeScheduleDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.RecordService
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `ActiveWorkoutModel`'s finish flow (`ActiveWorkoutFlowTests.swift`) on the view model the tab
 * shell hosts: Finish stamps the end at once, "Edit sets" reopens, Done releases the session while
 * the summary keeps rendering, Discard deletes after the slide — and a model the session let go of
 * can neither reopen nor end anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModelTest {

    private val zone = ZoneOffset.UTC
    /** Monday 21 Sep 2026, 18:00 UTC. */
    private val monday = LocalDate.of(2026, 9, 21)
    private val now = monday.atTime(18, 0).toInstant(zone).toEpochMilli()

    private val workouts = FakeWorkoutDao()
    private val attendance = FakeAttendanceDao()
    private val schedule = FakeScheduleDao(GymScheduleEntity(weekdays = listOf(1, 3, 5)))
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

    /** What Finish sent to the backend (`AttendanceSync.report`). */
    private val reported = mutableListOf<Pair<LocalDate, AttendanceStatus>>()

    private fun model(session: WorkoutSessionController, at: Long = now) = ActiveWorkoutViewModel(
        workoutId = "w",
        workoutDao = workouts,
        recordService = RecordService(workouts),
        attendanceService = AttendanceService(attendance, schedule, FakeProfileDao(), zone),
        restTimer = restTimer,
        session = session,
        appPrefs = mockk<AppPrefs>(relaxed = true),
        strings = mockk<NtStrings>(relaxed = true),
        zone = zone,
        clock = { at },
        reportAttendance = AttendanceReporter { day, status -> reported += day to status },
    )

    @Test
    fun `finish stamps the end at once, shows the summary and marks the gym day`() = runTest {
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 1)
        val session = newSession()
        session.begin("w")
        val model = model(session)

        model.finish()
        runCurrent()

        assertEquals(now, workouts.workout("w")?.endedAt, "a kill on the summary can no longer stretch it")
        assertTrue(model.state.value.showsDone)
        assertTrue(session.showsSummary.value)
        assertEquals("w", session.activeWorkoutId.value, "the summary keeps its workout until Done")
        assertEquals(AttendanceStatus.Attended, attendance.rows.value.single().status)
        verify { restTimer.skip() }
    }

    @Test
    fun `finish counts the day the workout started, rest day or not, and reports it`() = runTest {
        // Sunday (a rest day) 22:30 → Monday 00:20.
        val sunday = monday.minusDays(1)
        val start = sunday.atTime(22, 30).toInstant(zone).toEpochMilli()
        val end = monday.atTime(0, 20).toInstant(zone).toEpochMilli()
        workouts.seed("w", startedAt = start, completed = 2)
        val session = newSession()
        session.begin("w")

        model(session, at = end).finish()
        runCurrent()

        val row = attendance.rows.value.single()
        assertEquals(sunday, java.time.Instant.ofEpochMilli(row.day).atZone(zone).toLocalDate())
        assertEquals(AttendanceStatus.Attended, row.status)
        assertEquals(listOf(sunday to AttendanceStatus.Attended), reported)
    }

    @Test
    fun `finishing an empty workout marks nothing attended`() = runTest {
        workouts.seed("w", startedAt = now - 60_000L)
        val session = newSession()
        session.begin("w")

        model(session).finish()
        runCurrent()

        assertEquals(now, workouts.workout("w")?.endedAt)
        assertTrue(attendance.rows.value.isEmpty())
        assertTrue(reported.isEmpty(), "nothing done, nothing reported")
    }

    @Test
    fun `edit sets puts the workout back in progress`() = runTest {
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 1)
        val session = newSession()
        session.begin("w")
        val model = model(session)
        model.finish()
        runCurrent()

        model.reopen()
        runCurrent()

        assertNull(workouts.workout("w")?.endedAt)
        assertFalse(model.state.value.showsDone)
        assertFalse(session.showsSummary.value)
        assertTrue(session.isWorkoutInProgressNow())
    }

    /** Finish with a set done, then "Edit sets" and every set unticked: the state `uncount` starts from. */
    private suspend fun TestScope.reopenedWithNothingDone(session: WorkoutSessionController): ActiveWorkoutViewModel {
        val model = model(session)
        model.finish()
        runCurrent()
        assertEquals(AttendanceStatus.Attended, attendance.rows.value.single().status)
        model.reopen()
        runCurrent()
        val entries = workouts.exercises.value.filter { it.workoutId == "w" }.map { it.id }.toSet()
        workouts.sets.value.filter { it.workoutExerciseId in entries && it.isCompleted }.forEach { model.uncomplete(it.id) }
        runCurrent()
        assertEquals(0, workouts.completedSetCount("w"))
        return model
    }

    @Test
    fun `a finish with every set unticked after edit sets gives the day back, here and on the server`() = runTest {
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 2)
        val session = newSession()
        session.begin("w")
        val model = reopenedWithNothingDone(session)

        model.finish()
        runCurrent()

        assertTrue(attendance.rows.value.isEmpty(), "today derives from the schedule again")
        assertEquals(
            listOf(monday to AttendanceStatus.Attended, monday to AttendanceStatus.Planned),
            reported,
            "the server has no delete: today's gym day is planned again",
        )
    }

    @Test
    fun `a discard after edit sets gives the day back`() = runTest {
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 1)
        val session = newSession()
        session.begin("w")
        val model = reopenedWithNothingDone(session)

        model.discard()
        runCurrent()

        assertTrue(attendance.rows.value.isEmpty())
        assertEquals(listOf(monday to AttendanceStatus.Attended, monday to AttendanceStatus.Planned), reported)
    }

    @Test
    fun `another finished workout that day keeps it attended`() = runTest {
        workouts.seed("morning", startedAt = now - 9 * 3_600_000L, endedAt = now - 8 * 3_600_000L, completed = 1)
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 1)
        val session = newSession()
        session.begin("w")
        val model = reopenedWithNothingDone(session)

        model.finish()
        runCurrent()

        assertEquals(AttendanceStatus.Attended, attendance.rows.value.single().status)
        assertEquals(listOf(monday to AttendanceStatus.Attended), reported, "nothing changed, nothing reported")
    }

    @Test
    fun `done lets go while the summary keeps rendering, and nothing reopens it`() = runTest {
        workouts.seed("w", startedAt = now - 3_600_000L, completed = 1)
        val session = newSession()
        session.begin("w")
        val model = model(session)
        model.finish()
        runCurrent()

        model.commitFinish()
        assertNull(session.activeWorkoutId.value)
        assertFalse(session.showsActiveWorkout.value)
        assertTrue(model.state.value.showsDone, "the summary stays for the slide down")

        // A back press while it slides away.
        model.reopen()
        runCurrent()
        assertEquals(now, workouts.workout("w")?.endedAt)
    }

    @Test
    fun `discard releases the session and deletes the row after the slide`() = runTest {
        workouts.seed("w", startedAt = now - 60_000L)
        val session = newSession()
        session.begin("w")
        val model = model(session)

        model.discard()
        assertNull(session.activeWorkoutId.value)
        verify { restTimer.skip() }

        advanceTimeBy(WorkoutSessionController.DISCARD_DELAY_MS + 1)
        runCurrent()
        assertNull(workouts.workout("w"))
        assertFalse(model.state.value.missing, "a released model stops reading and keeps its last state")
    }

    @Test
    fun `a row deleted elsewhere ends the session`() = runTest {
        workouts.seed("w", startedAt = now - 60_000L)
        val session = newSession()
        session.begin("w")
        session.collapse()
        val model = model(session)
        assertEquals("Push A", model.state.value.name)

        workouts.deleteWorkoutById("w")
        runCurrent()

        assertTrue(model.state.value.missing)
        assertNull(session.activeWorkoutId.value, "no mini bar for a workout that is gone")
    }

    @Test
    fun `the mini bar names the exercise the user is on`() = runTest {
        workouts.seed("w", startedAt = now - 60_000L, completed = 3, total = 3)
        val session = newSession()
        session.begin("w")

        val state = model(session).state.value

        assertEquals(1, state.exercises.size)
        assertEquals(state.exercises.single().id, state.currentExercise?.id, "all done: the last exercise")
    }
}
