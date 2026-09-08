package app.notomorrow.feature.fuel

import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.util.S
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.model.FoodCandidate
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.util.Fmt
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/*
 * Shared helpers for the Fuel feature — the port of `Features/Fuel/FuelSupport.swift`
 * (meal-slot ordering and the `PortionFood` payload) plus the pure derivations
 * `FuelModel.swift` keeps as computed properties.
 *
 * Everything in [FuelDerive] is deliberately framework-free so it can be unit tested
 * on the JVM (`app/src/test/java/app/notomorrow/feature/fuelhome`).
 */

/** `MealSlot.ordered` (`FuelSupport.swift:8`) — display order on the Fuel home and in menus. */
val MealSlotOrdered: List<MealSlot> =
    listOf(MealSlot.Breakfast, MealSlot.Lunch, MealSlot.Snack, MealSlot.Dinner)

/**
 * `MealSlot.suggested(at:)` (`FuelSupport.swift:16`) — the slot the bottom bar targets
 * when the user has not picked one, by clock time.
 */
fun suggestedMealSlot(at: LocalTime = LocalTime.now()): MealSlot = when {
    at.hour < 11 -> MealSlot.Breakfast
    at.hour < 15 -> MealSlot.Lunch
    at.hour < 18 -> MealSlot.Snack
    else -> MealSlot.Dinner
}

/** `FuelModel.Goals` (`FuelModel.swift:10`). */
@Immutable
data class FuelGoals(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
) {
    companion object {
        /** `Goals.fallback` — used until onboarding has written a profile. */
        val Fallback = FuelGoals(kcal = 2600.0, protein = 180.0, carbs = 300.0, fat = 80.0)
    }
}

/**
 * `PortionFood` (`FuelSupport.swift:52`): what a [PortionSheet] is sizing — a fresh
 * Open Food Facts hit, or a food already in the local library.
 */
@Immutable
sealed interface PortionFood {
    val id: String
    val name: String
    val brand: String?
    val source: FoodSource
    val kcalPer100: Double
    val proteinPer100: Double
    val carbsPer100: Double
    val fatPer100: Double
    val servingSizeG: Double?
    val servingLabel: String?

    @Immutable
    data class Candidate(val candidate: FoodCandidate) : PortionFood {
        override val id: String get() = candidate.id
        override val name: String get() = candidate.name
        override val brand: String? get() = candidate.brand
        override val source: FoodSource get() = FoodSource.OpenFoodFacts
        override val kcalPer100: Double get() = candidate.kcalPer100
        override val proteinPer100: Double get() = candidate.proteinPer100
        override val carbsPer100: Double get() = candidate.carbsPer100
        override val fatPer100: Double get() = candidate.fatPer100
        override val servingSizeG: Double? get() = candidate.servingSizeG
        override val servingLabel: String? get() = candidate.servingLabel
    }

    @Immutable
    data class Item(val item: FoodItemEntity) : PortionFood {
        override val id: String get() = item.id
        override val name: String get() = item.name
        override val brand: String? get() = item.brand
        override val source: FoodSource get() = item.source
        override val kcalPer100: Double get() = item.kcalPer100
        override val proteinPer100: Double get() = item.proteinPer100
        override val carbsPer100: Double get() = item.carbsPer100
        override val fatPer100: Double get() = item.fatPer100
        override val servingSizeG: Double? get() = item.servingSizeG
        override val servingLabel: String? get() = item.servingLabel
    }
}

/**
 * The pure half of `FuelModel` and `PortionSheet` — no Room, no Compose, no Android.
 *
 * Every function here is a 1:1 port of a computed property or a `private static` in the
 * Swift source; the view models call them and nothing else duplicates the arithmetic.
 */
object FuelDerive {

    /** `PortionSheet.step` — the −/+ increment, in grams. */
    const val PORTION_STEP: Double = 10.0

    /** `PortionSheet.minimum` — the stepper never goes below this. */
    const val PORTION_MINIMUM: Double = 5.0

    /** `FoodSearchModel.debounce` — 500 ms after the last keystroke. */
    const val SEARCH_DEBOUNCE_MS: Long = 500

    /** `FoodSearchModel.run` polls the cache this long after an `alreadyInFlight`. */
    const val IN_FLIGHT_RETRY_MS: Long = 700

    /** How far back `computeStreak` scans (`FuelModel.swift:88`). */
    const val STREAK_WINDOW_DAYS: Long = 120

    /** `FuelModel.kcalLeft` */
    fun kcalLeft(eaten: Double, goal: Double): Double = max(0.0, goal - eaten)

    /** `FuelModel.ringProgress` */
    fun ringProgress(eaten: Double, goal: Double): Double =
        if (goal > 0) min(1.0, eaten / goal) else 0.0

    /** `FuelModel.proteinRemaining` */
    fun proteinRemaining(eaten: Double, goal: Double): Double = max(0.0, goal - eaten)

    /** `FuelModel.canGoForward` — forward navigation stops at today. */
    fun canGoForward(day: LocalDate, today: LocalDate): Boolean = day.isBefore(today)

    /**
     * `FuelModel.computeStreak` (`FuelModel.swift:85`): consecutive days — ending today,
     * or yesterday when today has not reached the goal yet — with protein at or above
     * the goal. A non-positive goal has no streak.
     */
    fun proteinStreak(
        proteinByDay: Map<LocalDate, Double>,
        goal: Double,
        today: LocalDate,
    ): Int {
        if (goal <= 0) return 0
        var cursor = today
        if ((proteinByDay[cursor] ?: 0.0) < goal) cursor = cursor.minusDays(1)
        var streak = 0
        while ((proteinByDay[cursor] ?: 0.0) >= goal) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /**
     * `PortionSheet.text(for:)` (`PortionSheet.swift:171`): a whole number prints as a
     * bare integer (no grouping — iOS uses `String(Int(value))`), anything else through
     * the locale's decimal formatter at one fraction digit.
     */
    fun portionText(value: Double, locale: Locale): String =
        if (Fmt.roundHalfAwayFromZero(value) == value) value.toInt().toString()
        else Fmt.weight(value, withUnit = false, locale = locale)

    /** `PortionSheet.set(_:)` — the stepper clamps at [PORTION_MINIMUM]. */
    fun clampPortion(value: Double): Double = max(PORTION_MINIMUM, value)

    /** `FoodRecentRow.amount` — a recent food is priced at its serving, else 100 g. */
    fun recentAmount(servingSizeG: Double?): Double = servingSizeG ?: 100.0

    /**
     * `PortionSheet.portionChips` — the serving chip only exists when the food declares a
     * positive serving that is not simply 100 g.
     */
    fun showsServingChip(servingSizeG: Double?): Boolean {
        val serving = servingSizeG ?: return false
        return serving > 0 && kotlin.math.abs(serving - 100.0) > 0.5
    }

    /** `PortionSheet.portionChip` — selection is a 0.5 g tolerance around the chip value. */
    fun isPortionSelected(grams: Double, chip: Double): Boolean =
        kotlin.math.abs(grams - chip) < 0.5

    /** `ManualBarcodeEntry.digits` / `isValid` (`BarcodeScannerView.swift:130`). */
    fun barcodeDigits(raw: String): String = raw.filter { it.isDigit() }

    fun isValidBarcode(digits: String): Boolean = digits.length in 8..14
}

// ─────────────────────────────────────────────────────────────────────────────
// Text helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SwiftUI's `.minimumScaleFactor(f)` on a single line: measure the string at its natural
 * size and shrink the font just enough to fit, never below `f`.
 *
 * Two sites in this feature — the 132 dp hero counter (`0.6`) and nothing else — but the
 * hero number is exactly the place a four-digit `display(44)` overflows its 100 dp box.
 */
@Composable
fun ShrinkingText(
    text: String,
    style: TextStyle,
    color: Color,
    minScale: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val resolved = remember(text, style, available) {
            if (available <= 0) {
                style
            } else {
                val measured = measurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                )
                val width = measured.size.width
                val scale =
                    if (width > available) (available.toFloat() / width).coerceAtLeast(minScale)
                    else 1f
                if (scale >= 1f) style else style.copy(fontSize = style.fontSize * scale)
            }
        }
        NtText(text = text, style = resolved, color = color, maxLines = 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small shared views (used by both the Fuel home and the search sheet)
// ─────────────────────────────────────────────────────────────────────────────

/** The `bad` background revealed by a trailing swipe on an entry row. */
@Composable
fun FuelDeleteBackground(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.bad),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `Label("common.delete", systemImage: "trash")` — glyph *and* word.
        NtIcon(
            icon = NtIcons.Trash,
            size = sfIconSize(15f),
            tint = NT.Colors.ink,
            contentDescription = null,
        )
        NtText(
            text = stringResource(S.common_delete),
            modifier = Modifier.padding(end = 20.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/** The "Looking up the barcode…" pill (`FuelHomeView.lookupPill`). */
@Composable
fun FuelLookupPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(40.dp)
            .background(NT.Colors.surface2, CircleShape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtSpinner(color = NT.Colors.ink)
        NtText(
            text = stringResource(S.fuel_lookingUp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}
