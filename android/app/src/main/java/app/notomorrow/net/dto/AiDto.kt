package app.notomorrow.net.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
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

/**
 * One item on the plate — port of `AIFood` in `Services/BackendClient.swift`,
 * including its tolerant key lookup: the backend sends `proteinG`, Claude replies
 * with `protein_g`, the mock uses `protein`, and any of them may arrive as a
 * JSON string rather than a number.
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
) {
    /** Same food scaled to a different portion; kcal and macros follow proportionally. */
    fun scaled(toGrams: Double): AIFood {
        if (grams <= 0.0) return this
        val f = toGrams / grams
        return copy(grams = toGrams, kcal = kcal * f, protein = protein * f, carbs = carbs * f, fat = fat * f)
    }

    companion object {
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

/** The whole plate. `overallConfidence` is 0…1. */
@Serializable(with = AIEstimateSerializer::class)
data class AIEstimate(
    val foods: List<AIFood>,
    val overallConfidence: Double,
    val assumptions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
) {
    val totalKcal: Double get() = foods.sumOf { it.kcal }
    val totalProtein: Double get() = foods.sumOf { it.protein }
    val totalCarbs: Double get() = foods.sumOf { it.carbs }
    val totalFat: Double get() = foods.sumOf { it.fat }
}

// MARK: - Tolerant decoding

private fun JsonObject.looseDouble(keys: List<String>, fallback: Double? = null): Double {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        // Numbers may arrive as JSON strings ("12.5") — `content` covers both forms.
        element.content.toDoubleOrNull()?.let { return it }
    }
    return fallback ?: throw SerializationException("missing $keys")
}

private fun JsonObject.looseString(keys: List<String>): String? {
    for (key in keys) {
        val element = this[key] as? JsonPrimitive ?: continue
        if (element.content != "null") return element.content
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
            isGuess = o.looseBool(listOf("isGuess", "is_guess")) ?: (confidence < 0.5),
        )
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
        fun strings(key: String): List<String> = (o[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return AIEstimate(foods = foods, overallConfidence = min(1.0, max(0.0, overall)), assumptions = strings("assumptions"), questions = strings("questions"))
    }

    override fun serialize(encoder: Encoder, value: AIEstimate) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("AIEstimate requires a JSON encoder")
        out.encodeJsonElement(
            buildJsonObject {
                put("foods", buildJsonArray { value.foods.forEach { add(AIFoodSerializer.toJson(it)) } })
                put("overallConfidence", value.overallConfidence)
                put("assumptions", buildJsonArray { value.assumptions.forEach { add(JsonPrimitive(it)) } })
                put("questions", buildJsonArray { value.questions.forEach { add(JsonPrimitive(it)) } })
            }
        )
    }
}

private fun jsonObject(decoder: Decoder): JsonObject {
    val input = decoder as? JsonDecoder ?: throw SerializationException("AI DTOs require a JSON decoder")
    return input.decodeJsonElement().jsonObject
}
