package app.notomorrow.app

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The language override — 1:1 port of `NoTomorrow/App/AppLocale.swift`.
 *
 * A language override keeps the device **region** and swaps only the language, so "English" on a
 * phone set to Poland is `en_PL`: 24-hour clock, decimal comma, Monday-first week. The Swift
 * version feeds that `Locale` into `.environment(\.locale, …)`; Android has no such environment
 * value, so [apply] pushes it into `AppCompatDelegate.setApplicationLocales` instead and every
 * `stringResource`, `Fmt` call and `uppercase()` picks it up from
 * [app.notomorrow.util.LocaleProvider].
 *
 * That call **recreates the Activity**, which is why the Android build drops iOS's
 * `settings.language.footnote` ("Applies after you relaunch the app.") — the switch is immediate
 * (`docs/android-architecture.md`, "Language override"). Nothing else about the semantics changes:
 * the stored value is still `"en"` / `"pl"` / `null` under `nt.language`, and `null` means
 * "follow the system".
 */
object AppLocale {

    /** iOS's fallback when the device reports no region. */
    const val FALLBACK_REGION: String = "PL"

    /** `AppLocale.effective(languageOverride:)`. */
    fun effective(languageOverride: String?, device: Locale = deviceLocale()): Locale {
        val language = languageOverride?.takeIf { it.isNotEmpty() } ?: return device
        return Locale.Builder()
            .setLanguage(language)
            .setRegion(device.country.takeIf { it.isNotEmpty() } ?: FALLBACK_REGION)
            .build()
    }

    /**
     * The BCP-47 tag handed to `AppCompatDelegate`, e.g. `"en-PL"`. `null` for "System", which
     * maps to an empty locale list.
     */
    fun languageTag(languageOverride: String?, device: Locale = deviceLocale()): String? {
        val language = languageOverride?.takeIf { it.isNotEmpty() } ?: return null
        return "$language-${device.country.takeIf { it.isNotEmpty() } ?: FALLBACK_REGION}"
    }

    /**
     * Applies the override. Main thread only — it recreates the Activity.
     *
     * Setting the same value twice is a no-op inside AppCompat, but [applyIfNeeded] is what
     * start-up uses so a cold launch on API 32 and below (where `autoStoreLocales="false"` means
     * AppCompat persists nothing) does not recreate the Activity for no reason.
     */
    fun apply(languageOverride: String?) {
        val tag = languageTag(languageOverride)
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** [apply], skipped when the delegate already carries exactly this list. */
    fun applyIfNeeded(languageOverride: String?) {
        val tag = languageTag(languageOverride).orEmpty()
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == tag) return
        apply(languageOverride)
    }

    /** `"en"` / `"pl"` — the language the device is set to, used by the "System" option. */
    fun systemLanguage(): String = deviceLocale().language.lowercase(Locale.ROOT)

    /**
     * The **device** locale, not the app one: `Locale.getDefault()` already carries the per-app
     * override once it is applied, so re-deriving the region from it would be circular.
     */
    fun deviceLocale(): Locale {
        val system = Resources.getSystem().configuration.locales
        return if (!system.isEmpty) system[0] else Locale.getDefault()
    }
}
