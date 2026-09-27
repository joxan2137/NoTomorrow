package app.notomorrow.feature.workouttrain

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.feature.workout.RoutineDraft
import app.notomorrow.feature.workout.RoutineEditRequest
import app.notomorrow.feature.workout.RoutineEditorViewModel
import app.notomorrow.feature.workout.RoutineItemDraft
import app.notomorrow.feature.workout.RoutineStore
import app.notomorrow.model.SetKind
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.FakeWorkoutDao
import app.notomorrow.service.RoutineSeeder
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The routine builder (`NoTomorrowTests/RoutineEditorTests.swift`): draft edits, names, "Save as
 * routine", and the store writes behind Save / Duplicate / Move / Delete, plus the editor's model
 * and the once-only seeder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorTest {

    private val exercises = FakeExerciseDao()
    private val routines = FakeRoutineDao { id -> exercises.rows.value.firstOrNull { it.id == id } }
    private val store = RoutineStore(routines, exercises)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun exercise(id: String): ExerciseEntity =
        ExerciseEntity(id = id, name = id, primaryMuscles = listOf("chest")).also {
            exercises.rows.value = exercises.rows.value + it
        }

    private fun line(id: String, sets: Int = 3, reps: Int = 8, rest: Int = 0) =
        RoutineItemDraft.of(exerciseId = id, name = id, sets = sets, reps = reps, restSeconds = rest)

    private fun ordered() = routines.routines.value.sortedBy { it.order }

    private suspend fun linesOf(routineId: String) = routines.items(routineId)

    // MARK: - Draft

    @Test
    fun `steps clamp to their ranges`() {
        var draft = RoutineDraft(name = "A", items = listOf(line("bench", sets = 1, reps = 50)))
        val id = draft.items[0].id
        draft = draft.steppingSets(id, -1).steppingReps(id, 1)
        assertEquals(1, draft.items[0].sets)
        assertEquals(50, draft.items[0].reps)
        draft = draft.steppingSets(id, 1).steppingReps(id, -1)
        assertEquals(2, draft.items[0].sets)
        assertEquals(49, draft.items[0].reps)
        assertEquals(10, line("x", sets = 40, reps = 0).sets)
        assertEquals(1, line("x", sets = 40, reps = 0).reps)
    }

    @Test
    fun `append skips an exercise already in the routine`() {
        val draft = RoutineDraft(name = "A", items = listOf(line("bench")))
            .appending(line("bench"))
            .appending(line("row"))
        assertEquals(listOf("bench", "row"), draft.items.map { it.exerciseId })
    }

    @Test
    fun `move stays in bounds`() {
        var draft = RoutineDraft(name = "A", items = listOf(line("a"), line("b"), line("c")))
        draft = draft.moving(draft.items[0].id, -1)
        assertEquals(listOf("a", "b", "c"), draft.items.map { it.exerciseId })
        draft = draft.moving(draft.items[0].id, 1)
        assertEquals(listOf("b", "a", "c"), draft.items.map { it.exerciseId })
    }

    @Test
    fun `rest is never negative and the menu starts with the default`() {
        val draft = RoutineDraft(name = "A", items = listOf(line("a")))
        assertEquals(0, draft.settingRest(draft.items[0].id, -30).items[0].restSeconds)
        assertEquals(RoutineDraft.INHERIT_REST, RoutineDraft.REST_OPTIONS.first())
        assertEquals(listOf(0, 30, 45, 60, 75, 90, 120, 150, 180, 240, 300), RoutineDraft.REST_OPTIONS)
    }

    @Test
    fun `save needs a free name and an exercise`() {
        var draft = RoutineDraft(name = "  ", items = listOf(line("a")))
        assertFalse(draft.canSave(emptyList()))
        draft = draft.copy(name = "push a")
        assertFalse(draft.canSave(listOf("Push A")))
        assertTrue(draft.canSave(listOf("Pull A")))
        draft = draft.copy(items = emptyList())
        assertFalse(draft.canSave(emptyList()))
    }

    @Test
    fun `unique name counts up`() {
        assertEquals("Legs", RoutineDraft.uniqueName("Legs", listOf("Push A")))
        assertEquals("Legs 3", RoutineDraft.uniqueName("Legs", listOf("legs", "Legs 2")))
    }

    @Test
    fun `from workout keeps working sets and custom rest`() {
        val draft = RoutineDraft.from(
            workoutName = "Push A",
            exercises = listOf(
                RoutineDraft.LoggedExercise("bench", "Bench", "chest", listOf(8, 6, 6), 120, usesDefaultRest = true),
                RoutineDraft.LoggedExercise("fly", "Fly", "chest", listOf(12, 12), 45, usesDefaultRest = false),
                RoutineDraft.LoggedExercise("skipped", "Skipped", null, emptyList(), 90, usesDefaultRest = true),
            ),
            takenNames = listOf("Push A"),
        )
        assertEquals("Push A 2", draft.name)
        assertEquals(listOf("bench", "fly"), draft.items.map { it.exerciseId })
        assertEquals(listOf(3, 2), draft.items.map { it.sets })
        assertEquals(listOf(8, 12), draft.items.map { it.reps })
        assertEquals(listOf(RoutineDraft.INHERIT_REST, 45), draft.items.map { it.restSeconds })
    }

    // MARK: - Store

    @Test
    fun `save creates then replaces lines`() = runBlocking {
        exercise("bench"); exercise("row"); exercise("curl")
        val id = assertNotNull(
            store.save(RoutineDraft(" Upper ", listOf(line("bench"), line("row", sets = 4, reps = 10, rest = 60))), null),
        )
        assertEquals("Upper", routines.routines.value.single().name)
        assertEquals(listOf("bench", "row"), linesOf(id).map { it.exerciseId })
        assertEquals(4, linesOf(id).last().targetSets)
        assertEquals(60, linesOf(id).last().restSeconds)

        var edit = RoutineStore.draft(of = assertNotNull(routines.routineWithItems(id)), locale = Locale.ENGLISH)
        edit = edit.copy(name = "Upper B").removing(edit.items[0].id).appending(line("curl"))
        store.save(edit, id)
        assertEquals(1, routines.routines.value.size)
        assertEquals("Upper B", routines.routines.value.single().name)
        assertEquals(listOf("row", "curl"), linesOf(id).map { it.exerciseId })
        assertEquals(listOf(0, 1), linesOf(id).map { it.order })
        assertEquals(2, routines.items.value.size)
    }

    @Test
    fun `save skips exercises the library no longer has`() = runBlocking {
        exercise("bench")
        val id = assertNotNull(store.save(RoutineDraft("A", listOf(line("gone"), line("bench"))), null))
        assertEquals(listOf("bench"), linesOf(id).map { it.exerciseId })
        assertEquals(listOf(0), linesOf(id).map { it.order })
        assertNull(store.save(RoutineDraft("B", listOf(line("bench"))), "missing"))
    }

    @Test
    fun `new routines go to the end and duplicate sits after its original`() = runBlocking {
        exercise("bench")
        val a = assertNotNull(store.save(RoutineDraft("A", listOf(line("bench", sets = 5))), null))
        store.save(RoutineDraft("B", listOf(line("bench"))), null)
        store.duplicate(a, Locale.ENGLISH)
        assertEquals(listOf("A", "A 2", "B"), ordered().map { it.name })
        assertEquals(listOf(0, 1, 2), ordered().map { it.order })
        assertEquals(listOf(5), linesOf(ordered()[1].id).map { it.targetSets })
    }

    @Test
    fun `move swaps neighbours and delete removes items`() = runBlocking {
        exercise("bench")
        val a = assertNotNull(store.save(RoutineDraft("A", listOf(line("bench"))), null))
        val b = assertNotNull(store.save(RoutineDraft("B", listOf(line("bench"))), null))
        store.move(b, -1)
        assertEquals(listOf("B", "A"), ordered().map { it.name })
        store.move(b, -1)
        assertEquals(listOf("B", "A"), ordered().map { it.name })
        store.delete(a)
        assertEquals(listOf("B"), ordered().map { it.name })
        assertEquals(1, routines.items.value.size)
    }

    @Test
    fun `save as routine reads the finished workout`() {
        val bench = exercise("bench")
        val workout = WorkoutWithExercises(
            workout = WorkoutEntity(id = "w", name = "Push A", startedAt = 1_000, endedAt = 2_000),
            exercises = listOf(
                WorkoutExerciseWithSets(
                    workoutExercise = WorkoutExerciseEntity(id = 1, workoutId = "w", exerciseId = "bench", order = 0, restSeconds = 45),
                    exercise = bench,
                    sets = listOf(SetKind.Warmup, SetKind.Normal, SetKind.Normal).mapIndexed { index, kind ->
                        SetEntryEntity(
                            id = index + 1L,
                            workoutExerciseId = 1,
                            order = index,
                            kind = kind,
                            weightKg = 60.0,
                            reps = 10 - index,
                            completedAt = 1_500,
                        )
                    },
                ),
            ),
        )
        val draft = RoutineStore.draft(from = workout, defaultRest = 90, takenNames = emptyList(), locale = Locale.ENGLISH)
        assertEquals("Push A", draft.name)
        assertEquals(1, draft.items.size)
        assertEquals(2, draft.items[0].sets)
        assertEquals(9, draft.items[0].reps)
        assertEquals(45, draft.items[0].restSeconds)
        assertEquals("chest", draft.items[0].primaryMuscle)

        // The rest the default gives this exercise is not kept: the line follows the setting.
        val atDefault = RoutineStore.draft(from = workout, defaultRest = 45, takenNames = emptyList(), locale = Locale.ENGLISH)
        assertEquals(RoutineDraft.INHERIT_REST, atDefault.items[0].restSeconds)
    }

    // MARK: - Editor model

    private fun model() = RoutineEditorViewModel(
        routineDao = routines,
        exerciseDao = exercises,
        workoutDao = FakeWorkoutDao(),
        profileDao = FakeProfileDao(UserProfileEntity(name = "Jan")),
        locale = { Locale.ENGLISH },
    )

    @Test
    fun `a new routine is saved from the editor at the end of the list`() = runBlocking {
        exercise("bench"); exercise("row")
        store.save(RoutineDraft("Push A", listOf(line("bench"))), null)
        val model = model()
        model.open(RoutineEditRequest.New)
        val opened = assertNotNull(model.state.value)
        assertTrue(opened.isNew)
        assertFalse(opened.isDirty)
        assertEquals(listOf("Push A"), opened.otherNames)

        model.setName("push a")
        model.append(listOf("row", "bench", "row"))
        val edited = assertNotNull(model.state.value)
        assertEquals(listOf("row", "bench"), edited.draft.items.map { it.exerciseId })
        assertTrue(edited.isDirty)
        assertTrue(edited.isNameTaken)
        assertFalse(edited.canSave)

        model.setName("Pull A")
        var saved = false
        model.save { saved = true }
        assertTrue(saved)
        assertEquals(listOf("Push A", "Pull A"), ordered().map { it.name })
        assertEquals(listOf("row", "bench"), linesOf(ordered()[1].id).map { it.exerciseId })
    }

    @Test
    fun `editing a routine keeps its own name free and closing drops the draft`() = runBlocking {
        exercise("bench")
        val id = assertNotNull(store.save(RoutineDraft("Legs", listOf(line("bench"))), null))
        val model = model()
        model.open(RoutineEditRequest.Edit(id))
        val opened = assertNotNull(model.state.value)
        assertFalse(opened.isNew)
        assertTrue(opened.canSave, "its own name is not taken")
        assertFalse(opened.isDirty)
        model.stepSets(opened.draft.items[0].id, 1)
        assertTrue(assertNotNull(model.state.value).isDirty)

        model.open(null)
        assertNull(model.state.value)
        model.open(RoutineEditRequest.Edit(id))
        assertEquals(3, assertNotNull(model.state.value).draft.items[0].sets, "Cancel wrote nothing")
    }

    // MARK: - Seeder

    @Test
    fun `the seeder runs once so deleted routines stay deleted`() = runBlocking {
        RoutineSeeder.TEMPLATES.flatMap { it.exerciseIds }.toSet().forEach { exercise(it) }
        val seeded = RoutineSeeder.Seeded.InMemory()
        val seeder = RoutineSeeder(routines, exercises, seeded)
        seeder.seedIfNeeded()
        assertEquals(RoutineSeeder.TEMPLATES.size, routines.routines.value.size)
        assertTrue(seeded.value)
        routines.routines.value.map { it.id }.forEach { store.delete(it) }
        seeder.seedIfNeeded()
        assertTrue(routines.routines.value.isEmpty())

        // A local data wipe clears the flag: the next setup seeds again.
        seeded.value = false
        seeder.seedIfNeeded()
        assertEquals(RoutineSeeder.TEMPLATES.size, routines.routines.value.size)
    }

    @Test
    fun `an existing routine marks the starters as seeded`() = runBlocking {
        exercise("bench")
        store.save(RoutineDraft("Mine", listOf(line("bench"))), null)
        val seeded = RoutineSeeder.Seeded.InMemory()
        RoutineSeeder(routines, exercises, seeded).seedIfNeeded()
        assertTrue(seeded.value)
        assertEquals(1, routines.routines.value.size)
    }

    @Test
    fun `nothing is marked while the library is not imported`() = runBlocking {
        val seeded = RoutineSeeder.Seeded.InMemory()
        RoutineSeeder(routines, FakeExerciseDao(), seeded).seedIfNeeded()
        assertFalse(seeded.value)
    }
}
