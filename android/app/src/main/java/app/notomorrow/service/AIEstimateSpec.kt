package app.notomorrow.service

import android.content.Context
import app.notomorrow.net.dto.AIPer100
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

/**
 * The shared AI spec, `backend/data/ai/estimate-spec.json` (packaged as the asset `estimate-spec.json`,
 * see `app/build.gradle.kts`): prompts, response schemas, Atwater constants, limits, the notes-context
 * patterns, provider settings and the generic Polish food table — the port of
 * `NoTomorrow/Services/AIEstimateSpec.swift`.
 *
 * The backend (`aiSpec.ts`) and iOS load the same file and assemble every string the same way;
 * `fixtures/prompt-*.json` pins the results for all three (`AIEstimateFinalizerTest`). The file is
 * parsed with [AIJson], so the schemas keep their property order (identify → portion → density).
 */
class AIEstimateSpec private constructor(root: JsonElement) {

    data class GenericFood(
        val key: String,
        val en: String,
        val pl: String,
        val per100: AIPer100,
        val unitPl: String,
        val unitEn: String,
        val typical: Int,
        val min: Int,
        val max: Int,
    )

    /** Estimated and table values are corrected to Atwater ([repair]); label and user values are only flagged. */
    data class Tolerance(val rel: Double, val abs: Double, val repair: Boolean)

    data class Atwater(
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val alcohol: Double,
        val fiber: Double,
        val kjPerKcal: Double,
        /** Keyed by nutrition source (`estimated`, `generic_table`, `visible_label`, `user_notes`). */
        val tolerance: Map<String, Tolerance>,
    )

    data class Limits(
        val maxItems: Int,
        val maxGrams: Double,
        val maxKcalPer100: Double,
        val maxMassPer100: Double,
        val portionMismatchGrams: Double,
        val defaultConfidence: Double,
        val energyRepairPenalty: Double,
        val confidenceCapWithoutReference: Double,
        val maxNameLength: Int,
        val maxUnitLength: Int,
        val maxBrandLength: Int,
        val maxScaleReferenceLength: Int,
        val maxAssumptions: Int,
        val maxQuestions: Int,
        val maxTextLength: Int,
        val maxNotesLength: Int,
    )

    data class Label(
        val promptLines: List<String>,
        val languageLine: String,
        val requestText: String,
        val schema: JsonElement,
        val reviewRel: Double,
        val reviewAbs: Double,
        val reviewBelowConfidence: Double,
        val maxServingSizeG: Double,
        val maxPackageSizeG: Double,
    )

    data class Gemini(
        val model: String,
        val thinkingLevel: String,
        /** Interactions API per-image resolution. */
        val imageResolution: String,
        /** generateContent `generationConfig.mediaResolution`. */
        val generateContentMediaResolution: String,
    )

    data class Claude(
        val model: String,
        val anthropicVersion: String,
        val effort: String,
        /** Thinking tokens count toward `max_tokens`, hence 8192 / 4096 rather than 1024. */
        val estimateMaxTokens: Int,
        val labelMaxTokens: Int,
    )

    /** What the notes tell the finalizer: a stated weight or a measured length lifts the confidence cap. */
    data class NotesContext(val weightGiven: Boolean, val measuredReference: Boolean)

    /** A missing or malformed spec is a build mistake (the unit tests load it), not a runtime state. */
    class LoadException(message: String) : IllegalStateException(message)

    val languages: Map<String, String>
    val estimatePromptLines: List<String>
    val estimateLanguageLine: String
    val genericFoodFormat: String
    val genericFoodSeparator: String
    val requestTemplate: String

    /** Canonical schema; `genericKey.enum` is empty in the file and filled by [estimateSchema]. */
    val estimateSchemaTemplate: JsonElement
    val label: Label
    val atwater: Atwater
    val limits: Limits
    val weightPattern: Regex
    val referencePattern: Regex
    val gemini: Gemini
    val claude: Claude

    /** In file order: the order matters (schema enum, prompt table). */
    val genericFoods: List<GenericFood>
    val table: Map<String, GenericFood>

    init {
        val r = SpecReader(root, "spec")
        if (r.number("version") != 2.0) throw LoadException("unsupported spec version")

        val langs = r.obj("languages")
        languages = langs.members.keys.associateWith { langs.string(it) }

        val estimate = r.obj("estimate")
        estimatePromptLines = estimate.strings("promptLines")
        estimateLanguageLine = estimate.string("languageLine")
        genericFoodFormat = estimate.string("genericFoodFormat")
        genericFoodSeparator = estimate.string("genericFoodSeparator")
        requestTemplate = estimate.string("requestTemplate")
        estimateSchemaTemplate = estimate.value("schema")
        if (estimateSchemaTemplate["properties"]["foods"]["items"]["properties"]["genericKey"] !is JsonObject) {
            throw LoadException("estimate schema has no foods.items.properties.genericKey")
        }

        val labelNode = r.obj("label")
        val review = labelNode.obj("reviewTolerance")
        label = Label(
            promptLines = labelNode.strings("promptLines"),
            languageLine = labelNode.string("languageLine"),
            requestText = labelNode.string("requestText"),
            schema = labelNode.value("schema"),
            reviewRel = review.number("rel"),
            reviewAbs = review.number("abs"),
            reviewBelowConfidence = labelNode.number("reviewBelowConfidence"),
            maxServingSizeG = labelNode.number("maxServingSizeG"),
            maxPackageSizeG = labelNode.number("maxPackageSizeG"),
        )

        val a = r.obj("atwater")
        val toleranceNode = a.obj("tolerance")
        val tolerance = toleranceNode.members.keys.associateWith { key ->
            val t = toleranceNode.obj(key)
            Tolerance(rel = t.number("rel"), abs = t.number("abs"), repair = t.bool("repair"))
        }
        for (source in listOf("estimated", "generic_table", "visible_label", "user_notes")) {
            if (source !in tolerance) throw LoadException("atwater.tolerance.$source is missing")
        }
        atwater = Atwater(
            protein = a.number("protein"),
            carbs = a.number("carbs"),
            fat = a.number("fat"),
            alcohol = a.number("alcohol"),
            fiber = a.number("fiber"),
            kjPerKcal = a.number("kjPerKcal"),
            tolerance = tolerance,
        )

        val l = r.obj("limits")
        limits = Limits(
            maxItems = l.int("maxItems"),
            maxGrams = l.number("maxGrams"),
            maxKcalPer100 = l.number("maxKcalPer100"),
            maxMassPer100 = l.number("maxMassPer100"),
            portionMismatchGrams = l.number("portionMismatchGrams"),
            defaultConfidence = l.number("defaultConfidence"),
            energyRepairPenalty = l.number("energyRepairPenalty"),
            confidenceCapWithoutReference = l.number("confidenceCapWithoutReference"),
            maxNameLength = l.int("maxNameLength"),
            maxUnitLength = l.int("maxUnitLength"),
            maxBrandLength = l.int("maxBrandLength"),
            maxScaleReferenceLength = l.int("maxScaleReferenceLength"),
            maxAssumptions = l.int("maxAssumptions"),
            maxQuestions = l.int("maxQuestions"),
            maxTextLength = l.int("maxTextLength"),
            maxNotesLength = l.int("maxNotesLength"),
        )

        val context = r.obj("context")
        try {
            weightPattern = Regex(context.string("weightPattern"))
            referencePattern = Regex(context.string("referencePattern"))
        } catch (e: IllegalArgumentException) {
            throw LoadException("context pattern does not compile: ${e.message}")
        }

        val providers = r.obj("providers")
        val g = providers.obj("gemini")
        gemini = Gemini(
            model = g.string("model"),
            thinkingLevel = g.string("thinkingLevel"),
            imageResolution = g.string("imageResolution"),
            generateContentMediaResolution = g.string("generateContentMediaResolution"),
        )
        val c = providers.obj("claude")
        claude = Claude(
            model = c.string("model"),
            anthropicVersion = c.string("anthropicVersion"),
            effort = c.string("effort"),
            estimateMaxTokens = c.int("estimateMaxTokens"),
            labelMaxTokens = c.int("labelMaxTokens"),
        )

        val foods = ArrayList<GenericFood>()
        val byKey = LinkedHashMap<String, GenericFood>()
        r.array("genericFoods").forEachIndexed { index, node ->
            val f = SpecReader(node, "genericFoods[$index]")
            val key = f.string("key")
            if (!KEY_PATTERN.matches(key) || key == "none") throw LoadException("bad generic food key $key")
            if (key in byKey) throw LoadException("duplicate generic food key $key")
            val p = f.obj("per100")
            val unit = f.obj("unit")
            val typical = unit.int("typical")
            val min = unit.int("min")
            val max = unit.int("max")
            if (min > typical || typical > max) throw LoadException("bad unit for $key")
            val food = GenericFood(
                key = key,
                en = f.string("en"),
                pl = f.string("pl"),
                per100 = AIPer100(
                    kcal = p.number("kcal"),
                    protein = p.number("protein"),
                    carbs = p.number("carbs"),
                    fat = p.number("fat"),
                    alcohol = p.number("alcohol"),
                ),
                unitPl = unit.string("pl"),
                unitEn = unit.string("en"),
                typical = typical,
                min = min,
                max = max,
            )
            foods += food
            byKey[key] = food
        }
        genericFoods = foods
        table = byKey
    }

    // MARK: Language

    fun languageName(locale: String): String = languages[languageCode(locale)] ?: languages["en"] ?: "English"

    // MARK: Prompts

    /** The `{genericFoods}` text: one formatted entry per table row, in file order. */
    val genericFoodsText: String by lazy {
        genericFoods.joinToString(genericFoodSeparator) { food ->
            var entry = genericFoodFormat
            entry = substitute(entry, "{key}", food.key)
            entry = substitute(entry, "{pl}", food.pl)
            entry = substitute(entry, "{unit}", food.unitPl)
            entry = substitute(entry, "{typical}", food.typical.toString())
            entry = substitute(entry, "{min}", food.min.toString())
            substitute(entry, "{max}", food.max.toString())
        }
    }

    /** Static per language, so Gemini's implicit prompt caching applies. */
    fun estimateSystemInstruction(locale: String): String {
        val foods = genericFoodsText
        val lines = estimatePromptLines.map { substitute(it, "{genericFoods}", foods) } +
            substitute(estimateLanguageLine, "{language}", languageName(locale))
        return lines.joinToString("\n")
    }

    /**
     * The per-request text: the meal slot (`breakfast|lunch|snack|dinner`) and the notes, cut to
     * `maxNotesLength` UTF-16 units like the backend's `slice`, then JSON-quoted.
     */
    fun estimateRequestText(meal: String, notes: String): String {
        val quoted = AIJson.stringLiteral(notes.take(limits.maxNotesLength))
        return substitute(substitute(requestTemplate, "{meal}", meal), "{notes}", quoted)
    }

    fun labelSystemInstruction(locale: String): String =
        (label.promptLines + substitute(label.languageLine, "{language}", languageName(locale))).joinToString("\n")

    val labelRequestText: String get() = label.requestText

    // MARK: Schemas

    /** Table keys in file order, then "none". */
    val genericKeys: List<String> get() = genericFoods.map { it.key } + "none"

    /** Canonical (Claude) estimate schema with `genericKey.enum` filled in. */
    fun estimateSchema(): JsonElement = AIJson.setting(
        estimateSchemaTemplate,
        listOf("properties", "foods", "items", "properties", "genericKey", "enum"),
        JsonArray(genericKeys.map { JsonPrimitive(it) }),
    )

    fun labelSchema(): JsonElement = label.schema

    // MARK: Notes context

    /**
     * Both patterns run case-sensitively on the lower-cased notes. Pass the full notes string that
     * is sent (typed details plus any "User corrections" / "Measured reference" line).
     */
    fun notesContext(notes: String): NotesContext {
        val text = notes.lowercase()
        return NotesContext(
            weightGiven = weightPattern.containsMatchIn(text),
            measuredReference = referencePattern.containsMatchIn(text),
        )
    }

    companion object {
        const val ASSET_NAME: String = "estimate-spec.json"

        private val KEY_PATTERN = Regex("[a-z0-9_]+")

        @Volatile
        private var shared: AIEstimateSpec? = null

        fun parse(text: String): AIEstimateSpec = AIEstimateSpec(AIJson.parse(text))

        /**
         * The packaged spec, parsed once per process. Small (≈30 KB), but it is an asset read:
         * call it off the main thread the first time.
         */
        fun shared(context: Context): AIEstimateSpec = shared ?: synchronized(this) {
            shared ?: context.applicationContext.assets.open(ASSET_NAME).use { stream ->
                parse(stream.readBytes().toString(Charsets.UTF_8))
            }.also { shared = it }
        }

        /** `pl`, `pl-PL`, `pl_PL` → "pl"; everything else → "en". */
        fun languageCode(locale: String): String {
            val lang = locale.trim().lowercase().split('-', '_').firstOrNull() ?: ""
            return if (lang == "pl") "pl" else "en"
        }

        /** Literal replace-all, the same operation as the backend's `split(token).join(value)`. */
        fun substitute(template: String, token: String, value: String): String = template.replace(token, value)

        /** Gemini gets the same schema with every `additionalProperties` removed, at any depth. */
        fun forGemini(schema: JsonElement): JsonElement = when (schema) {
            is JsonArray -> JsonArray(schema.map(::forGemini))
            is JsonObject -> JsonObject(
                LinkedHashMap<String, JsonElement>().apply {
                    for ((key, value) in schema) if (key != "additionalProperties") put(key, forGemini(value))
                },
            )
            else -> schema
        }
    }
}

// MARK: - Reading the spec

private class SpecReader(value: JsonElement?, private val path: String) {
    val members: JsonObject =
        value as? JsonObject ?: throw AIEstimateSpec.LoadException("$path is not an object")

    fun value(key: String): JsonElement = members[key] ?: throw AIEstimateSpec.LoadException("$path.$key is missing")

    fun obj(key: String): SpecReader = SpecReader(value(key), "$path.$key")

    fun string(key: String): String =
        value(key).stringValue ?: throw AIEstimateSpec.LoadException("$path.$key is not a string")

    fun number(key: String): Double =
        value(key).numberValue ?: throw AIEstimateSpec.LoadException("$path.$key is not a number")

    fun int(key: String): Int {
        val n = number(key)
        if (n != floor(n) || abs(n) >= 1e9) throw AIEstimateSpec.LoadException("$path.$key is not an integer")
        return n.toInt()
    }

    fun bool(key: String): Boolean =
        value(key).booleanValue ?: throw AIEstimateSpec.LoadException("$path.$key is not a boolean")

    fun array(key: String): JsonArray =
        value(key).arrayValue ?: throw AIEstimateSpec.LoadException("$path.$key is not an array")

    fun strings(key: String): List<String> = array(key).mapIndexed { index, item ->
        item.stringValue ?: throw AIEstimateSpec.LoadException("$path.$key[$index] is not a string")
    }
}
