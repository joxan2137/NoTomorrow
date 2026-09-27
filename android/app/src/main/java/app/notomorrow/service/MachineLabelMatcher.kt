package app.notomorrow.service

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Matches the text read off a gym machine's placard ("SEATED LEG CURL", "Hammer Strength
 * ISO-LATERAL ROW") against the exercise library, on device — 1:1 port of
 * `NoTomorrow/Services/MachineLabelMatcher.swift`; spec in `docs/machine-scan.md`.
 *
 * Every name and label word is folded, split and stemmed the same way; a word's weight is its
 * rarity across the library (so "machine" or "hammer strength" count for little and "adductor"
 * for a lot) times how prominent its line is on the label. Each exercise gets an F-score of how
 * much of its name the label covers and how much of the label's library words its name explains.
 * Words the library never uses (brand logos, safety text) are ignored.
 */
class MachineLabelMatcher(candidates: List<Candidate>) {

    data class Candidate(val id: String, val name: String, val namePL: String?, val equipment: String?)

    /** One recognised line and how prominent it is (its text height over the tallest line's), 0..1. */
    data class Line(val text: String, val weight: Double)

    data class Match(val id: String, val score: Double)

    private class Entry(val id: String, val nameLength: Int, val names: List<List<String>>, val freeWeight: Boolean)

    private val entries: List<Entry>
    private val idf: Map<String, Double>

    init {
        val df = HashMap<String, Int>()
        entries = candidates.map { c ->
            val en = uniqueTokens(c.name)
            val pl = c.namePL?.let(::uniqueTokens).orEmpty()
            (en + pl).toSet().forEach { df[it] = (df[it] ?: 0) + 1 }
            Entry(c.id, c.name.length, listOf(en, pl).filter { it.isNotEmpty() }, c.equipment in FREE_WEIGHT_EQUIPMENT)
        }
        val n = max(candidates.size, 1).toDouble()
        idf = df.mapValues { (_, count) -> ln(1 + n / count) }
    }

    /** Best matches first, at most [MAX_MATCHES], none under [MINIMUM_SCORE], skipping [excluding]. */
    fun match(lines: List<Line>, excluding: Set<String> = emptySet()): List<Match> {
        val weights = labelWeights(lines)
        val labelMass = weights.entries.sumOf { (token, w) -> (idf[token] ?: 0.0) * w }
        if (labelMass <= 0) return emptyList()

        val scored = ArrayList<Pair<Match, Int>>()
        for (entry in entries) {
            if (entry.id in excluding) continue
            var best = 0.0
            for (name in entry.names) {
                var total = 0.0
                var matched = 0.0
                var plainMatched = 0.0
                for (token in name) {
                    val rarity = idf[token] ?: 0.0
                    val w = weights[token] ?: 0.0
                    total += rarity
                    matched += rarity * w
                    plainMatched += w
                }
                if (matched <= 0 || total <= 0) continue
                // Recall mixes rarity-weighted and plain word coverage, so one rare word left out
                // ("One Arm") and several common ones left out both cost something.
                val recall = 0.5 * matched / total + 0.5 * plainMatched / name.size
                val precision = matched / labelMass
                best = max(best, 2 * recall * precision / (recall + precision))
            }
            // A placard sits on a machine or a cable stack, never on a dumbbell.
            if (entry.freeWeight) best *= 0.85
            if (best >= MINIMUM_SCORE) scored += Match(entry.id, best) to entry.nameLength
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Match, Int>> { it.first.score }
                    .thenBy { it.second }
                    .thenBy { it.first.id },
            )
            .take(MAX_MATCHES)
            .map { it.first }
    }

    /**
     * The label's library words, each at the weight of the most prominent line it appears on. A
     * word the library does not use counts as its one-letter-off library neighbours (OCR misreads)
     * at 0.7, else not at all.
     */
    private fun labelWeights(lines: List<Line>): Map<String, Double> {
        val weights = HashMap<String, Double>()
        for (line in lines) {
            val lineWeight = line.weight.coerceIn(0.0, 1.0)
            val base = tokens(line.text)
            val all = base + base.flatMap { LABEL_EXPANSIONS[it].orEmpty() }
            for (token in all) {
                if (idf.containsKey(token)) {
                    weights[token] = max(weights[token] ?: 0.0, lineWeight)
                } else if (token.length >= 6) {
                    for (known in idf.keys) {
                        if (isOneEditApart(token, known)) weights[known] = max(weights[known] ?: 0.0, lineWeight * 0.7)
                    }
                }
            }
        }
        return weights
    }

    companion object {
        /** Below this nothing is offered and the scan falls back to the picker's search. */
        const val MINIMUM_SCORE = 0.4
        const val MAX_MATCHES = 3

        val FREE_WEIGHT_EQUIPMENT = setOf(
            "dumbbell", "barbell", "kettlebells", "bands", "body only", "medicine ball", "exercise ball",
            "foam roll", "e-z curl bar",
        )
        private val STOP_WORDS = setOf("the", "on", "of", "and", "with", "a", "to", "for", "in", "na", "z", "do", "i", "w", "ze")

        /** Two-word spellings that the library writes as one word. */
        private val PHRASES = listOf("pull down" to "pulldown", "push down" to "pushdown", "pec deck" to "butterfly")
        private val CANONICAL = mapOf(
            "flye" to "fly", "abduction" to "abductor", "adduction" to "adductor", "calve" to "calf",
            "ab" to "abdominal", "abs" to "abdominal", "delt" to "deltoid",
        )

        /** Label words that also stand for a library word: pec-fly and pectoral machines are the library's "Butterfly". */
        private val LABEL_EXPANSIONS = mapOf("pec" to listOf("butterfly"), "pectoral" to listOf("butterfly"))

        private val COMBINING_MARKS = Regex("\\p{Mn}+")
        private val NOT_ALNUM = Regex("[^a-z0-9]+")

        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                .replace(COMBINING_MARKS, "")
                .replace("ł", "l")

        /**
         * Folded, split on anything but a–z and 0–9, two-word phrases joined, stop words, numbers
         * and single letters dropped, plurals stemmed.
         */
        fun tokens(text: String): List<String> {
            var spaced = " " + fold(text).replace(NOT_ALNUM, " ").trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") + " "
            for ((from, to) in PHRASES) spaced = spaced.replace(" $from ", " $to ")
            return spaced.split(' ').mapNotNull { token ->
                if (token.length < 2 || token in STOP_WORDS || token.all { it in '0'..'9' }) null else stem(token)
            }
        }

        fun stem(token: String): String {
            val t = when {
                token.length > 4 && token.endsWith("sses") -> token.dropLast(2)
                token.length > 3 && token.endsWith("ies") -> token.dropLast(3) + "y"
                token.length > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") -> token.dropLast(1)
                else -> token
            }
            return CANONICAL[t] ?: t
        }

        private fun uniqueTokens(text: String): List<String> = tokens(text).distinct()

        /** One substitution, insertion or deletion apart (Levenshtein distance exactly 1). */
        fun isOneEditApart(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1 || a == b) return false
            if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
            val (short, long) = if (a.length < b.length) a to b else b to a
            var i = 0
            while (i < short.length && short[i] == long[i]) i++
            return short.substring(i) == long.substring(i + 1)
        }
    }
}
