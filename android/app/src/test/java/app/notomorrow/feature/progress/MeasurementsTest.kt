package app.notomorrow.feature.progress

import app.notomorrow.data.dao.BodyMeasurementDao
import app.notomorrow.data.entity.BodyMeasurementEntity
import app.notomorrow.model.MeasurementKind
import app.notomorrow.model.WeightUnit
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Body measurements (`MeasurementsTests.swift`): inches for lb users, per-kind summaries, one reading per kind per day. */
class MeasurementsTest {

    @Test
    fun `inches only for lengths and pound users`() {
        assertEquals(100.0, Measurements.display(254.0, MeasurementKind.Waist, WeightUnit.Lb), 0.001)
        assertEquals(84.0, Measurements.display(84.0, MeasurementKind.Waist, WeightUnit.Kg))
        assertEquals(18.0, Measurements.display(18.0, MeasurementKind.BodyFat, WeightUnit.Lb))
        assertEquals(83.82, Measurements.stored(33.0, MeasurementKind.Arm, WeightUnit.Lb), 0.001)
    }

    @Test
    fun `summaries keep kind order and change`() {
        val day = { n: Long -> n * 86_400_000 }
        val summaries = Measurements.summaries(
            listOf(
                Measurements.Reading(MeasurementKind.Arm, day(1), 38.0),
                Measurements.Reading(MeasurementKind.Waist, day(3), 84.0),
                Measurements.Reading(MeasurementKind.Waist, day(1), 86.5),
            ),
        )
        assertEquals(listOf(MeasurementKind.Waist, MeasurementKind.Arm), summaries.map { it.kind })
        assertEquals(84.0, summaries[0].latest.value)
        assertEquals(-2.5, summaries[0].change!!, 0.001)
        assertNull(summaries[1].change)
    }

    @Test
    fun `labels round to one decimal and sign a change`() {
        val pl = Locale.forLanguageTag("pl-PL")
        assertEquals("84,5 cm", Measurements.label(84.54, MeasurementKind.Waist, WeightUnit.Kg, locale = pl))
        assertEquals("+1.5 %", Measurements.label(1.5, MeasurementKind.BodyFat, WeightUnit.Kg, signed = true, locale = Locale.US))
        assertEquals("-1 in", Measurements.label(-2.54, MeasurementKind.Arm, WeightUnit.Lb, signed = true, locale = Locale.US))
        assertEquals("0 cm", Measurements.label(0.0, MeasurementKind.Arm, WeightUnit.Kg, signed = true, locale = Locale.US))
    }

    @Test
    fun `fields are read in the shown unit and out-of-range values dropped`() {
        val parsed = Measurements.parsed(
            mapOf(
                MeasurementKind.Waist to "33,5",
                MeasurementKind.BodyFat to "85",
                MeasurementKind.Neck to "",
                MeasurementKind.Chest to "abc",
            ),
            WeightUnit.Lb,
        )
        assertEquals(setOf(MeasurementKind.Waist), parsed.keys)
        assertEquals(85.09, parsed[MeasurementKind.Waist]!!, 0.001)
    }

    @Test
    fun `saving twice a day replaces that kind`() = runBlocking {
        val dao = FakeMeasurementDao()
        val now = 1_790_000_000_000L
        Measurements.save(mapOf(MeasurementKind.Waist to 85.0, MeasurementKind.Chest to 100.0), dao, now, ZoneOffset.UTC)
        Measurements.save(mapOf(MeasurementKind.Waist to 84.5), dao, now + 60_000, ZoneOffset.UTC)
        assertEquals(2, dao.rows.value.size)
        assertEquals(84.5, dao.rows.value.first { it.kind == "waist" }.value)
    }

    private class FakeMeasurementDao : BodyMeasurementDao {
        val rows = MutableStateFlow<List<BodyMeasurementEntity>>(emptyList())
        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = rows
        override suspend fun forDay(day: Long) = rows.value.filter { it.day == day }
        override suspend fun insert(row: BodyMeasurementEntity) {
            rows.value = rows.value + row
        }
        override suspend fun update(row: BodyMeasurementEntity) {
            rows.value = rows.value.map { if (it.id == row.id) row else it }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }
}
