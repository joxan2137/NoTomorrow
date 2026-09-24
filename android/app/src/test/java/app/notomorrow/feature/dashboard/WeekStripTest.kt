package app.notomorrow.feature.dashboard

import app.notomorrow.service.DayState
import app.notomorrow.service.WeekDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The v2 week strip marks — `WeekStripView.dots(for:isPaired:)` and `isUpcomingGymDay`
 * (`Features/Dashboard/WeekStripView.swift`), mirrored by `WeekStripTests.swift`.
 */
class WeekStripTest {

    private fun day(
        isToday: Boolean = false,
        isGymDay: Boolean = true,
        me: DayState = DayState.Rest,
        partner: DayState = DayState.Rest,
    ) = WeekDay(
        date = LocalDate.of(2026, 9, 21),
        isoWeekday = 1,
        isToday = isToday,
        isGymDay = isGymDay,
        myState = me,
        partnerState = partner,
    )

    // MARK: - Dots

    @Test
    fun `dots show you then your partner`() {
        assertEquals(
            listOf(WeekStripDot.You, WeekStripDot.PartnerTrained),
            weekStripDots(day(me = DayState.Attended, partner = DayState.Attended), isPaired = true),
        )
        assertEquals(
            listOf(WeekStripDot.You, WeekStripDot.PartnerMissed),
            weekStripDots(day(me = DayState.Attended, partner = DayState.Missed), isPaired = true),
        )
        assertEquals(
            "a miss of mine is the rose ring, not a dot",
            listOf(WeekStripDot.PartnerTrained),
            weekStripDots(day(me = DayState.Missed, partner = DayState.Attended), isPaired = true),
        )
    }

    @Test
    fun `a partner cancellation is a rose dot`() {
        assertEquals(
            listOf(WeekStripDot.PartnerMissed),
            weekStripDots(day(partner = DayState.Cancelled("sick")), isPaired = true),
        )
    }

    @Test
    fun `solo shows only your own dot`() {
        assertEquals(
            listOf(WeekStripDot.You),
            weekStripDots(day(me = DayState.Attended, partner = DayState.Attended), isPaired = false),
        )
        assertEquals(emptyList<WeekStripDot>(), weekStripDots(day(partner = DayState.Missed), isPaired = false))
    }

    @Test
    fun `no dots while nothing happened`() {
        for (state in listOf(DayState.Rest, DayState.Planned, DayState.Confirmed)) {
            assertEquals(emptyList<WeekStripDot>(), weekStripDots(day(me = state, partner = state), isPaired = true))
        }
        assertEquals(
            "today keeps its white circle and still shows the dot",
            listOf(WeekStripDot.You),
            weekStripDots(day(isToday = true, me = DayState.Attended), isPaired = true),
        )
    }

    // MARK: - Upcoming ring

    @Test
    fun `an upcoming gym day is planned or confirmed and not today`() {
        assertTrue(isUpcomingGymDay(day(me = DayState.Planned)))
        assertTrue(isUpcomingGymDay(day(me = DayState.Confirmed)))
        assertFalse(isUpcomingGymDay(day(isToday = true, me = DayState.Planned)))
    }

    @Test
    fun `settled and unscheduled days stay plain`() {
        assertFalse(isUpcomingGymDay(day(me = DayState.Attended)))
        assertFalse(isUpcomingGymDay(day(me = DayState.Missed)))
        assertFalse(isUpcomingGymDay(day(me = DayState.Cancelled(null))))
        assertFalse("a past gym day with no record", isUpcomingGymDay(day(me = DayState.Rest)))
        assertFalse(isUpcomingGymDay(day(isGymDay = false, me = DayState.Planned)))
    }
}
