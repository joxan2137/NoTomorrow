package app.notomorrow.service

import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.AISkippedItem
import app.notomorrow.net.dto.AITotals
import app.notomorrow.net.dto.LabelReading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The model's answer cannot be used at all (as opposed to single items, which are skipped).
 * [code] is the contract's code, as in the fixtures' `expectedError`.
 */
class AIOutputException(val code: String) : Exception(code) {
    companion object {
        const val NO_JSON: String = "no_json"
        const val INVALID_JSON: String = "invalid_json"
        const val NOT_OBJECT: String = "not_object"
        const val NO_FOODS_ARRAY: String = "no_foods_array"
    }
}

/**
 * Port of `backend/src/aiFinalize.ts` (and `AIFinalizer` in `NoTomorrow/Services/AIEstimateFinalizer.swift`):
 * raw model JSON → the finalized estimate (schema v2) or label reading.
 *
 * Every step and rounding rule is part of the cross-platform contract and is checked against the
 * shared fixtures (`AIEstimateFinalizerTest`). Keep expressions in the written order: floating-point
 * results must match the backend bit for bit (`1.4 × 175 / 100` is `2.4499999999999997`, which
 * rounds to 2.4). Takes a parsed [AIEstimateSpec], never a `Context`.
 */
object AIFinalizer {

    // MARK: Numbers

    /** Half-up to one decimal for non-negative values: `floor(x × 10 + 0.5) / 10`. Never `Math.round`. */
    fun round1(x: Double): Double = floor(x * 10 + 0.5) / 10

    fun round2(x: Double): Double = floor(x * 100 + 0.5) / 100

    fun clamp01(x: Double): Double = min(1.0, max(0.0, x))

    private val NUMERIC_STRING = Regex("[-+]?[0-9]+(?:[.,][0-9]+)?")

    /** A finite JSON number, or a string like "12", "-3", "4,5", "4.5" (trimmed); anything else is null. */
    fun num(value: JsonElement?): Double? {
        val p = value as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) {
            val trimmed = p.content.trim()
            if (!NUMERIC_STRING.matches(trimmed)) return null
            val parsed = trimmed.replaceFirst(',', '.').toDoubleOrNull() ?: return null
            return parsed.takeIf { it.isFinite() }
        }
        return value.numberValue?.takeIf { it.isFinite() }
    }

    /** The first key whose value parses as a number. */
    fun firstNum(obj: JsonObject, keys: List<String>): Double? {
        for (key in keys) num(obj[key])?.let { return it }
        return null
    }

    /** Trimmed string cut to [max] characters; non-strings become "". */
    fun text(value: JsonElement?, max: Int): String = value.stringValue?.trim()?.take(max) ?: ""

    fun isTrue(value: JsonElement?): Boolean {
        val p = value as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        return if (p.isString) p.content.trim().lowercase() == "true" else p.booleanValue == true
    }

    fun shortStrings(value: JsonElement?, maxCount: Int, maxLength: Int): List<String> {
        val items = value as? JsonArray ?: return emptyList()
        val out = ArrayList<String>()
        for (item in items) {
            if (out.size >= maxCount) break
            val s = text(item, maxLength)
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private val GTIN = Regex("[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}")

    /** GTIN-8/12/13/14 with a valid mod-10 check digit. */
    fun validGtin(code: String): Boolean {
        if (!GTIN.matches(code)) return false
        val digits = code.map { it - '0' }
        val check = digits.last()
        val body = digits.dropLast(1)
        var sum = 0
        for (i in body.indices) {
            // Weight 3 on the digit next to the check digit, then alternating 1, 3, …
            sum += body[body.size - 1 - i] * (if (i % 2 == 0) 3 else 1)
        }
        return (10 - sum % 10) % 10 == check
    }

    // MARK: Model text → JSON

    private val FENCE = Regex("```(?:json|JSON)?")
    private val TRAILING_COMMA = Regex(",[ \\t\\r\\n]*([}\\]])")

    /** Removes every ``` / ```json / ```JSON fence, then returns the text from the first `{` to the last `}`. */
    fun extractJsonObject(input: String): String? {
        val unfenced = FENCE.replace(input, "")
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end < 0 || start >= end) return null
        return unfenced.substring(start, end + 1)
    }

    /**
     * Model text → JSON value. Tries strict JSON; then with trailing commas removed; then also with
     * every `'` replaced by `"`.
     */
    fun parseModelJson(input: String): JsonElement {
        val json = extractJsonObject(input) ?: throw AIOutputException(AIOutputException.NO_JSON)
        val noTrailingCommas = TRAILING_COMMA.replace(json, "$1")
        for (attempt in listOf(json, noTrailingCommas, noTrailingCommas.replace('\'', '"'))) {
            try {
                return AIJson.parse(attempt)
            } catch (_: AIJson.ParseException) {
                // Next repair.
            }
        }
        throw AIOutputException(AIOutputException.INVALID_JSON)
    }

    // MARK: Estimate

    val COOKING_VALUES: Set<String> = setOf(
        "raw", "boiled", "steamed", "stewed", "baked", "grilled", "pan_fried", "deep_fried", "prepared", "packaged",
    )
    private val MODEL_SOURCES = setOf("visible_label", "user_notes", "estimated")
    private val ITEM_BARCODE = Regex("[0-9]{8,14}")

    /**
     * Raw model JSON (schema v2, or a legacy v1 reply with totals only) → the finalized estimate.
     * Throws [AIOutputException] only when the answer is not an object or has no foods array; bad
     * items are skipped and listed in `skipped`.
     */
    fun finalizeEstimate(raw: JsonElement, spec: AIEstimateSpec, context: AIEstimateSpec.NotesContext): AIEstimate {
        val obj = raw as? JsonObject ?: throw AIOutputException(AIOutputException.NOT_OBJECT)
        val list = obj["foods"] as? JsonArray ?: obj["items"] as? JsonArray
            ?: throw AIOutputException(AIOutputException.NO_FOODS_ARRAY)

        val l = spec.limits
        val capActive = !context.weightGiven && !context.measuredReference
        val foods = ArrayList<AIFood>()
        val skipped = ArrayList<AISkippedItem>()

        for ((index, item) in list.withIndex()) {
            if (index >= l.maxItems) {
                skipped += AISkippedItem(index, text(item["name"], l.maxNameLength), "too_many_items")
                continue
            }
            val f = item as? JsonObject
            if (f == null) {
                skipped += AISkippedItem(index, "", "not_object")
                continue
            }
            val adjustments = ArrayList<String>()

            // 1. Name.
            val name = text(f["name"], l.maxNameLength)
            if (name.isEmpty()) {
                skipped += AISkippedItem(index, "", "no_name")
                continue
            }
            fun skip(reason: String) {
                skipped += AISkippedItem(index, name, reason)
            }

            // 2. Grams, repaired from count × grams per unit when missing or out of range.
            var count: Double? = num(f["portionCount"])?.let { if (it > 0) round1(it) else null }
            if (count != null && count <= 0) count = null
            val perUnit: Double? = num(f["gramsPerUnit"])?.takeIf { it > 0 }
            fun gramsOk(g: Double?): Boolean = g != null && g > 0 && g <= l.maxGrams
            var gramsValue = firstNum(f, listOf("grams", "estimatedGrams", "estimated_grams"))
            if (!gramsOk(gramsValue) && count != null && perUnit != null) {
                gramsValue = count * perUnit
                adjustments += "grams_from_portion"
            }
            if (gramsValue == null || !gramsOk(gramsValue)) {
                skip("invalid_grams")
                continue
            }
            val grams = round1(gramsValue)
            if (grams <= 0) {
                skip("invalid_grams")
                continue
            }

            // 3. Per-100 g nutrition: generic table, the model's per100, or legacy totals.
            var source = f["nutritionSource"].stringValue?.takeIf { it in MODEL_SOURCES } ?: "estimated"
            val keyRaw = f["genericKey"].stringValue?.trim() ?: ""
            val tableRow = spec.table[keyRaw]
            val genericKey = if (tableRow != null) keyRaw else "none"
            var per100: AIPer100
            val modelPer100 = f["per100"] as? JsonObject
            if (tableRow != null && source == "estimated") {
                per100 = tableRow.per100
                source = "generic_table"
                adjustments += "generic_table"
            } else if (modelPer100 != null) {
                val kcal = num(modelPer100["kcal"])
                val protein = num(modelPer100["protein"])
                val carbs = num(modelPer100["carbs"])
                val fat = num(modelPer100["fat"])
                if (kcal == null || protein == null || carbs == null || fat == null) {
                    skip("invalid_per100")
                    continue
                }
                per100 = AIPer100(kcal, protein, carbs, fat, num(modelPer100["alcohol"]) ?: 0.0)
            } else {
                val kcal = firstNum(f, listOf("kcal", "calories"))
                if (kcal == null) {
                    skip("no_nutrition")
                    continue
                }
                val protein = firstNum(f, listOf("proteinG", "protein_g", "protein")) ?: 0.0
                val carbs = firstNum(f, listOf("carbsG", "carbs_g", "carbs", "carbohydrates")) ?: 0.0
                val fat = firstNum(f, listOf("fatG", "fat_g", "fat")) ?: 0.0
                per100 = AIPer100(
                    kcal = kcal * 100 / grams,
                    protein = protein * 100 / grams,
                    carbs = carbs * 100 / grams,
                    fat = fat * 100 / grams,
                    alcohol = 0.0,
                )
                adjustments += "per100_from_totals"
            }

            // 4. Plausibility.
            val values = listOf(per100.kcal, per100.protein, per100.carbs, per100.fat, per100.alcohol)
            if (values.any { it < 0 } || per100.kcal > l.maxKcalPer100 ||
                per100.protein + per100.carbs + per100.fat + per100.alcohol > l.maxMassPer100
            ) {
                skip("invalid_per100")
                continue
            }

            // 5. Energy consistency (Atwater 4/4/9/7).
            val energy = atwaterEnergy(spec, per100)
            val tolerance = spec.atwater.tolerance.getValue(source)
            val allowed = max(tolerance.abs, tolerance.rel * energy)
            var repaired = false
            if (abs(per100.kcal - energy) > allowed) {
                if (tolerance.repair) {
                    per100 = per100.copy(kcal = min(energy, l.maxKcalPer100))
                    repaired = true
                    adjustments += "energy_repaired"
                } else {
                    adjustments += "energy_mismatch_kept"
                }
            }

            // 6. Portion fields, consistent with grams.
            val portionCount: Double
            val gramsPerUnit: Double
            if (count != null) {
                portionCount = count
                if (perUnit != null && abs(count * perUnit - grams) <= l.portionMismatchGrams) {
                    gramsPerUnit = round1(perUnit)
                } else {
                    gramsPerUnit = round1(grams / count)
                    adjustments += "portion_normalized"
                }
            } else {
                portionCount = 1.0
                gramsPerUnit = grams
            }

            // 7. Confidence.
            var confidence = num(f["confidence"]) ?: l.defaultConfidence
            if (repaired) confidence -= l.energyRepairPenalty
            confidence = clamp01(confidence)
            if (capActive && confidence > l.confidenceCapWithoutReference) {
                confidence = l.confidenceCapWithoutReference
                adjustments += "confidence_capped"
            }

            val cooking = f["cooking"].stringValue?.takeIf { it in COOKING_VALUES } ?: "prepared"
            val barcodeRaw = f["barcode"].stringValue?.trim() ?: ""
            foods += AIFood(
                name = name,
                grams = grams,
                kcal = 0.0,
                protein = 0.0,
                carbs = 0.0,
                fat = 0.0,
                confidence = round2(confidence),
                isGuess = isTrue(f["isGuess"]) || isTrue(f["is_guess"]),
                portionCount = portionCount,
                portionUnit = text(f["portionUnit"], l.maxUnitLength),
                gramsPerUnit = gramsPerUnit,
                nutritionSource = source,
                cooking = cooking,
                genericKey = genericKey,
                barcode = if (ITEM_BARCODE.matches(barcodeRaw)) barcodeRaw else "",
                adjustments = adjustments,
            ).withNutrition(per100)
        }

        // Whole estimate.
        val mean = if (foods.isEmpty()) 0.0 else foods.fold(0.0) { acc, food -> acc + food.confidence } / foods.size
        var overall = clamp01(firstNum(obj, listOf("overallConfidence", "overall_confidence")) ?: mean)
        if (capActive && overall > l.confidenceCapWithoutReference) overall = l.confidenceCapWithoutReference
        val scale = text(obj["scaleReferenceUsed"], l.maxScaleReferenceLength)

        return AIEstimate(
            foods = foods,
            overallConfidence = round2(overall),
            assumptions = shortStrings(obj["assumptions"], l.maxAssumptions, l.maxTextLength),
            questions = shortStrings(obj["questions"], l.maxQuestions, l.maxTextLength),
            scaleReferenceUsed = scale.ifEmpty { "none" },
            version = 2,
            totals = computeTotals(foods),
            skipped = skipped,
        )
    }

    /** Model text (fences, prose and small JSON slips tolerated) → finalized estimate. */
    fun finalizeEstimateText(input: String, spec: AIEstimateSpec, context: AIEstimateSpec.NotesContext): AIEstimate =
        finalizeEstimate(parseModelJson(input), spec, context)

    private fun atwaterEnergy(spec: AIEstimateSpec, p: AIPer100): Double {
        val a = spec.atwater
        return a.protein * p.protein + a.carbs * p.carbs + a.fat * p.fat + a.alcohol * p.alcohol
    }

    /** Sums the rounded item totals in order, then rounds. */
    fun computeTotals(foods: List<AIFood>): AITotals {
        var kcal = 0.0
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        for (food in foods) {
            kcal += food.kcal
            protein += food.protein
            carbs += food.carbs
            fat += food.fat
        }
        return AITotals(round1(kcal), round1(protein), round1(carbs), round1(fat))
    }

    /**
     * Database grounding after finalize (a readable barcode → Open Food Facts): the product's per-100 g
     * values replace the model's, like the backend does. Confidence is unchanged.
     */
    fun groundWithDatabase(estimate: AIEstimate, index: Int, per100: AIPer100): AIEstimate {
        if (index !in estimate.foods.indices) return estimate
        val foods = estimate.foods.toMutableList()
        val food = foods[index]
        foods[index] = food.copy(
            nutritionSource = "open_food_facts",
            adjustments = (food.adjustments ?: emptyList()) + "open_food_facts",
        ).withNutrition(per100)
        return estimate.copy(foods = foods, totals = computeTotals(foods))
    }

    // MARK: Nutrition label

    private val BASES = setOf("per100g", "per100ml", "perServing")

    /**
     * Raw label JSON → per-100 g values. A readable answer that is not a usable table is
     * `legible: false`, not an error; only a non-object throws.
     */
    fun finalizeLabel(raw: JsonElement, spec: AIEstimateSpec): LabelReading {
        val obj = raw as? JsonObject ?: throw AIOutputException(AIOutputException.NOT_OBJECT)
        val l = spec.limits
        val s = spec.label

        val basis = obj["basis"].stringValue?.takeIf { it in BASES } ?: "per100g"
        fun size(value: JsonElement?, maximum: Double): Double? {
            val n = num(value) ?: return null
            if (n <= 0 || n > maximum) return null
            val rounded = round1(n)
            return if (rounded > 0) rounded else null
        }
        val servingSizeG = size(obj["servingSizeG"], s.maxServingSizeG)
        val barcodeRaw = obj["barcode"].stringValue?.trim() ?: ""
        val base = LabelReading(
            legible = false,
            unreadableReason = "illegible",
            basis = basis,
            energyFrom = null,
            name = text(obj["name"], l.maxNameLength),
            brand = text(obj["brand"], l.maxBrandLength),
            per100 = null,
            servingSizeG = servingSizeG,
            packageSizeG = size(obj["packageSizeG"], s.maxPackageSizeG),
            barcode = if (validGtin(barcodeRaw)) barcodeRaw else "",
            confidence = round2(clamp01(num(obj["confidence"]) ?: l.defaultConfidence)),
            needsReview = false,
        )
        fun fail(reason: String): LabelReading = base.copy(unreadableReason = reason)

        if (!isTrue(obj["legible"])) return fail("illegible")
        val values = obj["values"] as? JsonObject ?: JsonObject(emptyMap())

        /** Printed value, or null for missing / negative (the -1 "not printed" sentinel). */
        fun printed(key: String): Double? = num(values[key])?.takeIf { it >= 0 }

        var kcal: Double
        var energyFrom = "kcal"
        val printedKcal = printed("kcal")
        if (printedKcal != null) {
            kcal = printedKcal
        } else {
            val kj = printed("kj") ?: return fail("no_energy")
            kcal = kj / spec.atwater.kjPerKcal
            energyFrom = "kj"
        }
        var protein = printed("protein")
        var carbs = printed("carbs")
        var fat = printed("fat")
        if (protein == null || carbs == null || fat == null) return fail("incomplete")
        var fiber = printed("fiber")
        var sugar = printed("sugar")
        var salt = printed("salt")

        if (basis == "perServing") {
            val serving = servingSizeG ?: return fail("no_serving_size")
            fun scale(v: Double): Double = v * 100 / serving
            kcal = scale(kcal)
            protein = scale(protein)
            carbs = scale(carbs)
            fat = scale(fat)
            fiber = fiber?.let(::scale)
            sugar = sugar?.let(::scale)
            salt = salt?.let(::scale)
        }

        if (kcal > l.maxKcalPer100 || protein + carbs + fat + (fiber ?: 0.0) > l.maxMassPer100) return fail("implausible")

        // Salt is printed with two decimals (0,63 g), everything else with at most one.
        val per100 = LabelReading.Per100(
            kcal = round1(kcal),
            protein = round1(protein),
            carbs = round1(carbs),
            fat = round1(fat),
            fiber = fiber?.let(::round1),
            sugar = sugar?.let(::round1),
            salt = salt?.let(::round2),
        )
        val a = spec.atwater
        val energy = a.protein * per100.protein + a.carbs * per100.carbs + a.fat * per100.fat +
            a.fiber * (per100.fiber ?: 0.0)
        val allowed = max(s.reviewAbs, s.reviewRel * per100.kcal)
        val needsReview = abs(per100.kcal - energy) > allowed ||
            (per100.sugar?.let { it > per100.carbs } ?: false) ||
            base.confidence < s.reviewBelowConfidence

        return base.copy(
            legible = true,
            unreadableReason = null,
            energyFrom = energyFrom,
            per100 = per100,
            needsReview = needsReview,
        )
    }

    fun finalizeLabelText(input: String, spec: AIEstimateSpec): LabelReading = finalizeLabel(parseModelJson(input), spec)
}

// MARK: - Canonical JSON (the contract's key names; the fixtures compare against it)

private fun jsonNumber(value: Double): JsonPrimitive = AIJson.number(value)

private fun jsonOptional(value: Double?): JsonElement = value?.let(::jsonNumber) ?: JsonNull

private fun jsonStrings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/** The finalized estimate with the contract's keys (`FinalEstimate`, contract §5). */
fun AIEstimate.contractJson(): JsonObject {
    val t = totals ?: AIFinalizer.computeTotals(foods)
    return JsonObject(
        linkedMapOf(
            "version" to jsonNumber((version ?: 2).toDouble()),
            "foods" to JsonArray(foods.map { it.contractJson() }),
            "totals" to JsonObject(
                linkedMapOf("kcal" to jsonNumber(t.kcal), "protein" to jsonNumber(t.protein), "carbs" to jsonNumber(t.carbs), "fat" to jsonNumber(t.fat)),
            ),
            "overallConfidence" to jsonNumber(overallConfidence),
            "scaleReferenceUsed" to JsonPrimitive(scaleReferenceUsed ?: "none"),
            "assumptions" to jsonStrings(assumptions),
            "questions" to jsonStrings(questions),
            "skipped" to JsonArray(
                (skipped ?: emptyList()).map {
                    JsonObject(
                        linkedMapOf(
                            "index" to jsonNumber(it.index.toDouble()),
                            "name" to JsonPrimitive(it.name),
                            "reason" to JsonPrimitive(it.reason),
                        ),
                    )
                },
            ),
        ),
    )
}

fun AIFood.contractJson(): JsonObject {
    val o = linkedMapOf<String, JsonElement>(
        "name" to JsonPrimitive(name),
        "grams" to jsonNumber(grams),
        "kcal" to jsonNumber(kcal),
        "protein" to jsonNumber(protein),
        "carbs" to jsonNumber(carbs),
        "fat" to jsonNumber(fat),
        "proteinG" to jsonNumber(protein),
        "carbsG" to jsonNumber(carbs),
        "fatG" to jsonNumber(fat),
        "confidence" to jsonNumber(confidence),
        "isGuess" to JsonPrimitive(isGuess),
        "barcode" to JsonPrimitive(barcode ?: ""),
        "nutritionSource" to JsonPrimitive(nutritionSource ?: "estimated"),
        "cooking" to JsonPrimitive(cooking ?: "prepared"),
        "genericKey" to JsonPrimitive(genericKey ?: "none"),
        "portionCount" to jsonNumber(portionCount ?: 1.0),
        "portionUnit" to JsonPrimitive(portionUnit ?: ""),
        "gramsPerUnit" to jsonNumber(gramsPerUnit ?: grams),
    )
    per100?.let { p ->
        o["per100"] = JsonObject(
            linkedMapOf(
                "kcal" to jsonNumber(p.kcal),
                "protein" to jsonNumber(p.protein),
                "carbs" to jsonNumber(p.carbs),
                "fat" to jsonNumber(p.fat),
                "alcohol" to jsonNumber(p.alcohol),
            ),
        )
    }
    o["adjustments"] = jsonStrings(adjustments ?: emptyList())
    return JsonObject(o)
}

/** The label reading with the contract's keys (`LabelReading`, contract §6). */
fun LabelReading.contractJson(): JsonObject = JsonObject(
    linkedMapOf(
        "version" to jsonNumber(version.toDouble()),
        "legible" to JsonPrimitive(legible),
        "unreadableReason" to (unreadableReason?.let { JsonPrimitive(it) } ?: JsonNull),
        "basis" to JsonPrimitive(basis),
        "energyFrom" to (energyFrom?.let { JsonPrimitive(it) } ?: JsonNull),
        "name" to JsonPrimitive(name),
        "brand" to JsonPrimitive(brand),
        "per100" to (
            per100?.let { p ->
                JsonObject(
                    linkedMapOf(
                        "kcal" to jsonNumber(p.kcal),
                        "protein" to jsonNumber(p.protein),
                        "carbs" to jsonNumber(p.carbs),
                        "fat" to jsonNumber(p.fat),
                        "fiber" to jsonOptional(p.fiber),
                        "sugar" to jsonOptional(p.sugar),
                        "salt" to jsonOptional(p.salt),
                    ),
                )
            } ?: JsonNull
            ),
        "servingSizeG" to jsonOptional(servingSizeG),
        "packageSizeG" to jsonOptional(packageSizeG),
        "barcode" to JsonPrimitive(barcode),
        "confidence" to jsonNumber(confidence),
        "needsReview" to JsonPrimitive(needsReview),
    ),
)
