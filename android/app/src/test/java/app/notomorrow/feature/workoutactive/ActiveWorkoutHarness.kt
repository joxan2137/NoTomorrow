package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
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
import app.notomorrow.util.NtStrings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A running workout on the fake DAOs for the active-workout feature tests (warm-ups, notes and
 * RPE, supersets): the view model reads Room through [workouts] and rests through a relaxed
 * [restTimer] mock; rest auto-start is on, as its default.
 */
internal class ActiveWorkoutHarness(private val scope: CoroutineScope) {

    val workouts = FakeWorkoutDao()
    val restTimer = mockk<RestTimerController>(relaxed = true) {
        every { state } returns MutableStateFlow(RestTimerState())
    }
    private val prefs = mockk<AppPrefs>(relaxed = true) {
        coEvery { restAutoStartOnce() } returns true
    }

    /** Inserts workout [id] with one entry per exercise id, [sets] open sets each (60 kg × 10); returns the entry ids. */
    suspend fun seed(
        id: String = "w",
        exerciseIds: List<String>,
        startedAt: Long = 0L,
        endedAt: Long? = null,
        sets: Int = 2,
        notes: String = "",
        supersetGroup: Int? = null,
    ): List<Long> {
        workouts.insertWorkout(WorkoutEntity(id = id, name = "W", startedAt = startedAt, endedAt = endedAt))
        return exerciseIds.mapIndexed { index, exerciseId ->
            workouts.insertWorkoutExerciseWithSets(
                WorkoutExerciseEntity(
                    workoutId = id,
                    exerciseId = exerciseId,
                    order = index,
                    notes = notes,
                    supersetGroup = supersetGroup,
                ),
                (0 until sets).map { row ->
                    SetEntryEntity(
                        workoutExerciseId = 0,
                        order = row,
                        weightKg = 60.0,
                        reps = 10,
                        completedAt = if (endedAt != null) startedAt + row else null,
                    )
                },
            )
        }
    }

    fun model(workoutId: String = "w", unit: WeightUnit = WeightUnit.Kg, defaultRest: Int = 90): ActiveWorkoutViewModel {
        val zone = ZoneOffset.UTC
        return ActiveWorkoutViewModel(
            workoutId = workoutId,
            workoutDao = workouts,
            recordService = RecordService(workouts),
            attendanceService = AttendanceService(
                FakeAttendanceDao(),
                FakeScheduleDao(GymScheduleEntity(weekdays = listOf(1, 3, 5))),
                FakeProfileDao(),
                zone,
            ),
            restTimer = restTimer,
            session = WorkoutSessionController(
                object : WorkoutSessionController.Store {
                    override suspend fun activeWorkoutId(): String? = null
                    override suspend fun setActiveWorkoutId(id: String?) = Unit
                    override suspend fun discarding(): Set<String> = emptySet()
                    override suspend fun setDiscarding(ids: Set<String>) = Unit
                },
                workouts,
                scope,
            ),
            appPrefs = prefs,
            strings = mockk<NtStrings>(relaxed = true),
            zone = zone,
            clock = { 1_000_000L },
            units = { unit },
            defaultRest = { defaultRest },
        )
    }
}
