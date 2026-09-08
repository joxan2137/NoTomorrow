package app.notomorrow.feature.settings

import app.notomorrow.util.Fmt

/**
 * The pure half of `SettingsModel`'s display helpers (`SettingsModel.swift:150-215`).
 *
 * Everything here takes its catalog text as a parameter, so the value shown on a Settings row
 * is testable on the JVM without resources — and so the composable stays free of logic.
 */
object SettingsFormat {

    /** `gymDaysValue` — "Mon · Wed · Fri · 18:00", the weekdays sorted, the time last. */
    fun gymDays(
        weekdays: List<Int>,
        minuteOfDay: Int,
        shortName: (Int) -> String,
        time: (Int) -> String = { Fmt.time(it) },
    ): String = (weekdays.sorted().map(shortName) + time(minuteOfDay)).joinToString(SEPARATOR)

    /** `restTimerValue` — "1:30 · auto-start", or just the clock when auto-start is off. */
    fun restTimer(seconds: Int, autoStart: Boolean, autoStartLabel: String): String {
        val clock = Fmt.clock(seconds)
        return if (autoStart) clock + SEPARATOR + autoStartLabel else clock
    }

    /** `notificationsValue` — how many of the two reminders are on. */
    fun notificationCount(remindHourBefore: Boolean, askIfSkippedAt21: Boolean): Int =
        (if (remindHourBefore) 1 else 0) + (if (askIfSkippedAt21) 1 else 0)

    /** `languageValue` — which of the three rows is ticked. `null` = follow the system. */
    fun language(override: String?): LanguageOption = when (override) {
        "en" -> LanguageOption.English
        "pl" -> LanguageOption.Polish
        else -> LanguageOption.System
    }

    /** The rest-length stepper: 15 s steps, clamped to 15…600 (`SettingsTrainingEditors.swift`). */
    fun steppedRest(seconds: Int, delta: Int): Int =
        (seconds + delta).coerceIn(REST_RANGE.first, REST_RANGE.last)

    fun canStepRest(seconds: Int, delta: Int): Boolean =
        seconds + delta in REST_RANGE

    /** " · " — the separator iOS joins these value strings with. */
    const val SEPARATOR: String = " · "

    const val REST_STEP: Int = 15

    val REST_RANGE: IntRange = 15..600
}

/** The three rows of the language editor, in iOS order. */
enum class LanguageOption(val code: String?) {
    System(null),
    English("en"),
    Polish("pl"),
}
