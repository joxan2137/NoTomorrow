package app.notomorrow.service

import android.content.Context
import androidx.annotation.StringRes
import app.notomorrow.R
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.makeFoodItem
import app.notomorrow.model.FoodCandidate
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Open Food Facts client — the 1:1 port of the `FoodSearchService` actor in
 * `NoTomorrow/Services/FoodSearchService.swift`.
 *
 * Debouncing ([DEBOUNCE_MS], ≥ [MINIMUM_QUERY_LENGTH] characters) is the view model's job; this
 * service refuses a second identical in-flight query ([FoodSearchError.AlreadyInFlight]) and
 * memoises results for five minutes. Swift's `actor` isolation is a [Mutex] here.
 */
class FoodSearchService(
    engine: HttpClientEngine? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val client: HttpClient = HttpClient(engine ?: OkHttp.create()) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
        }
    }

    private val mutex = Mutex()
    private val cache = LruTtlCache<String, List<FoodCandidate>>(CACHE_CAPACITY, CACHE_TTL_MS, now)
    private val inFlight = mutableSetOf<String>()

    // MARK: - Text search

    suspend fun search(query: String, locale: String): List<FoodCandidate> {
        val q = query.trim()
        if (q.length < MINIMUM_QUERY_LENGTH) return emptyList()
        val lc = languageCode(locale)
        val key = "$lc|${q.lowercase(Locale.ROOT)}"
        mutex.withLock { cache.value(key) }?.let { return it }
        claim(key)
        try {
            val body = get(
                SEARCH_URL,
                listOf(
                    "search_terms" to q,
                    "search_simple" to "1",
                    "action" to "process",
                    "json" to "1",
                    "page_size" to PAGE_SIZE.toString(),
                    "fields" to FIELDS,
                    "lc" to lc,
                ),
            )
            val root = jsonObjectOrNull(body) ?: throw FoodSearchError.BadResponse(0)
            val results = offSearchProducts(root)
                .mapNotNull { candidate(it, lc) }
                .distinctBy { it.id }
            mutex.withLock { cache.set(results, key) }
            return results
        } finally {
            release(key)
        }
    }

    // MARK: - Barcode

    suspend fun lookup(barcode: String): FoodCandidate? {
        val forms = barcodeForms(barcode)
        if (forms.isEmpty()) return null
        for (code in forms) {
            val key = "barcode|$code"
            mutex.withLock { cache.value(key)?.firstOrNull() }?.let { return it }
            claim(key)
            try {
                val body = try {
                    get("$PRODUCT_URL_PREFIX$code.json", listOf("fields" to FIELDS, "lc" to "pl"))
                } catch (e: FoodSearchError.BadResponse) {
                    if (e.status == 404) continue else throw e
                }
                val root = jsonObjectOrNull(body) ?: continue
                val product = offProduct(root) ?: continue
                val candidate = candidate(product, "pl") ?: continue
                mutex.withLock { cache.set(listOf(candidate), key) }
                return candidate
            } finally {
                release(key)
            }
        }
        return null
    }

    // MARK: - Local cache (the tap path)

    /**
     * `FoodItemOrCandidate.resolveItem(in:)` (`FuelSupport.swift:115`) for the candidate branch:
     * the row is inserted on first use and its usage stats are bumped. Returns the persisted row.
     */
    suspend fun cacheOnTap(candidate: FoodCandidate, dao: FoodDao, at: Long = now()): FoodItemEntity {
        val existing = dao.byId(candidate.id)
        val item = existing ?: candidate.makeFoodItem().also { dao.upsert(it) }
        dao.bumpUsage(item.id, at)
        return item.copy(useCount = item.useCount + 1, lastUsedAt = at)
    }

    // MARK: - In-flight guard

    /** Claims the key. Throws [FoodSearchError.AlreadyInFlight] when the same call is already running. */
    private suspend fun claim(key: String) {
        mutex.withLock { if (!inFlight.add(key)) throw FoodSearchError.AlreadyInFlight }
    }

    private suspend fun release(key: String) {
        mutex.withLock { inFlight.remove(key) }
    }

    // MARK: - Transport

    private suspend fun get(url: String, params: List<Pair<String, String>>): String {
        val response = try {
            client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                params.forEach { (name, value) -> parameter(name, value) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // URLSession failures surface as a plain error on iOS; the search sheet shows
            // `fuel.search.error.network` either way.
            throw FoodSearchError.BadResponse(0)
        }
        val status = response.status.value
        return when {
            status in 200..299 -> response.bodyAsText()
            status == 429 -> throw FoodSearchError.RateLimited
            else -> throw FoodSearchError.BadResponse(status)
        }
    }

    companion object {
        const val USER_AGENT: String = "NoTomorrow/0.1 (markzaluben@proton.me)"
        const val FIELDS: String =
            "code,product_name,product_name_pl,brands,quantity,serving_size,serving_quantity," +
                "nutriments,image_front_small_url,countries_tags"
        const val MINIMUM_QUERY_LENGTH: Int = 2

        /** `FoodSearchModel.debounce` — the view model's, documented here beside the minimum length. */
        const val DEBOUNCE_MS: Long = 500

        const val SEARCH_URL: String = "https://world.openfoodfacts.org/cgi/search.pl"
        const val PRODUCT_URL_PREFIX: String = "https://world.openfoodfacts.org/api/v2/product/"
        const val PAGE_SIZE: Int = 24
        const val TIMEOUT_MS: Long = 15_000
        const val CACHE_CAPACITY: Int = 40
        const val CACHE_TTL_MS: Long = 5 * 60 * 1000

        fun languageCode(locale: String): String =
            if (locale.lowercase(Locale.ROOT).startsWith("pl")) "pl" else "en"

        /**
         * EAN-13 from the scanner; a leading zero usually means the product is stored under its
         * 12-digit UPC-A form.
         */
        fun barcodeForms(raw: String): List<String> {
            val digits = raw.filter { it in '0'..'9' }
            if (digits.length !in listOf(8, 12, 13, 14)) return emptyList()
            val forms = mutableListOf(digits)
            if (digits.length == 14 && digits.startsWith("0")) forms.add(digits.substring(1))
            if (digits.length == 13 && digits.startsWith("0")) forms.add(digits.substring(1))
            if (digits.length == 12) forms.add("0$digits")
            return forms
        }

        /** A product with no `energy-kcal_100g`, no code or no name is dropped. */
        internal fun candidate(p: OffProduct, locale: String): FoodCandidate? {
            val code = p.code
            if (code.isNullOrEmpty()) return null
            val pl = p.productNamePL?.trim() ?: ""
            val en = p.productName?.trim() ?: ""
            val name = if (locale == "pl" && pl.isNotEmpty()) pl else (if (en.isNotEmpty()) en else pl)
            if (name.isEmpty()) return null
            val n = p.nutriments
            val kj = n["energy-kj_100g"] ?: n["energy_100g"]
            val kcal = n["energy-kcal_100g"] ?: kj?.div(4.184) ?: return null
            if (!kcal.isFinite() || kcal !in 0.0..950.0) return null
            if (listOf("proteins_100g", "carbohydrates_100g", "fat_100g").any { key ->
                n[key]?.let { !it.isFinite() || it !in 0.0..100.0 } == true
            }) return null

            val brand = p.brands?.split(",")?.firstOrNull()?.trim()
            val servingLabel = p.servingSize?.trim()
            var servingG = if (servingLabel?.lowercase()?.contains("ml") == true) null else p.servingQuantity
            if (servingG == null && servingLabel != null) servingG = grams(servingLabel)
            if (servingG != null && servingG <= 0) servingG = null

            return FoodCandidate(
                id = "off:$code",
                code = code,
                name = name,
                brand = brand?.takeIf { it.isNotEmpty() },
                quantity = p.quantity,
                servingSizeG = servingG,
                servingLabel = servingLabel?.takeIf { it.isNotEmpty() },
                kcalPer100 = kcal,
                proteinPer100 = n["proteins_100g"] ?: 0.0,
                carbsPer100 = n["carbohydrates_100g"] ?: 0.0,
                fatPer100 = n["fat_100g"] ?: 0.0,
                fiberPer100 = n["fiber_100g"],
                imageURL = p.imageFrontSmallURL,
            )
        }

        /** Parse mass units only; a millilitre is not necessarily a gram. */
        fun grams(fromLabel: String): Double? {
            val text = fromLabel.lowercase(Locale.ROOT).replace(",", ".")
            val match = MASS.find(text) ?: return null
            val amount = match.groupValues[2].toDoubleOrNull() ?: return null
            val count = match.groupValues[1].toDoubleOrNull() ?: 1.0
            return (amount * count * if (match.groupValues[3] == "kg") 1000 else 1).takeIf { it > 0 && it.isFinite() }
        }
        private val MASS = Regex("""(?:(\d+(?:\.\d+)?)\s*[x×]\s*)?(\d+(?:\.\d+)?)\s*(kg|g)\b""")

    }
}

/** `FoodSearchError` (`FoodSearchService.swift:26`). */
sealed class FoodSearchError(message: String? = null) : Exception(message) {

    data object AlreadyInFlight : FoodSearchError()

    data object RateLimited : FoodSearchError()

    data class BadResponse(val status: Int) : FoodSearchError()

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            AlreadyInFlight -> R.string.fuel_search_error_inFlight
            RateLimited -> R.string.fuel_search_error_rateLimited
            is BadResponse -> R.string.fuel_search_error_network
        }

    fun localizedMessage(context: Context): String = context.getString(messageRes)
}
