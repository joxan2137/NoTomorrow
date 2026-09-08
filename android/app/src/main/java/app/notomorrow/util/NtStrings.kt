package app.notomorrow.util

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The one thing [Fmt] needs from Android: catalog text by resource id.
 *
 * Declared as an interface so every formatter stays a pure-JVM function —
 * tests pass a lambda, the app passes [NtStrings].
 */
fun interface Localizer {
    fun string(@StringRes id: Int, vararg args: Any): String
}

/**
 * Resource-backed string lookup, the Android replacement for iOS's four
 * `String(localized:)` styles. Feature code inside composition should keep
 * using `stringResource`; this exists for view models, services and formatters
 * that hold a `Context` instead.
 *
 * The catalog key -> resource id mapping lives in the generated [S] / [P]
 * objects (`scripts/gen_string_keys.py`); runtime-built iOS keys
 * (`"meal." + rawValue` and friends) are exhaustive `when` maps in
 * `NtStringKeys.kt`.
 */
class NtStrings(private val res: Resources) : Localizer {

    override fun string(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    /**
     * `pluralStringResource(id, count, count)` — the count is passed both as
     * the plural selector and as the first format argument, exactly as the
     * catalog's `%lld` expects. Extra [args] replace that default.
     */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        if (args.isEmpty()) res.getQuantityString(id, count, count)
        else res.getQuantityString(id, count, *args)

    companion object {
        fun from(context: Context): NtStrings = NtStrings(context.resources)
    }
}

/** `NtStrings` for a `Context` — services, receivers, notifications. */
fun Context.ntStrings(): NtStrings = NtStrings(resources)

/** `NtStrings` inside composition, for the rare view that hands text to [Fmt]. */
@Composable
fun rememberNtStrings(): NtStrings {
    val context = LocalContext.current
    return remember(context) { NtStrings(context.resources) }
}
