package app.notomorrow.feature.settings

import app.notomorrow.data.db.wipeUserData
import app.notomorrow.service.WorkoutSessionController
import kotlinx.coroutines.CancellationException

/**
 * The on-device half of "Delete account and data" (`SettingsModel.deleteAccount`), in iOS's order:
 * the rest timer and the workout session let go first, then every user-owned row goes
 * ([wipeUserData], one transaction).
 *
 * Without the first two, a collapsed workout outlived its rows: the session kept its id (and
 * `nt.activeWorkoutId`), so the next account got the old workout's mini bar and a "Resume" that
 * led nowhere, and a running rest kept its alarm and notification.
 *
 * [afterWipe] runs only once the rows are gone — iOS's `LocalDataWipe` clears
 * `nt.routines.seeded` there, so the next setup seeds the starter routines again.
 */
internal object AccountDataReset {

    /** `false` when the wipe failed: the transaction rolled back and every row is still there. */
    suspend fun run(
        stopRestTimer: () -> Unit,
        session: WorkoutSessionController,
        wipe: suspend () -> Unit,
        afterWipe: suspend () -> Unit = {},
    ): Boolean {
        stopRestTimer()
        session.end()
        val wiped = try {
            wipe()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (wiped) afterWipe()
        return wiped
    }
}
