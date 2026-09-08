package app.notomorrow.feature.dashboard

import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import app.notomorrow.model.SetKind
import app.notomorrow.service.Days
import app.notomorrow.service.DayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The pure half of `DashboardViewModel` — the `DashboardModel` statics and the
 * `DashboardScreen` computed properties (`Features/Dashboard/DashboardModel.swift:88-100`,
 * `DashboardView.swift:161-185`). No Room, no Android.
 */
class DashboardDerivationsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val today: LocalDate = LocalDate.of(2026, 9, 2) // a Wednesday
    private val locale: Locale = Locale.ENGLISH

    // MARK: - suggestedRoutine

    @Test
    fun `suggested routine rotates by finished count`() {
        val routines = listOf(routine("a"), routine("b"), routine("c"))
        assertEquals("a", DashboardViewModel.suggestedRoutine(routines, 0)?.id)
        assertEquals("b", DashboardViewModel.suggestedRoutine(routines, 1)?.id)
        assertEquals("c", DashboardViewModel.suggestedRoutine(routines, 2)?.id)
        assertEquals("a", DashboardViewModel.suggestedRoutine(routines, 3)?.id)
    }

    @Test
    fun `suggested routine is null without routines`() {
        assertNull(DashboardViewModel.suggestedRoutine(emptyList(), 7))
    }

    // MARK: - lastCompletedWeight

    @Test
    fun `last completed weight ignores warmups and takes the newest set`() {
        val rows = listOf(
            completedSet(weightKg = 60.0, completedAt = 300L, kind = SetKind.Normal),
            completedSet(weightKg = 200.0, completedAt = 900L, kind = SetKind.Warmup),
            completedSet(weightKg = 80.0, completedAt = 500L, kind = SetKind.Drop),
        )
        assertEquals(80.0, DashboardViewModel.lastCompletedWeight(rows)!!, 0.0001)
    }

    @Test
    fun `last completed weight is null when only warmups exist`() {
        val rows = listOf(completedSet(weightKg = 40.0, completedAt = 1L, kind = SetKind.Warmup))
        assertNull(DashboardViewModel.lastCompletedWeight(rows))
    }

    // MARK: - Fuel

    @Test
    fun `fuel totals sum every entry`() {
        val totals = FuelTotals.of(
            listOf(
                meal(kcal = 500.0, protein = 40.0, carbs = 50.0, fat = 12.0),
                meal(kcal = 250.5, protein = 10.0, carbs = 20.0, fat = 3.0),
            )
        )
        assertEquals(750.5, totals.kcal, 0.0001)
        assertEquals(50.0, totals.protein, 0.0001)
        assertEquals(70.0, totals.carbs, 0.0001)
        assertEquals(15.0, totals.fat, 0.0001)
    }

    @Test
    fun `fuel goals fall back to the onboarding defaults`() {
        assertEquals(FuelGoals(2600.0, 180.0, 300.0, 80.0), FuelGoals.of(null))
        val profile = UserProfileEntity(
            name = "Kuba",
            calorieGoal = 3000,
            proteinGoalG = 200,
            carbsGoalG = 320,
            fatGoalG = 90,
        )
        assertEquals(FuelGoals(3000.0, 200.0, 320.0, 90.0), FuelGoals.of(profile))
    }

    // MARK: - build()

    @Test
    fun `today session on a gym day is marked today and carries my confirmation`() {
        val schedule = GymScheduleEntity(weekdays = listOf(1, 3, 5), defaultMinuteOfDay = 18 * 60)
        val mine = record(today, Participant.Me, AttendanceStatus.Confirmed, updatedAt = 111L)
        val state = DashboardViewModel.build(
            basis = basis(schedule = schedule, routines = listOf(routine("push"))),
            live = live(records = listOf(mine)),
            local = local(),
            now = Days.at(today, 9 * 60, zone),
            zone = zone,
            locale = locale,
        )
        assertEquals(today, state.session?.date)
        assertTrue(state.session!!.isToday)
        assertEquals(18 * 60, state.session!!.minuteOfDay)
        assertEquals(DayState.Confirmed, state.myState)
        assertEquals(111L, state.myTime?.toEpochMilli())
        assertEquals("push", state.routineName)
        assertEquals(7, state.week.size)
    }

    @Test
    fun `a session more than two hours past rolls to the next gym day`() {
        val schedule = GymScheduleEntity(weekdays = listOf(1, 3, 5), defaultMinuteOfDay = 18 * 60)
        val state = DashboardViewModel.build(
            basis = basis(schedule = schedule),
            live = live(),
            // 21:30 on Wednesday — past 18:00 + 2 h grace.
            now = Days.at(today, 21 * 60 + 30, zone),
            local = local(),
            zone = zone,
            locale = locale,
        )
        assertEquals(today.plusDays(2), state.session?.date) // Friday
        assertFalse(state.session!!.isToday)
    }

    @Test
    fun `no schedule means no session`() {
        val state = DashboardViewModel.build(
            basis = basis(schedule = null),
            live = live(),
            local = local(),
            now = Days.at(today, 9 * 60, zone),
            zone = zone,
            locale = locale,
        )
        assertNull(state.session)
    }

    @Test
    fun `signed out on the real backend counts as solo`() {
        val state = DashboardViewModel.build(
            basis = basis(pairing = BroPairingEntity(partnerId = "p", partnerName = "Tomek", myCode = "NT-1234")),
            live = live(),
            local = local(partnerName = "Tomek", needsSignIn = true),
            zone = zone,
            locale = locale,
        )
        assertFalse(state.isPaired)
        assertNull(state.partnerName)
    }

    @Test
    fun `a local pairing row alone is enough to be paired`() {
        val state = DashboardViewModel.build(
            basis = basis(pairing = BroPairingEntity(partnerId = "p", partnerName = "Tomek", myCode = "NT-1234")),
            live = live(),
            local = local(partnerName = null, needsSignIn = false),
            zone = zone,
            locale = locale,
        )
        assertTrue(state.isPaired)
        assertEquals("Tomek", state.partnerName)
    }

    @Test
    fun `avatar initial falls back to an en dash`() {
        assertEquals(
            DashboardUiState.FALLBACK_INITIAL,
            DashboardViewModel.build(basis(profile = null), live(), local(), zone = zone, locale = locale)
                .avatarInitial,
        )
        assertEquals(
            "Kuba",
            DashboardViewModel.build(
                basis(profile = UserProfileEntity(name = "  Kuba  ")),
                live(),
                local(),
                zone = zone,
                locale = locale,
            ).avatarInitial,
        )
        assertEquals(
            DashboardUiState.FALLBACK_INITIAL,
            DashboardViewModel.build(
                basis(profile = UserProfileEntity(name = "   ")),
                live(),
                local(),
                zone = zone,
                locale = locale,
            ).avatarInitial,
        )
    }

    @Test
    fun `last session flattens duration and pr chips`() {
        val started = Days.at(today, 17 * 60, zone).toEpochMilli()
        val ended = started + 52 * 60 * 1000
        val graph = WorkoutWithExercises(
            workout = WorkoutEntity(id = "w", name = "Legs", startedAt = started, endedAt = ended),
            exercises = listOf(
                WorkoutExerciseWithSets(
                    workoutExercise = WorkoutExerciseEntity(id = 1, workoutId = "w", exerciseId = null, order = 0),
                    exercise = null,
                    sets = listOf(
                        SetEntryEntity(id = 1, workoutExerciseId = 1, order = 1, weightKg = 100.0, reps = 5, completedAt = ended, isPR = true),
                        SetEntryEntity(id = 2, workoutExerciseId = 1, order = 0, weightKg = 90.0, reps = 5, completedAt = ended),
                    ),
                )
            ),
        )
        val state = DashboardViewModel.build(
            basis = basis(),
            live = live(lastWorkout = graph),
            local = local(),
            zone = zone,
            locale = locale,
        )
        val last = state.lastSession!!
        assertEquals("Legs", last.name)
        assertEquals(52.0 * 60, last.durationSeconds, 0.001)
        assertEquals(1, last.prCount)
        assertEquals(1, last.prSets.size)
        assertEquals(100.0, last.prSets.first().weightKg, 0.0001)
    }

    // MARK: - Fixtures

    private fun routine(id: String) = RoutineEntity(id = id, name = id, order = 0)

    private fun completedSet(weightKg: Double, completedAt: Long, kind: SetKind) = CompletedSetRow(
        setId = completedAt,
        setOrder = 0,
        kind = kind,
        weightKg = weightKg,
        reps = 5,
        completedAt = completedAt,
        isPR = false,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = 1,
        workoutExerciseOrder = 0,
        exerciseId = "ex",
        workoutId = "w",
        workoutName = "W",
        workoutStartedAt = 0L,
        workoutEndedAt = null,
    )

    private fun meal(kcal: Double, protein: Double, carbs: Double, fat: Double) = MealEntryEntity(
        id = "m$kcal",
        day = Days.millis(today, zone),
        slot = MealSlot.Lunch,
        grams = 100.0,
        kcal = kcal,
        proteinG = protein,
        carbsG = carbs,
        fatG = fat,
    )

    private fun record(
        day: LocalDate,
        participant: Participant,
        status: AttendanceStatus,
        updatedAt: Long,
    ) = AttendanceRecordEntity(
        id = "$day-$participant",
        day = Days.millis(day, zone),
        participant = participant,
        scheduledMinuteOfDay = 18 * 60,
        status = status,
        updatedAt = updatedAt,
    )

    private fun basis(
        profile: UserProfileEntity? = UserProfileEntity(name = "Kuba"),
        schedule: GymScheduleEntity? = null,
        pairing: BroPairingEntity? = null,
        routines: List<RoutineEntity> = emptyList(),
        finishedCount: Int = 0,
    ) = Basis(profile, schedule, pairing, routines, finishedCount)

    private fun live(
        records: List<AttendanceRecordEntity> = emptyList(),
        meals: List<MealEntryEntity> = emptyList(),
        lastWorkout: WorkoutWithExercises? = null,
        hasActiveWorkout: Boolean = false,
    ) = Live(today, records, meals, lastWorkout, hasActiveWorkout)

    private fun local(
        partnerName: String? = null,
        needsSignIn: Boolean = false,
        isConfirming: Boolean = false,
        showsCantMakeIt: Boolean = false,
    ) = Local(partnerName, needsSignIn, isConfirming, showsCantMakeIt)
}
