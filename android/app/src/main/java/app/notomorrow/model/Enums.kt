package app.notomorrow.model

/**
 * Every enum the app persists or puts on the wire, with the **iOS raw strings**
 * verbatim (`NoTomorrow/Models/Models.swift`, `Services/AppConfig.swift`,
 * `Services/AttendanceService.swift`, `App/AppState.swift`).
 *
 * `raw` is the storage/wire form and must never change. `from(raw)` is the
 * tolerant decoder used by Room `TypeConverters` and the backend DTOs.
 */

enum class TrainingGoal(val raw: String) {
    BuildMuscle("buildMuscle"),
    LoseFat("loseFat"),
    Maintain("maintain");

    companion object {
        fun from(raw: String?): TrainingGoal? = entries.firstOrNull { it.raw == raw }
    }
}

enum class WeightUnit(val raw: String) {
    Kg("kg"),
    Lb("lb");

    companion object {
        fun from(raw: String?): WeightUnit? = entries.firstOrNull { it.raw == raw }
    }
}

enum class SetKind(val raw: String) {
    Normal("normal"),
    Warmup("warmup"),
    Drop("drop"),
    Failure("failure");

    companion object {
        fun from(raw: String?): SetKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** `MeasurementKind` — what a `body_measurement` row measures, in display order. */
enum class MeasurementKind(val raw: String) {
    Waist("waist"),
    Chest("chest"),
    Hips("hips"),
    Arm("arm"),
    Thigh("thigh"),
    Neck("neck"),
    BodyFat("bodyFat");

    companion object {
        fun from(raw: String?): MeasurementKind? = entries.firstOrNull { it.raw == raw }
    }
}

enum class MealSlot(val raw: String) {
    Breakfast("breakfast"),
    Lunch("lunch"),
    Snack("snack"),
    Dinner("dinner");

    companion object {
        fun from(raw: String?): MealSlot? = entries.firstOrNull { it.raw == raw }
    }
}

enum class FoodSource(val raw: String) {
    OpenFoodFacts("openFoodFacts"),
    Usda("usda"),
    Custom("custom"),
    AiEstimate("aiEstimate"),
    QuickAdd("quickAdd");

    companion object {
        fun from(raw: String?): FoodSource? = entries.firstOrNull { it.raw == raw }
    }
}

enum class AttendanceStatus(val raw: String) {
    Planned("planned"),
    Confirmed("confirmed"),
    Attended("attended"),
    Missed("missed"),
    Cancelled("cancelled");

    companion object {
        fun from(raw: String?): AttendanceStatus? = entries.firstOrNull { it.raw == raw }
    }
}

enum class HeadsUpKind(val raw: String) {
    CantMakeIt("cantMakeIt"),
    RunningLate("runningLate"),
    LetsGo("letsGo"),
    Custom("custom"),
    MakeUpProposal("makeUpProposal");

    companion object {
        fun from(raw: String?): HeadsUpKind? = entries.firstOrNull { it.raw == raw }
    }
}

enum class Participant(val raw: String) {
    Me("me"),
    Partner("partner");

    companion object {
        fun from(raw: String?): Participant? = entries.firstOrNull { it.raw == raw }
    }
}

enum class BodyWeightSource(val raw: String) {
    Manual("manual"),
    HealthKit("healthKit");

    companion object {
        fun from(raw: String?): BodyWeightSource? = entries.firstOrNull { it.raw == raw }
    }
}

enum class AIProvider(val raw: String) {
    Standard("standard"),
    ClaudeBYOK("claudeBYOK"),
    GeminiBYOK("geminiBYOK");

    companion object {
        // Case-insensitive: the BYOK raws are camel-cased, and a value that came back lowercased
        // (an older build, a hand-edited preference) must still resolve rather than reset to standard.
        fun from(raw: String?): AIProvider? = entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) }
    }
}

/**
 * Derived, never persisted — the Swift original carries an associated value on
 * `cancelled`, so this is a sealed hierarchy rather than an enum.
 * (`AttendanceService.swift:5`.)
 */
sealed interface DayState {
    data object Rest : DayState
    data object Planned : DayState
    data object Confirmed : DayState
    data object Attended : DayState
    data object Missed : DayState
    data class Cancelled(val reason: String?) : DayState
}

/** `AppState.swift:4` — `Int` raw values, order is the tab-bar order. */
enum class AppTab(val raw: Int, val route: String) {
    Today(0, "today"),
    Train(1, "train"),
    Fuel(2, "fuel"),
    Progress(3, "progress"),
    Bro(4, "bro");

    companion object {
        fun from(raw: Int?): AppTab? = entries.firstOrNull { it.raw == raw }
        fun fromRoute(route: String?): AppTab? = entries.firstOrNull { it.route == route }
    }
}
