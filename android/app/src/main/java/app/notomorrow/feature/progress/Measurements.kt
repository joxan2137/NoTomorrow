package app.notomorrow.feature.progress

import app.notomorrow.data.dao.BodyMeasurementDao
import app.notomorrow.data.entity.BodyMeasurementEntity
import app.notomorrow.model.MeasurementKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Tape measurements — port of `Measurements` (`Features/Progress/MeasurementsViews.swift`):
 * conversion (cm stored, inches shown to lb users), labels and the per-kind summary.
 */
object Measurements {

    const val CM_PER_INCH = 2.54

    fun usesInches(kind: MeasurementKind, unit: WeightUnit): Boolean =
        kind != MeasurementKind.BodyFat && unit == WeightUnit.Lb

    fun display(value: Double, kind: MeasurementKind, unit: WeightUnit): Double =
        if (usesInches(kind, unit)) value / CM_PER_INCH else value

    fun stored(value: Double, kind: MeasurementKind, unit: WeightUnit): Double =
        if (usesInches(kind, unit)) value * CM_PER_INCH else value

    fun unitLabel(kind: MeasurementKind, unit: WeightUnit): String = when {
        kind == MeasurementKind.BodyFat -> "%"
        unit == WeightUnit.Lb -> "in"
        else -> "cm"
    }

    /** "84,5 cm", "33.3 in", "18,5 %" — [signed] puts a plus on a positive change. */
    fun label(
        value: Double,
        kind: MeasurementKind,
        unit: WeightUnit,
        signed: Boolean = false,
        locale: Locale = LocaleProvider.current(),
    ): String = number(display(value, kind, unit), signed, locale) + Fmt.NBSP + unitLabel(kind, unit)

    /** `.number.precision(.fractionLength(0...1))`, the sign `.always(includingZero: false)` when [signed]. */
    fun number(value: Double, signed: Boolean = false, locale: Locale = LocaleProvider.current()): String {
        val format = (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        val text = format.format(value)
        return if (signed && text != format.format(0.0) && value > 0) "+$text" else text
    }

    data class Reading(val kind: MeasurementKind, val day: Long, val value: Double)

    data class Summary(
        val kind: MeasurementKind,
        val latest: Reading,
        /** Latest minus the first reading, when there are two or more. */
        val change: Double?,
    )

    fun readings(rows: List<BodyMeasurementEntity>): List<Reading> =
        rows.mapNotNull { row -> MeasurementKind.from(row.kind)?.let { Reading(it, row.day, row.value) } }

    /** One summary per kind that has readings, in [MeasurementKind] order. */
    fun summaries(readings: List<Reading>): List<Summary> = MeasurementKind.entries.mapNotNull { kind ->
        val mine = readings.filter { it.kind == kind }.sortedBy { it.day }
        val first = mine.firstOrNull() ?: return@mapNotNull null
        val last = mine.last()
        Summary(kind, last, if (mine.size >= 2) last.value - first.value else null)
    }

    /**
     * What the log sheet's fields hold, stored units: a field reads in the shown unit and counts
     * when it is above 0 and under 80 % (body fat) or 400 (lengths).
     */
    fun parsed(texts: Map<MeasurementKind, String>, unit: WeightUnit): Map<MeasurementKind, Double> =
        buildMap {
            for ((kind, text) in texts) {
                val value = app.notomorrow.feature.workout.SetInput.number(text)
                if (value <= 0 || value >= if (kind == MeasurementKind.BodyFat) 80.0 else 400.0) continue
                put(kind, stored(value, kind, unit))
            }
        }

    /** Today's value per kind is replaced; an empty field leaves that kind alone. */
    suspend fun save(
        values: Map<MeasurementKind, Double>,
        dao: BodyMeasurementDao,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val start = Days.startOfDay(now, zone)
        val existing = dao.forDay(start)
        for ((kind, value) in values) {
            if (value <= 0) continue
            val row = existing.firstOrNull { it.kind == kind.raw }
            if (row != null) {
                dao.update(row.copy(value = value))
            } else {
                dao.insert(BodyMeasurementEntity(id = UUID.randomUUID().toString(), day = start, kind = kind.raw, value = value))
            }
        }
    }
}
