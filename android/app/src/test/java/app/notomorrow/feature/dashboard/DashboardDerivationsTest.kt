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
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.bro.BroDerived
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
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
    fun `suggested routine follows the one done most recently`() {
        val routines = listOf(routine("a", "Push A"), routine("b", "Pull A"), routine("c", "Legs"))
        assertEquals("b", DashboardViewModel.suggestedRoutine(routines, listOf("Push A"))?.id)
        assertEquals("c", DashboardViewModel.suggestedRoutine(routines, listOf("Pull A", "Push A"))?.id)
        assertEquals("a", DashboardViewModel.suggestedRoutine(routines, listOf("Legs"))?.id)
    }

    @Test
    fun `ad-hoc workouts do not shift the rotation`() {
        val routines = listOf(routine("a", "Push A"), routine("b", "Pull A"))
        assertEquals("b", DashboardViewModel.suggestedRoutine(routines, listOf("Workout", "Trening", "Push A"))?.id)
    }

    @Test
    fun `suggested routine starts at the first without history`() {
        val routines = listOf(routine("a", "Push A"), routine("b", "Pull A"))
        assertEquals("a", DashboardViewModel.suggestedRoutine(routines, emptyList())?.id)
        assertEquals("a", DashboardViewModel.suggestedRoutine(routines, listOf("Workout"))?.id)
    }

    @Test
    fun `suggested routine is null without routines`() {
        assertNull(DashboardViewModel.suggestedRoutine(emptyList(), listOf("Push A")))
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

    // MARK: - Can't make it

    @Test
    fun `cant make it is offered only for a session still ahead today`() {
        assertTrue(offersCantMakeIt(sessionIsToday = true, myState = DayState.Planned, trainedToday = false))
        assertTrue(offersCantMakeIt(sessionIsToday = true, myState = DayState.Confirmed, trainedToday = false))
        assertFalse("not on another day", offersCantMakeIt(false, DayState.Planned, false))
        assertFalse("not once I am out", offersCantMakeIt(true, DayState.Cancelled(null), false))
        assertFalse("not once I am out", offersCantMakeIt(true, DayState.Missed, false))
        assertFalse("not once today is attended", offersCantMakeIt(true, DayState.Attended, false))
        assertFalse("not once I trained today", offersCantMakeIt(true, DayState.Confirmed, true))
    }

    @Test
    fun `a workout trained today reaches the card`() {
        val state = DashboardViewModel.build(
            basis = basis(),
            live = live(trainedToday = true),
            local = local(),
            zone = zone,
            locale = locale,
        )
        assertTrue(state.trainedToday)
        assertFalse(DashboardViewModel.build(basis(), live(), local(), zone = zone, locale = locale).trainedToday)
    }

    // MARK: - Today's session trained

    /** Push A finished this morning with a ticked set; today is a gym day at 18:00. */
    private fun trainedThisMorning(name: String = "Push A"): WorkoutWithExercises {
        val started = Days.at(today, 8 * 60 + 30, zone).toEpochMilli()
        val ended = started + 45 * 60 * 1000
        return WorkoutWithExercises(
            workout = WorkoutEntity(id = "w", name = name, startedAt = started, endedAt = ended),
            exercises = listOf(
                WorkoutExerciseWithSets(
                    workoutExercise = WorkoutExerciseEntity(id = 1, workoutId = "w", exerciseId = null, order = 0),
                    exercise = null,
                    sets = listOf(SetEntryEntity(id = 1, workoutExerciseId = 1, order = 0, weightKg = 30.0, reps = 10, completedAt = ended)),
                )
            ),
        )
    }

    @Test
    fun `after training today the card names today's workout and says it is done`() {
        val schedule = GymScheduleEntity(weekdays = listOf(1, 3, 5), defaultMinuteOfDay = 18 * 60)
        val routines = listOf(routine("push", "Push A"), routine("pull", "Pull A"))
        val workout = trainedThisMorning()
        val state = DashboardViewModel.build(
            basis = basis(schedule = schedule, routines = routines, recentRoutineWorkoutNames = listOf("Push A")),
            live = live(
                records = listOf(record(today, Participant.Me, AttendanceStatus.Attended, updatedAt = 222L)),
                lastWorkout = workout,
                trainedToday = true,
            ),
            local = local(partnerName = "Tomek"),
            now = Days.at(today, 9 * 60 + 22, zone),
            zone = zone,
            locale = locale,
        )
        assertTrue(state.session!!.isToday)
        assertTrue("no countdown to a session already trained", state.sessionDone)
        // The Kolega tab's rule for the same day, so both tabs name today's session alike.
        assertEquals(
            BroDerived.routineName(today, routines, listOf(workout.workout), zone),
            state.routineName,
        )
        assertEquals("Push A", state.routineName)
        assertEquals("the next start still rotates on", "pull", state.suggestedRoutineId)
        assertFalse(offersCantMakeIt(state.session!!.isToday, state.myState, state.trainedToday))
    }

    @Test
    fun `before training today the card counts down to the suggested routine`() {
        val schedule = GymScheduleEntity(weekdays = listOf(1, 3, 5), defaultMinuteOfDay = 18 * 60)
        val routines = listOf(routine("push", "Push A"), routine("pull", "Pull A"))
        val state = DashboardViewModel.build(
            basis = basis(schedule = schedule, routines = routines, recentRoutineWorkoutNames = listOf("Push A")),
            live = live(records = listOf(record(today, Participant.Me, AttendanceStatus.Confirmed, updatedAt = 1L))),
            local = local(),
            now = Days.at(today, 9 * 60, zone),
            zone = zone,
            locale = locale,
        )
        assertTrue(state.session!!.isToday)
        assertFalse(state.sessionDone)
        assertEquals("Pull A", state.routineName)
    }

    @Test
    fun `a workout today on a rest day leaves the next session's card alone`() {
        // Friday only: today (Wednesday) is a rest day, the card is about Friday.
        val schedule = GymScheduleEntity(weekdays = listOf(5), defaultMinuteOfDay = 18 * 60)
        val routines = listOf(routine("push", "Push A"), routine("pull", "Pull A"))
        val state = DashboardViewModel.build(
            basis = basis(schedule = schedule, routines = routines, recentRoutineWorkoutNames = listOf("Push A")),
            live = live(lastWorkout = trainedThisMorning(), trainedToday = true),
            local = local(),
            now = Days.at(today, 9 * 60 + 22, zone),
            zone = zone,
            locale = locale,
        )
        assertFalse(state.session!!.isToday)
        assertFalse(state.sessionDone)
        assertEquals("Pull A", state.routineName)
    }

    @Test
    fun `session done needs today and an attended day or a counted workout`() {
        assertTrue(DashboardViewModel.sessionDone(true, DayState.Attended, trainedToday = false))
        assertTrue(DashboardViewModel.sessionDone(true, DayState.Confirmed, trainedToday = true))
        assertFalse(DashboardViewModel.sessionDone(true, DayState.Confirmed, trainedToday = false))
        assertFalse(DashboardViewModel.sessionDone(false, DayState.Attended, trainedToday = true))
    }

    // MARK: - Fixtures

    private fun routine(id: String, name: String = id) = RoutineEntity(id = id, name = name, order = 0)

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
        recentRoutineWorkoutNames: List<String> = emptyList(),
    ) = Basis(profile, schedule, pairing, routines, recentRoutineWorkoutNames)

    private fun live(
        records: List<AttendanceRecordEntity> = emptyList(),
        meals: List<MealEntryEntity> = emptyList(),
        lastWorkout: WorkoutWithExercises? = null,
        hasActiveWorkout: Boolean = false,
        trainedToday: Boolean = false,
    ) = Live(today, records, meals, lastWorkout, hasActiveWorkout, trainedToday)

    private fun local(
        partnerName: String? = null,
        needsSignIn: Boolean = false,
        isConfirming: Boolean = false,
        showsCantMakeIt: Boolean = false,
    ) = Local(partnerName, needsSignIn, isConfirming, showsCantMakeIt)
}
