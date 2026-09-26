package app.notomorrow.feature.settings

import app.notomorrow.data.db.NoTomorrowDatabase
import app.notomorrow.data.db.UserDataWipe
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.WorkoutSessionController
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The on-device half of "Delete account and data" with a workout collapsed and its rest running —
 * the state the iOS build was caught leaving behind: the next account got the old workout back
 * as the one in progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDataResetTest {

    /** `nt.activeWorkoutId` / `nt.workout.discarding` for one test. */
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

    /** The real wipe steps over the in-memory workout tables (the other DAOs are relaxed mocks). */
    private val db = mockk<NoTomorrowDatabase>(relaxed = true) {
        every { workoutDao() } returns dao
    }

    private suspend fun TestScope.newSession(): WorkoutSessionController =
        WorkoutSessionController(store, dao, backgroundScope).also { it.awaitRestored() }

    @Test
    fun `a collapsed workout and its rest do not outlive the account`() = runTest {
        dao.seed("old", startedAt = 1_000, completed = 1)
        val session = newSession()
        session.begin("old")
        session.collapse()
        runCurrent()
        assertEquals("old", store.active)

        var restStopped = false
        var sessionLetGoFirst = false
        var seededCleared = false
        val wiped = AccountDataReset.run(
            stopRestTimer = { restStopped = true },
            session = session,
            wipe = {
                sessionLetGoFirst = session.activeWorkoutId.value == null
                for (step in UserDataWipe.steps) step.run(db)
            },
            afterWipe = { seededCleared = true },
        )

        assertTrue(wiped)
        assertTrue(seededCleared, "nt.routines.seeded is cleared, so the next setup seeds again")
        assertTrue(restStopped, "the rest alarm and notification are cancelled")
        assertTrue(sessionLetGoFirst, "the session lets go before the rows go, as on iOS")
        assertNull(session.activeWorkoutId.value)
        assertFalse(session.isWorkoutInProgressNow(), "no mini bar, no Resume")
        assertFalse(session.showsActiveWorkout.value)
        runCurrent()
        assertNull(store.active, "nt.activeWorkoutId is cleared")
        assertTrue(dao.workouts.value.isEmpty())
        assertTrue(dao.sets.value.isEmpty())

        // Nothing to adopt now, and nothing comes back on the next launch.
        assertNull(session.activeWorkout())
        val relaunched = newSession()
        relaunched.restore()
        assertNull(relaunched.activeWorkoutId.value)
        assertFalse(relaunched.isWorkoutInProgressNow())
    }

    @Test
    fun `a failed wipe is reported and still lets go of the session`() = runTest {
        dao.seed("old", startedAt = 1_000, completed = 1)
        val session = newSession()
        session.begin("old")
        session.collapse()

        var restStopped = false
        var seededCleared = false
        val wiped = AccountDataReset.run(
            stopRestTimer = { restStopped = true },
            session = session,
            wipe = { throw IllegalStateException("FOREIGN KEY constraint failed") },
            afterWipe = { seededCleared = true },
        )

        assertFalse(wiped)
        assertFalse(seededCleared, "the routines are still there, so they stay seeded")
        assertTrue(restStopped)
        assertNull(session.activeWorkoutId.value)
    }
}
