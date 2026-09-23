package app.notomorrow.service

import app.notomorrow.net.dto.NtJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Open Food Facts responses captured live on 2026-09-22 (diag/barcode.md §3.3), trimmed to the
 * fields the app requests — the same JSON as `NoTomorrowTests/OFFFixtures.swift`, kept in
 * `src/test/resources/off/`.
 */
internal object OffFixtures {
    /** Kopernik "Bagatelka": `product_name` / `product_name_pl` empty, only `product_name_en`; full nutrition. */
    const val NAME_ONLY_ENGLISH = "off_5900056007181_name_only_en.json"

    /** Pudliszki concentrate: no name in any field, brand + quantity only; full nutrition. */
    const val NO_NAME_BRAND_QUANTITY = "off_5900783000134_no_name_brand_qty.json"

    /** Lidl mini kiwi: empty `nutriments`, OFF's own `nutriments_estimated`; main language French. */
    const val ESTIMATED_ONLY = "off_20582555_estimated_only.json"

    /** Carrefour "Łosoś świeży" MOWI: name, brand, 150 g, no nutrition at all. */
    const val NAME_NO_NUTRITION = "off_2050401935713_name_no_nutrition.json"

    /** Żabka code: a stub with every field empty. */
    const val EMPTY_STUB = "off_5901067400831_empty_stub.json"

    /** The v2 body OFF sends with HTTP 404. */
    const val NOT_FOUND = "off_404.json"

    /**
     * search-a-licious `/search?q=serek wiejski&langs=pl,en`: `hits`, `brands` as an array, one
     * hit named only in English, one without nutriments (dropped).
     */
    const val SEARCH_A_LICIOUS = "sal_serek_wiejski.json"

    fun text(name: String): String {
        val stream = requireNotNull(OffFixtures::class.java.getResourceAsStream("/off/$name")) { "missing fixture $name" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun json(name: String): JsonObject = NtJson.parseToJsonElement(text(name)).jsonObject

    fun response(name: String): OffProductResponse = OffProductResponse.from(json(name))
}
