package app.notomorrow.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The pure derivations behind the Settings rows (`SettingsModel.swift:150-215` and the rest-length
 * stepper in `SettingsTrainingEditors.swift`). Everything catalog-shaped is injected, so these run
 * on the JVM with no resources and no Android framework.
 */
class SettingsFormatTest {

    private val short = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

    private fun names(iso: Int) = short.getValue(iso)

    private fun time(minute: Int) = String.format(Locale.ROOT, "%d:%02d", minute / 60, minute % 60)

    @Test
    fun `gym days are sorted and end with the time`() {
        assertEquals(
            "Mon · Wed · Fri · 18:00",
            SettingsFormat.gymDays(listOf(5, 1, 3), 18 * 60, ::names, ::time),
        )
    }

    @Test
    fun `gym days with no days is just the time`() {
        assertEquals("7:30", SettingsFormat.gymDays(emptyList(), 450, ::names, ::time))
    }

    @Test
    fun `rest timer appends auto-start only when it is on`() {
        assertEquals("1:30 · auto-start", SettingsFormat.restTimer(90, true, "auto-start"))
        assertEquals("1:30", SettingsFormat.restTimer(90, false, "auto-start"))
        assertEquals("10:00", SettingsFormat.restTimer(600, false, "auto-start"))
    }

    @Test
    fun `notification count is the number of reminders that are on`() {
        assertEquals(0, SettingsFormat.notificationCount(false, false))
        assertEquals(1, SettingsFormat.notificationCount(true, false))
        assertEquals(1, SettingsFormat.notificationCount(false, true))
        assertEquals(2, SettingsFormat.notificationCount(true, true))
    }

    @Test
    fun `language maps the two codes and treats everything else as system`() {
        assertEquals(LanguageOption.System, SettingsFormat.language(null))
        assertEquals(LanguageOption.English, SettingsFormat.language("en"))
        assertEquals(LanguageOption.Polish, SettingsFormat.language("pl"))
        assertEquals(LanguageOption.System, SettingsFormat.language(""))
        assertEquals(LanguageOption.System, SettingsFormat.language("de"))
    }

    @Test
    fun `rest stepper moves in 15 second steps inside 15 to 600`() {
        assertEquals(105, SettingsFormat.steppedRest(90, SettingsFormat.REST_STEP))
        assertEquals(75, SettingsFormat.steppedRest(90, -SettingsFormat.REST_STEP))
        assertEquals(15, SettingsFormat.steppedRest(15, -SettingsFormat.REST_STEP))
        assertEquals(600, SettingsFormat.steppedRest(600, SettingsFormat.REST_STEP))
    }

    @Test
    fun `rest stepper buttons disable at the bounds`() {
        assertFalse(SettingsFormat.canStepRest(15, -SettingsFormat.REST_STEP))
        assertTrue(SettingsFormat.canStepRest(30, -SettingsFormat.REST_STEP))
        assertFalse(SettingsFormat.canStepRest(600, SettingsFormat.REST_STEP))
        assertTrue(SettingsFormat.canStepRest(585, SettingsFormat.REST_STEP))
    }
}
