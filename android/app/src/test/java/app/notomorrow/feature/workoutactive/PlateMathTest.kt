package app.notomorrow.feature.workoutactive

import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.feature.workout.PlateMath
import app.notomorrow.feature.workout.WarmupPlan
import app.notomorrow.feature.workout.WarmupPlan.Step
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Plate calculator (`PlateMathTests.swift`): plates per side, the closest load when the plates
 * cannot make a weight, and the bar edge cases; then the warm-up ramp and "Add warm-up sets".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlateMathTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LocaleProvider.override = { Locale.UK }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LocaleProvider.override = null
    }

    @Test
    fun targetOverTheLimitLoadsNothing() {
        val over = PlateMath.load(10_002.5, 20.0, PlateMath.plates(WeightUnit.Kg), limit = PlateMath.maxTarget(WeightUnit.Kg))
        assertTrue(over.isOverMax)
        assertTrue(over.perSide.isEmpty())
        assertFalse(over.isExact)
        val atLimit = PlateMath.load(500.0, 20.0, PlateMath.plates(WeightUnit.Kg), limit = PlateMath.maxTarget(WeightUnit.Kg))
        assertFalse(atLimit.isOverMax)
        assertTrue(atLimit.isExact)
        assertTrue(PlateMath.load(1105.0, 45.0, PlateMath.plates(WeightUnit.Lb), limit = PlateMath.maxTarget(WeightUnit.Lb)).isOverMax)
    }

    @Test
    fun `loads heaviest plates first`() {
        val load = PlateMath.load(target = 142.5, bar = 20.0, plates = PlateMath.plates(WeightUnit.Kg))
        assertEquals(listOf(25.0, 25.0, 10.0, 1.25), load.perSide)
        assertEquals(142.5, load.total)
        assertTrue(load.isExact)
        assertEquals(listOf(25.0, 10.0, 1.25), load.groups.map { it.plate })
        assertEquals(listOf(2, 1, 1), load.groups.map { it.count })
    }

    @Test
    fun `pound plates`() {
        val load = PlateMath.load(target = 225.0, bar = 45.0, plates = PlateMath.plates(WeightUnit.Lb))
        assertEquals(listOf(45.0, 45.0), load.perSide)
        assertTrue(load.isExact)
    }

    @Test
    fun `closest load stays under the target`() {
        val load = PlateMath.load(target = 101.0, bar = 20.0, plates = PlateMath.plates(WeightUnit.Kg))
        assertEquals(100.0, load.total)
        assertEquals(1.0, load.shortBy, 0.001)
        assertFalse(load.isExact)
    }

    @Test
    fun `bar only and below the bar`() {
        val bare = PlateMath.load(target = 20.0, bar = 20.0, plates = PlateMath.plates(WeightUnit.Kg))
        assertTrue(bare.perSide.isEmpty())
        assertTrue(bare.isExact)
        val light = PlateMath.load(target = 12.5, bar = 20.0, plates = PlateMath.plates(WeightUnit.Kg))
        assertTrue(light.isBelowBar)
        assertFalse(light.isExact)
        assertEquals(20.0, light.total)
    }

    @Test
    fun `plate labels keep two decimals`() {
        assertEquals("1,25 kg", Fmt.plate(1.25, WeightUnit.Kg, Locale.forLanguageTag("pl-PL")))
        assertEquals("45 lb", Fmt.plate(45.0, WeightUnit.Lb, Locale.UK))
    }

    // MARK: - Warm-up ramp

    @Test
    fun `barbell ramp starts with the bar`() {
        val steps = WarmupPlan.steps(working = 100.0, unit = WeightUnit.Kg, equipment = "barbell")
        assertEquals(listOf(Step(20.0, 10), Step(50.0, 5), Step(70.0, 3), Step(85.0, 1)), steps)
    }

    @Test
    fun `light barbell drops steps below the bar`() {
        val steps = WarmupPlan.steps(working = 40.0, unit = WeightUnit.Kg, equipment = "barbell")
        assertEquals(listOf(20.0, 27.5, 32.5), steps.map { it.weight })
        assertTrue(WarmupPlan.steps(working = 22.5, unit = WeightUnit.Kg, equipment = "barbell").isEmpty())
    }

    @Test
    fun `dumbbell ramp and pounds`() {
        assertEquals(
            listOf(Step(15.0, 8), Step(22.5, 4)),
            WarmupPlan.steps(working = 30.0, unit = WeightUnit.Kg, equipment = "dumbbell"),
        )
        assertEquals(
            listOf(45.0, 110.0, 155.0, 190.0),
            WarmupPlan.steps(working = 225.0, unit = WeightUnit.Lb, equipment = "barbell").map { it.weight },
        )
        assertTrue(WarmupPlan.steps(working = 0.0, unit = WeightUnit.Kg, equipment = "body only").isEmpty())
    }

    @Test
    fun `add warm-ups replaces open warm-ups before the first working set and keeps done ones`() = runTest {
        val harness = ActiveWorkoutHarness(backgroundScope)
        val entry = harness.seed(exerciseIds = listOf("row"), sets = 0).single()
        val dao = harness.workouts
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 0, kind = SetKind.Warmup, weightKg = 10.0, reps = 5, completedAt = 1L))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 1, kind = SetKind.Warmup, weightKg = 12.0, reps = 5))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 2, weightKg = 30.0, reps = 8))
        dao.insertSet(SetEntryEntity(workoutExerciseId = entry, order = 3, weightKg = 30.0, reps = 8))
        val model = harness.model()
        runCurrent()
        assertEquals(listOf(Step(15.0, 8), Step(22.5, 4)), model.state.value.exercises.single().warmupSteps)

        model.addWarmups(entry)
        runCurrent()

        val rows = dao.sets(entry).map { Triple(it.kind, it.weightKg, it.completedAt != null) }
        assertEquals(
            listOf(
                Triple(SetKind.Warmup, 10.0, true),
                Triple(SetKind.Warmup, 15.0, false),
                Triple(SetKind.Warmup, 22.5, false),
                Triple(SetKind.Normal, 30.0, false),
                Triple(SetKind.Normal, 30.0, false),
            ),
            rows,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), dao.sets(entry).map { it.order })
    }
}
