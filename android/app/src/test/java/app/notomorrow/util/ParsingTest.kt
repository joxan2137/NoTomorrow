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
