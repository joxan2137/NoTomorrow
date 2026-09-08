package app.notomorrow.model

/**
 * Non-entity domain value types that no other package owns.
 *
 * The backend surface (`Session`, `Me`, `Partner`, `ScheduleDto`, `AttendanceDto`,
 * `HeadsUpDto`, `PartnerState`, `AIFood`, `AIEstimate`) lives in `net/dto` — it is wire
 * shaped, carries its own serializers and belongs to the backend client, per
 * `docs/android-architecture.md`. Do not redeclare those here.
 */

/**
 * One Open Food Facts hit, normalised per 100 g — the port of `FoodCandidate`
 * (`Services/FoodSearchService.swift:4`). `FoodSearchService` produces it;
 * `FoodCandidate.makeFoodItem()` (`data/entity/Nutrition.kt`) turns it into the
 * persisted `FoodItemEntity` when the user taps it.
 */
data class FoodCandidate(
    /** `"off:<code>"`. */
    val id: String,
    val code: String,
    val name: String,
    val brand: String? = null,
    /** `"500 g"` as printed on the pack. */
    val quantity: String? = null,
    val servingSizeG: Double? = null,
    val servingLabel: String? = null,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    val fiberPer100: Double? = null,
    val imageURL: String? = null,
)
