package app.notomorrow.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * "Export my data": `workouts.csv` (one row per completed set) and `meals.csv`.
 * 1:1 port of `ExportCSV` in `Features/Settings/SettingsExport.swift` — same
 * columns, same order, same escaping.
 *
 * Machine-readable on purpose: ISO timestamps, dot decimals, no localization.
 * The Settings feature owns the file writing and the share sheet; this object
 * owns the bytes, so the format is testable without Room or a `Context`.
 */
object Csv {

    const val WORKOUTS_FILE = "workouts.csv"
    const val MEALS_FILE = "meals.csv"

    const val WORKOUTS_HEADER =
        "workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps,completed_at,pr,set_record"

    const val MEALS_HEADER =
        "day,slot,food,brand,grams,kcal,protein_g,carbs_g,fat_g,ai_estimate,logged_at"

    /** `"NoTomorrow-export-2026-09-04"` — the temp directory iOS builds. */
    fun exportDirectoryName(today: LocalDate): String = "NoTomorrow-export-" + day(today)

    /** One CSV row: fields escaped, joined with commas. */
    fun line(fields: List<String>): String = fields.joinToString(",") { escape(it) }

    /** Rows joined with `\n`, plus the trailing newline iOS writes. */
    fun file(rows: List<String>): String = rows.joinToString("\n") + "\n"

    /** Quote only when the field carries a comma, a quote or a newline; `"` doubles. */
    fun escape(field: String): String {
        if (!field.contains(',') && !field.contains('"') && !field.contains('\n')) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /** `.iso8601` — UTC, second precision, "2026-09-04T18:00:00Z". */
    fun iso(instant: Instant): String = ISO.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /** `.iso8601.year().month().day()` — "2026-09-04". */
    fun day(date: LocalDate): String = date.toString()

    /**
     * The day column when the source is a timestamp. iOS formats a day-start
     * `Date` through a **UTC** ISO style, so east-of-Greenwich days can land on
     * the previous date; pass the entity's `LocalDate` instead where you have
     * one, or an explicit [zone] to fix it.
     */
    fun day(instant: Instant, zone: ZoneId = ZoneOffset.UTC): String =
        day(instant.atZone(zone).toLocalDate())

    /** Whole numbers plain, everything else one decimal — dot separator, never localized. */
    fun number(value: Double): String {
        if (!value.isFinite()) return "0"
        if (value == floor(value)) return value.toLong().toString()
        // HALF_EVEN over the exact double, which is what Swift's `String(format: "%.1f")` does.
        return BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    }

    /** iOS writes booleans as "1" / "0". */
    fun flag(value: Boolean): String = if (value) "1" else "0"

    /** One completed set. `order` is zero-based; the column is `order + 1`, as on iOS. */
    fun workoutSetRow(
        workoutId: String,
        workoutName: String,
        startedAt: Instant,
        endedAt: Instant?,
        exerciseName: String,
        order: Int,
        kind: String,
        weightKg: Double,
        reps: Int,
        completedAt: Instant?,
        isPR: Boolean,
        isSetRecord: Boolean,
    ): String = line(
        listOf(
            workoutId,
            workoutName,
            iso(startedAt),
            endedAt?.let { iso(it) } ?: "",
            exerciseName,
            (order + 1).toString(),
            kind,
            number(weightKg),
            reps.toString(),
            completedAt?.let { iso(it) } ?: "",
            flag(isPR),
            flag(isSetRecord),
        )
    )

    /** One meal entry. */
    fun mealRow(
        day: String,
        slot: String,
        food: String,
        brand: String,
        grams: Double,
        kcal: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        isAIEstimate: Boolean,
        loggedAt: Instant,
    ): String = line(
        listOf(
            day,
            slot,
            food,
            brand,
            number(grams),
            number(kcal),
            number(proteinG),
            number(carbsG),
            number(fatG),
            flag(isAIEstimate),
            iso(loggedAt),
        )
    )

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
}
