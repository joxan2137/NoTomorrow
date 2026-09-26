package app.notomorrow.feature.progress

import androidx.compose.runtime.Immutable
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.feature.workout.WorkoutStrings
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Localizer
import app.notomorrow.util.S
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Lifetime milestones — port of `Milestones.swift`: derived from the finished workouts alone,
 * nothing stored. Each milestone is a tier of one track — workouts completed, total volume, the
 * longest run of ISO weeks with a workout, and three lifts against body weight — and is either
 * achieved by the workout that crossed it or shows how far along it is.
 */
object Milestones {

    enum class Kind {
        Workouts, Volume, WeekStreak, Bench, Squat, Deadlift;

        val isLift: Boolean get() = this in LIFTS
    }

    /** The three lifts measured against body weight. */
    val LIFTS: List<Kind> = listOf(Kind.Bench, Kind.Squat, Kind.Deadlift)

    // MARK: - Tiers

    val WORKOUT_TIERS: List<Int> = listOf(1, 10, 25, 50, 100, 250, 500)

    /** 1 t, 10 t, 50 t, 100 t, 500 t, 1000 t — in kilograms. */
    val VOLUME_TIERS_KG: List<Double> = listOf(1_000.0, 10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0)
    val WEEK_STREAK_TIERS: List<Int> = listOf(4, 12, 26, 52)

    /** Body-weight multiple each lift is measured against. */
    fun multiplier(kind: Kind): Double? = when (kind) {
        Kind.Bench -> 1.0
        Kind.Squat -> 1.5
        Kind.Deadlift -> 2.0
        else -> null
    }

    /** The catalog exercises (free-exercise-db ids) that count as each lift: the barbell competition movements. */
    val LIFT_EXERCISE_IDS: Map<Kind, Set<String>> = mapOf(
        Kind.Bench to setOf("Barbell_Bench_Press_-_Medium_Grip", "Bench_Press_-_Powerlifting"),
        Kind.Squat to setOf("Barbell_Squat", "Barbell_Full_Squat"),
        Kind.Deadlift to setOf("Barbell_Deadlift"),
    )

    fun lift(exerciseId: String?): Kind? =
        exerciseId?.let { id -> LIFTS.firstOrNull { LIFT_EXERCISE_IDS[it]?.contains(id) == true } }

    // MARK: - Input / output

    /** One finished workout with at least one completed set. */
    data class Session(
        val workoutId: String,
        val startedAt: Long,
        /** Its start day, in the zone the weeks are counted in. */
        val day: LocalDate,
        /** Working-set volume (warm-ups left out, as `Workout.totalVolumeKg`). */
        val volumeKg: Double,
        /** Heaviest completed working set of each lift in the workout. */
        val heaviestKg: Map<Kind, Double> = emptyMap(),
    )

    @Immutable
    data class Achievement(val workoutId: String, val day: LocalDate)

    @Immutable
    data class Milestone(
        val kind: Kind,
        /** Workouts, kilograms, weeks — for a lift, the kilograms to lift ([multiplier] × body weight). */
        val target: Double,
        /** Where the track stands now, in [target]'s unit (a lift: its heaviest set). */
        val current: Double,
        val achieved: Achievement?,
    ) {
        val id: String get() = "${kind.name}-$target"
        val isAchieved: Boolean get() = achieved != null

        /** 0…1 toward [target]; 1 once achieved. */
        val progress: Double
            get() = when {
                isAchieved -> 1.0
                target <= 0 -> 0.0
                else -> (current / target).coerceIn(0.0, 1.0)
            }

        val multiplier: Double? get() = Milestones.multiplier(kind)
    }

    /**
     * One [Session] per finished workout in [rows] (completed sets only, so a workout without one
     * never appears) — as the training calendar and the weekly stats count them.
     */
    fun sessions(rows: List<CompletedSetRow>, zone: ZoneId): List<Session> =
        rows.filter { it.workoutEndedAt != null }
            .groupBy { it.workoutId }
            .map { (id, sets) ->
                val first = sets.first()
                val working = sets.filter { it.kind != SetKind.Warmup }
                val heaviest = mutableMapOf<Kind, Double>()
                for (set in working) {
                    val lift = lift(set.exerciseId) ?: continue
                    if (set.reps <= 0) continue
                    heaviest[lift] = maxOf(heaviest[lift] ?: 0.0, set.weightKg)
                }
                Session(
                    workoutId = id,
                    startedAt = first.workoutStartedAt,
                    day = Instant.ofEpochMilli(first.workoutStartedAt).atZone(zone).toLocalDate(),
                    volumeKg = working.sumOf { it.weightKg * it.reps },
                    heaviestKg = heaviest,
                )
            }

    /** The latest logged body weight, else the one in the profile; `null` without a positive one. */
    fun bodyWeight(latestEntryKg: Double?, profileKg: Double?): Double? =
        latestEntryKg?.takeIf { it > 0 } ?: profileKg?.takeIf { it > 0 }

    // MARK: - Evaluation

    /** Every tier of every track, in track order then tier order. The lifts are left out without a body weight. */
    fun evaluate(sessions: List<Session>, bodyWeightKg: Double?): List<Milestone> {
        val ordered = sessions.sortedWith(compareBy({ it.startedAt }, { it.workoutId }))
        val result = mutableListOf<Milestone>()
        result += countTrack(Kind.Workouts, WORKOUT_TIERS.map { it.toDouble() }, ordered) { index -> index + 1.0 }
        var running = 0.0
        val volumes = ordered.map { running += it.volumeKg; running }
        result += countTrack(Kind.Volume, VOLUME_TIERS_KG, ordered) { index -> volumes[index] }
        val streaks = longestStreaks(ordered.map { it.day })
        result += countTrack(Kind.WeekStreak, WEEK_STREAK_TIERS.map { it.toDouble() }, ordered) { index ->
            streaks[index].toDouble()
        }
        if (bodyWeightKg != null && bodyWeightKg > 0) {
            for (kind in LIFTS) {
                val target = (multiplier(kind) ?: continue) * bodyWeightKg
                var best = 0.0
                var achieved: Achievement? = null
                for (session in ordered) {
                    val heaviest = session.heaviestKg[kind] ?: 0.0
                    best = maxOf(best, heaviest)
                    if (achieved == null && heaviest >= target) achieved = Achievement(session.workoutId, session.day)
                }
                result += Milestone(kind, target, best, achieved)
            }
        }
        return result
    }

    /**
     * A monotonic track: `value(index)` is where the track stands after `ordered[index]`; each
     * tier is achieved by the first session whose value reaches it.
     */
    private fun countTrack(
        kind: Kind,
        tiers: List<Double>,
        ordered: List<Session>,
        value: (Int) -> Double,
    ): List<Milestone> {
        var current = 0.0
        val achieved = mutableMapOf<Double, Achievement>()
        ordered.forEachIndexed { index, session ->
            current = maxOf(current, value(index))
            for (tier in tiers) {
                if (tier !in achieved && current >= tier) achieved[tier] = Achievement(session.workoutId, session.day)
            }
        }
        return tiers.map { Milestone(kind, it, current, achieved[it]) }
    }

    /**
     * For each day (oldest first), the longest run so far of consecutive ISO weeks with a
     * workout — [TrainingCalendar.weekStreak]'s rule, counted over the whole history instead of
     * back from today.
     */
    fun longestStreaks(days: List<LocalDate>): List<Int> {
        var lastWeek: LocalDate? = null
        var run = 0
        var longest = 0
        return days.map { day ->
            val week = Fmt.startOfIsoWeek(day)
            run = when (lastWeek) {
                week -> run
                week.minusWeeks(1) -> run + 1
                else -> 1
            }
            lastWeek = week
            longest = maxOf(longest, run)
            longest
        }
    }

    // MARK: - Reading the list

    /** The milestones [workoutId] crossed, in track order. */
    fun crossed(workoutId: String, milestones: List<Milestone>): List<Milestone> =
        milestones.filter { it.achieved?.workoutId == workoutId }

    /** The most recently achieved milestone (a later tier wins a tie: same workout, bigger number). */
    fun latest(milestones: List<Milestone>): Milestone? {
        var best: Milestone? = null
        for (milestone in milestones) {
            val achieved = milestone.achieved ?: continue
            val current = best?.achieved
            if (current != null && current.day > achieved.day) continue
            best = milestone
        }
        return best
    }

    /** The next tier of each track still to reach, closest to done first (ties keep track order). */
    fun next(milestones: List<Milestone>): List<Milestone> {
        val seen = mutableSetOf<Kind>()
        return milestones
            .filter { !it.isAchieved && seen.add(it.kind) }
            .sortedByDescending { it.progress } // stable: ties keep track order
    }

    /** Kilograms shown in [unit]: the pound equivalent for lb users, as [Fmt.volume] / [Fmt.weight] convert. */
    fun displayAmount(kg: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.Kg) kg else kg * Fmt.LB_PER_KG

    // MARK: - Copy

    /** "50 workouts", "10 000 kg lifted", "12-week streak", "Squat 1.5× body weight". */
    fun title(
        milestone: Milestone,
        unit: WeightUnit,
        strings: Localizer,
        locale: Locale = LocaleProvider.current(),
    ): String = when (milestone.kind) {
        Kind.Workouts -> WorkoutStrings.workouts(milestone.target.toInt(), strings)
        Kind.Volume -> strings.string(S.milestone_volume_s, Fmt.volume(milestone.target, unit, locale = locale))
        Kind.WeekStreak -> WorkoutStrings.weekStreak(milestone.target.toInt(), strings)
        Kind.Bench -> strings.string(S.milestone_bench_s, multiple(milestone, locale))
        Kind.Squat -> strings.string(S.milestone_squat_s, multiple(milestone, locale))
        Kind.Deadlift -> strings.string(S.milestone_deadlift_s, multiple(milestone, locale))
    }

    /** "32 / 50", "7 450 / 10 000 kg", "95 / 100 kg". */
    fun progressText(milestone: Milestone, unit: WeightUnit, locale: Locale = LocaleProvider.current()): String =
        when (milestone.kind) {
            Kind.Workouts, Kind.WeekStreak -> "${milestone.current.toInt()} / ${milestone.target.toInt()}"
            Kind.Volume -> Fmt.volume(milestone.current, unit, withUnit = false, locale = locale) + " / " +
                Fmt.volume(milestone.target, unit, locale = locale)
            else -> Fmt.weight(milestone.current, unit, withUnit = false, locale = locale) + " / " +
                Fmt.weight(milestone.target, unit, locale = locale)
        }

    /** "1.5×" / "1,5×" */
    fun multiple(milestone: Milestone, locale: Locale = LocaleProvider.current()): String {
        val format = (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        return format.format(milestone.multiplier ?: 1.0) + Fmt.TIMES
    }
}
