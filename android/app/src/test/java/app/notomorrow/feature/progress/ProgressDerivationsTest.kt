package app.notomorrow.feature.progress

import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.feature.workout.muscleHeatLevel
import app.notomorrow.model.SetKind
import app.notomorrow.util.S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The pure half of the Progress tab — `ProgressModel.buildLifts` / `buildWeekly` /
 * `buildMuscleWeek` / `buildBody` and `ProgressPhrase`, ported from
 * `Features/Progress/ProgressModel.swift` and `ProgressSupport.swift`.
 */
class ProgressDerivationsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 9, 4)   // a Friday
    private val names = { id: String -> if (id == "ghost") null else id.replaceFirstChar { it.uppercase() } }

    private fun at(day: LocalDate, hour: Int = 18): Long =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun row(
        exerciseId: String? = "bench",
        workoutId: String = "w1",
        workoutName: String = "Push A",
        started: LocalDate = today,
        completed: LocalDate = started,
        weightKg: Double = 100.0,
        reps: Int = 5,
        kind: SetKind = SetKind.Normal,
        isPR: Boolean = false,
        ended: Boolean = true,
    ) = CompletedSetRow(
        setId = SET_ID++,
        setOrder = 0,
        kind = kind,
        weightKg = weightKg,
        reps = reps,
        completedAt = at(completed),
        isPR = isPR,
        isSetRecord = false,
        rpe = null,
        workoutExerciseId = 1,
        workoutExerciseOrder = 0,
        exerciseId = exerciseId,
        workoutId = workoutId,
        workoutName = workoutName,
        workoutStartedAt = at(started),
        workoutEndedAt = if (ended) at(started, 19) else null,
    )

    // MARK: - buildLifts

    @Test
    fun `warm-ups, zero reps, zero weight and unknown exercises never make a lift`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                row(kind = SetKind.Warmup),
                row(reps = 0),
                row(weightKg = 0.0),
                row(exerciseId = null),
                row(exerciseId = "ghost"),
            ),
            names,
        )
        assertTrue(lifts.isEmpty())
    }

    @Test
    fun `one point per workout, keeping that workout's best e1RM`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", started = today.minusDays(7), weightKg = 100.0, reps = 5),
                row(workoutId = "w1", started = today.minusDays(7), weightKg = 100.0, reps = 8),
                row(workoutId = "w2", started = today, weightKg = 105.0, reps = 5),
            ),
            names,
        )
        val history = lifts.single().history
        assertEquals(2, history.size)
        // Epley: 100 * (1 + 8/30) = 126.66…, ahead of the 5-rep set in the same workout.
        assertEquals(126.666, history.first().e1RM, 0.001)
        assertEquals(122.5, history.last().e1RM, 0.001)
        assertEquals(126.666, lifts.single().current, 0.001)
    }

    @Test
    fun `a workout point is a PR when any of its sets is`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", weightKg = 100.0, reps = 5, isPR = false),
                row(workoutId = "w1", weightKg = 110.0, reps = 5, isPR = true),
            ),
            names,
        )
        assertTrue(lifts.single().history.single().isPR)
        assertEquals(Instant.ofEpochMilli(at(today)), lifts.single().lastPR)
    }

    @Test
    fun `context is the newest workout's name`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", workoutName = "Push A", started = today.minusDays(3)),
                row(workoutId = "w2", workoutName = "Push B", started = today),
            ),
            names,
        )
        assertEquals("Push B", lifts.single().context)
    }

    @Test
    fun `lifts sort by last PR desc, PR-less last, ties on the last session`() {
        val lifts = ProgressDerivations.buildLifts(
            listOf(
                row(exerciseId = "old", workoutId = "a", started = today.minusDays(30), isPR = true),
                row(exerciseId = "fresh", workoutId = "b", started = today.minusDays(1), isPR = true),
                row(exerciseId = "never", workoutId = "c", started = today),
                row(exerciseId = "stale", workoutId = "d", started = today.minusDays(9)),
            ),
            names,
        )
        assertEquals(listOf("Fresh", "Old", "Never", "Stale"), lifts.map { it.name })
    }

    // MARK: - Range windows

    @Test
    fun `points fall back to the whole history when the range holds fewer than two`() {
        val lift = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", started = today.minusDays(300)),
                row(workoutId = "w2", started = today.minusDays(280)),
                row(workoutId = "w3", started = today.minusDays(2), weightKg = 120.0),
            ),
            names,
        ).single()

        val month = ProgressRange.M1.start(today, zone)
        assertEquals(1, lift.history.count { !it.date.isBefore(month) })
        assertEquals(2, lift.points(month).size)
        assertEquals(3, lift.points(ProgressRange.All.start(today, zone)).size)
    }

    @Test
    fun `delta measures against the best known before the window`() {
        val lift = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", started = today.minusDays(200), weightKg = 100.0, reps = 1),
                row(workoutId = "w2", started = today.minusDays(10), weightKg = 120.0, reps = 1),
            ),
            names,
        ).single()

        // The 100 kg single sits before both windows, so it is the baseline for both.
        assertEquals(20.0, lift.delta(ProgressRange.M3.start(today, zone)), 0.001)
        assertEquals(20.0, lift.delta(ProgressRange.M1.start(today, zone)), 0.001)
        // All time: current minus the oldest point.
        assertEquals(20.0, lift.delta(null), 0.001)
        assertEquals(1, lift.sessions(ProgressRange.M1.start(today, zone)))
        assertEquals(2, lift.sessions(null))
    }

    @Test
    fun `with nothing before the window the delta starts at the first point inside it`() {
        val lift = ProgressDerivations.buildLifts(
            listOf(
                row(workoutId = "w1", started = today.minusDays(5), weightKg = 100.0, reps = 1),
                row(workoutId = "w2", started = today.minusDays(1), weightKg = 110.0, reps = 1),
            ),
            names,
        ).single()
        assertEquals(10.0, lift.delta(ProgressRange.M1.start(today, zone)), 0.001)
    }

    @Test
    fun `records break ties on the other axis`() {
        val lift = ProgressDerivations.buildLifts(
            listOf(
                row(weightKg = 120.0, reps = 3),
                row(weightKg = 120.0, reps = 5),
                row(weightKg = 80.0, reps = 12),
            ),
            names,
        ).single()
        assertEquals(120.0, lift.heaviest!!.weightKg, 0.0)
        assertEquals(5, lift.heaviest!!.reps)
        assertEquals(12, lift.mostReps!!.reps)
    }

    // MARK: - buildWeekly

    @Test
    fun `weekly covers eight ISO weeks, oldest first, and skips unfinished workouts`() {
        val weekly = ProgressDerivations.buildWeekly(
            listOf(
                row(started = today, weightKg = 100.0, reps = 5),                       // 500
                row(started = today, weightKg = 50.0, reps = 4, kind = SetKind.Warmup), // ignored
                row(started = today, weightKg = 100.0, reps = 5, ended = false),        // ignored
                row(started = today.minusWeeks(1), weightKg = 100.0, reps = 10),        // 1000
                row(started = today.minusWeeks(20), weightKg = 100.0, reps = 10),       // off-chart
            ),
            today,
            zone,
        )
        assertEquals(8, weekly.size)
        assertTrue(weekly.last().isCurrent)
        assertFalse(weekly.first().isCurrent)
        assertEquals(500.0, weekly.last().volumeKg, 0.001)
        assertEquals(1000.0, weekly[6].volumeKg, 0.001)
        assertEquals(0.0, weekly.first().volumeKg, 0.001)
        assertEquals(-0.5, ProgressDerivations.weekOverWeek(weekly)!!, 0.001)
        assertEquals(500.0, ProgressDerivations.thisWeekVolume(weekly), 0.001)
    }

    @Test
    fun `week over week is null when last week was empty`() {
        val weekly = ProgressDerivations.buildWeekly(listOf(row()), today, zone)
        assertNull(ProgressDerivations.weekOverWeek(weekly))
    }

    // MARK: - Muscles this week (`ProgressMuscleWeekTests.swift`)

    private val primary = mapOf(
        "bench" to listOf("chest"),
        "squat" to listOf("quadriceps", "glutes"),
        "custom-curl" to emptyList(),
    )
    private val weekStart: Instant = LocalDate.of(2026, 8, 31).atStartOfDay(zone).toInstant()   // Monday

    @Test
    fun `muscle week counts completed sets per primary muscle of finished workouts this week`() {
        val week = ProgressDerivations.buildMuscleWeek(
            listOf(
                row(exerciseId = "bench"),
                row(exerciseId = "bench"),
                row(exerciseId = "bench", kind = SetKind.Warmup),               // counts, as on iOS
                row(exerciseId = "squat", weightKg = 0.0),                      // bodyweight too
                row(exerciseId = "squat"),
                row(exerciseId = "custom-curl"),                                // total only
                row(exerciseId = null),                                         // deleted: total only
                row(exerciseId = "bench", started = LocalDate.of(2026, 8, 30)), // last Sunday
                row(exerciseId = "squat", ended = false),                       // still running
            ),
            weekStart,
        ) { primary[it] }

        assertEquals(mapOf("chest" to 3, "quadriceps" to 2, "glutes" to 2), week.setsByMuscle)
        assertEquals(7, week.totalSets)
    }

    @Test
    fun `nothing this week is an empty muscle week`() {
        val week = ProgressDerivations.buildMuscleWeek(
            listOf(row(exerciseId = "bench", started = LocalDate.of(2026, 8, 30))),
            weekStart,
        ) { primary[it] }

        assertEquals(MuscleWeek(), week)
        assertTrue(week.top.isEmpty())
        assertTrue(week.notTrainedYet.isEmpty())
    }

    @Test
    fun `top five sorts by sets, then by name`() {
        val week = MuscleWeek(
            setsByMuscle = mapOf(
                "quadriceps" to 10, "middle back" to 7, "lats" to 8, "hamstrings" to 7,
                "glutes" to 7, "chest" to 2,
            ),
            totalSets = 30,
        )
        assertEquals(
            listOf("quadriceps", "lats", "glutes", "hamstrings", "middle back"),
            week.top.map { it.first },
        )
        assertEquals(listOf(10, 8, 7, 7, 7), week.top.map { it.second })
    }

    @Test
    fun `not trained yet lists the key muscles at zero, in order`() {
        assertEquals(
            listOf("shoulders", "quadriceps", "hamstrings"),
            MuscleWeek(mapOf("chest" to 3, "lats" to 2, "biceps" to 4), totalSets = 9).notTrainedYet,
        )
        // Sets logged, none on a muscle the model knows: every key muscle is still untrained.
        assertEquals(MuscleWeek.KEY_MUSCLES, MuscleWeek(emptyMap(), totalSets = 2).notTrainedYet)
        assertEquals(
            emptyList<String>(),
            MuscleWeek(MuscleWeek.KEY_MUSCLES.associateWith { 1 }, totalSets = 5).notTrainedYet,
        )
        assertEquals(emptyList<String>(), MuscleWeek().notTrainedYet)
    }

    @Test
    fun `heat levels step at 1, 4, 7 and 10 sets`() {
        val expected = mapOf(
            0 to 0, 1 to 1, 3 to 1, 4 to 2, 6 to 2, 7 to 3, 9 to 3, 10 to 4, 25 to 4, -1 to 0,
        )
        for ((sets, level) in expected) assertEquals("$sets sets", level, muscleHeatLevel(sets))
    }

    // MARK: - buildBody

    @Test
    fun `body stats measure against the last reading at or before 28 days ago`() {
        val stats = ProgressDerivations.buildBody(
            listOf(
                BodyEntry(today.minusDays(40), 80.0),
                BodyEntry(today.minusDays(28), 81.0),
                BodyEntry(today.minusDays(10), 82.0),
                BodyEntry(today, 82.5),
            ),
            today,
        )
        assertEquals(1.5, stats.delta4w!!, 0.001)
        assertEquals(2, stats.loggedLast28)     // strictly after the boundary day
        assertEquals(82.5, stats.latest!!.kg, 0.0)
    }

    @Test
    fun `a single reading has no delta and no window to average`() {
        val stats = ProgressDerivations.buildBody(listOf(BodyEntry(today, 82.0)), today)
        assertNull(stats.delta4w)
        assertEquals(listOf(82.0), stats.smoothed)
        assertFalse(stats.isEmpty)
        assertTrue(ProgressDerivations.buildBody(emptyList(), today).isEmpty)
    }

    @Test
    fun `smoothing is a seven-day trailing mean, not a seven-sample one`() {
        val stats = ProgressDerivations.buildBody(
            listOf(
                BodyEntry(today.minusDays(20), 90.0),   // outside the window of every later day
                BodyEntry(today.minusDays(2), 80.0),
                BodyEntry(today.minusDays(1), 82.0),
                BodyEntry(today, 84.0),
            ),
            today,
        )
        assertEquals(listOf(90.0, 80.0, 81.0, 82.0), stats.smoothed)
    }

    // MARK: - ProgressPhrase

    @Test
    fun `last PR phrasing walks today, yesterday, weekday, weeks, stalled`() {
        fun phrase(daysAgo: Int) = ProgressPhrase.lastPR(instant(daysAgo), today, zone)

        assertEquals(ProgressPhraseRef.Res(S.progress_noPRYet), ProgressPhrase.lastPR(null, today, zone))
        assertEquals(ProgressPhraseRef.Res(S.progress_prToday), phrase(0))
        assertEquals(ProgressPhraseRef.Res(S.progress_prYesterday), phrase(1))
        assertEquals(S.progress_prOn_s, (phrase(3) as ProgressPhraseRef.Res).id)
        assertEquals(ProgressPhraseRef.Res(S.progress_weeksSincePR_one, 1), phrase(7))
        assertEquals(ProgressPhraseRef.Res(S.progress_weeksSincePR_few, 3), phrase(21))
        assertEquals(ProgressPhraseRef.Res(S.progress_stalledWeeks_n, 5), phrase(35))
    }

    @Test
    fun `ago switches to a date after 30 days and eyebrow after a week`() {
        assertEquals(ProgressPhraseRef.Res(S.day_today), ProgressPhrase.ago(instant(0), today, zone))
        assertEquals(ProgressPhraseRef.Res(S.progress_daysAgo_n, 9), ProgressPhrase.ago(instant(9), today, zone))
        assertTrue(ProgressPhrase.ago(instant(30), today, zone) is ProgressPhraseRef.Raw)

        assertEquals(
            ProgressPhraseRef.Res(S.progress_yesterday),
            ProgressPhrase.eyebrowDate(instant(1), today, zone),
        )
        assertTrue(ProgressPhrase.eyebrowDate(instant(3), today, zone) is ProgressPhraseRef.Raw)
        assertEquals(30, ProgressPhrase.daysSince(instant(30), today, zone))
    }

    // MARK: - LogWeightSheet parsing

    @Test
    fun `weight parsing accepts a comma and rejects the impossible`() {
        assertEquals(82.5, parseWeight("82,5", app.notomorrow.model.WeightUnit.Kg)!!, 0.001)
        assertEquals(82.5, parseWeight(" 82.5 ", app.notomorrow.model.WeightUnit.Kg)!!, 0.001)
        assertEquals(81.647, parseWeight("180", app.notomorrow.model.WeightUnit.Lb)!!, 0.001)
        assertNull(parseWeight("", app.notomorrow.model.WeightUnit.Kg))
        assertNull(parseWeight("0", app.notomorrow.model.WeightUnit.Kg))
        assertNull(parseWeight("500", app.notomorrow.model.WeightUnit.Kg))
        assertNull(parseWeight("abc", app.notomorrow.model.WeightUnit.Kg))
    }

    private fun instant(daysAgo: Int): Instant =
        today.minusDays(daysAgo.toLong()).atStartOfDay(zone).plusHours(9).toInstant()

    private companion object {
        var SET_ID: Long = 1
    }
}
