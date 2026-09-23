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
import androidx.compose.foundation.layout.Box
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
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.model.FoodCandidate
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.service.Days
import app.notomorrow.service.GTINExtractor
import app.notomorrow.util.Parsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /** OFF's estimate stands in for missing label values; a saved food never carries the flag. */
    val isEstimated: Boolean get() = false

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
        override val isEstimated: Boolean get() = candidate.isEstimated
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
     * `FuelModel.snapBackInterval`: a past day left in the background longer than this returns
     * to today (30 min, in milliseconds).
     */
    const val SNAP_BACK_MS: Long = 30L * 60 * 1000

    /**
     * `FuelModel.rolledDay`: the day to show after the calendar day moved from [previousToday]
     * to [today] — a model that was on today follows it, a past day being browsed stays put, and
     * anything ahead of the new today clamps to it.
     */
    fun rolledDay(selected: LocalDate, previousToday: LocalDate, today: LocalDate): LocalDate =
        if (selected == previousToday || selected.isAfter(today)) today else selected

    /**
     * `FuelModel.resumedDay`: the day to show on return from the background after [awayMs]
     * (null when the app was never seen leaving). Strictly more than [SNAP_BACK_MS]: exactly
     * 30 minutes stays.
     */
    fun resumedDay(selected: LocalDate, today: LocalDate, awayMs: Long?): LocalDate =
        if (awayMs != null && awayMs > SNAP_BACK_MS) today else selected

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
     * `FuelText.fieldText` (`FuelSupport.swift`): a figure as it should appear in an editable
     * field — at most one decimal in the user's locale and **never grouped**, so it parses back
     * through [Parsing.decimal] ("1234,5", not "1 234,5" or "1,234.5"; finding l10n-a11y-11).
     * Half-even rounding, like Foundation's: 72.04 reads "72", 0.25 reads "0,2".
     */
    fun portionText(value: Double, locale: Locale): String =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(value)

    /**
     * `FuelText.editedFigure`: the value of an edit field that was prefilled from a stored
     * figure. The field shows the figure rounded, so text the user left alone keeps the exact
     * figure; anything else is parsed (null when it is not a non-negative number).
     */
    fun editedFigure(text: String, prefill: String, original: Double): Double? =
        if (text == prefill) original else Parsing.nonNegative(text)

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

    /**
     * `MealEntry.resize(to:)` (`FuelSupport.swift:154`): a food-backed entry re-sized — the four
     * macros follow the food's per-100 g figures, exactly as `PortionSheet` derives them on
     * insert. `id` and `loggedAt` are untouched, so the row keeps its place in the day.
     */
    fun resized(
        entry: MealEntryEntity,
        food: FoodItemEntity,
        grams: Double,
        slot: MealSlot,
    ): MealEntryEntity {
        val factor = grams / 100.0
        return entry.copy(
            slot = slot,
            grams = grams,
            kcal = food.kcalPer100 * factor,
            proteinG = food.proteinPer100 * factor,
            carbsG = food.carbsPer100 * factor,
            fatG = food.fatPer100 * factor,
        )
    }

    /**
     * `MealEntry.overwrite(name:…)` (`FuelSupport.swift:166`): a custom (quick-add / AI) entry
     * rewritten with the user's figures. Once any number moves the row is no longer an estimate,
     * so the AI badge and its confidence go; a rename or a slot move alone keeps them.
     */
    fun overwritten(
        entry: MealEntryEntity,
        name: String,
        grams: Double,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        slot: MealSlot,
    ): MealEntryEntity {
        val figuresChanged = entry.grams != grams || entry.kcal != kcal ||
            entry.proteinG != proteinG || entry.carbsG != carbsG || entry.fatG != fatG
        return entry.copy(
            slot = slot,
            customName = name,
            grams = grams,
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            isAIEstimate = if (figuresChanged) false else entry.isAIEstimate,
            confidence = if (figuresChanged) null else entry.confidence,
        )
    }

    /**
     * The whole of `PortionSheet.add()` in edit mode: grams that really changed re-derive the
     * figures from the food ([resized]); a slot or day move alone keeps the logged kcal and
     * macros exactly, even if the food's per-100 g values changed since. Then [moved].
     */
    fun editedPortion(
        entry: MealEntryEntity,
        food: FoodItemEntity,
        grams: Double,
        slot: MealSlot,
        day: LocalDate,
        zone: ZoneId,
    ): MealEntryEntity {
        val sized = if (grams != entry.grams) resized(entry, food, grams, slot) else entry.copy(slot = slot)
        return moved(sized, day, zone)
    }

    /**
     * The whole of `QuickAddSheet.add()` in edit mode. Every number field still showing its
     * [prefill] saves the entry's exact figure ([editedFigure]), so a rename, slot or day move
     * keeps an AI row's badge, confidence and numbers; [overwritten] only sees real edits.
     * [written] is what the sheet itself last put in the figure fields (the prefill, then each
     * [rescaledTexts]): while they still show it, a grams change saves the exact rescaled figures
     * ([figuresAt]). Null when the kcal text is not a number (Save is disabled then).
     */
    fun editedCustomEntry(
        entry: MealEntryEntity,
        name: String,
        texts: EntryEditTexts,
        prefill: EntryEditTexts,
        showsGrams: Boolean,
        slot: MealSlot,
        day: LocalDate,
        zone: ZoneId,
        written: EntryEditTexts? = null,
    ): MealEntryEntity? {
        val typedKcal = Parsing.nonNegative(texts.kcal) ?: return null
        val grams = if (showsGrams) editedFigure(texts.grams, prefill.grams, entry.grams) ?: entry.grams else 0.0
        // The figures still show what the sheet wrote (the prefill, then each rescale).
        val untouched = written != null && texts.sameFigures(written)
        val exact = if (showsGrams && grams != entry.grams && untouched) figuresAt(entry, grams) else null
        val overwritten = if (exact != null) {
            // The fields show the rescale rounded; save it exact.
            overwritten(
                entry = entry,
                name = name,
                grams = grams,
                kcal = exact.kcal,
                proteinG = exact.protein,
                carbsG = exact.carbs,
                fatG = exact.fat,
                slot = slot,
            )
        } else {
            overwritten(
                entry = entry,
                name = name,
                grams = grams,
                kcal = editedFigure(texts.kcal, prefill.kcal, entry.kcal) ?: typedKcal,
                proteinG = editedFigure(texts.protein, prefill.protein, entry.proteinG) ?: 0.0,
                carbsG = editedFigure(texts.carbs, prefill.carbs, entry.carbsG) ?: 0.0,
                fatG = editedFigure(texts.fat, prefill.fat, entry.fatG) ?: 0.0,
                slot = slot,
            )
        }
        return moved(overwritten, day, zone)
    }

    /**
     * `MealEntry.figures(atGrams:)`: this entry's kcal and macros at [newGrams], keeping its
     * figures per gram — an AI or quick-add row whose portion is weighed later scales like a food
     * would. Null when the row has no portion to scale from.
     */
    fun figuresAt(entry: MealEntryEntity, newGrams: Double): EntryFigures? {
        if (entry.grams <= 0 || newGrams <= 0) return null
        val factor = newGrams / entry.grams
        return EntryFigures(
            kcal = entry.kcal * factor,
            protein = entry.proteinG * factor,
            carbs = entry.carbsG * factor,
            fat = entry.fatG * factor,
        )
    }

    /**
     * `MealEntry.rescaledTexts(gramsText:prefill:)`: the edit sheet's figure texts after its grams
     * field changed to [gramsText] — rescaled from [entry], or the prefill again when the grams are
     * back at theirs. Null when the grams are not a positive number (leave the fields).
     */
    fun rescaledTexts(
        entry: MealEntryEntity,
        gramsText: String,
        prefill: EntryEditTexts,
        locale: Locale,
    ): EntryEditTexts? {
        if (gramsText == prefill.grams) return prefill
        val newGrams = Parsing.nonNegative(gramsText) ?: return null
        val f = figuresAt(entry, newGrams) ?: return null
        return EntryEditTexts(
            grams = gramsText,
            kcal = portionText(f.kcal, locale),
            protein = portionText(f.protein, locale),
            carbs = portionText(f.carbs, locale),
            fat = portionText(f.fat, locale),
        )
    }

    /**
     * `MealEntry.move(to:)`: the entry on another calendar day (local midnight). Slot, figures
     * and `loggedAt` stay — the slot is the "time" the UI shows, and the row sorts by `loggedAt`
     * inside it. Picking the day it is already listed under ([FuelCalendar.dayKey]) changes
     * nothing, so a midnight stored in another time zone is not rewritten.
     */
    fun moved(entry: MealEntryEntity, day: LocalDate, zone: ZoneId): MealEntryEntity =
        if (FuelCalendar.dayKey(entry.day, zone) == day) entry
        else entry.copy(day = Days.millis(day, zone))

    /**
     * `MealEntry.copy(to:loggedAt:)`: a new entry ([id]) with this one's slot, food, name and
     * figures — AI flags included — on [day], logged at [loggedAt].
     */
    fun copied(
        entry: MealEntryEntity,
        id: String,
        day: LocalDate,
        loggedAt: Long,
        zone: ZoneId,
    ): MealEntryEntity = entry.copy(id = id, day = Days.millis(day, zone), loggedAt = loggedAt)

    /**
     * `MealEntry.Snapshot.restore()`: a deleted row ready to insert again — same id, the exact
     * stored `day`, `loggedAt` and AI flags. A food deleted in the meantime is dropped (its row
     * would violate the foreign key); the entry keeps the [displayName] it showed.
     */
    fun restored(entry: MealEntryEntity, displayName: String, foodExists: Boolean): MealEntryEntity =
        if (entry.foodId != null && !foodExists) entry.copy(foodId = null, customName = displayName)
        else entry

    /** `EntryDayStepper.step`: [days] from [day], never past [today]. */
    fun stepDay(day: LocalDate, days: Long, today: LocalDate): LocalDate =
        minOf(day.plusDays(days), today)

    /**
     * How far (as a fraction of the row's width) an entry row must travel before a swipe edits
     * or deletes it, **whatever the release speed**. Material's default commits any flick over
     * 125 dp/s, which deleted a past entry on an 83 dp flick; iOS needs a long full swipe.
     */
    const val SWIPE_COMMIT_FRACTION: Float = 0.5f

    /** Whether a row released at [offsetPx] (either direction) of [widthPx] commits its swipe. */
    fun swipeCommits(offsetPx: Float, widthPx: Float): Boolean =
        widthPx > 0f && kotlin.math.abs(offsetPx) >= widthPx * SWIPE_COMMIT_FRACTION

    /** `FuelModel.undoDuration` / `undoDurationVoiceOver`: the undo toast's life, in ms. */
    const val UNDO_MS: Long = 4_000

    /** TalkBack needs time to reach the Undo button. */
    const val UNDO_SCREEN_READER_MS: Long = 10_000

    fun undoDurationMs(screenReader: Boolean): Long = if (screenReader) UNDO_SCREEN_READER_MS else UNDO_MS

    /** `ManualBarcodeEntry.digits` (`BarcodeScannerView.swift`): ASCII digits only. */
    fun barcodeDigits(raw: String): String = raw.filter { it in '0'..'9' }

    fun isValidBarcode(digits: String): Boolean = digits.length in 8..14

    /**
     * `ManualBarcodeEntry.validCode`: the code to look up — a GTIN whose check digit is right, or
     * a UPC-E expanded. `null` while it cannot be a real code.
     */
    fun manualBarcode(digits: String): String? = GTINExtractor.manual(digits)

    /** `ManualBarcodeEntry.showsCheckDigits`: a complete-looking code whose check digit is wrong, most likely a typo. */
    fun showsCheckDigits(digits: String): Boolean = digits.length in MANUAL_CODE_LENGTHS && manualBarcode(digits) == null

    private val MANUAL_CODE_LENGTHS = setOf(8, 12, 13, 14)
}

/** `MealEntry.Figures`: an entry's kcal and macros at another portion ([FuelDerive.figuresAt]). */
@Immutable
data class EntryFigures(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)

/**
 * `MealEntry.EditTexts`: what the quick-add edit sheet prefills — grams only when the row has a
 * portion (AI rows), every figure through [FuelDerive.portionText] (ungrouped, one decimal).
 */
@Immutable
data class EntryEditTexts(
    val grams: String,
    val kcal: String,
    val protein: String,
    val carbs: String,
    val fat: String,
) {
    /** Same kcal and macro texts (grams ignored): the user has not typed over what the sheet filled in. */
    fun sameFigures(other: EntryEditTexts): Boolean =
        kcal == other.kcal && protein == other.protein && carbs == other.carbs && fat == other.fat

    companion object {
        fun of(entry: MealEntryEntity, locale: Locale): EntryEditTexts = EntryEditTexts(
            grams = if (entry.grams > 0) FuelDerive.portionText(entry.grams, locale) else "",
            kcal = FuelDerive.portionText(entry.kcal, locale),
            protein = FuelDerive.portionText(entry.proteinG, locale),
            carbs = FuelDerive.portionText(entry.carbsG, locale),
            fat = FuelDerive.portionText(entry.fatG, locale),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Day boundary
// ─────────────────────────────────────────────────────────────────────────────

/** The selected day and the calendar day Fuel treats as today, always updated together. */
@Immutable
data class FuelDays(val day: LocalDate, val today: LocalDate)

/**
 * `FuelModel`'s day state (`FuelModel.swift`): the selected day, the calendar day it last treated
 * as today, and the background stamp behind the 30-minute snap-back. [FuelViewModel] owns one and
 * feeds it the clock, so the rollover rules run on the JVM.
 *
 * Every move returns whether the day actually changed: the screen ticks the selection haptic on
 * that, and only at the call sites the user drives (never for midnight, the snap-back or the
 * Dashboard jump).
 */
class FuelDayNavigator(today: LocalDate) {

    private val _days = MutableStateFlow(FuelDays(day = today, today = today))

    /** One flow for both, so a rollover never emits a new today with a stale day. */
    val days: StateFlow<FuelDays> = _days.asStateFlow()

    val day: LocalDate get() = _days.value.day

    /** `syncToday` moves it, and `day` with it when it was on today. */
    val today: LocalDate get() = _days.value.today

    /** When the app last went to the background (epoch millis); cleared when it comes back. */
    private var backgroundedAt: Long? = null

    fun goPreviousDay(): Boolean = move(day.minusDays(1))

    /** Forward stops at [now] (the real clock, as `FuelModel.canGoForward` reads it). */
    fun goNextDay(now: LocalDate): Boolean {
        if (!FuelDerive.canGoForward(day, now)) return false
        return move(day.plusDays(1))
    }

    /** Jump straight to a day (the History sheet). Future days clamp to [now]. */
    fun goTo(date: LocalDate, now: LocalDate): Boolean = move(minOf(date, now))

    fun goToday(now: LocalDate): Boolean = goTo(now, now)

    /**
     * Midnight rollover, on appear and on a date change: when the calendar day has changed since
     * the last call, a navigator that was showing today follows it; a past day being browsed stays.
     */
    fun syncToday(now: LocalDate) {
        val current = _days.value
        if (now == current.today) return
        _days.value = FuelDays(day = FuelDerive.rolledDay(current.day, current.today, now), today = now)
    }

    fun appDidEnterBackground(atMillis: Long) {
        backgroundedAt = atMillis
    }

    /** Back in the foreground: roll over, then a past day left for longer than 30 min returns to today. */
    fun appWillEnterForeground(atMillis: Long, now: LocalDate) {
        val away = backgroundedAt?.let { atMillis - it }
        backgroundedAt = null
        syncToday(now)
        move(FuelDerive.resumedDay(day, today, away))
    }

    private fun move(target: LocalDate): Boolean {
        val current = _days.value
        if (target == current.day) return false
        _days.value = current.copy(day = target)
        return true
    }
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

/**
 * The `surface3` background revealed by a leading swipe on an entry row — iOS's
 * `.swipeActions(edge: .leading)` Edit button (`pencil`, `.tint(NT.Colors.surface3)`).
 */
@Composable
fun FuelEditBackground(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.surface3),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(
            icon = NtIcons.Pencil,
            modifier = Modifier.padding(start = 20.dp),
            size = sfIconSize(15f),
            tint = NT.Colors.ink,
            contentDescription = null,
        )
        NtText(
            text = stringResource(S.common_edit),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * `FuelHomeView.undoToast`: "Entry deleted   Undo" / "Added to today   Undo" on a 44 dp
 * `surface2` capsule, in the lookup pill's place above the add bar. Not a live region: the screen
 * announces every new undo itself (`UndoTimerEffect`, as iOS posts an announcement), which a live
 * region could not do for a second delete in a row, whose text does not change.
 */
@Composable
fun FuelUndoToast(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(NT.Size.control)
            .background(NT.Colors.surface2, CircleShape)
            .padding(start = 18.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = message,
            modifier = Modifier.weight(1f, fill = false),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .height(NT.Size.control)
                .pressScale(onClick = onUndo)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_undo),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
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
