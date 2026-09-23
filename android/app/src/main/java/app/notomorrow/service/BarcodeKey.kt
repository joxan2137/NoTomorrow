package app.notomorrow.service

import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.model.FoodSource

/**
 * Where a scanned code lives on this device: check-digit validation, the key an in-store weight
 * label is saved under, and which saved food wins when several share a code — the port of
 * `NoTomorrow/Services/BarcodeKey.swift`.
 */
object BarcodeKey {

    /** GTIN-8/12/13/14 mod-10 check digit (port of backend/src/food.ts `validBarcode`). */
    fun isValidGTIN(code: String): Boolean {
        if (code.length !in GTIN_LENGTHS || !code.all(::isAsciiDigit)) return false
        val digits = code.map { it - '0' }
        val sum = digits.dropLast(1).reversed()
            .withIndex()
            .sumOf { (index, digit) -> digit * if (index % 2 == 0) 3 else 1 }
        return (10 - sum % 10) % 10 == digits.last()
    }

    /**
     * In-store variable-measure codes (GS1 Polska RCN: 23/27 packer-printed, 24/29 scale labels,
     * 25/26/28 retailer-internal) carry the weight or price in digits 8–12, so the full code
     * changes with every package. They are saved under the 7-digit item prefix instead. Fixed
     * retailer codes (20–22, e.g. Lidl, Carrefour own brands, which are in Open Food Facts) and
     * codes whose value field is 00000 keep the full code.
     */
    fun storageKey(code: String): String {
        if (code.length != 13 || !code.all(::isAsciiDigit)) return code
        val prefix = code.substring(0, 2).toInt()
        if (prefix !in 23..29 || code.substring(7, 12) == "00000") return code
        return code.substring(0, 7)
    }

    /** Every barcode a scanned code may already be saved under on this device: its OFF forms plus the RCN item key. */
    fun localKeys(code: String): List<String> {
        val keys = FoodSearchService.barcodeForms(code).toMutableList()
        val key = storageKey(keys.firstOrNull() ?: code)
        if (key !in keys) keys.add(key)
        return keys
    }

    /**
     * Several saved foods can share a code (a label the user typed and an Open Food Facts hit
     * logged from search). The user's own label wins, then the most recently used, then the id,
     * so the pick never depends on store order. `FoodDao.preferredByBarcode` is the same order in SQL.
     */
    fun preferred(items: List<FoodItemEntity>): FoodItemEntity? = items.minWithOrNull(PREFERRED)

    private val PREFERRED: Comparator<FoodItemEntity> =
        compareBy<FoodItemEntity> { if (it.source == FoodSource.Custom) 0 else 1 }
            .thenBy { it.lastUsedAt == null }
            .thenByDescending { it.lastUsedAt }
            .thenBy { it.id }

    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

    internal fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
}
