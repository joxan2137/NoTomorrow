package app.notomorrow.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

/**
 * Strict, order-keeping JSON for the AI paths — the port of `NoTomorrow/Services/JSONValue.swift`.
 *
 * The model's answer is parsed exactly like JavaScript `JSON.parse` does on the backend, so the
 * shared finalizer fixtures agree bit for bit: RFC 8259 only (no trailing commas, no single
 * quotes, no bare words, which kotlinx's tree reader lets through), a duplicated key keeps its
 * first position and its last value, and numbers go through [String.toDouble] (correctly rounded).
 * Values come back as kotlinx [JsonElement]s; [JsonObject] keeps key order, which the response
 * schemas rely on (identify → portion → density).
 */
object AIJson {

    class ParseException(val offset: Int) : Exception("Invalid JSON at $offset")

    fun parse(text: String): JsonElement = Parser(text).document()

    /**
     * Exactly JavaScript `JSON.stringify(string)`: `"` and `\` escaped, U+0008/9/A/C/D as
     * `\b \t \n \f \r`, any other code unit below U+0020 and any lone surrogate as `\uXXXX`
     * (lower-case hex), everything else (`/` and Polish letters included) as is.
     */
    fun stringLiteral(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append(hexEscape(c))
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    out.append(c).append(text[i + 1])
                    i += 1
                }
                c.isSurrogate() -> out.append(hexEscape(c))
                else -> out.append(c)
            }
            i += 1
        }
        out.append('"')
        return out.toString()
    }

    private fun hexEscape(c: Char): String = "\\u" + c.code.toString(16).padStart(4, '0')

    private val writer = Json { prettyPrint = false }

    /** Compact JSON in key order — request bodies and the fixture comparison. */
    fun serialize(value: JsonElement): String = writer.encodeToString(JsonElement.serializer(), value)

    /** A copy with the value at [path] (object keys) replaced; intermediate objects must exist. */
    fun setting(value: JsonElement, path: List<String>, newValue: JsonElement): JsonElement {
        val key = path.firstOrNull() ?: return newValue
        val obj = value as? JsonObject ?: return value
        val child = obj[key] ?: return value
        val members = LinkedHashMap<String, JsonElement>(obj)
        members[key] = setting(child, path.drop(1), newValue)
        return JsonObject(members)
    }

    /** A JSON number as the parser stores it: integral values below 1e15 as a long, like JavaScript prints them. */
    fun number(value: Double): JsonPrimitive {
        val negativeZero = value == 0.0 && 1.0 / value < 0
        return if (value.isFinite() && value == floor(value) && abs(value) < 1e15 && !negativeZero) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): JsonElement {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            if (i != s.length) fail()
            return value
        }

        private fun fail(): Nothing = throw ParseException(i)

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i += 1
        }

        private fun value(depth: Int): JsonElement {
            if (depth >= MAX_DEPTH || i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> number(numberValue())
                else -> fail()
            }
        }

        private fun literal(word: String, value: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return value
        }

        private fun obj(depth: Int): JsonElement {
            i += 1
            val members = LinkedHashMap<String, JsonElement>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i += 1
                return JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (i >= s.length || s[i] != '"') fail()
                val key = string()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') fail()
                i += 1
                skipWhitespace()
                // LinkedHashMap keeps a re-put key at its first position, as JavaScript objects do.
                members[key] = value(depth + 1)
                skipWhitespace()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i += 1
                    '}' -> {
                        i += 1
                        return JsonObject(members)
                    }
                    else -> fail()
                }
            }
        }

        private fun array(depth: Int): JsonElement {
            i += 1
            val items = ArrayList<JsonElement>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i += 1
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items.add(value(depth + 1))
                skipWhitespace()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i += 1
                    ']' -> {
                        i += 1
                        return JsonArray(items)
                    }
                    else -> fail()
                }
            }
        }

        private fun string(): String {
            i += 1
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i += 1
                        return out.toString()
                    }
                    c == '\\' -> {
                        i += 1
                        if (i >= s.length) fail()
                        val escape = s[i]
                        i += 1
                        when (escape) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            // JavaScript keeps each code unit, so a surrogate pair written as two
                            // escapes joins up and a lone surrogate stays one.
                            'u' -> out.append(hex4().toChar())
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> {
                        out.append(c)
                        i += 1
                    }
                }
            }
            fail()
        }

        private fun hex4(): Int {
            if (i + 4 > s.length) fail()
            var value = 0
            repeat(4) {
                val digit = Character.digit(s[i], 16)
                if (digit < 0 || s[i].code > 0x7F) fail()
                value = value * 16 + digit
                i += 1
            }
            return value
        }

        /** `-? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?`. */
        private fun numberValue(): Double {
            val start = i
            if (s[i] == '-') i += 1
            if (i >= s.length || s[i] !in '0'..'9') fail()
            if (s[i] == '0') {
                i += 1
            } else {
                while (i < s.length && s[i] in '0'..'9') i += 1
            }
            if (i < s.length && s[i] == '.') {
                i += 1
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i += 1
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i += 1
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i += 1
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i += 1
            }
            return s.substring(start, i).toDouble()
        }
    }

    private const val MAX_DEPTH = 512
}

// MARK: - Reading parsed values

/** The member of an object; null for anything else. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

val JsonElement?.objectValue: JsonObject? get() = this as? JsonObject

val JsonElement?.arrayValue: JsonArray? get() = this as? JsonArray

/** A JSON string's content; null for numbers, booleans and null. */
val JsonElement?.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number; null for strings, booleans and null. */
val JsonElement?.numberValue: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.content == "true" || p.content == "false") return null
        return p.content.toDoubleOrNull()
    }

val JsonElement?.booleanValue: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
