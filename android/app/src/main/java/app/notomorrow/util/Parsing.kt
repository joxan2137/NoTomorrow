package app.notomorrow.util

import java.util.Locale

/**
 * The mirror image of [Fmt]: text a user typed back into numbers.
 *
 * iOS parses `TextField` input with
 * `Double(text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))`
 * in six places (`STNumber.parse`, `QuickAddSheet`, `AIScanFoodRow`,
 * `PortionSheet`, `LogWeightSheet`, `OnboardingModel`), so parsing is **always**
 * comma-tolerant. Android adds NBSP stripping, because [Fmt] emits U+00A0
 * between a value and its unit and users paste formatted text back in.
 */
object Parsing {

    /** Everything that separates digits in a formatted number but must not reach the parser. */
    private const val SPACES = "\u0020\u00A0\u202F\u2009\u2007\t"

    /**
     * Swift's `Double(String)` is far stricter than `String.toDoubleOrNull`,
     * which happily accepts `"1.5f"`, `"0x1p3"`, `"Infinity"` and `"NaN"`.
     */
    private val DECIMAL = Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")

    /**
     * "82,4" / "82.4" / "12 500" → 82.4 / 12500.0. `null` when the text is not
     * a finite number. Sign is accepted here; the callers that need a bound
     * use [nonNegative] / [positive].
     */
    fun decimal(text: String): Double? {
        val cleaned = buildString(text.length) {
            for (c in text) {
                when {
                    c == ',' -> append('.')
                    SPACES.indexOf(c) >= 0 -> Unit
                    else -> append(c)
                }
            }
        }.trim()
        if (!DECIMAL.matches(cleaned)) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    /** `STNumber.parse` — rejects negatives (`SettingsComponents.swift:267`). */
    fun nonNegative(text: String): Double? = decimal(text)?.takeIf { it >= 0 }

    /** The `> 0` variant used by weight and portion entry. */
    fun positive(text: String): Double? = decimal(text)?.takeIf { it > 0 }

    /** `STNumber.parseInt` — parse, then round half away from zero. */
    fun int(text: String): Int? = nonNegative(text)?.let { Fmt.roundHalfAwayFromZero(it).toInt() }

    /**
     * `BroService.normalizedCode` — "abcd", "nt-abcd", "NTABCD " → "NT-ABCD".
     * Anything that does not reduce to four code characters is returned as-is,
     * trimmed and uppercased (and then rejected by [isValidPairCode]).
     */
    fun normalizedPairCode(raw: String): String {
        var body = raw.uppercase(Locale.ROOT).filter { it.isLetter() || it.isDigit() }
        if (body.startsWith("NT") && body.length == 6) body = body.substring(2)
        return if (body.length == 4) "NT-$body" else raw.trim().uppercase(Locale.ROOT)
    }

    /** `BroService.pair` accepts exactly `"NT-XXXX"`. */
    fun isValidPairCode(code: String): Boolean = code.length == 7

    /**
     * `OnboardingModel.normalizedCode` — the onboarding field is looser: it only
     * prefixes a bare four-character entry and never strips punctuation.
     */
    fun onboardingPairCode(raw: String): String {
        var code = raw.uppercase(Locale.ROOT).trim()
        if (code.length == 4 && !code.startsWith("NT")) code = "NT-$code"
        return code
    }
}
