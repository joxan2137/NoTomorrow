package app.notomorrow.util

import app.notomorrow.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * `Fmt` under a fixed locale — no Android `Resources`: catalog text arrives
 * through a [Localizer] lambda carrying the real values from
 * `res/values{,-pl}/strings.xml`.
 */
class FmtTest {

    private val en: Locale = Locale.US
    private val pl: Locale = Locale.forLanguageTag("pl-PL")

    private val enText = mapOf(
        S.n_min to "%d min",
        S.n_h_n_min to "%1\$d h %2\$d min",
        S.in_n_min to "in %d min",
        S.in_n_h_n_min to "in %1\$d h %2\$d min",
        S.in_n_d_n_h to "in %1\$d d %2\$d h",
        S.day_today to "Today",
        S.day_tomorrow to "Tomorrow",
    )

    private val plText = mapOf(
        S.n_min to "%d min",
        S.n_h_n_min to "%1\$d godz. %2\$d min",
        S.in_n_min to "za %d min",
        S.in_n_h_n_min to "za %1\$d godz. %2\$d min",
        S.in_n_d_n_h to "za %1\$d dn. %2\$d godz.",
        S.day_today to "Dziś",
        S.day_tomorrow to "Jutro",
    )

    private fun localizer(text: Map<Int, String>, locale: Locale) =
        Localizer { id, args -> String.format(locale, text.getValue(id), *args) }

    private val enStrings = localizer(enText, en)
    private val plStrings = localizer(plText, pl)

    // MARK: Weights

    @Test
    fun `weight keeps 0 to 1 fraction digits and a non-breaking space`() {
        assertEquals("82.5${Fmt.NBSP}kg", Fmt.weight(82.5, locale = en))
        assertEquals("82,5${Fmt.NBSP}kg", Fmt.weight(82.5, locale = pl))
        assertEquals("82", Fmt.weight(82.0, withUnit = false, locale = en))
        assertEquals("82,3", Fmt.weight(82.34, withUnit = false, locale = pl))
    }

    @Test
    fun `weight converts to pounds with the iOS factor`() {
        assertEquals("181.9${Fmt.NBSP}lb", Fmt.weight(82.5, unit = WeightUnit.Lb, locale = en))
    }

    @Test
    fun `kcal grams and volume round and group`() {
        assertEquals("2,750${Fmt.NBSP}kcal", Fmt.kcal(2750.4, locale = en))
        assertEquals("2,751", Fmt.kcal(2750.5, withUnit = false, locale = en))   // .rounded() is half away from zero
        assertEquals("176${Fmt.NBSP}g", Fmt.grams(175.6, locale = en))
        assertEquals("12\u00A0500${Fmt.NBSP}kg", Fmt.volume(12_500.0, locale = pl))   // pl groups with NBSP
    }

    @Test
    fun `set uses the multiplication sign`() {
        assertEquals("85 ${Fmt.TIMES} 7", Fmt.set(85.0, 7, locale = en))
        assertEquals("82,5 ${Fmt.TIMES} 5", Fmt.set(82.5, 5, locale = pl))
    }

    @Test
    fun `signed weight and percent always carry a sign`() {
        assertEquals("+17", Fmt.signedWeight(17.0, locale = en))
        assertEquals("+0", Fmt.signedWeight(0.0, locale = en))
        assertEquals("-3,2", Fmt.signedWeight(-3.25, locale = pl))
        assertEquals("+2.5${Fmt.NBSP}kg", Fmt.signedWeight(2.5, withUnit = true, locale = en))
        assertEquals("+5%", Fmt.signedPercent(0.05, locale = en))
        assertEquals("+0%", Fmt.signedPercent(0.0, locale = en))
        assertEquals("-3%", Fmt.signedPercent(-0.034, locale = en))
    }

    @Test
    fun `epley matches the iOS estimator`() {
        assertEquals(102.0, Fmt.epley(85.0, 6), 0.01)
        assertEquals(85.0, Fmt.epley(85.0, 1), 0.0)
        assertEquals(0.0, Fmt.epley(85.0, 0), 0.0)
        assertEquals(0.0, Fmt.epley(0.0, 5), 0.0)
    }

    // MARK: Durations

    @Test
    fun `clock is not localized`() {
        assertEquals("1:12", Fmt.clock(72))
        assertEquals("0:00", Fmt.clock(0))
        assertEquals("0:00", Fmt.clock(-5))
        assertEquals("10:05", Fmt.clock(605.4))
    }

    @Test
    fun `duration switches to hours at 60 minutes`() {
        assertEquals("52 min", Fmt.duration(52 * 60.0, enStrings))
        assertEquals("1 h 12 min", Fmt.duration(72 * 60.0, enStrings))
        assertEquals("1 godz. 12 min", Fmt.duration(72 * 60.0, plStrings))
    }

    @Test
    fun `countdown picks minutes hours or days and clamps at zero`() {
        val now = Instant.parse("2026-09-04T10:00:00Z")
        assertEquals("in 45 min", Fmt.countdown(now.plusSeconds(45 * 60), enStrings, now))
        assertEquals("in 4 h 12 min", Fmt.countdown(now.plusSeconds((4 * 60 + 12) * 60L), enStrings, now))
        assertEquals("in 2 d 3 h", Fmt.countdown(now.plusSeconds((51 * 60 + 5) * 60L), enStrings, now))
        assertEquals("in 0 min", Fmt.countdown(now.minusSeconds(600), enStrings, now))
        assertEquals("za 45 min", Fmt.countdown(now.plusSeconds(45 * 60), plStrings, now))
    }

    // MARK: Dates

    @Test
    fun `time follows the locale clock style`() {
        assertEquals("18:00", Fmt.time(LocalTime.of(18, 0), pl))
        assertEquals("18:00", Fmt.time(18 * 60, pl))
        assertEquals("07:05", Fmt.time(7 * 60 + 5, pl))
        assertTrue(Fmt.time(18 * 60, en).startsWith("6:00"))
    }

    @Test
    fun `long short and day-month drop the year and keep locale field order`() {
        val date = LocalDate.of(2026, 9, 4)
        assertEquals("piątek, 4 września", Fmt.longDay(date, pl))
        assertEquals("pt., 4 wrz", Fmt.shortDay(date, pl))
        assertEquals("4 wrz", Fmt.dayMonth(date, pl))
        assertEquals("pt.", Fmt.weekdayShort(date, pl))
        assertEquals("Friday, September 4", Fmt.longDay(date, en))
        assertEquals("Fri, Sep 4", Fmt.shortDay(date, en))
        assertEquals("Sep 4", Fmt.dayMonth(date, en))
        assertEquals("Fri", Fmt.weekdayShort(date, en))
    }

    @Test
    fun `withoutYear survives every localized pattern shape`() {
        assertEquals("EEEE, MMMM d", Fmt.withoutYear("EEEE, MMMM d, y"))
        assertEquals("d MMMM", Fmt.withoutYear("d MMMM y"))
        assertEquals("d MMMM", Fmt.withoutYear("d MMMM y 'r.'"))
        assertEquals("MMM d", Fmt.withoutYear("MMM d, y"))
    }

    @Test
    fun `relativeDay says today tomorrow or the capitalized weekday`() {
        val today = LocalDate.of(2026, 9, 4)
        assertEquals("Today", Fmt.relativeDay(today, enStrings, en, today))
        assertEquals("Tomorrow", Fmt.relativeDay(today.plusDays(1), enStrings, en, today))
        assertEquals("Monday", Fmt.relativeDay(today.plusDays(3), enStrings, en, today))
        assertEquals("Dziś", Fmt.relativeDay(today, plStrings, pl, today))
        assertEquals("Poniedziałek", Fmt.relativeDay(today.plusDays(3), plStrings, pl, today))
    }

    @Test
    fun `iso week is Monday-first whatever the locale`() {
        val friday = LocalDate.of(2026, 9, 4)
        val sunday = LocalDate.of(2026, 9, 6)
        assertEquals(5, Fmt.isoWeekday(friday))
        assertEquals(7, Fmt.isoWeekday(sunday))
        assertEquals(LocalDate.of(2026, 8, 31), Fmt.startOfIsoWeek(friday))
        assertEquals(LocalDate.of(2026, 8, 31), Fmt.startOfIsoWeek(sunday))
    }

    @Test
    fun `rounding is half away from zero like Swift`() {
        assertEquals(3.0, Fmt.roundHalfAwayFromZero(2.5), 0.0)
        assertEquals(-3.0, Fmt.roundHalfAwayFromZero(-2.5), 0.0)
        assertEquals(2.0, Fmt.roundHalfAwayFromZero(2.4), 0.0)
    }
}
