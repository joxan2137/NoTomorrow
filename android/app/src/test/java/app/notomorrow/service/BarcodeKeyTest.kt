package app.notomorrow.service

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.model.FoodSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BarcodeKeyTests.swift`, the pure half: GTIN check digits, UPC-E expansion, what the scanner
 * accepts from each symbology, in-store (RCN) item keys and which saved food a scan picks. The
 * Room-backed half (saved-food lookup, label upsert) is `fuelhome/BarcodeLookupFlowTest`.
 */
class BarcodeKeyTest {

    // MARK: - Check digit

    @Test
    fun `GTIN check digit`() {
        for (valid in listOf("5901234123457", "20582555", "96385074", "036000291452", "05901234123457", "5900056007181", "2050401935713")) {
            assertTrue(valid, BarcodeKey.isValidGTIN(valid))
        }
        for (invalid in listOf("5901234123458", "20582556", "036000291453", "590123412345", "59012341234570", "590123412345a", "", "04252614")) {
            assertFalse(invalid, BarcodeKey.isValidGTIN(invalid))
        }
    }

    // MARK: - UPC-E

    @Test
    fun `UPC-E expansion`() {
        assertEquals("042100005264", GTINExtractor.expandUPCE("04252614")) // last digit 0–2
        assertEquals("012300000451", GTINExtractor.expandUPCE("01234531")) // 3
        assertEquals("012340000053", GTINExtractor.expandUPCE("01234543")) // 4
        assertEquals("012345000072", GTINExtractor.expandUPCE("01234572")) // 5–9
        assertEquals("112345000062", GTINExtractor.expandUPCE("11234562")) // number system 1
        assertEquals("042100005264", GTINExtractor.expandUPCE("425261")) // compressed digits only
        assertEquals("042100005264", GTINExtractor.expandUPCE("0425261")) // without the check digit
        assertNull("wrong check digit", GTINExtractor.expandUPCE("04252615"))
        assertNull("number system 2 has no UPC-E", GTINExtractor.expandUPCE("24252614"))
        for (upca in listOf("042100005264", "012300000451", "012340000053", "012345000072", "112345000062")) {
            assertTrue(upca, BarcodeKey.isValidGTIN(upca))
        }
    }

    // MARK: - Scanner payloads

    @Test
    fun `linear codes pass through`() {
        assertEquals("5901234123457", GTINExtractor.gtin("5901234123457", GTINExtractor.Symbology.Ean13))
        assertEquals("20582555", GTINExtractor.gtin("20582555", GTINExtractor.Symbology.Ean8))
        assertEquals("036000291452", GTINExtractor.gtin("036000291452", GTINExtractor.Symbology.UpcA))
        assertEquals("0042100005264", GTINExtractor.gtin("04252614", GTINExtractor.Symbology.UpcE))
        assertNull(GTINExtractor.gtin("04252615", GTINExtractor.Symbology.UpcE))
        assertNull(GTINExtractor.gtin("12345", GTINExtractor.Symbology.Ean13))
        assertNull(GTINExtractor.gtin("5901234123457", GTINExtractor.Symbology.Other))
    }

    @Test
    fun `a UPC-E the decoder already expanded passes through`() {
        assertEquals("042100005264", GTINExtractor.gtin("042100005264", GTINExtractor.Symbology.UpcE))
        assertNull(GTINExtractor.gtin("042100005265", GTINExtractor.Symbology.UpcE))
    }

    @Test
    fun `GS1 element strings`() {
        val gs = "\u001D"
        assertEquals("5901234123457", GTINExtractor.gtin("(01)05901234123457(3103)000150", GTINExtractor.Symbology.Gs1DataBar))
        assertEquals("5901234123457", GTINExtractor.gtin("0105901234123457", GTINExtractor.Symbology.Gs1DataBar))
        assertEquals("5901234123457", GTINExtractor.gtin("05901234123457", GTINExtractor.Symbology.Gs1DataBar))
        assertEquals("5901234123457", GTINExtractor.gtin("]e00105901234123457310300015015260131", GTINExtractor.Symbology.Gs1DataBar))
        assertEquals(
            "5901234123457",
            GTINExtractor.gtin("]d2" + gs + "0105901234123457" + "10LOT7" + gs + "17260131", GTINExtractor.Symbology.DataMatrix),
        )
        // (01) after a fixed-length AI and after a variable-length one.
        assertEquals("5901234123457", GTINExtractor.gtin("172601310105901234123457", GTINExtractor.Symbology.DataMatrix))
        assertEquals("5901234123457", GTINExtractor.gtin("10ABC" + gs + "0105901234123457", GTINExtractor.Symbology.DataMatrix))
        // Variable-measure GTIN-14 (indicator 9) stays 14 digits; a GTIN-8 inside a GTIN-14 comes back as 8.
        assertEquals("20582555", GTINExtractor.gtin("(01)00000020582555", GTINExtractor.Symbology.Gs1DataBar))
        assertNull("wrong check digit", GTINExtractor.gtin("(01)05901234123458", GTINExtractor.Symbology.Gs1DataBar))
        assertNull(GTINExtractor.gtin("10ABC", GTINExtractor.Symbology.DataMatrix))
    }

    @Test
    fun `GS1 Digital Link and promo codes`() {
        assertEquals(
            "5901234123457",
            GTINExtractor.gtin("https://id.gs1.org/01/05901234123457/10/ABC?17=260131", GTINExtractor.Symbology.Qr),
        )
        assertEquals("5901234123457", GTINExtractor.gtin("https://brand.example.pl/gtin/5901234123457", GTINExtractor.Symbology.Qr))
        assertEquals("20582555", GTINExtractor.gtin("https://id.gs1.org/01/20582555", GTINExtractor.Symbology.DataMatrix))
        assertEquals("5901234123457", GTINExtractor.gtin("]Q3(01)05901234123457", GTINExtractor.Symbology.Qr))
        // Promo and plain QR codes never fire the scanner.
        assertNull(GTINExtractor.gtin("https://www.example.pl/promocja?kod=0105901234123457", GTINExtractor.Symbology.Qr))
        assertNull(GTINExtractor.gtin("https://id.gs1.org/01/05901234123458", GTINExtractor.Symbology.Qr))
        assertNull(GTINExtractor.gtin("5901234123457", GTINExtractor.Symbology.Qr))
        assertNull(GTINExtractor.gtin("Wygraj nagrody!", GTINExtractor.Symbology.Qr))
        assertEquals("95901234123454", GTINExtractor.normalized("95901234123454"))
    }

    @Test
    fun `manual entry`() {
        assertEquals("5901234123457", GTINExtractor.manual("5901234123457"))
        assertEquals("036000291452", GTINExtractor.manual("036000291452"))
        assertEquals("20582555", GTINExtractor.manual("20582555"))
        assertEquals("a UPC-E fails the EAN-8 check and is expanded", "0042100005264", GTINExtractor.manual("04252614"))
        assertNull("typo in the last digit", GTINExtractor.manual("5901234123458"))
        assertNull(GTINExtractor.manual("5901234"))
    }

    // MARK: - In-store codes

    @Test
    fun `in-store codes are filed under the item key`() {
        assertEquals("2412345", BarcodeKey.storageKey("2412345004526")) // scale label, weight or price in 8–12
        assertEquals("2912345", BarcodeKey.storageKey("2912345012342"))
        assertEquals("2712345", BarcodeKey.storageKey("2712345123457"))
        assertEquals("value field 00000 is a fixed code", "2312345000002", BarcodeKey.storageKey("2312345000002"))
        assertEquals("20–22 are fixed retailer codes", "2212345004522", BarcodeKey.storageKey("2212345004522"))
        assertEquals("2050401935713", BarcodeKey.storageKey("2050401935713"))
        assertEquals("2873330000006", BarcodeKey.storageKey("2873330000006"))
        assertEquals("20582555", BarcodeKey.storageKey("20582555"))
        assertEquals("5901234123457", BarcodeKey.storageKey("5901234123457"))
    }

    @Test
    fun `local keys`() {
        assertEquals(listOf("2412345004526", "2412345"), BarcodeKey.localKeys("2412345004526"))
        assertEquals(listOf("0049000028911", "049000028911"), BarcodeKey.localKeys("0049000028911"))
        assertEquals(listOf("049000028911", "0049000028911"), BarcodeKey.localKeys("049000028911"))
        assertEquals(listOf("5901234123457"), BarcodeKey.localKeys("5901234123457"))
    }

    // MARK: - Saved foods

    private fun food(id: String, source: FoodSource, barcode: String?, lastUsed: Long?) = FoodItemEntity(
        id = id, name = id, source = source, barcode = barcode,
        kcalPer100 = 100.0, proteinPer100 = 1.0, carbsPer100 = 1.0, fatPer100 = 1.0, lastUsedAt = lastUsed,
    )

    @Test
    fun `the preferred pick is deterministic`() {
        val offOld = food("off:a", FoodSource.OpenFoodFacts, "1", 100)
        val offNew = food("off:b", FoodSource.OpenFoodFacts, "1", 200)
        val offNever = food("off:c", FoodSource.OpenFoodFacts, "1", null)
        assertEquals("most recently used", "off:b", BarcodeKey.preferred(listOf(offOld, offNever, offNew))?.id)
        assertEquals("never used goes last", "off:a", BarcodeKey.preferred(listOf(offNever, offOld))?.id)
        val label = food("label:1", FoodSource.Custom, "1", null)
        assertEquals("the user's own label wins", "label:1", BarcodeKey.preferred(listOf(offNew, label, offOld))?.id)
        val twinA = food("off:x", FoodSource.OpenFoodFacts, "2", null)
        val twinB = food("off:w", FoodSource.OpenFoodFacts, "2", null)
        assertEquals("ties break on id", "off:w", BarcodeKey.preferred(listOf(twinA, twinB))?.id)
        assertNull(BarcodeKey.preferred(emptyList()))
    }
}
