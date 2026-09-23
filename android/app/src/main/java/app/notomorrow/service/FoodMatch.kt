package app.notomorrow.service

import java.text.Normalizer
import java.util.Locale

/**
 * Local matching for the saved-food library — the port of `NoTomorrow/Services/FoodMatch.swift`.
 * People type "zolty ser" for "Żółty ser" and "mlekovita serek" for "Serek wiejski · Mlekovita",
 * so matching folds case, diacritics and ł, and every typed word must appear somewhere.
 */
object FoodMatch {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Case-, diacritic- and width-insensitive key. NFKD decomposes ą ć ę ń ó ś ź ż and the
     * full-width forms, but not the stroked ł, so it is flattened by hand. Locale-independent on
     * purpose ([Locale.ROOT]): the Turkish dotless ı would otherwise break matching.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .replace('ł', 'l')

    /** True when every whitespace-separated word of [query] appears in the name or the brand. An empty query matches. */
    fun matches(query: String, name: String, brand: String?): Boolean {
        val words = fold(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fold(brand?.let { "$name $it" } ?: name)
        return words.all { haystack.contains(it) }
    }
}
