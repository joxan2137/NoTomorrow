package app.notomorrow.feature.fuelhome

import app.notomorrow.feature.fuel.FuelDayNavigator
import app.notomorrow.feature.fuel.FuelDays
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Fuel's day boundary and navigation — the port of iOS's `FuelDayNavigationTests`: midnight
 * rollover, the 30-minute snap-back, jumps from the History sheet, and which moves report a
 * change (the haptic's trigger).
 */
class FuelDayNavigatorTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 22)

    /** Noon on [today], as epoch millis — the background stamps only ever subtract. */
    private val noon: Long = 1_789_000_000_000L

    private val minute = 60_000L

    private fun day(offset: Long): LocalDate = today.plusDays(offset)

    // MARK: - Midnight rollover

    @Test
    fun `showing today follows midnight`() {
        val nav = FuelDayNavigator(today)
        nav.syncToday(day(1))
        assertEquals(FuelDays(day = day(1), today = day(1)), nav.days.value)
    }

    @Test
    fun `browsing the past stays across midnight`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()
        nav.goPreviousDay()

        nav.syncToday(day(1))

        assertEquals(day(1), nav.today)
        assertEquals(day(-2), nav.day)
    }

    @Test
    fun `sync on the same day changes nothing`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()

        nav.syncToday(today)

        assertEquals(day(-1), nav.day)
        assertEquals(today, nav.today)
    }

    @Test
    fun `a clock set back clamps a day ahead of the new today`() {
        val nav = FuelDayNavigator(today)
        nav.syncToday(day(-3))
        assertEquals(FuelDays(day = day(-3), today = day(-3)), nav.days.value)
    }

    // MARK: - Snap-back after the background

    @Test
    fun `a past day snaps back after more than 30 minutes`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()

        nav.appDidEnterBackground(noon)
        nav.appWillEnterForeground(noon + 31 * minute, today)

        assertEquals(today, nav.day)
    }

    @Test
    fun `a past day stays after exactly 30 minutes`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()

        nav.appDidEnterBackground(noon)
        nav.appWillEnterForeground(noon + 30 * minute, today)

        assertEquals(day(-1), nav.day)
    }

    @Test
    fun `a past day stays after a short break`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()

        nav.appDidEnterBackground(noon)
        nav.appWillEnterForeground(noon + 29 * minute, today)

        assertEquals(day(-1), nav.day)
    }

    @Test
    fun `a foreground without a background stamp keeps the past day`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()
        nav.appDidEnterBackground(noon)
        nav.appWillEnterForeground(noon + minute, today)

        // The stamp is consumed: a second return, hours later, has no known time away.
        nav.appWillEnterForeground(noon + 5 * 60 * minute, today)

        assertEquals(day(-1), nav.day)
    }

    @Test
    fun `a short break across midnight still follows today`() {
        val nav = FuelDayNavigator(today)
        val lateEvening = noon + (11 * 60 + 55) * minute

        nav.appDidEnterBackground(lateEvening)
        nav.appWillEnterForeground(lateEvening + 10 * minute, day(1))

        assertEquals(FuelDays(day = day(1), today = day(1)), nav.days.value)
    }

    // MARK: - Moves

    @Test
    fun `go to clamps the future and reports whether the day moved`() {
        val nav = FuelDayNavigator(today)

        assertTrue(nav.goTo(day(-10), now = today))
        assertEquals(day(-10), nav.day)

        assertTrue(nav.goTo(day(3), now = today))
        assertEquals(today, nav.day)

        assertFalse(nav.goTo(today, now = today), "the day already shown: no change, no tick")

        nav.goTo(day(-4), now = today)
        assertTrue(nav.goToday(now = today))
        assertEquals(today, nav.day)
        assertFalse(nav.goToday(now = today))
    }

    @Test
    fun `forward stops at today and back is unbounded`() {
        val nav = FuelDayNavigator(today)

        assertFalse(nav.goNextDay(now = today))
        assertEquals(today, nav.day)

        assertTrue(nav.goPreviousDay())
        assertTrue(nav.goNextDay(now = today))
        assertEquals(today, nav.day)

        repeat(400) { nav.goPreviousDay() }
        assertEquals(day(-400), nav.day)
    }

    @Test
    fun `moves never touch today`() {
        val nav = FuelDayNavigator(today)
        nav.goPreviousDay()
        nav.goTo(day(-30), now = today)
        nav.goToday(now = today)
        assertEquals(today, nav.today)
    }
}
