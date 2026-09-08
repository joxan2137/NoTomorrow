package app.notomorrow.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.Test

class CsvTest {

    private val started = Instant.parse("2026-09-04T16:30:00Z")
    private val ended = Instant.parse("2026-09-04T17:22:41Z")

    @Test
    fun `headers match SettingsExport column for column`() {
        assertEquals(
            "workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps," +
                "completed_at,pr,set_record",
            Csv.WORKOUTS_HEADER,
        )
        assertEquals(
            "day,slot,food,brand,grams,kcal,protein_g,carbs_g,fat_g,ai_estimate,logged_at",
            Csv.MEALS_HEADER,
        )
    }

    @Test
    fun `escape quotes only when it has to`() {
        assertEquals("Push A", Csv.escape("Push A"))
        assertEquals("\"Push, A\"", Csv.escape("Push, A"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.escape("two\nlines"))
    }

    @Test
    fun `numbers are dot decimals with at most one fraction digit`() {
        assertEquals("2", Csv.number(2.0))
        assertEquals("0", Csv.number(0.0))
        assertEquals("2.5", Csv.number(2.5))
        assertEquals("2.2", Csv.number(2.25))       // printf %.1f is half-even over the exact double
        assertEquals("-1.2", Csv.number(-1.25))
        assertEquals("12500", Csv.number(12_500.0))
    }

    @Test
    fun `timestamps are UTC ISO-8601 at second precision`() {
        assertEquals("2026-09-04T16:30:00Z", Csv.iso(started))
        assertEquals("2026-09-04T16:30:00Z", Csv.iso(started.plusMillis(499)))
        assertEquals("2026-09-04", Csv.day(LocalDate.of(2026, 9, 4)))
        assertEquals("2026-09-04", Csv.day(started))
        assertEquals("2026-09-04", Csv.day(started, ZoneOffset.ofHours(2)))
        assertEquals("NoTomorrow-export-2026-09-04", Csv.exportDirectoryName(LocalDate.of(2026, 9, 4)))
    }

    @Test
    fun `a workout row keeps the iOS field order and one-based set number`() {
        val row = Csv.workoutSetRow(
            workoutId = "5B7E1E1C-0000-0000-0000-000000000001",
            workoutName = "Push A",
            startedAt = started,
            endedAt = ended,
            exerciseName = "Barbell Bench Press",
            order = 2,
            kind = "normal",
            weightKg = 82.5,
            reps = 7,
            completedAt = ended,
            isPR = true,
            isSetRecord = false,
        )
        assertEquals(
            "5B7E1E1C-0000-0000-0000-000000000001,Push A,2026-09-04T16:30:00Z,2026-09-04T17:22:41Z," +
                "Barbell Bench Press,3,normal,82.5,7,2026-09-04T17:22:41Z,1,0",
            row,
        )
    }

    @Test
    fun `an unfinished workout and an unnamed exercise leave empty cells`() {
        val row = Csv.workoutSetRow(
            workoutId = "id",
            workoutName = "Pull, A",
            startedAt = started,
            endedAt = null,
            exerciseName = "",
            order = 0,
            kind = "warmup",
            weightKg = 60.0,
            reps = 10,
            completedAt = null,
            isPR = false,
            isSetRecord = true,
        )
        assertEquals("id,\"Pull, A\",2026-09-04T16:30:00Z,,,1,warmup,60,10,,0,1", row)
    }

    @Test
    fun `a meal row and the assembled file`() {
        val row = Csv.mealRow(
            day = Csv.day(LocalDate.of(2026, 9, 4)),
            slot = "lunch",
            food = "Skyr",
            brand = "",
            grams = 250.0,
            kcal = 162.5,
            proteinG = 27.5,
            carbsG = 9.0,
            fatG = 0.5,
            isAIEstimate = true,
            loggedAt = ended,
        )
        assertEquals("2026-09-04,lunch,Skyr,,250,162.5,27.5,9,0.5,1,2026-09-04T17:22:41Z", row)
        assertEquals(
            Csv.MEALS_HEADER + "\n" + row + "\n",
            Csv.file(listOf(Csv.MEALS_HEADER, row)),
        )
    }
}
