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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * A product Open Food Facts knows by barcode but cannot size: a name, maybe a brand, pack size
 * and photo, and no usable nutrition. The label form starts from it (`ProductStub`).
 */
data class ProductStub(
    val code: String,
    val name: String,
    val brand: String? = null,
    val quantity: String? = null,
    val servingSizeG: Double? = null,
    val imageURL: String? = null,
)

/** What a barcode lookup found: a product ready to size, a product without nutrition, or nothing (`BarcodeLookup`). */
sealed interface BarcodeLookup {
    data class Found(val candidate: FoodCandidate) : BarcodeLookup
    data class Partial(val stub: ProductStub) : BarcodeLookup
    data object NotFound : BarcodeLookup
}

/**
 * Open Food Facts client — the 1:1 port of the `FoodSearchService` actor in
 * `NoTomorrow/Services/FoodSearchService.swift`.
 *
 * Debouncing ([DEBOUNCE_MS], ≥ [MINIMUM_QUERY_LENGTH] characters) is the view model's job; this
 * service refuses a second identical in-flight query ([FoodSearchError.AlreadyInFlight]),
 * memoises results for five minutes and paces text searches so back-and-forth typing stays inside
 * OFF's budget. Text search goes to search-a-licious; the legacy `cgi/search.pl` (which answered
 * 503 to anonymous users in September 2026) is a fallback, and a supplement for Polish queries
 * search-a-licious answers thinly: it does not fold diacritics ("zurek" finds English hits,
 * "żurek" finds nothing), the legacy search does. Barcodes use the v2 product API, which allows
 * 15 reads per minute per IP. Swift's `actor` isolation is a [Mutex] here.
 *
 * [isOnline] tells "no connection" from "no answer" when a request fails in transport, the way
 * `URLError.notConnectedToInternet` does on iOS.
 */
class FoodSearchService(
    engine: HttpClientEngine? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val isOnline: () -> Boolean = { true },
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
    private val lookups = LruTtlCache<String, BarcodeLookup>(CACHE_CAPACITY, CACHE_TTL_MS, now)
    private val inFlight = mutableSetOf<String>()
    private val searchSlots = RequestWindow(SEARCH_BUDGET, SEARCH_WINDOW_MS)
    private var searchCooldownUntil: Long? = null

    // MARK: - Text search

    suspend fun search(query: String, locale: String): List<FoodCandidate> {
        val q = query.trim()
        if (q.length < MINIMUM_QUERY_LENGTH) return emptyList()
        val terms = searchTerms(q)
        if (terms.isEmpty()) return emptyList()
        val lc = languageCode(locale)
        val key = "$lc|${q.lowercase(Locale.ROOT)}"
        mutex.withLock { cache.value(key) }?.let { return it }
        claim(key)
        try {
            waitForSearchSlot()
            val results = try {
                val primary = candidates(searchALicious(terms, lc), lc)
                if (needsSupplement(primary.size, lc)) {
                    supplement(q, lc)?.let { merged(primary, it) } ?: primary
                } else {
                    primary
                }
            } catch (error: Throwable) {
                if (!fallsBack(error)) throw error
                candidates(legacySearch(q, lc), lc)
            }
            // A supplement cut short by a newer query must not leave its thinner answer in the cache.
            currentCoroutineContext().ensureActive()
            mutex.withLock { cache.set(results, key) }
            return results
        } finally {
            release(key)
        }
    }

    /** Usable products, one per code, in the order OFF ranked them. */
    private fun candidates(products: List<OffProduct>, lc: String): List<FoodCandidate> =
        merged(emptyList(), products.mapNotNull { candidate(it, lc) })

    /**
     * The legacy search as a supplement: only with a search slot free right now (it counts against
     * the same budget and never makes the user wait), and `null` on any failure, so the primary
     * answer still shows.
     */
    private suspend fun supplement(q: String, lc: String): List<FoodCandidate>? {
        val at = now()
        val free = mutex.withLock {
            val coolingDown = searchCooldownUntil?.let { it > at } ?: false
            !coolingDown && searchSlots.reserve(at, 0) != null
        }
        if (!free) return null
        val products = try {
            legacySearch(q, lc)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        return candidates(products, lc)
    }

    private suspend fun searchALicious(terms: String, lc: String): List<OffProduct> {
        val body = get(
            SEARCH_A_LICIOUS_URL,
            listOf(
                "q" to terms,
                "langs" to if (lc == "pl") "pl,en" else "en",
                "page_size" to PAGE_SIZE.toString(),
                "fields" to FIELDS,
            ),
            isSearch = true,
        )
        return searchALiciousHits(jsonObjectOrNull(body) ?: throw FoodSearchError.BadResponse(200))
    }

    private suspend fun legacySearch(q: String, lc: String): List<OffProduct> {
        val body = get(
            LEGACY_SEARCH_URL,
            listOf(
                "search_terms" to q,
                "search_simple" to "1",
                "action" to "process",
                "json" to "1",
                "page_size" to PAGE_SIZE.toString(),
                "fields" to FIELDS,
                "lc" to lc,
            ),
            isSearch = true,
        )
        return offSearchProducts(jsonObjectOrNull(body) ?: throw FoodSearchError.BadResponse(200))
    }

    private suspend fun waitForSearchSlot() {
        val at = now()
        val wait = mutex.withLock {
            searchCooldownUntil?.let { if (it > at) throw FoodSearchError.RateLimited }
            searchSlots.reserve(at, MAX_SEARCH_WAIT_MS) ?: throw FoodSearchError.RateLimited
        }
        if (wait > 0) delay(wait)
    }

    // MARK: - Barcode

    /**
     * Tries every EAN/UPC form of the code. A product with usable nutrition wins at once; a
     * product with only a name is remembered while the other forms are tried, and comes back as
     * [BarcodeLookup.Partial].
     */
    suspend fun lookup(barcode: String, locale: String = "pl"): BarcodeLookup {
        val forms = barcodeForms(barcode)
        val first = forms.firstOrNull() ?: return BarcodeLookup.NotFound
        val lc = languageCode(locale)
        val key = "barcode|$lc|$first"
        mutex.withLock { lookups.value(key) }?.let { return it }
        claim(key)
        try {
            var stub: ProductStub? = null
            for (code in forms) {
                val body = try {
                    product(code, lc)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A later form failing does not undo what an earlier one found.
                    if (stub != null) break else throw error
                } ?: continue
                when (val result = outcome(body, lc)) {
                    is BarcodeLookup.Found -> {
                        mutex.withLock { lookups.set(result, key) }
                        return result
                    }
                    is BarcodeLookup.Partial -> if (stub == null) stub = result.stub
                    BarcodeLookup.NotFound -> continue
                }
            }
            val result = stub?.let { BarcodeLookup.Partial(it) } ?: BarcodeLookup.NotFound
            mutex.withLock { lookups.set(result, key) }
            return result
        } finally {
            release(key)
        }
    }

    /**
     * One product read: `null` on 404. A 429 or 5xx is retried once after [retryDelayMs], then
     * surfaces as [FoodSearchError.RateLimited] / [FoodSearchError.Busy].
     */
    private suspend fun product(code: String, lc: String): OffProductResponse? {
        val url = "$PRODUCT_URL_PREFIX$code.json"
        val params = listOf("fields" to FIELDS, "lc" to lc)
        val body = try {
            try {
                get(url, params)
            } catch (error: FoodSearchError) {
                if (!error.isTransient) throw error
                delay(retryDelayMs)
                get(url, params)
            }
        } catch (error: FoodSearchError.BadResponse) {
            if (error.status == 404) return null
            throw error
        }
        return OffProductResponse.from(jsonObjectOrNull(body) ?: throw FoodSearchError.BadResponse(200))
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

    /** Runs from `finally`, so a cancelled caller still frees the key. */
    private suspend fun release(key: String) {
        withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
    }

    // MARK: - Transport

    private suspend fun get(url: String, params: List<Pair<String, String>>, isSearch: Boolean = false): String {
        val response = try {
            client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                params.forEach { (name, value) -> parameter(name, value) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw FoodSearchError.transport(online = runCatching(isOnline).getOrDefault(true))
        }
        val status = response.status.value
        if (status in 200..299) {
            return try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw FoodSearchError.transport(online = runCatching(isOnline).getOrDefault(true))
            }
        }
        if (status == 429 && isSearch) {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toDoubleOrNull()
            val seconds = (retryAfter ?: RATE_LIMIT_COOLDOWN_S).coerceIn(5.0, 120.0)
            mutex.withLock { searchCooldownUntil = now() + (seconds * 1000).toLong() }
        }
        throw FoodSearchError.from(status)
    }

    companion object {
        const val USER_AGENT: String = "NoTomorrow/0.1 (markzaluben@proton.me)"

        /**
         * One list for barcode reads and both search endpoints. The name fallbacks and
         * `nutriments_estimated` rescue products whose label name or values were never typed in.
         */
        const val FIELDS: String =
            "code,product_name,product_name_pl,product_name_en,generic_name,generic_name_pl," +
                "abbreviated_product_name,lang,brands,quantity,serving_size,serving_quantity," +
                "nutriments,nutriments_estimated,image_front_small_url,countries_tags"
        const val MINIMUM_QUERY_LENGTH: Int = 2

        /** `FoodSearchModel.debounce` — the view model's, documented here beside the minimum length. */
        const val DEBOUNCE_MS: Long = 500

        const val SEARCH_A_LICIOUS_URL: String = "https://search.openfoodfacts.org/search"
        const val LEGACY_SEARCH_URL: String = "https://world.openfoodfacts.org/cgi/search.pl"
        const val PRODUCT_URL_PREFIX: String = "https://world.openfoodfacts.org/api/v2/product/"
        const val PAGE_SIZE: Int = 24
        const val TIMEOUT_MS: Long = 15_000
        const val CACHE_CAPACITY: Int = 40
        const val CACHE_TTL_MS: Long = 5 * 60 * 1000

        /** The pause before the single retry of a barcode read that got a 429 or 5xx. */
        const val RETRY_DELAY_MS: Long = 1_500

        /** OFF allows 10 searches a minute per IP. A query past that waits up to [MAX_SEARCH_WAIT_MS] for a slot, else fails fast. */
        const val SEARCH_BUDGET: Int = 10
        const val SEARCH_WINDOW_MS: Long = 60_000
        const val MAX_SEARCH_WAIT_MS: Long = 6_000

        /** Search pause after a 429 when OFF sends no usable Retry-After, in seconds. */
        const val RATE_LIMIT_COOLDOWN_S: Double = 60.0

        /** A Polish search-a-licious answer with fewer usable products than this also asks the legacy search. */
        const val SUPPLEMENT_BELOW: Int = 5

        /** Polish only: search-a-licious matches the typed spelling exactly, so a thin answer is topped up. */
        fun needsSupplement(count: Int, lc: String): Boolean = lc == "pl" && count < SUPPLEMENT_BELOW

        /** [primary] first, then the products of [extra] it does not already have (by code). */
        fun merged(primary: List<FoodCandidate>, extra: List<FoodCandidate>): List<FoodCandidate> =
            (primary + extra).distinctBy { it.id }

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

        /**
         * search-a-licious is still labelled beta: a server error, timeout or odd body tries the
         * legacy endpoint once. A 429 (same budget), no connection or a cancel does not.
         */
        fun fallsBack(error: Throwable): Boolean = when (error) {
            is CancellationException -> false
            is FoodSearchError -> error != FoodSearchError.RateLimited &&
                error != FoodSearchError.Offline &&
                error != FoodSearchError.AlreadyInFlight
            else -> true
        }

        private const val LUCENE_OPERATORS = "+-&|!(){}[]^\"~*?:\\/"
        private val WHITESPACE = Regex("\\s+")

        /**
         * search-a-licious parses `q` as Lucene: typed punctuation would become operators or field
         * filters ("kawa: latte", "Coca-Cola"), so it is turned into spaces.
         */
        fun searchTerms(query: String): String =
            query.map { if (it in LUCENE_OPERATORS) ' ' else it }.joinToString("")
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        // MARK: - Mapping

        /** A v2 product response → lookup result. Empty stubs (no name in any field, no brand) count as not found. */
        internal fun outcome(body: OffProductResponse, locale: String): BarcodeLookup {
            if (body.status != 1) return BarcodeLookup.NotFound
            var product = body.product ?: return BarcodeLookup.NotFound
            if (product.code.isNullOrEmpty()) product = product.copy(code = body.code)
            candidate(product, locale)?.let { return BarcodeLookup.Found(it) }
            val code = product.code?.takeIf { it.isNotEmpty() } ?: return BarcodeLookup.NotFound
            val name = displayName(product, locale) ?: return BarcodeLookup.NotFound
            return BarcodeLookup.Partial(
                ProductStub(
                    code = code,
                    name = name,
                    brand = firstBrand(product),
                    quantity = trimmed(product.quantity),
                    servingSizeG = servingGrams(product),
                    imageURL = product.imageFrontSmallURL,
                ),
            )
        }

        /** Per-100 g figures the app can size a portion with. */
        data class Per100(
            val kcal: Double,
            val protein: Double,
            val carbs: Double,
            val fat: Double,
            val fiber: Double?,
            val isEstimated: Boolean,
        )

        /** A product with no code, no name or no usable nutrition (label or estimated) is dropped. */
        internal fun candidate(p: OffProduct, locale: String): FoodCandidate? {
            val code = p.code?.takeIf { it.isNotEmpty() } ?: return null
            val name = displayName(p, locale) ?: return null
            val n = per100(p) ?: return null
            val servingLabel = trimmed(p.servingSize)
            return FoodCandidate(
                id = "off:$code",
                code = code,
                name = name,
                brand = firstBrand(p),
                quantity = p.quantity,
                servingSizeG = servingGrams(p),
                servingLabel = servingLabel,
                kcalPer100 = n.kcal,
                proteinPer100 = n.protein,
                carbsPer100 = n.carbs,
                fatPer100 = n.fat,
                fiberPer100 = n.fiber,
                imageURL = p.imageFrontSmallURL,
                isEstimated = n.isEstimated,
            )
        }

        /**
         * Many Polish products have the name in only one field, or none. Polish UI: the Polish
         * name, the main name when the product's main language is Polish, English, the main name
         * in any language, the generic names, the abbreviated name. Then "Brand quantity"
         * ("Pudliszki 200 g"), which the user can rename from the label.
         */
        internal fun displayName(p: OffProduct, locale: String): String? {
            val mainIsPolish = p.lang == "pl"
            val chain = if (locale == "pl") {
                listOf(
                    p.productNamePL, if (mainIsPolish) p.productName else null, p.productNameEN, p.productName,
                    p.genericNamePL, p.genericName, p.abbreviatedProductName,
                )
            } else {
                listOf(p.productNameEN, p.productName, p.productNamePL, p.genericName, p.genericNamePL, p.abbreviatedProductName)
            }
            chain.firstNotNullOfOrNull(::trimmed)?.let { return it }
            val brand = firstBrand(p) ?: return null
            return listOfNotNull(brand, trimmed(p.quantity)).joinToString(" ")
        }

        /**
         * The label values (`nutriments`) when they have energy, else OFF's estimate
         * (`nutriments_estimated`). Energy is kcal, or kJ / 4.184; kcal must be 0…950 and protein,
         * carbs and fat 0…100, otherwise nothing is usable.
         */
        internal fun per100(p: OffProduct): Per100? {
            p.nutriments?.takeIf { kcal(it) != null }?.let { return per100(it, estimated = false) }
            p.nutrimentsEstimated?.takeIf { kcal(it) != null }?.let { return per100(it, estimated = true) }
            return null
        }

        private fun kcal(n: Map<String, Double?>): Double? {
            val kj = n["energy-kj_100g"] ?: n["energy_100g"]
            return n["energy-kcal_100g"] ?: kj?.div(4.184)
        }

        private fun per100(n: Map<String, Double?>, estimated: Boolean): Per100? {
            val kcal = kcal(n) ?: return null
            if (!kcal.isFinite() || kcal !in 0.0..950.0) return null
            if (listOf("proteins_100g", "carbohydrates_100g", "fat_100g").any { key ->
                    n[key]?.let { !it.isFinite() || it !in 0.0..100.0 } == true
                }
            ) return null
            return Per100(
                kcal = kcal,
                protein = n["proteins_100g"] ?: 0.0,
                carbs = n["carbohydrates_100g"] ?: 0.0,
                fat = n["fat_100g"] ?: 0.0,
                fiber = n["fiber_100g"],
                isEstimated = estimated,
            )
        }

        internal fun firstBrand(p: OffProduct): String? = trimmed(p.brands?.split(",")?.firstOrNull())

        private fun servingGrams(p: OffProduct): Double? {
            val label = trimmed(p.servingSize)
            var servingG = if (label?.lowercase(Locale.ROOT)?.contains("ml") == true) null else p.servingQuantity
            if (servingG == null && label != null) servingG = grams(label)
            if (servingG != null && servingG <= 0) servingG = null
            return servingG
        }

        private fun trimmed(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

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

/**
 * Sliding-window request budget (`RequestWindow`): at most [limit] requests start in any
 * [windowMs] milliseconds. Not thread-safe; the service holds it under its mutex.
 */
internal class RequestWindow(limit: Int, private val windowMs: Long) {
    val limit: Int = maxOf(1, limit)
    private val booked = mutableListOf<Long>()
    val starts: List<Long> get() = booked

    /**
     * Books the earliest start for a new request and returns how long to wait for it (0 = now),
     * or `null`, booking nothing, when that wait would be longer than [maxWaitMs].
     */
    fun reserve(now: Long, maxWaitMs: Long): Long? {
        booked.removeAll { now - it >= windowMs }
        val start = if (booked.size >= limit) booked[booked.size - limit] + windowMs else now
        val wait = maxOf(0L, start - now)
        if (wait > maxWaitMs) return null
        booked.add(maxOf(start, now))
        return wait
    }
}

/** `FoodSearchError` (`FoodSearchService.swift`). */
sealed class FoodSearchError(message: String? = null) : Exception(message) {

    data object AlreadyInFlight : FoodSearchError()

    /** HTTP 429: this phone (or everyone behind its carrier's NAT) used up OFF's per-minute budget. */
    data object RateLimited : FoodSearchError()

    /** HTTP 5xx: OFF is overloaded or down. */
    data object Busy : FoodSearchError()

    /** The phone has no connection. */
    data object Offline : FoodSearchError()

    /** Online, but OFF did not answer (timeout, DNS, TLS). */
    data object Unreachable : FoodSearchError()

    data class BadResponse(val status: Int) : FoodSearchError()

    /** Worth one more try after a short pause. */
    val isTransient: Boolean get() = this == RateLimited || this == Busy

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            AlreadyInFlight -> R.string.fuel_search_error_inFlight
            RateLimited -> R.string.fuel_search_error_rateLimited
            Busy -> R.string.fuel_search_error_busy
            Offline -> R.string.error_network
            Unreachable, is BadResponse -> R.string.fuel_search_error_network
        }

    fun localizedMessage(context: Context): String = context.getString(messageRes)

    companion object {
        fun from(status: Int): FoodSearchError = when (status) {
            429 -> RateLimited
            in 500..599 -> Busy
            else -> BadResponse(status)
        }

        /** A request that never got an HTTP answer: no connection at all, or no answer while online. */
        fun transport(online: Boolean): FoodSearchError = if (online) Unreachable else Offline

        /** The message for any error a lookup or search throws; unknown errors read as "couldn't reach". */
        @StringRes
        fun messageRes(error: Throwable): Int =
            (error as? FoodSearchError)?.messageRes ?: R.string.fuel_search_error_network

        /**
         * A barcode lookup's message. "Too many searches" blames the user's typing, but a scan is
         * one request that OFF refused (its per-minute budget, often shared behind a carrier NAT),
         * so a 429 there reads as the database being busy.
         */
        @StringRes
        fun lookupMessageRes(error: Throwable): Int =
            if (error == RateLimited) R.string.fuel_search_error_busy else messageRes(error)
    }
}
