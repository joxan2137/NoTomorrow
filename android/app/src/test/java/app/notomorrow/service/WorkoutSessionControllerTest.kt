package app.notomorrow.service

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `Services/WorkoutSessionController.swift` (`WorkoutSessionTests.swift` on iOS): the single
 * source of truth for the workout in progress — collapse and expand, persistence across a
 * relaunch, the summary, adoption, the delayed discard and the launch-time orphan repair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSessionControllerTest {

    /** `UserDefaults` for one test. */
    private class MemoryStore(
        var active: String? = null,
        var discarding: Set<String> = emptySet(),
    ) : WorkoutSessionController.Store {
        override suspend fun activeWorkoutId(): String? = active
        override suspend fun setActiveWorkoutId(id: String?) { active = id }
        override suspend fun discarding(): Set<String> = discarding
        override suspend fun setDiscarding(ids: Set<String>) { discarding = ids }
    }

    private val dao = FakeWorkoutDao()
    private val store = MemoryStore()

    private suspend fun TestScope.newSession(): WorkoutSessionController =
        WorkoutSessionController(store, dao, backgroundScope).also { it.awaitRestored() }

    @Test
    fun `collapse keeps the workout, expand brings it back, end lets go`() = runTest {
        dao.seed("w1", startedAt = 1_000)
        val session = newSession()

        session.begin("w1")
        assertEquals("w1", session.activeWorkoutId.value)
        assertTrue(session.showsActiveWorkout.value)

        session.collapse()
        assertEquals("w1", session.activeWorkoutId.value)
        assertFalse(session.showsActiveWorkout.value)
        assertTrue(session.isWorkoutInProgressNow())

        session.expand(restSheet = true)
        assertTrue(session.showsActiveWorkout.value)
        assertTrue(session.wantsRestSheet.value)
        session.consumeRestSheet()
        assertFalse(session.wantsRestSheet.value)

        session.end()
        assertNull(session.activeWorkoutId.value)
        assertFalse(session.showsActiveWorkout.value)
        assertFalse(session.isWorkoutInProgressNow())
        runCurrent()
        assertNull(store.active)
    }

    @Test
    fun `expand does nothing without a workout`() = runTest {
        val session = newSession()
        session.expand(restSheet = true)
        assertFalse(session.showsActiveWorkout.value)
        assertFalse(session.wantsRestSheet.value)
    }

    @Test
    fun `the workout survives a relaunch, collapsed`() = runTest {
        dao.seed("w1", startedAt = 1_000)
        newSession().begin("w1")
        runCurrent()
        assertEquals("w1", store.active)

        val relaunched = newSession()
        relaunched.restore()
        assertEquals("w1", relaunched.activeWorkoutId.value)
        assertFalse(relaunched.showsActiveWorkout.value, "a cold start shows the mini bar only")
        assertTrue(relaunched.isWorkoutInProgress.first())
    }

    @Test
    fun `the summary is not in progress and keeps the finished workout`() = runTest {
        dao.seed("w1", startedAt = 1_000, completed = 1)
        dao.seed("other", startedAt = 500)
        val session = newSession()
        session.begin("w1")

        session.setShowsSummary(true)
        dao.finishWorkout("w1", 9_000)

        assertNull(session.activeWorkout(), "nothing to resume on the summary")
        assertEquals("w1", session.activeWorkoutId.value, "no other workout is adopted meanwhile")
        assertFalse(session.isWorkoutInProgressNow())
        assertFalse(session.isWorkoutInProgress.first())
        assertEquals("w1", session.workout()?.id)
    }

    @Test
    fun `a workout killed on its summary is let go on the next launch`() = runTest {
        dao.seed("w1", startedAt = 1_000, endedAt = 5_000, completed = 2)
        store.active = "w1"

        val relaunched = newSession()
        relaunched.restore()

        assertNull(relaunched.activeWorkoutId.value)
        assertEquals(5_000L, dao.workout("w1")?.endedAt, "Finish stamped the end; the relaunch keeps it")
        runCurrent()
        assertNull(store.active)
    }

    @Test
    fun `a lost session adopts the newest unfinished workout`() = runTest {
        dao.seed("older", startedAt = 1_000)
        dao.seed("newer", startedAt = 2_000)
        val session = newSession()

        assertEquals("newer", session.activeWorkout()?.id)
        assertEquals("newer", session.activeWorkoutId.value)
        assertFalse(session.showsActiveWorkout.value)
    }

    @Test
    fun `discard lets go at once, is never adopted, and deletes after the slide`() = runTest {
        dao.seed("w1", startedAt = 1_000)
        val session = newSession()
        session.begin("w1")

        session.discard("w1")
        assertNull(session.activeWorkoutId.value)
        assertFalse(session.showsActiveWorkout.value)
        runCurrent()
        assertEquals(setOf("w1"), store.discarding)
        assertNotNull(dao.workout("w1"), "the row outlives the slide")
        assertNull(session.activeWorkout(), "a workout on its way out is never adopted")

        advanceTimeBy(WorkoutSessionController.DISCARD_DELAY_MS + 1)
        runCurrent()
        assertNull(dao.workout("w1"))
        assertTrue(dao.sets.value.isEmpty(), "its sets cascade")
        assertEquals(emptySet(), store.discarding)
    }

    @Test
    fun `an interrupted discard is finished on the next launch`() = runTest {
        dao.seed("empty", startedAt = 1_000)
        dao.seed("logged", startedAt = 2_000, completed = 1)
        store.discarding = setOf("empty", "logged")
        store.active = "empty"

        val relaunched = newSession()
        relaunched.restore()

        assertNull(dao.workout("empty"))
        assertNotNull(dao.workout("logged"), "a workout with a completed set is never deleted")
        assertEquals("logged", relaunched.activeWorkoutId.value)
        assertEquals(emptySet(), store.discarding)
    }

    @Test
    fun `orphans end at their last set or go, and the session's workout stays`() = runTest {
        dao.seed("mine", startedAt = 1_000)
        dao.seed("logged", startedAt = 2_000, completed = 2)
        dao.seed("empty", startedAt = 3_000)
        store.active = "mine"

        val session = newSession()
        session.restore()

        assertEquals("mine", session.activeWorkoutId.value)
        assertNull(dao.workout("mine")?.endedAt)
        assertEquals(2_000L + 2 * 60_000L, dao.workout("logged")?.endedAt)
        assertNull(dao.workout("empty"))
    }

    @Test
    fun `without a session the newest unfinished workout is kept`() = runTest {
        dao.seed("old", startedAt = 1_000, completed = 1)
        dao.seed("new", startedAt = 2_000)

        val session = newSession()
        session.restore()

        assertEquals("new", session.activeWorkoutId.value)
        assertEquals(1_000L + 60_000L, dao.workout("old")?.endedAt)
        assertEquals(1, dao.activeWorkouts().size)
    }

    @Test
    fun `a new workout clears the previous one's summary and rest request`() = runTest {
        dao.seed("w1", startedAt = 1_000)
        dao.seed("w2", startedAt = 2_000)
        val session = newSession()
        session.begin("w1")
        session.expand(restSheet = true)
        session.setShowsSummary(true)

        session.begin("w2")

        assertEquals("w2", session.activeWorkoutId.value)
        assertFalse(session.showsSummary.value)
        assertFalse(session.wantsRestSheet.value)
        assertTrue(session.isWorkoutInProgressNow())
    }

    @Test
    fun `in progress needs a workout and no summary`() {
        assertTrue(WorkoutSessionController.isInProgress("w", showsSummary = false))
        assertFalse(WorkoutSessionController.isInProgress("w", showsSummary = true))
        assertFalse(WorkoutSessionController.isInProgress(null, showsSummary = false))
    }
}
