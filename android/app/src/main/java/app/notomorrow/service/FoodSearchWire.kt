package app.notomorrow.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Open Food Facts wire shapes — the port of `OFFSearchResponse` / `OFFProductResponse` /
 * `OFFProduct` / `LooseNumber` at the bottom of `NoTomorrow/Services/FoodSearchService.swift`.
 *
 * Swift decodes every field with `try?`, so a malformed value is `nil` rather than a thrown
 * error. `JsonObject` lookups reproduce that exactly: nothing here can throw.
 */
internal data class OffProduct(
    val code: String? = null,
    val productName: String? = null,
    val productNamePL: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    val servingSize: String? = null,
    val servingQuantity: Double? = null,
    val nutriments: Map<String, Double?> = emptyMap(),
    val imageFrontSmallURL: String? = null,
) {
    companion object {
        fun from(json: JsonObject): OffProduct = OffProduct(
            // OFF sometimes returns the code as a number.
            code = LooseNumber.text(json["code"]),
            productName = LooseNumber.string(json["product_name"]),
            productNamePL = LooseNumber.string(json["product_name_pl"]),
            brands = LooseNumber.string(json["brands"]),
            quantity = LooseNumber.string(json["quantity"]),
            servingSize = LooseNumber.string(json["serving_size"]),
            servingQuantity = LooseNumber.value(json["serving_quantity"]),
            nutriments = (json["nutriments"] as? JsonObject)
                ?.mapValues { (_, element) -> LooseNumber.value(element) }
                ?: emptyMap(),
            imageFrontSmallURL = LooseNumber.string(json["image_front_small_url"]),
        )
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

    /** `code`: `String` first, then `Int64` rendered back to text. */
    fun text(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.content.toLongOrNull()?.toString()
    }
}

/** `OFFSearchResponse.products`, tolerant of a missing or wrongly typed array. */
internal fun offSearchProducts(root: JsonObject): List<OffProduct> {
    val array = root["products"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonObject)?.let(OffProduct::from) }
}

/** `OFFProductResponse` — `status == 1` plus the product object. */
internal fun offProduct(root: JsonObject): OffProduct? {
    val status = LooseNumber.value(root["status"])?.toInt()
    if (status != 1) return null
    val product = root["product"] as? JsonObject ?: return null
    return OffProduct.from(product)
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
