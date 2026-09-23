package app.notomorrow.feature.workoutactive

import app.notomorrow.app.AppState
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.RestAlertPrompt
import app.notomorrow.feature.workout.WorkoutExerciseUi
import app.notomorrow.feature.workout.WorkoutMiniBarState
import app.notomorrow.rest.RestEndAlert
import app.notomorrow.rest.RestTimerNotifier
import app.notomorrow.rest.restEndAlert
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The pure rules around the minimized workout (`ActiveWorkoutFlowTests.swift`): what the mini bar
 * says, which exercise it names, how a rest that runs out reaches the user, and where a tapped
 * rest notification lands.
 */
class ActiveWorkoutFlowTest {

    private val startedAt = 1_000_000L

    // MARK: - Mini bar

    @Test
    fun `training shows the elapsed time and the current exercise`() {
        val state = WorkoutMiniBarState.of(
            startedAt = startedAt,
            now = startedAt + (42 * 60 + 10) * 1000L + 900,
            restEnd = null,
            restTotal = 90,
            upNextName = "Squat",
            currentName = "Bench press",
        )

        assertFalse(state.isResting)
        assertEquals("42:10", state.elapsed)
        assertEquals("Bench press", state.exerciseName)
        assertEquals(0.0, state.restFraction)
    }

    @Test
    fun `resting counts down and names what is up next`() {
        val now = startedAt + 600_000L
        val state = WorkoutMiniBarState.of(
            startedAt = startedAt,
            now = now,
            restEnd = now + 45_000L,
            restTotal = 90,
            upNextName = "Squat",
            currentName = "Bench press",
        )

        assertTrue(state.isResting)
        assertEquals(45.0, state.restRemaining)
        assertEquals(0.5, state.restFraction, 1e-9)
        assertEquals("Squat", state.exerciseName)
    }

    @Test
    fun `a rest without an up-next name falls back to the current exercise`() {
        val now = startedAt + 60_000L
        val state = WorkoutMiniBarState.of(startedAt, now, now + 10_000L, 90, upNextName = "", currentName = "Row")
        assertEquals("Row", state.exerciseName)
    }

    @Test
    fun `talkback's state does not tick with the rest countdown`() {
        val now = startedAt + 600_000L
        val states = (0 until 5).map { second ->
            WorkoutMiniBarState.of(
                startedAt = startedAt,
                now = now + second * 1000L,
                restEnd = now + 45_000L,
                restTotal = 90,
                upNextName = "Squat",
                currentName = "Bench press",
            )
        }
        assertEquals(listOf("Przerwa, Squat"), states.map { it.accessibilityValue("Przerwa") }.distinct())
        val noName = WorkoutMiniBarState.of(startedAt, now, now + 45_000L, 90, upNextName = "", currentName = null)
        assertEquals("Przerwa", noName.accessibilityValue("Przerwa"))
        val training = WorkoutMiniBarState.of(startedAt, now, restEnd = null, restTotal = 90, upNextName = "", currentName = "Row")
        assertEquals("Row", training.accessibilityValue("Przerwa"))
    }

    @Test
    fun `a rest that has run out is training again`() {
        val now = startedAt + 60_000L
        val state = WorkoutMiniBarState.of(startedAt, now, restEnd = now, restTotal = 90, upNextName = "Squat", currentName = null)

        assertFalse(state.isResting)
        assertNull(state.restRemaining)
        assertEquals("", state.exerciseName)
    }

    @Test
    fun `the current exercise is the open one, else the first with work, else the last`() {
        val done = exercise(1, done = true)
        val open = exercise(2)
        val later = exercise(3)

        assertEquals(3L, ActiveWorkoutViewModel.currentExercise(listOf(done, open, later), expandedId = 3)?.id)
        assertEquals(2L, ActiveWorkoutViewModel.currentExercise(listOf(done, open, later), expandedId = null)?.id)
        assertEquals(2L, ActiveWorkoutViewModel.currentExercise(listOf(done, open, later), expandedId = 99)?.id)
        assertEquals(1L, ActiveWorkoutViewModel.currentExercise(listOf(done), expandedId = null)?.id)
        assertNull(ActiveWorkoutViewModel.currentExercise(emptyList(), expandedId = null))
    }

    // MARK: - Rest end

    @Test
    fun `the end of a rest is a haptic over the workout, a banner over the tabs, a notification away`() {
        assertEquals(RestEndAlert.Haptic, restEndAlert(appInForeground = true, workoutOnScreen = true))
        assertEquals(RestEndAlert.HapticAndNotification, restEndAlert(appInForeground = true, workoutOnScreen = false))
        assertEquals(RestEndAlert.Notification, restEndAlert(appInForeground = false, workoutOnScreen = true))
        assertEquals(RestEndAlert.Notification, restEndAlert(appInForeground = false, workoutOnScreen = false))
    }

    @Test
    fun `rest notifications open the workout, the running one with its rest sheet`() {
        assertEquals(AppState.Route.RestTimer, RestTimerNotifier.RUNNING_ROUTE)
        assertEquals(AppState.Route.ActiveWorkout, RestTimerNotifier.DONE_ROUTE)
        assertEquals(AppState.Route.RestTimer, AppState.Route.from(RestTimerNotifier.RUNNING_ROUTE.wire))
        assertEquals(AppState.Route.ActiveWorkout, AppState.Route.from(RestTimerNotifier.DONE_ROUTE.wire))
    }

    // MARK: - Exact-alarm prompt

    @Test
    fun `the prompt asks once, and only when something is missing`() {
        assertTrue(RestAlertPrompt.shouldAsk(exactAlarms = false, notifications = true, alreadyAsked = false))
        assertTrue(RestAlertPrompt.shouldAsk(exactAlarms = true, notifications = false, alreadyAsked = false))
        assertFalse(RestAlertPrompt.shouldAsk(exactAlarms = true, notifications = true, alreadyAsked = false))
        assertFalse(RestAlertPrompt.shouldAsk(exactAlarms = false, notifications = false, alreadyAsked = true))
    }

    @Test
    fun `allow fixes notifications before exact alarms`() {
        assertEquals(
            RestAlertPrompt.Step.RequestNotifications,
            RestAlertPrompt.nextStep(notificationsEnabled = false, permissionGranted = false),
            "never asked (a fresh install): the system dialog, not the settings page",
        )
        assertEquals(
            RestAlertPrompt.Step.NotificationSettings,
            RestAlertPrompt.nextStep(notificationsEnabled = false, permissionGranted = true),
            "granted but switched off for the app: only its settings page can turn it on",
        )
        assertEquals(
            RestAlertPrompt.Step.ExactAlarmSettings,
            RestAlertPrompt.nextStep(notificationsEnabled = true, permissionGranted = true),
        )
    }

    @Test
    fun `the dialog's answer decides what opens next`() {
        assertEquals(
            RestAlertPrompt.Step.ExactAlarmSettings,
            RestAlertPrompt.afterRequest(granted = true, canAskAgain = false, exactAlarms = false),
            "allowed: on to Alarms & reminders",
        )
        assertNull(RestAlertPrompt.afterRequest(granted = true, canAskAgain = false, exactAlarms = true))
        assertNull(
            RestAlertPrompt.afterRequest(granted = false, canAskAgain = true, exactAlarms = false),
            "a first no is respected",
        )
        assertEquals(
            RestAlertPrompt.Step.NotificationSettings,
            RestAlertPrompt.afterRequest(granted = false, canAskAgain = false, exactAlarms = false),
            "denied for good: the app's notification page is the only way",
        )
        assertEquals(
            RestAlertPrompt.Step.ExactAlarmSettings,
            RestAlertPrompt.afterNotificationSettings(notificationsEnabled = true, exactAlarms = false),
        )
        assertNull(RestAlertPrompt.afterNotificationSettings(notificationsEnabled = false, exactAlarms = false))
        assertNull(RestAlertPrompt.afterNotificationSettings(notificationsEnabled = true, exactAlarms = true))
    }

    private fun exercise(id: Long, done: Boolean = false) = WorkoutExerciseUi(
        id = id,
        exerciseId = "ex$id",
        name = "Exercise $id",
        primaryMuscle = null,
        restSeconds = 90,
        setCount = 3,
        isDone = done,
        last = null,
        sets = emptyList(),
    )
}
