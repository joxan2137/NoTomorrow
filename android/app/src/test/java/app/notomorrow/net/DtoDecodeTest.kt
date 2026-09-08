package app.notomorrow.net

import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.NtJson
import app.notomorrow.net.dto.PartnerState
import app.notomorrow.net.dto.ScheduleDto
import app.notomorrow.net.dto.Session
import app.notomorrow.net.dto.WireDay
import app.notomorrow.net.dto.WireInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** DTO decoding against the JSON the `backend/src/routes` handlers actually return. */
class DtoDecodeTest {

    @Test
    fun `session ignores the extra user envelope`() {
        val json = """
            {"accessToken":"a1","refreshToken":"r1","userId":"u1",
             "user":{"id":"u1","username":"kuba","displayName":"Kuba","email":null,
                     "emailVerified":false,"locale":"pl","tz":"Europe/Warsaw",
                     "partner":null,"pairCode":null,"createdAt":"2026-01-01T00:00:00Z"}}
        """.trimIndent()
        val session = NtJson.decodeFromString(Session.serializer(), json)
        assertEquals(Session("a1", "r1", "u1"), session)
    }

    @Test
    fun `partner state decodes the merged attendance list`() {
        val json = """
            {"partner":{"id":"p1","name":"Tomek","displayName":"Tomek","pairedAt":"2026-08-01T10:00:00Z"},
             "partnerName":"Tomek",
             "partnerSchedule":{"weekdays":[3,1,5],"defaultMinuteOfDay":1080,
                                "overrides":{"5":1140},"remindHourBefore":true,"askIfSkippedAt21":false},
             "attendance":[
               {"day":"2026-09-02","participant":"partner","status":"attended","scheduledMinute":1080,
                "reason":null,"note":null,"makeUpDay":null,"updatedAt":"2026-09-02T20:00:00Z"},
               {"day":"2026-09-04","participant":"me","status":"cancelled","scheduledMinute":1080,
                "reason":"sick","note":"flu","makeUpDay":"2026-09-06","updatedAt":"2026-09-04T09:00:00Z"}],
             "headsUps":[
               {"id":"h1","fromMe":false,"fromUser":"p1","toUser":"u1","kind":"cantMakeIt",
                "text":"Not today","sessionDay":"2026-09-04","sentAt":"2026-09-04T08:59:12Z","readAt":null}]}
        """.trimIndent()
        val state = NtJson.decodeFromString(PartnerState.serializer(), json)

        assertEquals("Tomek", state.partnerName)
        assertEquals(listOf(3, 1, 5), state.partnerSchedule.weekdays)
        assertEquals(mapOf(5 to 1140), state.partnerSchedule.overrides)
        assertEquals(1140, state.partnerSchedule.minuteOfDay(5))
        assertEquals(1080, state.partnerSchedule.minuteOfDay(1))
        assertEquals(false, state.partnerSchedule.askIfSkippedAt21)

        assertEquals(2, state.attendance.size)
        val partnerRow = state.attendance[0]
        assertEquals(LocalDate.of(2026, 9, 2), partnerRow.day)
        assertEquals(Participant.Partner, partnerRow.participant)
        assertEquals(AttendanceStatus.Attended, partnerRow.status)
        assertNull(partnerRow.makeUpDay)
        val myRow = state.attendance[1]
        assertEquals(Participant.Me, myRow.participant)
        assertEquals(AttendanceStatus.Cancelled, myRow.status)
        assertEquals("sick", myRow.reason)
        assertEquals(LocalDate.of(2026, 9, 6), myRow.makeUpDay)
        assertEquals("me-2026-09-04", myRow.id)

        val headsUp = state.headsUps.single()
        assertEquals(HeadsUpKind.CantMakeIt, headsUp.kind)
        assertEquals(LocalDate.of(2026, 9, 4), headsUp.sessionDay)
        assertEquals(Instant.parse("2026-09-04T08:59:12Z"), headsUp.sentAt)
    }

    @Test
    fun `day instants land on the device calendar day`() {
        // The server sends local midnight in the caller's time zone as an instant.
        val instant = "2026-09-05T00:00:00+02:00"
        val expected = WireInstant.parse(instant)!!.atZone(ZoneId.systemDefault()).toLocalDate()
        val json = """
            {"partnerName":"Tomek",
             "partnerSchedule":{"weekdays":[1],"defaultMinuteOfDay":1080,"overrides":{}},
             "attendance":[{"day":"$instant","participant":"partner","status":"planned"}],
             "headsUps":[]}
        """.trimIndent()
        val state = NtJson.decodeFromString(PartnerState.serializer(), json)
        assertEquals(expected, state.attendance.single().day)
    }

    @Test
    fun `schedule round-trips with string override keys and drops null toggles`() {
        val encoded = NtJson.encodeToString(
            ScheduleDto.serializer(),
            ScheduleDto.of(weekdays = listOf(5, 1, 3), defaultMinuteOfDay = 1080, overrides = mapOf(5 to 1140)),
        )
        assertTrue(encoded, encoded.contains("\"overrides\":{\"5\":1140}"))
        assertTrue(encoded, encoded.contains("\"weekdays\":[1,3,5]"))
        assertTrue(encoded, !encoded.contains("remindHourBefore"))
        assertEquals(
            ScheduleDto(listOf(1, 3, 5), 1080, mapOf(5 to 1140)),
            NtJson.decodeFromString(ScheduleDto.serializer(), encoded),
        )
    }

    @Test
    fun `ai estimate accepts the backend key style`() {
        val json = """
            {"foods":[{"name":"Grilled chicken","grams":180,"kcal":297,"proteinG":56,"carbsG":0,"fatG":6.5,
                       "confidence":0.92,"isGuess":false}],
             "overallConfidence":0.78,"scaleReferenceUsed":"plate"}
        """.trimIndent()
        val estimate = NtJson.decodeFromString(AIEstimate.serializer(), json)
        val food = estimate.foods.single()
        assertEquals("Grilled chicken", food.name)
        assertEquals(56.0, food.protein, 0.001)
        assertEquals(6.5, food.fat, 0.001)
        assertEquals(0.78, estimate.overallConfidence, 0.001)
        assertEquals(297.0, estimate.totalKcal, 0.001)
    }

    @Test
    fun `ai estimate tolerates snake case, string numbers and missing fields`() {
        val json = """
            {"foods":[{"name_en":"Rice","estimated_grams":"220","calories":"286","protein_g":"5,9"},
                      {"name":"Olive oil","grams":14,"kcal":124,"fat":14,"confidence":0.3,"is_guess":"true"}]}
        """.trimIndent()
        val estimate = NtJson.decodeFromString(AIEstimate.serializer(), json)

        val rice = estimate.foods[0]
        assertEquals("Rice", rice.name)
        assertEquals(220.0, rice.grams, 0.001)
        assertEquals(286.0, rice.kcal, 0.001)
        // "5,9" is not a Double literal — the tolerant reader falls back to the 0 default.
        assertEquals(0.0, rice.protein, 0.001)
        assertEquals(0.5, rice.confidence, 0.001)
        assertEquals(false, rice.isGuess)

        val oil = estimate.foods[1]
        assertEquals(true, oil.isGuess)
        assertEquals(0.3, oil.confidence, 0.001)

        // No `overallConfidence` on the wire → the item mean, clamped to 0…1.
        assertEquals((0.5 + 0.3) / 2, estimate.overallConfidence, 0.001)
    }

    @Test
    fun `ai food scales proportionally`() {
        val food = estimateFood()
        val scaled = food.scaled(toGrams = 90.0)
        assertEquals(90.0, scaled.grams, 0.001)
        assertEquals(148.5, scaled.kcal, 0.001)
        assertEquals(28.0, scaled.protein, 0.001)
    }

    @Test
    fun `wire day formats and parses`() {
        assertEquals("2026-09-05", WireDay.string(LocalDate.of(2026, 9, 5)))
        assertEquals(LocalDate.of(2026, 9, 5), WireDay.date("2026-09-05"))
        assertNull(WireDay.date("2026-09-05T00:00:00Z"))
        // The three decoding fallbacks: internet date-time, fractional seconds, bare day.
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), WireInstant.parse("2026-09-05T12:00:00Z"))
        assertEquals(Instant.parse("2026-09-05T12:00:00.482Z"), WireInstant.parse("2026-09-05T12:00:00.482Z"))
        assertEquals(
            LocalDate.of(2026, 9, 5).atStartOfDay(ZoneId.systemDefault()).toInstant(),
            WireInstant.parse("2026-09-05"),
        )
    }

    @Test
    fun `instant encoding drops fractional seconds`() {
        assertEquals("2026-09-05T12:00:00Z", WireInstant.string(Instant.parse("2026-09-05T12:00:00.482Z")))
    }

    private fun estimateFood() = NtJson.decodeFromString(
        AIEstimate.serializer(),
        """{"foods":[{"name":"Chicken","grams":180,"kcal":297,"protein":56,"carbs":0,"fat":6.5,"confidence":0.92}],
            "overallConfidence":0.9}""",
    ).foods.single()
}
