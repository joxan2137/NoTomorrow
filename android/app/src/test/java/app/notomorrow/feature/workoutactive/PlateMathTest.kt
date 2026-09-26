package app.notomorrow.feature.workoutactive

import app.notomorrow.feature.workout.PlateMath
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Plate calculator (`PlateMathTests.swift`): plates per side, the closest load when the plates
 * cannot make a weight, and the bar edge cases.
 */
class PlateMathTest {

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
}
