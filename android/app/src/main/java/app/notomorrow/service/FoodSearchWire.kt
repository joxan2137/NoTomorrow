package app.notomorrow.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Open Food Facts wire shapes — the port of `OFFSearchResponse` / `SearchALiciousResponse` /
 * `OFFProductResponse` / `OFFProduct` / `LooseNumber` at the bottom of
 * `NoTomorrow/Services/FoodSearchService.swift`.
 *
 * Swift decodes every field with `try?`, so a malformed value is `nil` rather than a thrown
 * error. `JsonObject` lookups reproduce that exactly: nothing here can throw.
 */
internal data class OffProduct(
    val code: String? = null,
    val productName: String? = null,
    val productNamePL: String? = null,
    val productNameEN: String? = null,
    val genericName: String? = null,
    val genericNamePL: String? = null,
    val abbreviatedProductName: String? = null,
    /** The product's main language ("pl", "fr"…): `product_name` is written in it. */
    val lang: String? = null,
    /** "Lidl, Nergi". search-a-licious sends an array, which is joined the same way. */
    val brands: String? = null,
    val quantity: String? = null,
    val servingSize: String? = null,
    val servingQuantity: Double? = null,
    val nutriments: Map<String, Double?>? = null,
    val nutrimentsEstimated: Map<String, Double?>? = null,
    val imageFrontSmallURL: String? = null,
) {
    companion object {
        fun from(json: JsonObject): OffProduct = OffProduct(
            // OFF sometimes returns the code as a number.
            code = LooseNumber.text(json["code"]),
            productName = LooseNumber.string(json["product_name"]),
            productNamePL = LooseNumber.string(json["product_name_pl"]),
            productNameEN = LooseNumber.string(json["product_name_en"]),
            genericName = LooseNumber.string(json["generic_name"]),
            genericNamePL = LooseNumber.string(json["generic_name_pl"]),
            abbreviatedProductName = LooseNumber.string(json["abbreviated_product_name"]),
            lang = LooseNumber.string(json["lang"]),
            brands = LooseNumber.string(json["brands"]) ?: LooseNumber.strings(json["brands"])?.joinToString(", "),
            quantity = LooseNumber.string(json["quantity"]),
            servingSize = LooseNumber.string(json["serving_size"]),
            servingQuantity = LooseNumber.value(json["serving_quantity"]),
            nutriments = numbers(json["nutriments"]),
            nutrimentsEstimated = numbers(json["nutriments_estimated"]),
            imageFrontSmallURL = LooseNumber.string(json["image_front_small_url"]),
        )

        /** `[String: LooseNumber]?`: an object of loose numbers, `null` when missing or not an object. */
        private fun numbers(element: JsonElement?): Map<String, Double?>? =
            (element as? JsonObject)?.mapValues { (_, value) -> LooseNumber.value(value) }
    }
}

/**
 * `LooseNumber` — OFF nutriment values arrive as numbers or numeric strings (and occasionally
 * as unit labels, which decode to `null`). Comma decimals are normalised, exactly as Swift does
 * before `Double(_:)`.
 */
internal object LooseNumber {

    fun value(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content.replace(',', '.').trim().toDoubleOrNull()
        } else {
            primitive.content.toDoubleOrNull()
        }
    }

    /** A field Swift decodes as `String` — a JSON number would fail there, so it must be a string. */
    fun string(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content
    }

    /** `[String]`: an array of strings, `null` when it is not one (one non-string element fails it). */
    fun strings(element: JsonElement?): List<String>? {
        val array = element as? JsonArray ?: return null
        return array.map { string(it) ?: return null }
    }

    /** `code`: `String` first, then `Int64` rendered back to text. */
    fun text(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.content.toLongOrNull()?.toString()
    }
}

/** `OFFSearchResponse.products` (legacy `cgi/search.pl`), tolerant of a missing or wrongly typed array. */
internal fun offSearchProducts(root: JsonObject): List<OffProduct> = products(root["products"])

/** `SearchALiciousResponse.hits`: search-a-licious puts the products under `hits`. */
internal fun searchALiciousHits(root: JsonObject): List<OffProduct> = products(root["hits"])

private fun products(element: JsonElement?): List<OffProduct> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item -> (item as? JsonObject)?.let(OffProduct::from) }
}

/** `OFFProductResponse`: the v2 product read, with the top-level `code` kept for products that lack their own. */
internal data class OffProductResponse(
    val code: String? = null,
    val status: Int? = null,
    val product: OffProduct? = null,
) {
    companion object {
        fun from(root: JsonObject): OffProductResponse = OffProductResponse(
            code = LooseNumber.string(root["code"]),
            status = LooseNumber.value(root["status"])?.toInt(),
            product = (root["product"] as? JsonObject)?.let(OffProduct::from),
        )
    }
}

/** Parses a response body into an object, returning `null` when it is not one. */
internal fun jsonObjectOrNull(body: String): JsonObject? =
    runCatching { app.notomorrow.net.dto.NtJson.parseToJsonElement(body).jsonObject }.getOrNull()

/**
 * `LRUCache<Key, Value>` with a TTL — capacity 40, TTL 5 min, so back-and-forth typing does not
 * burn the Open Food Facts 10 req/min budget. Not thread-safe; the service holds it under a mutex,
 * the way Swift's `actor` isolates the struct.
 */
internal class LruTtlCache<K, V>(
    capacity: Int,
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val value: V, val storedAt: Long)

    private val capacity = maxOf(1, capacity)

    /** `accessOrder = true` gives Swift's `touch(key)` for free. */
    private val storage = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    fun value(key: K): V? {
        val entry = storage[key] ?: return null
        if (now() - entry.storedAt > ttlMillis) {
            storage.remove(key)
            return null
        }
        return entry.value
    }

    fun set(value: V, key: K) {
        storage[key] = Entry(value, now())
        while (storage.size > capacity) {
            val oldest = storage.keys.firstOrNull() ?: break
            storage.remove(oldest)
        }
    }
}
