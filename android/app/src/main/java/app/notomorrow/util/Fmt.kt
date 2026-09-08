package app.notomorrow.util

import androidx.appcompat.app.AppCompatDelegate
import app.notomorrow.model.WeightUnit
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.floor

/**
 * The locale every formatter reads, on **every call** — never cached, so a
 * language change applies without a relaunch (iOS `Fmt.locale`,
 * `AppLocale.effective`).
 *
 * The per-app locale set through `AppCompatDelegate.setApplicationLocales`
 * wins; otherwise the device locale. [override] exists for tests and previews.
 */
object LocaleProvider {
    /** Test/preview hook. `null` in production. */
    @Volatile
    var override: (() -> Locale)? = null

    fun current(): Locale {
        override?.let { return it() }
        val app = AppCompatDelegate.getApplicationLocales()
        return if (!app.isEmpty) app[0] ?: Locale.getDefault() else Locale.getDefault()
    }
}

/**
 * Locale-aware formatting. 1:1 port of `NoTomorrow/Services/Formatters.swift`
 * (plus the `signedWeight` / `signedPercent` extension in
 * `Features/Progress/ProgressSupport.swift`).
 *
 * Never hand-roll dates or decimal separators anywhere else: Polish writes
 * "82,5 kg" and "12 500 kg". Functions that need catalog text take a
 * [Localizer] so they stay pure-JVM testable.
 */
object Fmt {

    /** iOS `WeightUnit` conversion factor. */
    const val LB_PER_KG = 2.2046226218

    /** U+00A0 — the value and its unit never break across lines. */
    const val NBSP = "\u00A0"

    /** U+00D7, the multiplication sign used in "85 × 7". */
    const val TIMES = "\u00D7"

    // MARK: - Weights and numbers

    /** "82,5 kg" / "82.5" — 0…1 fraction digits, NBSP before the unit. */
    fun weight(
        kg: Double,
        unit: WeightUnit = WeightUnit.Kg,
        withUnit: Boolean = true,
        locale: Locale = LocaleProvider.current(),
    ): String {
        val value = if (unit == WeightUnit.Kg) kg else kg * LB_PER_KG
        val number = decimal(locale).format(value)
        return if (withUnit) number + NBSP + unit.raw else number
    }

    /** "2 750 kcal" — rounded to a whole number, grouped. */
    fun kcal(value: Double, withUnit: Boolean = true, locale: Locale = LocaleProvider.current()): String {
        val number = integer(locale).format(roundHalfAwayFromZero(value))
        return if (withUnit) number + NBSP + "kcal" else number
    }

    /**
     * "2 750" — `Int(x.rounded()).formatted(.number.grouping(.automatic))`: a grouped whole
     * number with no unit. Same output as `kcal(withUnit = false)`, but says what it means at
     * the call site (macro grams, rep counts, anything unit-less).
     */
    fun whole(value: Double, locale: Locale = LocaleProvider.current()): String =
        integer(locale).format(roundHalfAwayFromZero(value))

    /**
     * The `Int` form of [whole] — Swift's plain `Int.formatted()`: grouped, no unit, nothing to
     * round. Used for counts that are already integral (session count, reps in a record row —
     * `ExerciseProgressView.swift:147,205`).
     */
    fun count(value: Int, locale: Locale = LocaleProvider.current()): String =
        integer(locale).format(value.toLong())

    /**
     * `.number.precision(.fractionLength(n))` — **exactly** [fractionDigits] fraction digits,
     * where [weight] gives 0…1. `fixed(2.2, 1)` is "2,2" in Polish and "2.2" in English.
     */
    fun fixed(
        value: Double,
        fractionDigits: Int,
        locale: Locale = LocaleProvider.current(),
    ): String =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }.format(value)

    /**
     * `.number.sign(strategy: .always(includingZero: false))` at zero fraction digits —
     * "+300", "-400", "0". [signedWeight] signs zero too, which is right for a PR delta and
     * wrong for a kcal adjustment.
     */
    fun signedInteger(value: Double, locale: Locale = LocaleProvider.current()): String {
        val format = integer(locale)
        if (value > 0) format.positivePrefix = "+" + format.positivePrefix
        return format.format(roundHalfAwayFromZero(value))
    }

    /** "176 g" */
    fun grams(value: Double, locale: Locale = LocaleProvider.current()): String =
        integer(locale).format(roundHalfAwayFromZero(value)) + NBSP + "g"

    /** "12 500 kg" — session/weekly volume. */
    fun volume(kg: Double, locale: Locale = LocaleProvider.current()): String =
        integer(locale).format(roundHalfAwayFromZero(kg)) + NBSP + "kg"

    /** "85 × 7" */
    fun set(
        kg: Double,
        reps: Int,
        unit: WeightUnit = WeightUnit.Kg,
        locale: Locale = LocaleProvider.current(),
    ): String = weight(kg, unit, withUnit = false, locale = locale) + " " + TIMES + " " + reps

    /** "+17" / "+0" / "-3" — signed, 0…1 fraction digits, unit optional. */
    fun signedWeight(
        kg: Double,
        unit: WeightUnit = WeightUnit.Kg,
        withUnit: Boolean = false,
        locale: Locale = LocaleProvider.current(),
    ): String {
        val value = if (unit == WeightUnit.Kg) kg else kg * LB_PER_KG
        val format = decimal(locale)
        format.positivePrefix = "+" + format.positivePrefix
        val number = format.format(value)
        return if (withUnit) number + NBSP + unit.raw else number
    }

    /** "+5%" — `ratio` is a fraction (0.05), zero keeps the plus sign. */
    fun signedPercent(ratio: Double, locale: Locale = LocaleProvider.current()): String {
        val format = NumberFormat.getPercentInstance(locale) as DecimalFormat
        format.minimumFractionDigits = 0
        format.maximumFractionDigits = 0
        format.positivePrefix = "+" + format.positivePrefix
        return format.format(ratio)
    }

    /**
     * Epley estimated one-rep max (`SetEntry.estimatedOneRepMax`,
     * `Models.swift:241`): 0 for a non-lift, the weight itself for a single.
     */
    fun epley(weightKg: Double, reps: Int): Double {
        if (reps <= 0 || weightKg <= 0) return 0.0
        if (reps == 1) return weightKg
        return weightKg * (1 + reps / 30.0)
    }

    /** An estimated 1RM rendered like any other weight ("102,3 kg"). */
    fun e1rm(
        kg: Double,
        unit: WeightUnit = WeightUnit.Kg,
        withUnit: Boolean = true,
        locale: Locale = LocaleProvider.current(),
    ): String = weight(kg, unit, withUnit, locale)

    // MARK: - Durations

    /** "1:12" — rest-timer clock, deliberately **not** localized. */
    fun clock(seconds: Double): String {
        val s = maxOf(0, roundHalfAwayFromZero(seconds).toInt())
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** "1:12" */
    fun clock(seconds: Int): String = clock(seconds.toDouble())

    /** "52 min" / "1 h 12 min" */
    fun duration(seconds: Double, strings: Localizer): String {
        val minutes = (seconds / 60).toInt()
        if (minutes < 60) return strings.string(S.n_min, minutes)
        return strings.string(S.n_h_n_min, minutes / 60, minutes % 60)
    }

    /** "in 4 h 12 min" / "in 2 d 3 h" — clamped at zero. */
    fun countdown(
        to: Instant,
        strings: Localizer,
        now: Instant = Instant.now(),
    ): String {
        val seconds = maxOf(0L, to.epochSecond - now.epochSecond)
        val minutes = (seconds / 60).toInt()
        if (minutes < 60) return strings.string(S.in_n_min, minutes)
        val h = minutes / 60
        val m = minutes % 60
        if (h >= 24) return strings.string(S.in_n_d_n_h, h / 24, h % 24)
        return strings.string(S.in_n_h_n_min, h, m)
    }

    // MARK: - Dates

    /** "18:00" / "6:00 PM" in the locale's clock style. */
    fun time(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        time(instant.atZone(zone).toLocalTime(), locale)

    /** "18:00" */
    fun time(time: LocalTime, locale: Locale = LocaleProvider.current()): String =
        DateTimeFormatter.ofPattern(pattern(null, FormatStyle.SHORT, locale), locale).format(time)

    /** Minutes since midnight, the schedule's storage form. */
    fun time(minuteOfDay: Int, locale: Locale = LocaleProvider.current()): String {
        val m = Math.floorMod(minuteOfDay, 24 * 60)
        return time(LocalTime.of(m / 60, m % 60), locale)
    }

    /** "Friday, 4 September" / "piątek, 4 września" */
    fun longDay(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        format(date, withoutYear(pattern(FormatStyle.FULL, null, locale)), locale)

    /** "Fri, 4 Sep" / "pt., 4 wrz" */
    fun shortDay(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        format(date, "EEE, " + withoutYear(pattern(FormatStyle.MEDIUM, null, locale)), locale)

    /** "Wed" / "śr." */
    fun weekdayShort(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        format(date, "EEE", locale)

    /** "4 Sep" / "4 wrz" */
    fun dayMonth(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        format(date, withoutYear(pattern(FormatStyle.MEDIUM, null, locale)), locale)

    /** "4" — `.dateTime.day()`, the bare day-of-month number on the week strip. */
    fun dayOfMonth(date: LocalDate, locale: Locale = LocaleProvider.current()): String =
        format(date, "d", locale)

    /** "Today", "Tomorrow", or the capitalized weekday. */
    fun relativeDay(
        date: LocalDate,
        strings: Localizer,
        locale: Locale = LocaleProvider.current(),
        today: LocalDate = LocalDate.now(),
    ): String = when (date) {
        today -> strings.string(S.day_today)
        today.plusDays(1) -> strings.string(S.day_tomorrow)
        else -> format(date, "EEEE", locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** ISO weekday: 1 = Monday … 7 = Sunday. */
    fun isoWeekday(date: LocalDate): Int = date.dayOfWeek.value

    /** Monday of the week containing [date] — the app is Monday-first by design, whatever the locale. */
    fun startOfIsoWeek(date: LocalDate): LocalDate = date.minusDays((isoWeekday(date) - 1).toLong())

    // MARK: - Instant overloads (features that hold a timestamp, not a day)

    fun longDay(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        longDay(instant.atZone(zone).toLocalDate(), locale)

    fun shortDay(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        shortDay(instant.atZone(zone).toLocalDate(), locale)

    fun weekdayShort(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        weekdayShort(instant.atZone(zone).toLocalDate(), locale)

    fun dayMonth(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        dayMonth(instant.atZone(zone).toLocalDate(), locale)

    fun dayOfMonth(instant: Instant, locale: Locale = LocaleProvider.current(), zone: ZoneId = ZoneId.systemDefault()): String =
        dayOfMonth(instant.atZone(zone).toLocalDate(), locale)

    // MARK: - Internals

    /** Swift `Double.rounded()`: half away from zero, unlike `kotlin.math.round`. */
    internal fun roundHalfAwayFromZero(value: Double): Double =
        if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

    /** `.number.precision(.fractionLength(0...1))` */
    private fun decimal(locale: Locale): DecimalFormat =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }

    /**
     * `.number.grouping(.automatic)` over a rounded whole number.
     *
     * **The grouping separator is the locale's, never a constant.** iOS shows "17 300" in the
     * parity captures and the emulator shows "3,050" — that is not a formatting difference, it
     * is a *device* difference: the iOS simulator runs English-with-region-Poland, whose
     * Foundation number symbols are Poland's (U+00A0 groups), while the emulator runs a plain
     * English locale (comma groups). Java agrees with Foundation locale for locale —
     * `pl_PL` → "17 300" (U+00A0), `en_US`/`en_GB` → "17,300" — so hard-coding either symbol
     * here would be wrong on the other device. Set the emulator to Polish and this prints
     * "17 300" too.
     */
    private fun integer(locale: Locale): DecimalFormat =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }

    private fun format(date: LocalDate, pattern: String, locale: Locale): String =
        foundationMonths(DateTimeFormatter.ofPattern(pattern, locale).format(date), locale)

    /**
     * CLDR 43 (2023) re-abbreviated English September as **"Sept"**, so Android's ICU renders
     * `MMM` as "Sept" for every English locale but `en_US` — the parity captures read
     * "SAT, 5 SEPT" / "5 Sept" where iOS reads "SAT, 5 SEP" / "5 Sep". Apple's Foundation still
     * ships the three-letter form, and it is the *iOS* string this port has to match.
     *
     * So: keep the locale's own pattern and field order (the emulator is a day-first English
     * locale and gets "5 Sep", `en_US` gets "Sep 5", `pl` keeps "wrz"), and correct only the one
     * abbreviation the two catalogues disagree on. `\b…\b` cannot fire inside "September" —
     * "Sept" there is followed by a word character — so the wide month is untouched, and no
     * non-English locale is touched at all.
     *
     * Verified against the Polish captures: `pl` abbreviates September "wrz" on both platforms
     * (`design/DashboardPL.dc.html` shows the wide "4 września"; `FmtTest` pins "pt., 4 wrz"),
     * so Polish needs no correction.
     */
    private fun foundationMonths(text: String, locale: Locale): String =
        if (locale.language != "en") text else SEPT.replace(text, "Sep")

    /** The single English month CLDR and Foundation abbreviate differently. */
    private val SEPT = Regex("\\bSept\\b")

    private fun pattern(date: FormatStyle?, time: FormatStyle?, locale: Locale): String =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, IsoChronology.INSTANCE, locale)

    /**
     * Drops the year field (and the punctuation glued to it) from a localized
     * date pattern, so "EEEE, MMMM d, y" becomes "EEEE, MMMM d" — the
     * year-less skeletons iOS builds with `.dateTime.day().month(.wide)`.
     * Field order stays the locale's own.
     */
    internal fun withoutYear(pattern: String): String {
        val marked = StringBuilder()
        var i = 0
        var quoted = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> { quoted = !quoted; marked.append(c); i++ }
                !quoted && (c == 'y' || c == 'u') -> {
                    while (i < pattern.length && pattern[i] == c) i++
                    marked.append(YEAR_MARK)
                }
                else -> { marked.append(c); i++ }
            }
        }
        return marked.toString()
            .replace(Regex(YEAR_MARK + "\\s*'[^']*'"), YEAR_MARK)          // Polish "y 'r.'"
            .replace(Regex("\\s*[,.]?\\s*" + YEAR_MARK + "\\s*[,.]?\\s*"), " ")
            .trim()
            .trim(',', '.', ' ')
    }

    /** Stand-in for the removed year field; never appears in output. */
    private const val YEAR_MARK = "\u0001"
}
