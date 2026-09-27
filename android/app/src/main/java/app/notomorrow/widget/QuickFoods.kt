package app.notomorrow.widget

import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.service.Days
import app.notomorrow.service.FoodMatch
import java.time.ZoneId

/**
 * A food the user logs over and over, logged again with one tap from the Quick log widget —
 * `QuickFood` (`Shared/WidgetSnapshot.swift`, `docs/widgets.md` "Quick foods").
 *
 * [key] is `food:<FoodItem.id>` or `name:<folded custom name>`; the figures are the newest entry's.
 */
data class QuickFood(
    val key: String,
    val foodId: String?,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val isAIEstimate: Boolean,
)

/**
 * `QuickFood.pick` — the same rule on both platforms, pure so the JVM tests assert it directly.
 */
object QuickFoods {

    /** How many foods the widget keeps; the medium layout shows 3, the small one 1. */
    const val LIMIT = 4

    /** Days of history the ranking looks at. */
    const val WINDOW_DAYS = 60L

    /** One meal entry as the ranking sees it: `MealEntryWithFood` flattened, [name] its display name. */
    data class Candidate(
        val foodId: String?,
        val name: String,
        val grams: Double,
        val kcal: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val isAIEstimate: Boolean,
        /** The stored `meal_entry.day` (local midnight at logging time). */
        val day: Long,
        val loggedAt: Long,
    )

    /**
     * The meal entries of the last [WINDOW_DAYS] days (by stored day, through the Fuel day key, as iOS
     * fetches them), grouped by food id, or by the folded name for entries without a food. Ranked by
     * number of entries (desc), then by the newest `loggedAt` (desc), then by key for a stable order;
     * the top [limit], each carrying the **newest** entry's name, grams, figures and AI flag.
     * An entry with neither a food nor a name is skipped.
     */
    fun pick(
        candidates: List<Candidate>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = LIMIT,
    ): List<QuickFood> {
        val lower = FuelCalendar.storedDayBounds(Days.date(now, zone).minusDays(WINDOW_DAYS), zone).lower
        class Group(var count: Int, var newest: Candidate)
        val groups = LinkedHashMap<String, Group>()
        for (candidate in candidates) {
            if (candidate.day < lower) continue
            val trimmed = candidate.name.trim()
            if (candidate.foodId == null && trimmed.isEmpty()) continue
            val key = candidate.foodId?.let { "food:$it" } ?: ("name:" + fold(trimmed))
            val group = groups[key]
            if (group == null) {
                groups[key] = Group(1, candidate)
            } else {
                group.count += 1
                if (candidate.loggedAt > group.newest.loggedAt) group.newest = candidate
            }
        }
        return groups.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Group>> { it.value.count }
                    .thenByDescending { it.value.newest.loggedAt }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { (key, group) ->
                val c = group.newest
                QuickFood(
                    key = key,
                    foodId = c.foodId,
                    name = c.name.trim(),
                    grams = c.grams,
                    kcal = c.kcal,
                    protein = c.protein,
                    carbs = c.carbs,
                    fat = c.fat,
                    isAIEstimate = c.isAIEstimate,
                )
            }
    }

    /**
     * `QuickFood.fold`: case, diacritics and width folded, `ł` → `l`, whitespace runs collapsed —
     * "Owsianka ", "owsianka" and "OWSIANKA" are one food.
     */
    fun fold(name: String): String =
        FoodMatch.fold(name).split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The Break timer's three lengths (`docs/widgets.md`, "Break timer"): `1:00`, the default and `2:00`;
 * when the default is itself 60 or 120 s the row is `1:00 · 1:30 · 2:00`. Ascending, so a default
 * outside 1–2 min still reads left to right; [primary] is the default (the white capsule).
 */
data class RestPresets(val seconds: List<Int>, val primary: Int) {
    companion object {
        fun of(defaultSeconds: Int): RestPresets {
            val default = defaultSeconds.coerceAtLeast(5)
            val middle = if (default == 60 || default == 120) 90 else default
            return RestPresets(listOf(60, middle, 120).sorted(), primary = default)
        }
    }
}
