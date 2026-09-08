package app.notomorrow.feature.progress

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.designsystem.E1RMChartPoint
import app.notomorrow.designsystem.WeekVolumeBar
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

/**
 * Value types behind the Progress tab — the port of `ProgressModel.swift` and
 * `ProgressSupport.swift`. Everything here is pure JVM so the derivations stay
 * unit-testable (`ProgressDerivationsTest`).
 */

// MARK: - Range

/** Time window for the e1RM history and its deltas (`ProgressSupport.swift:8`). */
enum class ProgressRange(
    @param:StringRes val titleRes: Int,
    /** Length of the window in days; `null` = everything. */
    val days: Int?,
    /** `"14 kg in 3 months"`. */
    @param:StringRes val deltaRes: Int,
) {
    M1(S.range_1m, 30, S.progress_delta_1m_s),
    M3(S.range_3m, 90, S.progress_delta_3m_s),
    Y1(S.range_1y, 365, S.progress_delta_1y_s),
    All(S.range_all, null, S.progress_delta_all_s);

    /** `Calendar.date(byAdding: .day, value: -days, to: startOfDay(now))`. */
    fun start(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Instant? =
        days?.let { today.minusDays(it.toLong()).atStartOfDay(zone).toInstant() }
}

/** The home screen's Lifts / Body switch. */
enum class ProgressTab(@param:StringRes val titleRes: Int) {
    Lifts(S.progress_lifts),
    Body(S.progress_body),
}

// MARK: - Lifts

/** Best e1RM of one workout for one exercise. */
@Immutable
data class E1RMPoint(
    val workoutId: String,
    val date: Instant,
    val e1RM: Double,
    val isPR: Boolean,
)

/** One exercise the user has actually trained (`ProgressModel.swift:14`). */
@Immutable
data class LiftSummary(
    val exerciseId: String,
    val name: String,
    /** Name of the most recent workout that contained the exercise ("Push A"). */
    val context: String?,
    val lastPR: Instant?,
    val lastSession: Instant,
    /** Oldest first. */
    val history: List<E1RMPoint>,
    /** Sets that count: completed, working, reps > 0, weight > 0. */
    val sets: List<CompletedSetRow>,
) {
    val current: Double get() = history.maxOfOrNull { it.e1RM } ?: 0.0

    fun prInLast30Days(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = lastPR ?: return false
        return ProgressPhrase.daysSince(date, today, zone) < 30
    }

    /** Points inside the range; falls back to everything when the range holds fewer than two. */
    fun points(start: Instant?): List<E1RMPoint> {
        if (start == null) return history
        val inRange = history.filter { !it.date.isBefore(start) }
        return if (inRange.size >= 2) inRange else history.takeLast(max(2, inRange.size))
    }

    /** Best e1RM now minus the best known at the start of the range. */
    fun delta(start: Instant?): Double {
        if (start == null) return current - (history.firstOrNull()?.e1RM ?: current)
        val before = history.filter { it.date.isBefore(start) }.maxOfOrNull { it.e1RM }
        if (before != null) return current - before
        val first = history.firstOrNull { !it.date.isBefore(start) } ?: return 0.0
        return current - first.e1RM
    }

    fun sessions(start: Instant?): Int {
        if (start == null) return history.size
        return history.count { !it.date.isBefore(start) }
    }

    /** Σ `weight × reps` over the sets completed since [weekStart]. */
    fun thisWeekVolume(weekStart: Instant): Double = sets
        .filter { it.completedAt >= weekStart.toEpochMilli() }
        .sumOf { it.weightKg * it.reps }

    val heaviest: CompletedSetRow?
        get() = sets.maxWithOrNull(compareBy({ it.weightKg }, { it.reps }))

    val mostReps: CompletedSetRow?
        get() = sets.maxWithOrNull(compareBy({ it.reps }, { it.weightKg }))
}

/** One ISO week of training volume across every lift. */
@Immutable
data class WeekVolume(
    val weekStart: LocalDate,
    val volumeKg: Double,
    val isCurrent: Boolean,
) {
    fun toBar(): WeekVolumeBar = WeekVolumeBar(weekStart, volumeKg, isCurrent)
}

// MARK: - Body weight

/** `BodyWeightEntry` as the charts want it: a calendar day, not a timestamp. */
@Immutable
data class BodyEntry(
    val day: LocalDate,
    val kg: Double,
    val source: BodyWeightSource = BodyWeightSource.Manual,
)

/** `BodyStats` (`ProgressModel.swift:88`). */
@Immutable
data class BodyStats(
    /** Oldest first. */
    val entries: List<BodyEntry> = emptyList(),
    /** Change vs the reading closest to (and not after) 28 days ago. */
    val delta4w: Double? = null,
    val loggedLast28: Int = 0,
    /** 7-day trailing moving average, aligned with [entries]. */
    val smoothed: List<Double> = emptyList(),
) {
    val latest: BodyEntry? get() = entries.lastOrNull()
    val isEmpty: Boolean get() = entries.isEmpty()
}

// MARK: - Screen state

/** One row of the home list, already derived (`ProgressLiftRow.swift`). */
@Immutable
data class LiftRowState(
    val exerciseId: String,
    val name: String,
    val context: String?,
    val lastPR: ProgressPhraseRef,
    /** e1RM values inside the 3-month window — the 72×24 sparkline. */
    val sparkline: List<Double>,
    val current: Double,
    val delta: Double,
    /** `prInLast30Days` — ember sparkline and an ember delta when the delta is positive. */
    val isHot: Boolean,
)

/** Everything `ProgressHomeScreen` renders. */
@Immutable
data class ProgressHomeUiState(
    val unit: WeightUnit = WeightUnit.Kg,
    /** `null` renders `progress.noPRYet`; otherwise "LAST PR · WEDNESDAY". */
    val lastPRDate: ProgressPhraseRef? = null,
    val lifts: List<LiftRowState> = emptyList(),
    val hasCompletedSets: Boolean = false,
    val body: BodyStats = BodyStats(),
    val tab: ProgressTab = ProgressTab.Lifts,
    val showsLogWeight: Boolean = false,
    /**
     * `false` until the first store emission. iOS derives everything synchronously in
     * `.onAppear`, so the screen is never seen with an empty model; here the screen holds
     * its content back for that one frame rather than flashing "NO PR YET" and the empty
     * state at a user who has trained.
     */
    val loaded: Boolean = false,
)

/** The heaviest / most-reps rows at the bottom of `ExerciseProgressScreen`. */
@Immutable
data class RecordSet(
    val weightKg: Double,
    val reps: Int,
    val day: LocalDate,
)

/** Everything `ExerciseProgressScreen` renders, for the selected range. */
@Immutable
data class ExerciseProgressUiState(
    val name: String = "",
    val unit: WeightUnit = WeightUnit.Kg,
    val range: ProgressRange = ProgressRange.M3,
    val hasLift: Boolean = false,
    val current: Double = 0.0,
    val delta: Double = 0.0,
    val points: List<E1RMChartPoint> = emptyList(),
    /** `null` renders `progress.noPRYet` in the tile. */
    val lastPR: ProgressPhraseRef? = null,
    val thisWeekVolume: Double = 0.0,
    val sessions: Int = 0,
    val weekly: List<WeekVolumeBar> = emptyList(),
    /** Volume of every lift this week, for the section header. */
    val allThisWeekVolume: Double = 0.0,
    val weekOverWeek: Double? = null,
    val heaviest: RecordSet? = null,
    val mostReps: RecordSet? = null,
    /** `false` until the first store emission — see [ProgressHomeUiState.loaded]. */
    val loaded: Boolean = false,
)

// MARK: - Relative PR phrasing

/**
 * A phrase that is either a catalog key (with at most one argument) or already
 * formatted text — the Compose stand-in for iOS returning a `LocalizedStringKey`
 * built from an interpolated string.
 */
@Immutable
sealed interface ProgressPhraseRef {
    data class Res(@param:StringRes val id: Int, val arg: Any? = null) : ProgressPhraseRef
    data class Raw(val value: String) : ProgressPhraseRef
}

@Composable
fun ProgressPhraseRef.asText(): String = when (this) {
    is ProgressPhraseRef.Raw -> value
    is ProgressPhraseRef.Res -> if (arg == null) stringResource(id) else stringResource(id, arg)
}

/** `ProgressPhrase` (`ProgressSupport.swift:83`). */
object ProgressPhrase {

    /** Row subtitle: "PR today", "PR Wednesday", "3 weeks since PR", "stalled 5 weeks". */
    fun lastPR(
        date: Instant?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): ProgressPhraseRef {
        if (date == null) return ProgressPhraseRef.Res(S.progress_noPRYet)
        val days = daysSince(date, today, zone)
        return when {
            days == 0 -> ProgressPhraseRef.Res(S.progress_prToday)
            days == 1 -> ProgressPhraseRef.Res(S.progress_prYesterday)
            days in 2..6 -> ProgressPhraseRef.Res(S.progress_prOn_s, weekday(date, zone, locale))
            days in 7..29 -> ProgressPhraseRef.Res(S.progress_weeksSincePR_n, days / 7)
            else -> ProgressPhraseRef.Res(S.progress_stalledWeeks_n, days / 7)
        }
    }

    /** Tile value: "Today", "Yesterday", "9 days ago", "26 Aug". */
    fun ago(
        date: Instant,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): ProgressPhraseRef {
        val days = daysSince(date, today, zone)
        return when {
            days == 0 -> ProgressPhraseRef.Res(S.day_today)
            days == 1 -> ProgressPhraseRef.Res(S.progress_yesterday)
            days in 2..29 -> ProgressPhraseRef.Res(S.progress_daysAgo_n, days)
            else -> ProgressPhraseRef.Raw(Fmt.dayMonth(date.atZone(zone).toLocalDate(), locale))
        }
    }

    /** Eyebrow date: "Wednesday" within the week, "26 Aug" beyond. */
    fun eyebrowDate(
        date: Instant,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): ProgressPhraseRef {
        val days = daysSince(date, today, zone)
        return when {
            days == 0 -> ProgressPhraseRef.Res(S.day_today)
            days == 1 -> ProgressPhraseRef.Res(S.progress_yesterday)
            days < 7 -> ProgressPhraseRef.Raw(weekday(date, zone, locale))
            else -> ProgressPhraseRef.Raw(Fmt.dayMonth(date.atZone(zone).toLocalDate(), locale))
        }
    }

    /** Whole calendar days between [date] and [today] — negative for a future date. */
    fun daysSince(date: Instant, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(date.atZone(zone).toLocalDate(), today).toInt()

    /** `.dateTime.weekday(.wide)`, capitalized in the app locale. */
    private fun weekday(date: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEEE", locale)
            .format(date.atZone(zone).toLocalDate())
            .replaceFirstChar { it.titlecase(locale) }
}
