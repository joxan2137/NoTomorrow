package app.notomorrow.service

import java.net.URI

/**
 * Turns a scanner payload into the product code Open Food Facts files it under, or `null` when
 * the payload carries no GTIN (a promo QR code, a misread), so the scanner keeps looking instead
 * of firing on it — the port of `NoTomorrow/Services/GTINExtractor.swift`.
 */
object GTINExtractor {

    /** The decoder's symbology, reduced to what changes the parsing. */
    enum class Symbology {
        Ean13, Ean8, UpcA, UpcE,

        /** GS1 DataBar, DataBar Expanded and DataBar Limited (iOS only: ML Kit cannot read them). */
        Gs1DataBar,
        Qr, DataMatrix, Other,
    }

    fun gtin(payload: String, symbology: Symbology): String? {
        val text = payload.trim()
        return when (symbology) {
            Symbology.Ean13, Symbology.Ean8, Symbology.UpcA -> {
                val digits = text.filter(BarcodeKey::isAsciiDigit)
                digits.takeIf { it.length in GTIN_LENGTHS }
            }
            Symbology.UpcE -> {
                val digits = text.filter(BarcodeKey::isAsciiDigit)
                // ML Kit may already hand over the expanded UPC-A for a UPC-E; a valid 12/13-digit
                // code passes through rather than stalling the scanner.
                expandUPCE(digits)?.let { "0$it" }
                    ?: digits.takeIf { it.length in 12..13 && BarcodeKey.isValidGTIN(it) }
            }
            Symbology.Gs1DataBar ->
                // Some decoders hand over the bare GTIN instead of the (01) element string.
                if (BarcodeKey.isValidGTIN(text)) {
                    if (text.length == 14) normalized(text) else text
                } else {
                    elementStringGTIN(text)
                }
            Symbology.Qr, Symbology.DataMatrix -> digitalLinkGTIN(text) ?: elementStringGTIN(text)
            Symbology.Other -> null
        }
    }

    /**
     * A code typed by hand: a valid GTIN as is, or an 8-digit UPC-E (which fails the EAN-8 check)
     * expanded to its 13-digit form. `null` when the digits cannot be a real code, so the entry
     * field can ask the user to check them.
     */
    fun manual(digits: String): String? {
        if (BarcodeKey.isValidGTIN(digits)) return digits
        if (digits.length == 8) expandUPCE(digits)?.let { return "0$it" }
        return null
    }

    // MARK: - UPC-E

    /**
     * UPC-E → 12-digit UPC-A (number system 0 or 1). Accepts the 8-digit form, 7 digits without
     * the check digit, or the 6 compressed digits alone; a given check digit must match the expansion.
     */
    fun expandUPCE(code: String): String? {
        if (code.length !in 6..8 || !code.all(BarcodeKey::isAsciiDigit)) return null
        val numberSystem = if (code.length == 6) '0' else code[0]
        if (numberSystem != '0' && numberSystem != '1') return null
        val x = if (code.length == 6) code else code.substring(1, 7)
        val body = when (x[5]) {
            '0', '1', '2' -> "$numberSystem${x[0]}${x[1]}${x[5]}0000${x[2]}${x[3]}${x[4]}"
            '3' -> "$numberSystem${x[0]}${x[1]}${x[2]}00000${x[3]}${x[4]}"
            '4' -> "$numberSystem${x[0]}${x[1]}${x[2]}${x[3]}00000${x[4]}"
            else -> "$numberSystem${x[0]}${x[1]}${x[2]}${x[3]}${x[4]}0000${x[5]}"
        }
        val check = checkDigit(body)
        if (code.length == 8 && code[7] != check) return null
        return "$body$check"
    }

    // MARK: - GS1 element strings (DataBar, GS1 DataMatrix, GS1 QR)

    private const val GROUP_SEPARATOR = '\u001D'

    /**
     * Total length (AI + data) of the predefined fixed-length AIs, keyed by their first two digits
     * (GS1 General Specifications, figure 7.8.5-2). Every other AI is variable length and ends at a
     * group separator.
     */
    private val FIXED_LENGTHS: Map<String, Int> = mapOf(
        "00" to 20, "01" to 16, "02" to 16, "03" to 16, "04" to 18,
        "11" to 8, "12" to 8, "13" to 8, "14" to 8, "15" to 8, "16" to 8, "17" to 8, "18" to 8, "19" to 8, "20" to 4,
        "31" to 10, "32" to 10, "33" to 10, "34" to 10, "35" to 10, "36" to 10, "41" to 16,
    )

    private val PARENTHESISED_GTIN = Regex("""\(01\)(\d{14})""")

    /**
     * AI (01) from "(01)05901234123457(3103)000150", or the raw form with an optional symbology
     * identifier (]C1, ]e0, ]d2, ]Q3) and FNC1 / group separators. `null` when there is no (01)
     * or its check digit is wrong.
     */
    fun elementStringGTIN(payload: String): String? {
        var s = payload
        if (s.startsWith("]") && s.length >= 3) s = s.substring(3)
        s = s.trimStart(GROUP_SEPARATOR)
        val gtin = if (s.startsWith("(")) PARENTHESISED_GTIN.find(s)?.groupValues?.get(1) else walkToGTIN(s)
        if (gtin == null || !BarcodeKey.isValidGTIN(gtin)) return null
        return normalized(gtin)
    }

    private fun walkToGTIN(element: String): String? {
        var s = element
        while (s.length >= 2) {
            val ai = s.substring(0, 2)
            if (!ai.all(BarcodeKey::isAsciiDigit)) return null
            if (ai == "01") {
                val value = s.drop(2).take(14)
                return value.takeIf { it.length == 14 && it.all(BarcodeKey::isAsciiDigit) }
            }
            val length = FIXED_LENGTHS[ai]
            if (length != null) {
                s = s.drop(length)
                if (s.firstOrNull() == GROUP_SEPARATOR) s = s.drop(1)
            } else {
                val separator = s.indexOf(GROUP_SEPARATOR)
                if (separator < 0) return null
                s = s.substring(separator + 1)
            }
        }
        return null
    }

    // MARK: - GS1 Digital Link

    /**
     * "https://id.gs1.org/01/05901234123457/10/ABC" (any host; "gtin" is the long alias of "01").
     * The GTIN may be 8–14 digits; it is padded to 14 and must pass the check digit.
     */
    fun digitalLinkGTIN(payload: String): String? {
        val uri = runCatching { URI(payload) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val parts = (uri.path ?: return null).split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            if ((part == "01" || part.lowercase() == "gtin") && index + 1 < parts.size) {
                val value = parts[index + 1]
                if (value.length in 8..14 && value.all(BarcodeKey::isAsciiDigit)) {
                    val gtin14 = value.padStart(14, '0')
                    if (BarcodeKey.isValidGTIN(gtin14)) return normalized(gtin14)
                }
            }
        }
        return null
    }

    // MARK: - Helpers

    /**
     * GTIN-14 → the form printed on retail packs: a GTIN-8 (six leading zeros, GS1 prefix 00000 is
     * reserved for them), else the EAN-13 without the leading packaging-level zero, else the 14
     * digits (outer cases, variable measure).
     */
    fun normalized(gtin14: String): String = when {
        gtin14.startsWith("000000") -> gtin14.takeLast(8)
        gtin14.startsWith("0") -> gtin14.drop(1)
        else -> gtin14
    }

    private fun checkDigit(body: String): Char {
        val sum = body.reversed().withIndex()
            .sumOf { (index, c) -> (c - '0') * if (index % 2 == 0) 3 else 1 }
        return '0' + (10 - sum % 10) % 10
    }

    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)
}
