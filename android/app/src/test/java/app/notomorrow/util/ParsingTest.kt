package app.notomorrow.util

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun `decimal accepts comma dot and formatted spacing`() {
        assertEquals(82.4, Parsing.decimal("82,4")!!, 0.0)
        assertEquals(82.4, Parsing.decimal("82.4")!!, 0.0)
        assertEquals(82.4, Parsing.decimal(" 82,4 ")!!, 0.0)
        assertEquals(12500.0, Parsing.decimal("12 500")!!, 0.0)
        assertEquals(12500.0, Parsing.decimal("12\u00A0500")!!, 0.0)
        assertEquals(12500.0, Parsing.decimal("12\u202F500")!!, 0.0)
        assertEquals(0.5, Parsing.decimal(",5")!!, 0.0)
    }

    @Test
    fun `decimal rejects what Swift's Double initializer rejects`() {
        assertNull(Parsing.decimal(""))
        assertNull(Parsing.decimal("  "))
        assertNull(Parsing.decimal("abc"))
        assertNull(Parsing.decimal("1.5f"))
        assertNull(Parsing.decimal("0x1p3"))
        assertNull(Parsing.decimal("Infinity"))
        assertNull(Parsing.decimal("NaN"))
        assertNull(Parsing.decimal("8,2,4"))
    }

    /** The iOS `NumberInput` vectors (`FuelInputTests`): both ports must accept and reject the same text. */
    @Test
    fun `decimal matches the iOS NumberInput vectors`() {
        val accepted = listOf(
            "72,5" to 72.5, "72.5" to 72.5, " 72 " to 72.0, "0" to 0.0, ",5" to 0.5, "5," to 5.0,
            "+2" to 2.0, "-3" to -3.0, "1e3" to 1000.0,
            "12 500" to 12500.0,
            "1 234,5" to 1234.5,
            "12 345,5" to 12345.5,
            "1 234" to 1234.0, "1 234" to 1234.0, "7\t2" to 72.0,
        )
        for ((text, expected) in accepted) assertEquals(expected, Parsing.decimal(text), text)
        val rejected = listOf(
            "", " ", "abc", "1.234.5", "1,234.5", "1.234,5", "inf", "nan", "Infinity", "0x10", "1.5f", "--1", ".", "e5",
        )
        for (text in rejected) assertNull(Parsing.decimal(text), text)
        assertEquals(82.4, Parsing.nonNegative("82,4"))
    }

    @Test
    fun `bounds mirror the iOS call sites`() {
        assertEquals(0.0, Parsing.nonNegative("0")!!, 0.0)
        assertNull(Parsing.nonNegative("-2"))
        assertNull(Parsing.positive("0"))
        assertEquals(2.0, Parsing.positive("2")!!, 0.0)
    }

    @Test
    fun `int rounds half away from zero`() {
        assertEquals(3, Parsing.int("2,6"))
        assertEquals(3, Parsing.int("2.5"))
        assertEquals(2, Parsing.int("2"))
        assertNull(Parsing.int("-1"))
    }

    @Test
    fun `pair code normalization matches BroService`() {
        assertEquals("NT-ABCD", Parsing.normalizedPairCode("abcd"))
        assertEquals("NT-ABCD", Parsing.normalizedPairCode("nt-abcd"))
        assertEquals("NT-ABCD", Parsing.normalizedPairCode("NTABCD "))
        assertEquals("NT-ABCD", Parsing.normalizedPairCode("NT-ABCD"))
        assertEquals("NT-7K4Q", Parsing.normalizedPairCode(" nt 7k4q "))
        assertTrue(Parsing.isValidPairCode(Parsing.normalizedPairCode("abcd")))
    }

    @Test
    fun `a five character code is returned uppercase and rejected`() {
        val code = Parsing.normalizedPairCode("abcde")
        assertEquals("ABCDE", code)
        assertFalse(Parsing.isValidPairCode(code))
    }

    @Test
    fun `onboarding only prefixes a bare four character entry`() {
        assertEquals("NT-ABCD", Parsing.onboardingPairCode("abcd"))
        assertEquals("NT-ABCD", Parsing.onboardingPairCode(" NT-ABCD "))
        assertEquals("NTAB", Parsing.onboardingPairCode("ntab"))
    }
}
