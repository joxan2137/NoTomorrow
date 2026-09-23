package app.notomorrow.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The spec and fixtures from `backend/data/ai` (test resources, see `app/build.gradle.kts`). */
object AISpecFixtures {

    private val loader: ClassLoader get() = AISpecFixtures::class.java.classLoader!!

    val spec: AIEstimateSpec by lazy {
        AIEstimateSpec.parse(loader.getResource(AIEstimateSpec.ASSET_NAME)!!.readText())
    }

    fun fixtureFiles(): List<File> =
        File(loader.getResource("fixtures")!!.toURI()).listFiles { f -> f.name.endsWith(".json") }!!
            .sortedBy { it.name }
}

/**
 * The cross-platform parity lock (contract §7): every shared fixture in `backend/data/ai/fixtures`
 * reproduces exactly — estimate and label finalizer outputs (numbers within 1e-9), the error codes,
 * the notes-context cases, and the assembled prompts byte for byte with both schema variants
 * (structure **and** key order). The same files run in `backend/test/ai-finalize.test.ts` and
 * `NoTomorrowTests/AIEstimateFinalizerTests.swift`.
 */
class AIEstimateFinalizerTest {

    private val spec get() = AISpecFixtures.spec

    // MARK: Fixtures

    @Test
    fun `every shared fixture reproduces exactly`() {
        val files = AISpecFixtures.fixtureFiles()
        val failures = ArrayList<String>()
        val kinds = HashMap<String, Int>()
        for (file in files) {
            val fixture = AIJson.parse(file.readText()) as JsonObject
            val kind = fixture["kind"].stringValue ?: ""
            kinds[kind] = (kinds[kind] ?: 0) + 1
            val problem = try {
                when (kind) {
                    "estimate" -> checkEstimate(fixture)
                    "label" -> checkLabel(fixture)
                    "context" -> checkContext(fixture)
                    "prompt" -> checkPrompt(fixture)
                    else -> "unknown kind '$kind'"
                }
            } catch (e: Exception) {
                "threw $e"
            }
            if (problem != null) failures += "${file.name}: $problem"
        }
        assertTrue("fixture failures:\n" + failures.joinToString("\n"), failures.isEmpty())
        // 29 files today: 16 estimate, 10 label, 1 context, 2 prompt. More may be added, never fewer.
        assertTrue("estimate fixtures: $kinds", (kinds["estimate"] ?: 0) >= 16)
        assertTrue("label fixtures: $kinds", (kinds["label"] ?: 0) >= 10)
        assertTrue("context fixtures: $kinds", (kinds["context"] ?: 0) >= 1)
        assertTrue("prompt fixtures: $kinds", (kinds["prompt"] ?: 0) >= 2)
    }

    private fun rawInput(fixture: JsonObject): JsonElement =
        fixture["raw"] ?: AIFinalizer.parseModelJson(fixture["rawText"].stringValue ?: error("no raw/rawText"))

    private fun checkEstimate(fixture: JsonObject): String? {
        val ctx = fixture["ctx"] as JsonObject
        val context = AIEstimateSpec.NotesContext(
            weightGiven = ctx["weightGiven"].booleanValue == true,
            measuredReference = ctx["measuredReference"].booleanValue == true,
        )
        val expectedError = fixture["expectedError"].stringValue
        return try {
            val result = AIFinalizer.finalizeEstimate(rawInput(fixture), spec, context)
            if (expectedError != null) {
                "expected error $expectedError, got a result"
            } else {
                diff(fixture["expected"]!!, result.contractJson(), "$")
            }
        } catch (e: AIOutputException) {
            if (e.code == expectedError) null else "unexpected error ${e.code} (expected ${expectedError ?: "a result"})"
        }
    }

    private fun checkLabel(fixture: JsonObject): String? {
        val expectedError = fixture["expectedError"].stringValue
        return try {
            val result = AIFinalizer.finalizeLabel(rawInput(fixture), spec)
            if (expectedError != null) {
                "expected error $expectedError, got a result"
            } else {
                diff(fixture["expected"]!!, result.contractJson(), "$")
            }
        } catch (e: AIOutputException) {
            if (e.code == expectedError) null else "unexpected error ${e.code} (expected ${expectedError ?: "a result"})"
        }
    }

    private fun checkContext(fixture: JsonObject): String? {
        for (case in fixture["cases"] as JsonArray) {
            val notes = case["notes"].stringValue!!
            val expected = AIEstimateSpec.NotesContext(
                weightGiven = case["weightGiven"].booleanValue == true,
                measuredReference = case["measuredReference"].booleanValue == true,
            )
            val actual = spec.notesContext(notes)
            if (actual != expected) return "notes '$notes': expected $expected, got $actual"
        }
        return null
    }

    private fun checkPrompt(fixture: JsonObject): String? {
        val input = fixture["input"] as JsonObject
        val expected = fixture["expected"] as JsonObject
        val locale = input["locale"].stringValue!!
        val meal = input["meal"].stringValue!!
        val notes = input["notes"].stringValue!!

        val strings = listOf(
            "estimateSystemInstruction" to spec.estimateSystemInstruction(locale),
            "estimateRequestText" to spec.estimateRequestText(meal, notes),
            "labelSystemInstruction" to spec.labelSystemInstruction(locale),
            "labelRequestText" to spec.labelRequestText,
        )
        for ((key, actual) in strings) {
            val want = expected[key].stringValue
            if (want != actual) return "$key differs at ${firstDifference(want ?: "", actual)}"
        }

        val context = spec.notesContext(notes)
        val wantContext = expected["notesContext"] as JsonObject
        if (wantContext["weightGiven"].booleanValue != context.weightGiven ||
            wantContext["measuredReference"].booleanValue != context.measuredReference
        ) {
            return "notesContext: expected $wantContext, got $context"
        }

        val schemas = listOf(
            "estimateSchema" to spec.estimateSchema(),
            "estimateSchemaGemini" to AIEstimateSpec.forGemini(spec.estimateSchema()),
            "labelSchema" to spec.labelSchema(),
            "labelSchemaGemini" to AIEstimateSpec.forGemini(spec.labelSchema()),
        )
        for ((key, actual) in schemas) {
            val want = expected[key]!!
            diff(want, actual, key)?.let { return it }
            // Property order is part of the contract (identify → portion → density).
            if (AIJson.serialize(want) != AIJson.serialize(actual)) return "$key: key order differs"
        }
        return null
    }

    private fun firstDifference(a: String, b: String): String {
        val i = a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.length, b.length) else it }
        return "index $i (expected length ${a.length}, got ${b.length}): " +
            "expected '${a.drop(i).take(40)}', got '${b.drop(i).take(40)}'"
    }

    /**
     * Contract §7 comparison: numbers within 1e-9 (210 == 210.0), strings and booleans exact, arrays
     * in order, the same object keys — except that a key whose expected value is null may be missing.
     */
    private fun diff(expected: JsonElement, actual: JsonElement?, path: String): String? {
        when (expected) {
            is JsonNull -> return if (actual == null || actual is JsonNull) null else "$path: expected null, got $actual"
            is JsonObject -> {
                val a = actual as? JsonObject ?: return "$path: expected an object, got $actual"
                for ((key, value) in expected) {
                    if (key !in a) {
                        if (value is JsonNull) continue
                        return "$path.$key is missing"
                    }
                    diff(value, a[key], "$path.$key")?.let { return it }
                }
                for (key in a.keys) if (key !in expected) return "$path.$key is unexpected (${a[key]})"
                return null
            }
            is JsonArray -> {
                val a = actual as? JsonArray ?: return "$path: expected an array, got $actual"
                if (a.size != expected.size) return "$path: expected ${expected.size} items, got ${a.size}: $a"
                for (i in expected.indices) diff(expected[i], a[i], "$path[$i]")?.let { return it }
                return null
            }
            is JsonPrimitive -> {
                val a = actual as? JsonPrimitive
                if (a == null || a is JsonNull) return "$path: expected $expected, got $actual"
                val ok = when {
                    expected.isString -> a.isString && a.content == expected.content
                    expected.booleanValue != null -> !a.isString && a.booleanValue == expected.booleanValue
                    else -> {
                        val e = expected.numberValue!!
                        val n = a.numberValue
                        n != null && abs(n - e) <= 1e-9
                    }
                }
                return if (ok) null else "$path: expected $expected, got $a"
            }
        }
    }

    // MARK: Helpers pinned by the contract (§4)

    @Test
    fun `round1 keeps IEEE order effects`() {
        assertEquals(2.4, AIFinalizer.round1(1.4 * 175 / 100), 0.0)
        assertEquals(0.1, AIFinalizer.round1(0.05), 0.0)
        assertEquals(0.0, AIFinalizer.round1(0.04), 0.0)
        assertEquals(0.13, AIFinalizer.round2(0.125), 0.0)
    }

    @Test
    fun `num accepts finite numbers and plain numeric strings only`() {
        fun n(json: String) = AIFinalizer.num(AIJson.parse("""{"v":$json}""")["v"])
        assertEquals(12.0, n("12")!!, 0.0)
        assertEquals(4.5, n("\" 4,5 \"")!!, 0.0)
        assertEquals(-3.0, n("\"-3\"")!!, 0.0)
        assertEquals(5.0, n("\"+5\"")!!, 0.0)
        assertNull(n("\"1e3\""))
        assertNull(n("\"\""))
        assertNull(n("\"4,5,6\""))
        assertNull(n("true"))
        assertNull(n("null"))
        assertNull(n("{}"))
    }

    @Test
    fun `isTrue takes the boolean or a true string`() {
        fun t(json: String) = AIFinalizer.isTrue(AIJson.parse("""{"v":$json}""")["v"])
        assertTrue(t("true"))
        assertTrue(t("\" TRUE \""))
        assertFalse(t("1"))
        assertFalse(t("\"yes\""))
        assertFalse(t("null"))
    }

    @Test
    fun `validGtin checks length and the mod-10 digit`() {
        assertTrue(AIFinalizer.validGtin("5900531000508"))
        assertFalse(AIFinalizer.validGtin("5900531000509"))
        assertTrue(AIFinalizer.validGtin("96385074"))
        assertFalse(AIFinalizer.validGtin("123456789"))
        assertFalse(AIFinalizer.validGtin("59005310005a8"))
    }

    @Test
    fun `parseModelJson strips fences, prose, trailing commas and single quotes`() {
        assertEquals(
            """{"foods":[]}""",
            AIJson.serialize(AIFinalizer.parseModelJson("Here you go:\n```json\n{\"foods\":[],}\n```\nThanks")),
        )
        assertEquals("""{"a":"b"}""", AIJson.serialize(AIFinalizer.parseModelJson("{'a': 'b'}")))
        assertEquals("""{"foods":[]}""", AIFinalizer.extractJsonObject("```JSON{\"foods\":[]}```"))
        assertNull(AIFinalizer.extractJsonObject("} {"))
        assertCode(AIOutputException.NO_JSON) { AIFinalizer.parseModelJson("I could not see any food.") }
        assertCode(AIOutputException.INVALID_JSON) { AIFinalizer.parseModelJson("{foods: [}") }
    }

    @Test
    fun `the parser behaves like JSON parse`() {
        // Last duplicate wins at its first position; strict RFC 8259.
        assertEquals("""{"a":3,"b":2}""", AIJson.serialize(AIJson.parse("""{"a":1,"b":2,"a":3}""")))
        assertCode(AIOutputException.INVALID_JSON) { AIFinalizer.parseModelJson("{\"a\":NaN}") }
        assertEquals("\"a/b\\\"c\\\\ \\n ł\"", AIJson.stringLiteral("a/b\"c\\ \n ł"))
        assertEquals("\"\\u0001\"", AIJson.stringLiteral("\u0001"))
    }

    @Test
    fun `language, substitution and the Gemini schema variant`() {
        assertEquals("pl", AIEstimateSpec.languageCode(" pl_PL "))
        assertEquals("pl", AIEstimateSpec.languageCode("PL-pl"))
        assertEquals("en", AIEstimateSpec.languageCode("de"))
        assertEquals("Polish", spec.languageName("pl-PL"))
        assertEquals("English", spec.languageName(""))
        assertEquals("a\$1b\$1", AIEstimateSpec.substitute("a{x}b{x}", "{x}", "\$1"))
        val gemini = AIJson.serialize(AIEstimateSpec.forGemini(spec.estimateSchema()))
        assertFalse(gemini.contains("additionalProperties"))
        assertTrue(AIJson.serialize(spec.estimateSchema()).contains("additionalProperties"))
        assertEquals(spec.genericFoods.size + 1, spec.genericKeys.size)
        assertEquals("none", spec.genericKeys.last())
    }

    @Test
    fun `request text caps notes at 1500 UTF-16 units before quoting`() {
        val text = spec.estimateRequestText("lunch", "x".repeat(2000))
        assertEquals(1500, text.count { it == 'x' })
        assertTrue(text.startsWith("Meal slot: lunch."))
    }

    @Test
    fun `a spec with another version is rejected`() {
        try {
            AIEstimateSpec.parse("""{"version":1}""")
            fail("expected a load error")
        } catch (e: AIEstimateSpec.LoadException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: AIOutputException) {
            assertEquals(code, e.code)
        }
    }
}
