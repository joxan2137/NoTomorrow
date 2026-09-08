package app.notomorrow.feature.onboarding

import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.TargetCalculator
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * The pure half of `OnboardingModel` (`OnboardingModel.swift`): step order, the weight parser and
 * its unit conversion, the target derivation, the pair-code normalisation and the split label.
 *
 * No Android framework: [LocaleProvider.override] keeps `Fmt` off `AppCompatDelegate`.
 */
class OnboardingUiStateTest {

    @Before
    fun setUp() {
        LocaleProvider.override = { Locale.UK }
    }

    @After
    fun tearDown() {
        LocaleProvider.override = null
    }

    // MARK: - Navigation

    @Test
    fun `standard order runs You Schedule Pair`() {
        val state = OnboardingUiState(step = OnboardingStep.You)
        assertEquals(0, state.stepIndex)
        assertFalse(state.isLastStep)
        assertEquals(3, state.stepCount)

        val last = state.copy(step = OnboardingStep.Pair)
        assertEquals(2, last.stepIndex)
        assertTrue(last.isLastStep)
    }

    @Test
    fun `pair-first order makes Schedule the last step`() {
        val state = OnboardingUiState(
            order = OnboardingUiState.PAIR_FIRST_ORDER,
            step = OnboardingStep.Pair,
        )
        assertEquals(0, state.stepIndex)
        assertFalse(state.isLastStep)
        assertTrue(state.copy(step = OnboardingStep.Schedule).isLastStep)
    }

    @Test
    fun `welcome has no step index`() {
        assertNull(OnboardingUiState().stepIndex)
    }

    // MARK: - You

    @Test
    fun `name must not be blank to continue`() {
        assertFalse(OnboardingUiState().canContinueFromYou)
        assertFalse(OnboardingUiState(name = "   ").canContinueFromYou)
        val named = OnboardingUiState(name = "  Kuba  ")
        assertTrue(named.canContinueFromYou)
        assertEquals("Kuba", named.trimmedName)
    }

    @Test
    fun `weight parses commas and rejects non-positive input`() {
        assertEquals(82.4, OnboardingUiState(weightText = "82,4").enteredWeight!!, 1e-9)
        assertEquals(82.4, OnboardingUiState(weightText = " 82.4 ").enteredWeight!!, 1e-9)
        assertNull(OnboardingUiState(weightText = "").enteredWeight)
        assertNull(OnboardingUiState(weightText = "abc").enteredWeight)
        assertNull(OnboardingUiState(weightText = "0").enteredWeight)
        assertNull(OnboardingUiState(weightText = "-5").enteredWeight)
    }

    @Test
    fun `pounds are converted back to kilograms`() {
        val kg = OnboardingUiState(weightText = "80", unit = WeightUnit.Kg)
        assertEquals(80.0, kg.bodyWeightKg!!, 1e-9)

        val lb = OnboardingUiState(weightText = "180", unit = WeightUnit.Lb)
        assertEquals(180 / Fmt.LB_PER_KG, lb.bodyWeightKg!!, 1e-9)
    }

    @Test
    fun `switching units keeps the same weight`() {
        val kg = OnboardingUiState(weightText = "80", unit = WeightUnit.Kg)
        val asLb = OnboardingUiState.convertedWeightText(kg, WeightUnit.Lb)
        // 80 kg is 176.4 lb; the field carries 0…1 fraction digits.
        assertEquals("176.4", asLb)

        val lb = kg.copy(unit = WeightUnit.Lb, weightText = asLb)
        val backToKg = OnboardingUiState.convertedWeightText(lb, WeightUnit.Kg)
        assertTrue(abs(backToKg.toDouble() - 80.0) < 0.05)
    }

    @Test
    fun `an empty field survives a unit switch`() {
        val empty = OnboardingUiState(weightText = "")
        assertEquals("", OnboardingUiState.convertedWeightText(empty, WeightUnit.Lb))
    }

    @Test
    fun `targets follow the calculator until they are edited`() {
        val state = OnboardingUiState(weightText = "80", goal = TrainingGoal.BuildMuscle)
        assertEquals(TargetCalculator.targets(80.0, TrainingGoal.BuildMuscle), state.targets)

        val custom = TargetCalculator.Targets(kcal = 3000, proteinG = 200, carbsG = 300, fatG = 80)
        assertEquals(custom, state.copy(customTargets = custom).targets)
    }

    @Test
    fun `an empty weight still yields a suggestion`() {
        val state = OnboardingUiState(weightText = "")
        assertEquals(
            TargetCalculator.targets(null, TrainingGoal.BuildMuscle),
            state.suggestedTargets,
        )
    }

    @Test
    fun `editing back to the suggestion is not a custom target`() {
        val state = OnboardingUiState(weightText = "80")
        val suggested = state.suggestedTargets
        val edited = OnboardingUiState.editedTargets(
            current = suggested,
            kcal = suggested.kcal.toString(),
            protein = suggested.proteinG.toString(),
            carbs = suggested.carbsG.toString(),
            fat = suggested.fatG.toString(),
        )
        assertEquals(suggested, edited)
    }

    @Test
    fun `an unparseable field keeps the current value`() {
        val current = TargetCalculator.Targets(kcal = 2600, proteinG = 180, carbsG = 300, fatG = 80)
        val edited = OnboardingUiState.editedTargets(
            current = current,
            kcal = "3 000",
            protein = "",
            carbs = "abc",
            fat = "90g",
        )
        assertEquals(3000, edited.kcal)
        assertEquals(180, edited.proteinG)
        assertEquals(300, edited.carbsG)
        assertEquals(90, edited.fatG)
    }

    // MARK: - Schedule

    @Test
    fun `the split follows the number of gym days`() {
        assertEquals(SplitKind.FullBody, OnboardingUiState.splitKind(1))
        assertEquals(SplitKind.FullBody, OnboardingUiState.splitKind(2))
        assertEquals(SplitKind.PushPull, OnboardingUiState.splitKind(3))
        assertEquals(SplitKind.PushPull, OnboardingUiState.splitKind(4))
        assertEquals(SplitKind.PushPullLegs, OnboardingUiState.splitKind(5))
        assertEquals(SplitKind.PushPullLegs, OnboardingUiState.splitKind(7))
    }

    @Test
    fun `the usual time splits into hour and minute`() {
        val state = OnboardingUiState(usualMinuteOfDay = 18 * 60 + 30)
        assertEquals(18, state.usualHour)
        assertEquals(30, state.usualMinute)
    }

    // MARK: - Pair

    @Test
    fun `a four-character entry is prefixed`() {
        assertEquals("NT-AB12", OnboardingUiState(codeEntry = "ab12").normalizedCode)
        assertEquals("NT-AB12", OnboardingUiState(codeEntry = " nt-ab12 ").normalizedCode)
        assertTrue(OnboardingUiState(codeEntry = "ab12").canPair)
    }

    @Test
    fun `a short or in-flight code cannot pair`() {
        assertFalse(OnboardingUiState(codeEntry = "ab").canPair)
        assertFalse(OnboardingUiState(codeEntry = "ab12", isPairing = true).canPair)
    }

    @Test
    fun `the local code is NT plus four unambiguous characters`() {
        repeat(50) {
            val code = OnboardingUiState.localCode(Random(it))
            assertEquals(7, code.length)
            assertTrue(code.startsWith("NT-"))
            assertTrue(code.drop(3).none { c -> c in "IO01" })
        }
    }
}
