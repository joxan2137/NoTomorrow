package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.feature.workout.RoutineDraft
import app.notomorrow.feature.workout.RoutineItemDraft
import app.notomorrow.feature.workout.RoutineShare
import app.notomorrow.feature.workout.RoutineStore
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeRoutineDao
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Sharing a routine as text ([RoutineShare]) — `NoTomorrowTests/RoutineShareTests.swift`: the
 * text a routine writes, reading it back from the `nt1:` line or the numbered lines, the editor's
 * limits, matching to the library, and "Add routine". The fixture is identical to the iOS one, so
 * both apps read the same text the same way.
 */
class RoutineShareTest {

    private val exercises = FakeExerciseDao()
    private val routines = FakeRoutineDao { id -> exercises.rows.value.firstOrNull { it.id == id } }
    private val store = RoutineStore(routines, exercises)

    private companion object {
        // Fixture (identical in NoTomorrowTests/RoutineShareTests.swift).
        val FIXTURE = """
            No Tomorrow routine: Push A · Pchnięcia
            1. Barbell Bench Press - Medium Grip — 4 × 8, rest 2:00
            2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]
            3. Dumbbell Flyes — 3 × 12 [Superset A]
            4. Żuraw "nordycki" — 3 × 15, rest 1:00
            nt1:eyJ2IjoxLCJuYW1lIjoiUHVzaCBBIMK3IFBjaG5pxJljaWEiLCJpdGVtcyI6W3siaWQiOiJCYXJiZWxsX0JlbmNoX1ByZXNzXy1fTWVkaXVtX0dyaXAiLCJzZXRzIjo0LCJyZXBzIjo4LCJyZXN0IjoxMjB9LHsiaWQiOiJJbmNsaW5lX0R1bWJiZWxsX1ByZXNzIiwic2V0cyI6MywicmVwcyI6MTAsInJlc3QiOjkwLCJzcyI6MX0seyJpZCI6IkR1bWJiZWxsX0ZseWVzIiwic2V0cyI6MywicmVwcyI6MTIsInJlc3QiOjAsInNzIjoxfSx7Im5hbWUiOiLFu3VyYXcgXCJub3JkeWNraVwiIiwic2V0cyI6MywicmVwcyI6MTUsInJlc3QiOjYwfV19
        """.trimIndent()

        val FIXTURE_DRAFT = RoutineDraft(
            name = "Push A · Pchnięcia",
            items = listOf(
                RoutineItemDraft.of("Barbell_Bench_Press_-_Medium_Grip", "Barbell Bench Press - Medium Grip", sets = 4, reps = 8, restSeconds = 120),
                RoutineItemDraft.of("Incline_Dumbbell_Press", "Incline Dumbbell Press", sets = 3, reps = 10, restSeconds = 90, supersetGroup = 1),
                RoutineItemDraft.of("Dumbbell_Flyes", "Dumbbell Flyes", sets = 3, reps = 12, restSeconds = 0, supersetGroup = 1),
                RoutineItemDraft.of("custom-1234", "Żuraw \"nordycki\"", sets = 3, reps = 15, restSeconds = 60),
            ),
        )

        val FIXTURE_SHARED = RoutineShare.Shared(
            name = "Push A · Pchnięcia",
            lines = listOf(
                RoutineShare.Line("Barbell_Bench_Press_-_Medium_Grip", "Barbell Bench Press - Medium Grip", 4, 8, 120, null),
                RoutineShare.Line("Incline_Dumbbell_Press", "Incline Dumbbell Press", 3, 10, 90, 1),
                RoutineShare.Line("Dumbbell_Flyes", "Dumbbell Flyes", 3, 12, 0, 1),
                RoutineShare.Line(null, "Żuraw \"nordycki\"", 3, 15, 60, null),
            ),
        )
    }

    private fun token(json: String) = RoutineShare.MARKER + RoutineShare.base64Url(json.toByteArray(Charsets.UTF_8))

    // MARK: - Writing

    @Test
    fun `writing the fixture routine gives the fixture text`() {
        assertEquals(FIXTURE, RoutineShare.text(FIXTURE_DRAFT, RoutineShare.Labels.English))
    }

    @Test
    fun `custom exercises are shared by name and library ones by id`() {
        val shared = RoutineShare.shared(FIXTURE_DRAFT)
        assertEquals(
            listOf("Barbell_Bench_Press_-_Medium_Grip", "Incline_Dumbbell_Press", "Dumbbell_Flyes", null),
            shared.lines.map { it.exerciseId },
        )
        assertEquals(listOf(null, null, null, "Żuraw \"nordycki\""), shared.lines.map { it.name })
    }

    @Test
    fun `base64url uses the url alphabet without padding`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        assertEquals("-_8", RoutineShare.base64Url(bytes))
        assertContentEquals(bytes, RoutineShare.base64UrlDecoded("-_8"))
        assertContentEquals(bytes, RoutineShare.base64UrlDecoded("+/8="))
        assertNull(RoutineShare.base64UrlDecoded("abcde"))
    }

    // MARK: - Reading

    @Test
    fun `the fixture decodes`() {
        assertEquals(FIXTURE_SHARED, RoutineShare.decode(FIXTURE))
    }

    @Test
    fun `a round trip keeps every line`() {
        val draft = RoutineDraft(
            name = "Legs\nday",
            items = listOf(
                RoutineItemDraft.of("Barbell_Squat", "Barbell Squat", sets = 5, reps = 5, restSeconds = 180),
                RoutineItemDraft.of("custom-x", "Sled \\ push", sets = 2, reps = 20, restSeconds = 45, supersetGroup = 3),
                RoutineItemDraft.of("Lunges", "Lunges", sets = 3, reps = 12, supersetGroup = 3),
            ),
        )
        val shared = assertNotNull(RoutineShare.decodePayload(RoutineShare.text(draft)))
        assertEquals("Legs day", shared.name)
        assertEquals(listOf("Barbell_Squat", null, "Lunges"), shared.lines.map { it.exerciseId })
        assertEquals("Sled \\ push", shared.lines[1].name)
        assertEquals(listOf(5, 2, 3), shared.lines.map { it.sets })
        assertEquals(listOf(5, 20, 12), shared.lines.map { it.reps })
        assertEquals(listOf(180, 45, 0), shared.lines.map { it.restSeconds })
        assertEquals(listOf(null, 1, 1), shared.lines.map { it.supersetGroup })
    }

    @Test
    fun `without the payload the numbered lines are read`() {
        val text = FIXTURE.lines().dropLast(1).joinToString("\n")
        val shared = assertNotNull(RoutineShare.decode(text))
        assertEquals("Push A · Pchnięcia", shared.name)
        assertEquals(FIXTURE_SHARED.lines.map { it.copy(exerciseId = null) }, shared.lines)
    }

    @Test
    fun `a damaged payload falls back to the numbered lines`() {
        val shared = assertNotNull(RoutineShare.decode(FIXTURE.replace("nt1:eyJ2", "nt1:eyJ3")))
        assertEquals(listOf<String?>(null, null, null, null), shared.lines.map { it.exerciseId })
        assertEquals(4, shared.lines.size)
    }

    @Test
    fun `a payload wrapped over lines still decodes`() {
        val payload = RoutineShare.payload(RoutineShare.shared(FIXTURE_DRAFT))
        val wrapped = "look at this\n" + payload.substring(0, 60) + "\n  " + payload.substring(60)
        val shared = assertNotNull(RoutineShare.decode(wrapped))
        assertEquals(FIXTURE_SHARED.lines.map { it.exerciseId }, shared.lines.map { it.exerciseId })
    }

    @Test
    fun `another language's lines are read by shape`() {
        val text = """
            Plan No Tomorrow: Góra
            1. Wyciskanie sztangi na ławce — 4 × 6, przerwa 3:00
            2) Podciąganie - 3x8 [Superseria B]
            3. Wiosłowanie – 3 * 10, przerwa 1:30 [Superseria B]
            4. Plank
        """.trimIndent()
        val shared = assertNotNull(RoutineShare.decode(text))
        assertEquals("Góra", shared.name)
        assertEquals(listOf("Wyciskanie sztangi na ławce", "Podciąganie", "Wiosłowanie", "Plank"), shared.lines.map { it.name })
        assertEquals(listOf(4, 3, 3, RoutineDraft.DEFAULT_SETS), shared.lines.map { it.sets })
        assertEquals(listOf(6, 8, 10, RoutineDraft.DEFAULT_REPS), shared.lines.map { it.reps })
        assertEquals(listOf(180, 0, 90, 0), shared.lines.map { it.restSeconds })
        assertEquals(listOf(null, 1, 1, null), shared.lines.map { it.supersetGroup })
    }

    @Test
    fun `values are clamped to the editor's limits`() {
        val json = """{"v":1,"name":"X","items":[{"id":"a","sets":40,"reps":0,"rest":100},{"id":"b","sets":-2,"reps":400,"rest":9999},{"id":"","name":"  "},{"name":"c","rest":-5,"ss":0}]}"""
        val shared = assertNotNull(RoutineShare.decode(token(json)))
        assertEquals(listOf("a", "b", null), shared.lines.map { it.exerciseId })
        assertEquals(listOf(10, 1, RoutineDraft.DEFAULT_SETS), shared.lines.map { it.sets })
        assertEquals(listOf(1, 50, RoutineDraft.DEFAULT_REPS), shared.lines.map { it.reps })
        assertEquals(listOf(90, 300, 0), shared.lines.map { it.restSeconds })
        assertEquals(listOf<Int?>(null, null, null), shared.lines.map { it.supersetGroup })
    }

    @Test
    fun `text without a routine gives nothing`() {
        assertNull(RoutineShare.decode(""))
        assertNull(RoutineShare.decode("hey, lunch tomorrow?"))
        assertNull(RoutineShare.decode("nt1:!!!"))
        assertNull(RoutineShare.decode(token("""{"v":2,"name":"X","items":[{"id":"a","sets":3,"reps":8,"rest":0}]}""")))
    }

    @Test
    fun `rest snaps to the nearest menu length`() {
        assertEquals(0, RoutineShare.snapRest(0))
        assertEquals(0, RoutineShare.snapRest(-10))
        assertEquals(30, RoutineShare.snapRest(1))
        assertEquals(90, RoutineShare.snapRest(100))
        assertEquals(90, RoutineShare.snapRest(105))
        assertEquals(120, RoutineShare.snapRest(106))
        assertEquals(300, RoutineShare.snapRest(1000))
    }

    // MARK: - Matching

    @Test
    fun `plan matches by id then by name and marks new exercises`() {
        val catalog = RoutineShare.Catalog()
        catalog.add(RoutineShare.Catalog.Entry("Barbell_Bench_Press_-_Medium_Grip", "Wyciskanie", "chest"), listOf("Barbell Bench Press - Medium Grip"))
        catalog.add(RoutineShare.Catalog.Entry("Dumbbell_Flyes", "Dumbbell Flyes", "chest"), listOf("Dumbbell Flyes", "Rozpiętki"))
        val shared = RoutineShare.Shared(
            "X",
            listOf(
                RoutineShare.Line("Barbell_Bench_Press_-_Medium_Grip", null, 4, 8, 120),
                RoutineShare.Line("Unknown_Id", "rozpietki", 3, 12, 0, 1),
                RoutineShare.Line(null, "Żuraw", 3, 15, 60, 1),
                RoutineShare.Line(null, "zuraw", 2, 5, 0),
                RoutineShare.Line("Gone", null, 2, 5, 0),
            ),
        )
        val planned = RoutineShare.plan(shared, catalog)
        assertEquals(listOf("Barbell_Bench_Press_-_Medium_Grip", "Dumbbell_Flyes", null), planned.map { it.exerciseId })
        assertEquals(listOf("Wyciskanie", "Dumbbell Flyes", "Żuraw"), planned.map { it.name })
        assertEquals(listOf(false, false, true), planned.map { it.isNew })
        assertEquals(listOf(null, 1, 1), planned.map { it.supersetGroup })
        assertEquals(listOf(0, 1, 2), planned.map { it.index })
    }

    // MARK: - Store

    @Test
    fun `adding a shared routine creates custom exercises and a unique name`() = runBlocking {
        exercises.rows.value = listOf(
            ExerciseEntity(id = "Barbell_Bench_Press_-_Medium_Grip", name = "Barbell Bench Press - Medium Grip", primaryMuscles = listOf("chest")),
            ExerciseEntity(id = "Incline_Dumbbell_Press", name = "Incline Dumbbell Press", primaryMuscles = listOf("chest")),
            ExerciseEntity(id = "Dumbbell_Flyes", name = "Dumbbell Flyes", primaryMuscles = listOf("chest")),
        )
        routines.routines.value = listOf(RoutineEntity(id = "r0", name = "push a · pchnięcia", order = 0, createdAt = 0))

        val shared = assertNotNull(RoutineShare.decode(FIXTURE))
        val planned = RoutineShare.plan(shared, store.shareCatalog(Locale.ENGLISH))
        val id = assertNotNull(store.addShared(shared.name, planned, fallbackName = "Shared", now = 1))

        val routine = assertNotNull(routines.routineWithItems(id))
        assertEquals("Push A · Pchnięcia 2", routine.routine.name)
        assertEquals(1, routine.routine.order)
        val items = routine.sortedItems
        assertEquals(4, items.size)
        assertEquals(
            listOf("Barbell_Bench_Press_-_Medium_Grip", "Incline_Dumbbell_Press", "Dumbbell_Flyes"),
            items.take(3).map { it.exercise?.id },
        )
        val custom = assertNotNull(items[3].exercise)
        assertTrue(custom.isCustom)
        assertTrue(custom.id.startsWith("custom-"))
        assertEquals("Żuraw \"nordycki\"", custom.name)
        assertEquals(listOf(4, 3, 3, 3), items.map { it.item.targetSets })
        assertEquals(listOf(8, 10, 12, 15), items.map { it.item.targetReps })
        assertEquals(listOf(120, 90, 0, 60), items.map { it.item.restSeconds })
        assertEquals(listOf(null, 1, 1, null), items.map { it.item.supersetGroup })

        // The same text again matches the custom exercise it created instead of adding another.
        val again = RoutineShare.plan(shared, store.shareCatalog(Locale.ENGLISH))
        assertEquals(listOf(false, false, false, false), again.map { it.isNew })
    }

    @Test
    fun `sharing a routine writes its lines`() = runBlocking {
        exercises.rows.value = listOf(
            ExerciseEntity(id = "Barbell_Bench_Press_-_Medium_Grip", name = "Barbell Bench Press - Medium Grip", primaryMuscles = listOf("chest")),
        )
        routines.routines.value = listOf(RoutineEntity(id = "r0", name = "Push", order = 0, createdAt = 0))
        routines.items.value = listOf(
            RoutineItemEntity(routineId = "r0", exerciseId = "Barbell_Bench_Press_-_Medium_Grip", order = 0, targetSets = 4, targetReps = 8, restSeconds = 120),
        )

        val text = assertNotNull(store.shareText("r0", RoutineShare.Labels.English, Locale.ENGLISH))
        val shared = assertNotNull(RoutineShare.decode(text))
        assertEquals("Push", shared.name)
        assertEquals(listOf("Barbell_Bench_Press_-_Medium_Grip"), shared.lines.map { it.exerciseId })
        assertEquals(listOf(4), shared.lines.map { it.sets })
        assertEquals(listOf(120), shared.lines.map { it.restSeconds })
        assertNull(store.shareText("gone", RoutineShare.Labels.English, Locale.ENGLISH))
    }
}
