package app.notomorrow.feature.fuel

import androidx.compose.runtime.Immutable
import app.notomorrow.net.dto.AIDatabaseFood
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.service.AIFinalizer
import app.notomorrow.service.FoodMatch
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The user's fixes to an AI estimate — `AIScanCorrections` (`Features/Fuel/AIScanCorrections.swift`).
 * They go to the model as an authoritative line on "Recalculate with details" and are re-applied to
 * its new answer, so a refine never undoes them. The line is machine text in English, not localized.
 */
object AIScanCorrections {

    const val PREFIX = "User corrections (authoritative): "
    const val MAX_COUNT: Double = 99.0

    /** The backend's notes limit, in UTF-16 units (contract §11). */
    const val MAX_NOTES_LENGTH = 1500

    /**
     * A corrected item and every name it was known by (the model's name first), so the refined
     * answer's item of the same food is found even after a rename.
     */
    data class Kept(val food: AIFood, val names: List<String>)

    /**
     * `User corrections (authoritative): Pierogi ruskie = 280 g (8 szt.); Kompot = 250 g; removed: Oliwa`.
     * Null when there is nothing to say.
     */
    fun line(kept: List<AIFood>, removed: List<String>): String? {
        val parts = kept.map { food ->
            val unit = food.unitName
            val part = "${food.name} = ${number(food.grams)} g"
            if (unit != null) "$part (${number(food.units)} $unit)" else part
        } + removed.map { "removed: $it" }
        return if (parts.isEmpty()) null else PREFIX + parts.joinToString("; ")
    }

    /**
     * The notes sent with the photo: the typed details, then the corrections line, within
     * [maxLength] UTF-16 units (the backend's limit). The typed part is cut first, at a character
     * boundary.
     */
    fun notes(typed: String, corrections: String?, maxLength: Int = MAX_NOTES_LENGTH): String {
        val details = typed.trim()
        if (corrections.isNullOrEmpty()) return truncated(details, maxLength)
        val line = truncated(corrections, maxLength)
        val room = maxLength - line.length - 1
        val head = if (room > 0) truncated(details, room).trim() else ""
        return if (head.isEmpty()) line else head + "\n" + line
    }

    /**
     * The refined answer with the corrections applied again, so a refine never undoes them or
     * counts a food twice.
     *
     * Each kept item takes the place of one of the model's items, looked for in three rounds over
     * all kept items (an earlier round wins): the same name as one of its names ([FoodMatch.fold]),
     * then the same generic-table key, then the same food under another name ([sameFood]:
     * "Pierogi ruskie" ↔ "Pierogi ruskie z cebulką"). A kept item with no match is appended. The
     * model's items left over are dropped when they are a part of a kept item ([isPart]:
     * "Ziemniaki z masłem" split into Ziemniaki + Masło) or when the user removed them: the same
     * name, the same food under another name, or a removed item's generic-table key ("Olej" back as
     * "Olej rzepakowy").
     */
    fun merge(
        refined: List<AIFood>,
        kept: List<Kept>,
        removed: List<String>,
        removedKeys: List<String> = emptyList(),
    ): List<AIFood> {
        val result = refined.toMutableList()
        val taken = mutableSetOf<Int>()
        val placed = mutableSetOf<Int>() // indices into `kept`
        fun place(matches: (Kept, AIFood) -> Boolean) {
            kept.forEachIndexed { k, item ->
                if (k in placed) return@forEachIndexed
                val index = result.indices.firstOrNull { it !in taken && matches(item, result[it]) }
                    ?: return@forEachIndexed
                result[index] = item.food
                taken += index
                placed += k
            }
        }
        place { item, food ->
            val name = FoodMatch.fold(food.name)
            item.names.any { FoodMatch.fold(it) == name }
        }
        place { item, food -> genericKey(item.food)?.let { it == genericKey(food) } ?: false }
        place { item, food -> item.names.any { sameFood(it, food.name) } }

        val removedKeySet = removedKeys.mapNotNull(::validKey).toSet()
        val keptNames = kept.flatMap { it.names }
        val survivors = result.indices.filter { index ->
            if (index in taken) return@filter true
            val food = result[index]
            if (keptNames.any { isPart(food.name, of = it) }) return@filter false
            if (removed.any { FoodMatch.fold(it) == FoodMatch.fold(food.name) || sameFood(it, food.name) }) {
                return@filter false
            }
            val key = genericKey(food)
            !(key != null && key in removedKeySet)
        }
        val appended = kept.indices.filter { it !in placed }.map { kept[it].food }
        return survivors.map { result[it] } + appended
    }

    // MARK: Name matching

    /**
     * Two names of one food: the same head word (the first word, which carries the food in Polish
     * names), and every word of one found in the other. "Olej" ↔ "Olej rzepakowy" match; "Olej" ↔
     * "Sałatka z olejem" do not.
     */
    fun sameFood(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        val hx = x.firstOrNull() ?: return false
        val hy = y.firstOrNull() ?: return false
        if (!sameWord(hx, hy)) return false
        return covers(x, y) || covers(y, x)
    }

    /** Every word of [part] is in [of]: the model's "Masło" is a part of the user's "Ziemniaki z masłem". */
    fun isPart(part: String, of: String): Boolean {
        val x = words(part)
        return x.isNotEmpty() && covers(words(of), x)
    }

    /** Folded words without the linking ones ("z", "w", "i", "with" …), in order. */
    fun words(name: String): List<String> {
        val folded = FoodMatch.fold(name)
        val out = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() {
            val w = word.toString()
            word.setLength(0)
            if (w.codePointCount(0, w.length) > 1 && w !in LINKING_WORDS) out += w
        }
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (isLetterOrNumber(cp)) word.appendCodePoint(cp) else flush()
            i += Character.charCount(cp)
        }
        flush()
        return out
    }

    /**
     * Equal, or one Polish stem with different endings ("masło" / "masłem", "ziemniaki" /
     * "ziemniakami"): both at least 4 letters and sharing all but at most the last 2 letters of the
     * shorter, and never fewer than 4.
     */
    fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        if (x.size < 4 || y.size < 4) return false
        var common = 0
        while (common < x.size && common < y.size && x[common] == y[common]) common++
        return common >= max(4, min(x.size, y.size) - 2)
    }

    private val LINKING_WORDS = setOf(
        "z", "ze", "w", "we", "i", "na", "do", "od", "po", "bez", "o", "a", "oraz",
        "with", "and", "in", "of", "the", "on", "or",
    )

    /** Swift's `Character.isLetter || isNumber`: letters, digits and other numerals (½, Ⅻ). */
    private fun isLetterOrNumber(cp: Int): Boolean =
        Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }

    /** Every word of [part] has the same word in [whole]. */
    private fun covers(whole: List<String>, part: List<String>): Boolean =
        part.isNotEmpty() && part.all { word -> whole.any { sameWord(it, word) } }

    /** The item's generic-table key, null for "none" or none. */
    fun genericKey(food: AIFood): String? = food.genericKey?.let(::validKey)

    private fun validKey(key: String): String? {
        val trimmed = key.trim(' ', '\t')
        return if (trimmed.isEmpty() || trimmed == "none") null else trimmed
    }

    /** The count after a −1 / +1 step: whole units, with half a unit as the floor ("ate half"). */
    fun steppedCount(count: Double, up: Boolean): Double =
        if (up) {
            if (count < 1) 1.0 else min(MAX_COUNT, AIFinalizer.round1(count + 1))
        } else {
            if (count > 1) max(0.5, AIFinalizer.round1(count - 1)) else 0.5
        }

    /** "280", "280.5": the machine form of a number, dot decimal, at most one decimal. */
    fun number(value: Double): String {
        val r = AIFinalizer.round1(value)
        return if (r == floor(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    /** The first characters of [text] that fit in [limit] UTF-16 units, never splitting a character. */
    fun truncated(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val boundaries = BreakIterator.getCharacterInstance()
        boundaries.setText(text)
        var end = 0
        var next = boundaries.next()
        while (next != BreakIterator.DONE && next <= limit) {
            end = next
            next = boundaries.next()
        }
        return text.substring(0, end)
    }

    /**
     * A food-database product added to the estimate ("add something it missed"): its per-100 g
     * values at [grams], one unit, full confidence, logged as an ordinary food entry.
     */
    fun fromDatabase(food: PortionFood, grams: Double): AIFood {
        val g = AIFinalizer.round1(grams)
        return AIFood(
            name = food.name,
            grams = g,
            kcal = 0.0,
            protein = 0.0,
            carbs = 0.0,
            fat = 0.0,
            confidence = 1.0,
            portionCount = 1.0,
            portionUnit = "",
            gramsPerUnit = g,
            nutritionSource = DATABASE_SOURCE,
            genericKey = "none",
            adjustments = emptyList(),
            databaseFood = when (food) {
                is PortionFood.Item -> AIDatabaseFood.Item(food.item.id)
                is PortionFood.Candidate -> AIDatabaseFood.Candidate(food.candidate)
            },
        ).withNutrition(AIPer100(food.kcalPer100, food.proteinPer100, food.carbsPer100, food.fatPer100))
    }

    /** The app-local `nutritionSource` of a food-database pick (never on the wire). */
    const val DATABASE_SOURCE = "database"
}

/**
 * `AIFood.replaced(by:)`: a food-database product standing in for this item — its name and
 * per-100 g values at this item's grams, the count kept when it had one, full confidence, logged as
 * an ordinary food entry.
 */
fun AIFood.replacedBy(food: PortionFood): AIFood {
    val copy = AIScanCorrections.fromDatabase(food, grams).copy(id = id)
    val count = portionCount
    return if (count != null && count > 0) {
        copy.copy(
            portionCount = count,
            portionUnit = portionUnit,
            gramsPerUnit = AIFinalizer.round1(copy.grams / count),
        )
    } else {
        copy
    }
}

/**
 * The editable half of `AIScanModel`: the items on screen and the corrections made to them. Pure,
 * so every correction is unit tested without a view model.
 *
 * [kept] maps an item the user corrected or added to every name it had (the model's first); those
 * survive "Recalculate with details". [added] are the user's food-database additions (removing one
 * tells the model nothing). [removedNames] are the model's items the user removed, and
 * [removedKeys] their generic-table keys, so a removed item that comes back renamed stays removed.
 */
@Immutable
data class AIScanEdits(
    val foods: List<AIFood> = emptyList(),
    val kept: Map<String, List<String>> = emptyMap(),
    val added: Set<String> = emptySet(),
    val removedNames: List<String> = emptyList(),
    val removedKeys: List<String> = emptyList(),
) {
    /** The corrections line the next "Recalculate" sends, if the user changed anything. */
    val correctionsLine: String?
        get() = AIScanCorrections.line(foods.filter { it.id in kept }, removedNames)

    /** The full notes string for a request: typed details plus, on a refine, the corrections line. */
    fun outgoingNotes(typed: String, refining: Boolean): String =
        AIScanCorrections.notes(typed, if (refining) correctionsLine else null)

    /** The kept items in screen order, with their names, for [AIScanCorrections.merge]. */
    val keptItems: List<AIScanCorrections.Kept>
        get() = foods.mapNotNull { food -> kept[food.id]?.let { AIScanCorrections.Kept(food, it) } }

    /** The items after an answer: the model's as they are, or on a refine merged with the corrections. */
    fun answered(refined: List<AIFood>, refining: Boolean): List<AIFood> =
        if (refining) AIScanCorrections.merge(refined, keptItems, removedNames, removedKeys) else refined

    /**
     * Replaces the item with the editor's copy (name, count, grams, or a food-database product).
     * Unchanged when nothing changed, so opening and closing the editor is no correction.
     */
    fun update(edited: AIFood): AIScanEdits {
        val index = foods.indexOfFirst { it.id == edited.id }
        if (index < 0 || foods[index] == edited) return this
        val names = kept[edited.id] ?: listOf(foods[index].name)
        return copy(
            foods = foods.toMutableList().also { it[index] = edited },
            kept = kept + (edited.id to if (edited.name in names) names else names + edited.name),
        )
    }

    /** Drops a wrong item (the guessed oil, a hallucinated side). A removed model item is reported on refine. */
    fun remove(id: String): AIScanEdits {
        val food = foods.firstOrNull { it.id == id } ?: return this
        val isModelItem = id !in added
        val reported = if (isModelItem) removedNames + (kept[id]?.firstOrNull() ?: food.name) else removedNames
        val key = if (isModelItem) AIScanCorrections.genericKey(food) else null
        return copy(
            foods = foods.filterNot { it.id == id },
            kept = kept - id,
            added = added - id,
            removedNames = reported,
            removedKeys = if (key != null) removedKeys + key else removedKeys,
        )
    }

    /** Something the model missed, picked from the food database and sized by the user. */
    fun append(food: AIFood): AIScanEdits = copy(
        foods = foods + food,
        added = added + food.id,
        kept = kept + (food.id to listOf(food.name)),
    )

    /** `AIScanModel.setGrams(_:for:)` — rescaled from the item's per-100 g values. */
    fun setGrams(id: String, grams: Double): AIScanEdits {
        val food = foods.firstOrNull { it.id == id } ?: return this
        if (grams <= 0) return this
        return update(food.scaled(grams))
    }

    /** `AIScanModel.scale(by:for:)` — the ±% menu, to whole grams. */
    fun scale(id: String, factor: Double): AIScanEdits {
        val food = foods.firstOrNull { it.id == id } ?: return this
        return setGrams(id, AIScanDerive.scaledGrams(food.grams, factor))
    }

    /** −1 / +1 unit ("6 szt." → "7 szt."), grams follow the unit weight. */
    fun stepCount(id: String, up: Boolean): AIScanEdits {
        val food = foods.firstOrNull { it.id == id } ?: return this
        return update(food.withCount(AIScanCorrections.steppedCount(food.units, up)))
    }
}
