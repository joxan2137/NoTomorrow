package app.notomorrow.net.dto

import app.notomorrow.model.FoodCandidate
import app.notomorrow.service.AIFinalizer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Per-100 g nutrition of one AI item (schema v2). `alcohol` is 0 when absent. */
data class AIPer100(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val alcohol: Double = 0.0,
)

/**
 * A food-database product that stands in for the model's guess: the user replaced the item or added
 * it by hand. Such rows log as ordinary food entries (linked to the product), not as AI estimates.
 * Local only, never on the wire.
 */
sealed interface AIDatabaseFood {
    /** A food already saved on this device, by `FoodItemEntity.id`. */
    data class Item(val id: String) : AIDatabaseFood

    /** An Open Food Facts hit, saved to the library when the estimate is logged. */
    data class Candidate(val candidate: FoodCandidate) : AIDatabaseFood
}

/**
 * One item on the plate — port of `AIFood` in `Services/BackendClient.swift`,
 * including its tolerant key lookup: the backend sends `proteinG`, Claude replies
 * with `protein_g`, the mock uses `protein`, and any of them may arrive as a
 * JSON string rather than a number.
 *
 * The schema v2 fields (backend v2 or the on-device finalizer) are all optional: an older server
 * sends none of them, and a malformed one is dropped rather than failing the estimate. Rescaling
 * uses the finalizer's rounding ([AIFinalizer.round1]), so the result screen matches it.
 */
@Serializable(with = AIFoodSerializer::class)
data class AIFood(
    val name: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    /** 0…1 */
    val confidence: Double,
    /** Item the model could not see clearly (e.g. cooking oil); shown with a "guess" badge. */
    val isGuess: Boolean = false,
    /** Local identity for editable rows; not part of the wire format. */
    val id: String = UUID.randomUUID().toString(),
    val per100: AIPer100? = null,
    val portionCount: Double? = null,
    val portionUnit: String? = null,
    val gramsPerUnit: Double? = null,
    /** `estimated`, `generic_table`, `visible_label`, `user_notes`, `open_food_facts`; `database` for local picks. */
    val nutritionSource: String? = null,
    val cooking: String? = null,
    val genericKey: String? = null,
    val barcode: String? = null,
    val adjustments: List<String>? = null,
    /** Local only, never on the wire. */
    val databaseFood: AIDatabaseFood? = null,
) {
    // MARK: Rescaling (keeps the result screen consistent with the finalizer)

    /**
     * Sets [per100] (rounded to 0.1) and recomputes the totals for the current grams, exactly as the
     * finalizer does (`setNutrition` on iOS).
     */
    fun withNutrition(values: AIPer100): AIFood {
        val r = AIFinalizer::round1
        val p = AIPer100(r(values.kcal), r(values.protein), r(values.carbs), r(values.fat), r(values.alcohol))
        return copy(
            per100 = p,
            kcal = r(p.kcal * grams / 100),
            protein = r(p.protein * grams / 100),
            carbs = r(p.carbs * grams / 100),
            fat = r(p.fat * grams / 100),
        )
    }

    /**
     * The same food at a different portion. With [per100] the totals are recomputed from it (grams to
     * 0.1 g); otherwise (older server) they follow proportionally. A counted portion keeps its count
     * and re-derives the grams of one unit.
     */
    fun scaled(toGrams: Double): AIFood {
        val resized = if (per100 != null) {
            val g = AIFinalizer.round1(toGrams)
            if (g <= 0) return this
            copy(grams = g).withNutrition(per100)
        } else {
            if (grams <= 0 || toGrams <= 0) return this
            val f = toGrams / grams
            copy(grams = toGrams, kcal = kcal * f, protein = protein * f, carbs = carbs * f, fat = fat * f)
        }
        val count = portionCount
        return if (count != null && count > 0) {
            resized.copy(gramsPerUnit = AIFinalizer.round1(resized.grams / count))
        } else {
            resized
        }
    }

    /** `n` units of the same size: `portionCount = round1(n)`, grams = count × grams per unit. */
    fun withCount(n: Double): AIFood {
        val count = AIFinalizer.round1(n)
        if (count <= 0) return this
        val perUnit = unitGrams
        return scaled(count * perUnit).copy(portionCount = count, gramsPerUnit = AIFinalizer.round1(perUnit))
    }

    /** Units of [perUnit] grams each, keeping the count. */
    fun withGramsPerUnit(perUnit: Double): AIFood {
        if (perUnit <= 0) return this
        val count = units
        return scaled(count * perUnit).copy(portionCount = count, gramsPerUnit = AIFinalizer.round1(perUnit))
    }

    /** Visible units; 1 for a single mass or an older server's item. */
    val units: Double get() = portionCount?.takeIf { it > 0 } ?: 1.0

    /** Grams of one unit; the whole portion when the item has no count. */
    val unitGrams: Double get() = gramsPerUnit?.takeIf { it > 0 } ?: (grams / units)

    /** The printed unit ("szt.", "kromka"), null when the model gave none. */
    val unitName: String? get() = portionUnit?.trim()?.takeIf { it.isNotEmpty() }

    /** kcal per 100 g: [per100] when known, otherwise derived from the totals. */
    val kcalPer100: Double? get() = per100?.kcal ?: if (grams > 0) kcal * 100 / grams else null

    companion object {
        /**
         * A v2 item built the way the finalizer builds one (the offline mock): grams = count × grams
         * per unit, totals from the rounded per100.
         */
        fun mock(
            name: String,
            per100: AIPer100,
            count: Double,
            unit: String,
            perUnit: Double,
            confidence: Double,
            isGuess: Boolean = false,
        ): AIFood = AIFood(
            name = name,
            grams = AIFinalizer.round1(count * perUnit),
            kcal = 0.0,
            protein = 0.0,
            carbs = 0.0,
            fat = 0.0,
            confidence = confidence,
            isGuess = isGuess,
            portionCount = count,
            portionUnit = unit,
            gramsPerUnit = perUnit,
            nutritionSource = "estimated",
            cooking = "prepared",
            genericKey = "none",
            barcode = "",
            adjustments = emptyList(),
        ).withNutrition(per100)

        /** The Swift memberwise initialiser: `confidence` is clamped to 0…1. */
        fun of(
            name: String,
            grams: Double,
            kcal: Double,
            protein: Double,
            carbs: Double,
            fat: Double,
            confidence: Double,
            isGuess: Boolean = false,
        ): AIFood = AIFood(name, grams, kcal, protein, carbs, fat, confidence.coerceIn(0.0, 1.0), isGuess)
    }
}

data class AITotals(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)

/** A model item the finalizer dropped (`invalid_grams`, `no_name`, …) instead of failing the whole estimate. */
data class AISkippedItem(
    val index: Int,
    val name: String,
    val reason: String,
)

/** The whole plate. `overallConfidence` is 0…1. */
@Serializable(with = AIEstimateSerializer::class)
data class AIEstimate(
    val foods: List<AIFood>,
    val overallConfidence: Double,
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val scaleReferenceUsed: String? = null,
    // Schema v2 additions; an older server sends none of them.
    val version: Int? = null,
    val totals: AITotals? = null,
    val skipped: List<AISkippedItem>? = null,
) {
    val totalKcal: Double get() = foods.sumOf { it.kcal }
    val totalProtein: Double get() = foods.sumOf { it.protein }
    val totalCarbs: Double get() = foods.sumOf { it.carbs }
    val totalFat: Double get() = foods.sumOf { it.fat }
}

/**
 * What the AI read from a photo of a pack's nutrition table (`POST /ai/label`, or the on-device
 * finalizer for the bring-your-own-key paths) — `LabelReading` in `BackendClient.swift`.
 * `legible == false` is an answer, not an error: the form stays editable.
 */
@Serializable(with = LabelReadingSerializer::class)
data class LabelReading(
    val legible: Boolean,
    /** `illegible`, `no_energy`, `incomplete`, `no_serving_size`, `implausible`; null when legible. */
    val unreadableReason: String? = null,
    /** `per100g`, `per100ml` or `perServing`: the column the numbers came from. */
    val basis: String = "per100g",
    /** `kcal` or `kj` (converted); null when not legible. */
    val energyFrom: String? = null,
    val name: String = "",
    val brand: String = "",
    val per100: Per100? = null,
    val servingSizeG: Double? = null,
    val packageSizeG: Double? = null,
    val barcode: String = "",
    val confidence: Double = 0.5,
    /** The numbers do not add up (Atwater, sugar above carbs) or the model was unsure: ask the user to check. */
    val needsReview: Boolean = false,
    val version: Int = 1,
) {
    /** Always per 100 g; a per-100 ml table is stored as per 100 g, like drinks elsewhere in the app. */
    data class Per100(
        val kcal: Double,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val fiber: Double? = null,
        val sugar: Double? = null,
        val salt: Double? = null,
    )
}

// MARK: - Tolerant decoding

private fun JsonObject.looseDouble(keys: List<String>, fallback: Double? = null): Double {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        if (element is JsonNull) continue
        // Numbers may arrive as JSON strings ("12.5") — `content` covers both forms.
        element.content.toDoubleOrNull()?.let { return it }
    }
    return fallback ?: throw SerializationException("missing $keys")
}

private fun JsonObject.looseString(keys: List<String>): String? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        if (element !is JsonNull) return element.content
    }
    return null
}

private fun JsonObject.looseBool(keys: List<String>): Boolean? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        when (element.content.lowercase()) {
            "true" -> return true
            "false" -> return false
        }
    }
    return null
}

/** v2 fields are decoded leniently: a missing or malformed optional field is null, never a failure. */
private fun JsonObject.optionalDouble(key: String): Double? {
    val element = this[key] as? JsonPrimitive ?: return null
    if (element is JsonNull || element.isString) return null
    return element.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.optionalBool(key: String): Boolean? {
    val element = this[key] as? JsonPrimitive ?: return null
    if (element is JsonNull || element.isString) return null
    return when (element.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonObject.optionalStrings(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
}

object AIFoodSerializer : KSerializer<AIFood> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.notomorrow.net.dto.AIFood")

    override fun deserialize(decoder: Decoder): AIFood = fromJson(jsonObject(decoder))

    fun fromJson(o: JsonObject): AIFood {
        val confidence = o.looseDouble(listOf("confidence"), 0.5)
        return AIFood.of(
            name = o.looseString(listOf("name", "name_en")) ?: "",
            grams = o.looseDouble(listOf("grams", "estimated_grams", "estimatedGrams"), 0.0),
            kcal = o.looseDouble(listOf("kcal", "calories"), 0.0),
            protein = o.looseDouble(listOf("protein", "proteinG", "protein_g"), 0.0),
            carbs = o.looseDouble(listOf("carbs", "carbsG", "carbs_g"), 0.0),
            fat = o.looseDouble(listOf("fat", "fatG", "fat_g"), 0.0),
            confidence = confidence,
            // iOS decodes a missing flag as `false` (contract §11); the badge still shows below 0.4.
            isGuess = o.looseBool(listOf("isGuess", "is_guess")) ?: false,
        ).copy(
            per100 = (o["per100"] as? JsonObject)?.let(::per100),
            portionCount = o.optionalDouble("portionCount"),
            portionUnit = o.optionalString("portionUnit"),
            gramsPerUnit = o.optionalDouble("gramsPerUnit"),
            nutritionSource = o.optionalString("nutritionSource"),
            cooking = o.optionalString("cooking"),
            genericKey = o.optionalString("genericKey"),
            barcode = o.optionalString("barcode"),
            adjustments = o.optionalStrings("adjustments"),
        )
    }

    /** All four of kcal/protein/carbs/fat or nothing; alcohol defaults to 0. */
    private fun per100(o: JsonObject): AIPer100? {
        val kcal = o.optionalDouble("kcal") ?: return null
        val protein = o.optionalDouble("protein") ?: return null
        val carbs = o.optionalDouble("carbs") ?: return null
        val fat = o.optionalDouble("fat") ?: return null
        return AIPer100(kcal, protein, carbs, fat, o.optionalDouble("alcohol") ?: 0.0)
    }

    override fun serialize(encoder: Encoder, value: AIFood) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("AIFood requires a JSON encoder")
        out.encodeJsonElement(toJson(value))
    }

    fun toJson(value: AIFood): JsonElement = buildJsonObject {
        put("name", value.name)
        put("grams", value.grams)
        put("kcal", value.kcal)
        put("protein", value.protein)
        put("carbs", value.carbs)
        put("fat", value.fat)
        put("confidence", value.confidence)
        put("isGuess", value.isGuess)
        value.per100?.let { p ->
            put(
                "per100",
                buildJsonObject {
                    put("kcal", p.kcal)
                    put("protein", p.protein)
                    put("carbs", p.carbs)
                    put("fat", p.fat)
                    put("alcohol", p.alcohol)
                },
            )
        }
        value.portionCount?.let { put("portionCount", it) }
        value.portionUnit?.let { put("portionUnit", it) }
        value.gramsPerUnit?.let { put("gramsPerUnit", it) }
        value.nutritionSource?.let { put("nutritionSource", it) }
        value.cooking?.let { put("cooking", it) }
        value.genericKey?.let { put("genericKey", it) }
        value.barcode?.let { put("barcode", it) }
        value.adjustments?.let { list -> put("adjustments", buildJsonArray { list.forEach { add(it) } }) }
    }
}

object AIEstimateSerializer : KSerializer<AIEstimate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.notomorrow.net.dto.AIEstimate")

    override fun deserialize(decoder: Decoder): AIEstimate {
        val o = jsonObject(decoder)
        val list = (o["foods"] ?: o["items"])?.jsonArray ?: throw SerializationException("missing foods")
        val foods = list.map { AIFoodSerializer.fromJson(it.jsonObject) }
        // The server always sends `overallConfidence`; a model talking straight to the
        // app may not, in which case the item mean stands in (clamped to 0…1).
        val mean = if (foods.isEmpty()) 0.0 else foods.sumOf { it.confidence } / foods.size
        val overall = o.looseDouble(listOf("overallConfidence", "overall_confidence"), mean)
        fun strings(key: String): List<String> = (o[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return AIEstimate(
            foods = foods,
            overallConfidence = min(1.0, max(0.0, overall)),
            assumptions = strings("assumptions"),
            questions = strings("questions"),
            scaleReferenceUsed = o.optionalString("scaleReferenceUsed"),
            version = o.optionalDouble("version")?.toInt(),
            totals = (o["totals"] as? JsonObject)?.let(::totals),
            skipped = (o["skipped"] as? JsonArray)?.let(::skipped),
        )
    }

    private fun totals(o: JsonObject): AITotals? = AITotals(
        kcal = o.optionalDouble("kcal") ?: return null,
        protein = o.optionalDouble("protein") ?: return null,
        carbs = o.optionalDouble("carbs") ?: return null,
        fat = o.optionalDouble("fat") ?: return null,
    )

    private fun skipped(array: JsonArray): List<AISkippedItem>? = array.map { element ->
        val o = element as? JsonObject ?: return null
        AISkippedItem(
            index = o.optionalDouble("index")?.toInt() ?: return null,
            name = o.optionalString("name") ?: "",
            reason = o.optionalString("reason") ?: return null,
        )
    }

    override fun serialize(encoder: Encoder, value: AIEstimate) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("AIEstimate requires a JSON encoder")
        out.encodeJsonElement(
            buildJsonObject {
                put("foods", buildJsonArray { value.foods.forEach { add(AIFoodSerializer.toJson(it)) } })
                put("overallConfidence", value.overallConfidence)
                put("assumptions", buildJsonArray { value.assumptions.forEach { add(JsonPrimitive(it)) } })
                put("questions", buildJsonArray { value.questions.forEach { add(JsonPrimitive(it)) } })
                value.scaleReferenceUsed?.let { put("scaleReferenceUsed", it) }
                value.version?.let { put("version", it) }
            }
        )
    }
}

/** `LabelReading.init(from:)`: only `legible` is required; everything else falls back to its default. */
object LabelReadingSerializer : KSerializer<LabelReading> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.notomorrow.net.dto.LabelReading")

    override fun deserialize(decoder: Decoder): LabelReading {
        val o = jsonObject(decoder)
        val legible = o.optionalBool("legible") ?: throw SerializationException("missing legible")
        return LabelReading(
            legible = legible,
            unreadableReason = o.optionalString("unreadableReason"),
            basis = o.optionalString("basis") ?: "per100g",
            energyFrom = o.optionalString("energyFrom"),
            name = o.optionalString("name") ?: "",
            brand = o.optionalString("brand") ?: "",
            per100 = (o["per100"] as? JsonObject)?.let(::per100),
            servingSizeG = o.optionalDouble("servingSizeG"),
            packageSizeG = o.optionalDouble("packageSizeG"),
            barcode = o.optionalString("barcode") ?: "",
            confidence = o.optionalDouble("confidence") ?: 0.5,
            needsReview = o.optionalBool("needsReview") ?: false,
            version = o.optionalDouble("version")?.toInt() ?: 1,
        )
    }

    private fun per100(o: JsonObject): LabelReading.Per100? = LabelReading.Per100(
        kcal = o.optionalDouble("kcal") ?: return null,
        protein = o.optionalDouble("protein") ?: return null,
        carbs = o.optionalDouble("carbs") ?: return null,
        fat = o.optionalDouble("fat") ?: return null,
        fiber = o.optionalDouble("fiber"),
        sugar = o.optionalDouble("sugar"),
        salt = o.optionalDouble("salt"),
    )

    override fun serialize(encoder: Encoder, value: LabelReading) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("LabelReading requires a JSON encoder")
        out.encodeJsonElement(
            buildJsonObject {
                put("version", value.version)
                put("legible", value.legible)
                value.unreadableReason?.let { put("unreadableReason", it) }
                put("basis", value.basis)
                value.energyFrom?.let { put("energyFrom", it) }
                put("name", value.name)
                put("brand", value.brand)
                value.per100?.let { p ->
                    put(
                        "per100",
                        buildJsonObject {
                            put("kcal", p.kcal)
                            put("protein", p.protein)
                            put("carbs", p.carbs)
                            put("fat", p.fat)
                            p.fiber?.let { put("fiber", it) }
                            p.sugar?.let { put("sugar", it) }
                            p.salt?.let { put("salt", it) }
                        },
                    )
                }
                value.servingSizeG?.let { put("servingSizeG", it) }
                value.packageSizeG?.let { put("packageSizeG", it) }
                put("barcode", value.barcode)
                put("confidence", value.confidence)
                put("needsReview", value.needsReview)
            }
        )
    }
}

private fun jsonObject(decoder: Decoder): JsonObject {
    val input = decoder as? JsonDecoder ?: throw SerializationException("AI DTOs require a JSON decoder")
    return input.decodeJsonElement().jsonObject
}
