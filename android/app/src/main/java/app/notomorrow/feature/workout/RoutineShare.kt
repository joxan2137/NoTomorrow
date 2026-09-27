package app.notomorrow.feature.workout

import app.notomorrow.service.WorkoutImport
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sharing a routine with a friend as text, and reading one back ("Share routine" / "Import
 * routine") — port of `NoTomorrow/Features/Workout/RoutineShare.swift`; both apps write and read
 * the same format. Offline: the text itself carries the routine.
 *
 * ```
 * No Tomorrow routine: Push A
 * 1. Barbell Bench Press - Medium Grip — 4 × 8, rest 2:00
 * 2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]
 * 3. Dumbbell Flyes — 3 × 12 [Superset A]
 * nt1:eyJ2IjoxLCJuYW1lIjoiUHVzaCBBIiwiaXRlbXMiOlt7ImlkIjoi…
 * ```
 *
 * The header and numbered lines are for people (localized; rest is left out when it is the
 * user's default). The `nt1:` line is the exact data: base64url (no padding) of compact UTF-8 JSON
 * `{"v":1,"name":…,"items":[{"id":…,"sets":…,"reps":…,"rest":…,"ss":…}]}` where a library
 * exercise has `id` and a custom one `name` instead, `rest` is seconds (0 = the user's default)
 * and `ss` the superset group (left out when on its own). Reading uses the `nt1:` line; without
 * one (or when it is damaged) it falls back to the numbered lines, matched by exercise name.
 * Everything read goes through the routine editor's limits.
 */
object RoutineShare {

    const val MARKER = "nt1:"
    const val FORMAT_VERSION = 1

    /** A shared routine longer than this is cut. */
    const val MAX_LINES = 50
    const val MAX_TOKEN_LENGTH = 20_000

    /** One exercise of a shared routine: a library id, or a name (custom exercises, or the numbered-line fallback). */
    data class Line(
        val exerciseId: String? = null,
        val name: String? = null,
        val sets: Int,
        val reps: Int,
        val restSeconds: Int,
        val supersetGroup: Int? = null,
    )

    data class Shared(val name: String, val lines: List<Line>)

    /** The human-readable parts: "No Tomorrow routine: %s", "rest %s", "Superset %s". */
    class Labels(
        val header: (String) -> String,
        val rest: (String) -> String,
        val superset: (String) -> String,
    ) {
        companion object {
            val English = Labels(
                header = { "No Tomorrow routine: $it" },
                rest = { "rest $it" },
                superset = { "Superset $it" },
            )
        }
    }

    // MARK: - Writing

    /** The whole share text for [draft]: header, numbered lines, then the `nt1:` line. */
    fun text(draft: RoutineDraft, labels: Labels = Labels.English): String {
        val letters = Superset.letters(draft.items.map { it.supersetGroup })
        val out = mutableListOf(labels.header(oneLine(draft.trimmedName)))
        draft.items.forEachIndexed { index, item ->
            val line = StringBuilder("${index + 1}. ${oneLine(item.name)} — ${item.sets} × ${item.reps}")
            if (item.restSeconds > 0) line.append(", ").append(labels.rest(clock(item.restSeconds)))
            letters[index]?.let { line.append(" [").append(labels.superset(it)).append("]") }
            out += line.toString()
        }
        out += payload(shared(draft))
        return out.joinToString("\n")
    }

    /** What the `nt1:` line carries for [draft]: library ids, custom exercises (`custom-…`) by name. */
    fun shared(draft: RoutineDraft): Shared {
        val groups = Superset.normalized(draft.items.map { it.supersetGroup })
        return Shared(
            name = oneLine(draft.trimmedName),
            lines = draft.items.mapIndexed { index, item ->
                val isCustom = item.exerciseId.startsWith("custom-")
                Line(
                    exerciseId = if (isCustom) null else item.exerciseId,
                    name = if (isCustom) oneLine(item.name) else null,
                    sets = item.sets,
                    reps = item.reps,
                    restSeconds = maxOf(0, item.restSeconds),
                    supersetGroup = groups[index],
                )
            },
        )
    }

    /** `nt1:` + base64url of the compact JSON. Keys in a fixed order, so both apps write the same bytes. */
    fun payload(shared: Shared): String {
        val json = StringBuilder("{\"v\":$FORMAT_VERSION,\"name\":${quoted(shared.name)},\"items\":[")
        shared.lines.forEachIndexed { index, line ->
            if (index > 0) json.append(',')
            json.append('{')
            if (line.exerciseId != null) {
                json.append("\"id\":").append(quoted(line.exerciseId))
            } else {
                json.append("\"name\":").append(quoted(line.name ?: ""))
            }
            json.append(",\"sets\":${line.sets},\"reps\":${line.reps},\"rest\":${line.restSeconds}")
            line.supersetGroup?.let { json.append(",\"ss\":$it") }
            json.append('}')
        }
        json.append("]}")
        return MARKER + base64Url(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** "2:00". */
    fun clock(seconds: Int): String {
        val s = maxOf(0, seconds)
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    private fun oneLine(text: String): String =
        text.lines().filter { it.isNotEmpty() }.joinToString(" ").trim()

    /** A JSON string: `"` and `\` escaped, control characters as `\n`, `\r`, `\t` or `\u00XX` (lower-case hex). */
    private fun quoted(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Accepts base64url or standard base64, with or without padding. */
    fun base64UrlDecoded(token: String): ByteArray? {
        val base = token.replace('+', '-').replace('/', '_').replace("=", "")
        if (base.length % 4 == 1) return null
        return runCatching { Base64.getUrlDecoder().decode(base) }.getOrNull()
    }

    // MARK: - Reading

    /**
     * The routine in a pasted text: the `nt1:` line when there is a readable one (names of library
     * exercises are taken from the numbered lines, for a library that lacks the id), else the
     * numbered lines. `null` when neither gives an exercise.
     */
    fun decode(text: String): Shared? {
        val listed = parseLines(text)
        val shared = decodePayload(text) ?: return listed
        if (listed == null || listed.lines.size != shared.lines.size) return shared
        return shared.copy(
            lines = shared.lines.mapIndexed { index, line ->
                if (line.name == null) line.copy(name = listed.lines[index].name) else line
            },
        )
    }

    /** Whether [text] has an `nt1:` line that decodes (the Paste button's check). */
    fun containsPayload(text: String): Boolean = decodePayload(text) != null

    /**
     * The last `nt1:` in [text]: the rest of its line, or, when a messenger wrapped it, every
     * base64 character after it up to the first other one.
     */
    fun decodePayload(text: String): Shared? {
        val start = text.lastIndexOf(MARKER)
        if (start < 0) return null
        val after = text.substring(start + MARKER.length)
        val lineEnd = after.indexOfFirst { it == '\n' || it == '\r' }.let { if (it < 0) after.length else it }
        val line = after.substring(0, lineEnd).trim()
        decodeToken(line)?.let { return it }
        val wrapped = StringBuilder()
        for (c in after) {
            if (c.isWhitespace()) continue
            if (!isBase64Char(c)) break
            wrapped.append(c)
        }
        return if (wrapped.toString() == line) null else decodeToken(wrapped.toString())
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_+/="

    @Serializable
    private data class Payload(val v: Int, val name: String? = null, val items: List<Item>) {
        @Serializable
        data class Item(
            val id: String? = null,
            val name: String? = null,
            val sets: Int? = null,
            val reps: Int? = null,
            val rest: Int? = null,
            val ss: Int? = null,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun decodeToken(token: String): Shared? {
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null
        val bytes = base64UrlDecoded(token) ?: return null
        val payload = runCatching { json.decodeFromString<Payload>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        if (payload.v != FORMAT_VERSION) return null
        val lines = payload.items.mapNotNull { item ->
            val id = item.id?.trim()?.ifEmpty { null }
            val name = item.name?.let(::oneLine)?.ifEmpty { null }
            if (id == null && name == null) return@mapNotNull null
            Line(
                exerciseId = id,
                name = name,
                sets = RoutineDraft.clampSets(item.sets ?: RoutineDraft.DEFAULT_SETS),
                reps = RoutineDraft.clampReps(item.reps ?: RoutineDraft.DEFAULT_REPS),
                restSeconds = snapRest(item.rest ?: RoutineDraft.INHERIT_REST),
                supersetGroup = item.ss?.takeIf { it > 0 },
            )
        }
        return finished(oneLine(payload.name ?: ""), lines)
    }

    /**
     * The numbered lines ("2. Incline Dumbbell Press — 3 × 10, rest 1:30 [Superset A]"), matched
     * by shape, not by words, so any language reads. The first unnumbered line before them is the
     * header; its name is what follows the first ":".
     */
    fun parseLines(text: String): Shared? {
        var name = ""
        val lines = mutableListOf<Line>()
        var sawHeader = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(MARKER)) continue
            val parsed = parseLine(line)
            if (parsed != null) {
                lines += parsed
            } else if (lines.isEmpty() && !sawHeader) {
                sawHeader = true
                val colon = line.indexOf(':')
                name = oneLine(if (colon >= 0) line.substring(colon + 1) else line)
            }
        }
        return finished(name, lines)
    }

    /** "3. Name — 3 × 10, rest 1:30 [Superset A]" → a line; `null` when it is not numbered. */
    fun parseLine(line: String): Line? {
        var i = 0
        while (i < line.length && line[i] in '0'..'9') i += 1
        if (i == 0 || i > 3 || i >= line.length || (line[i] != '.' && line[i] != ')')) return null
        if (i + 1 >= line.length || !line[i + 1].isWhitespace()) return null
        var body = line.substring(i + 1).trim()
        if (body.isEmpty()) return null

        var group: Int? = null
        if (body.endsWith("]")) {
            val open = body.lastIndexOf('[')
            if (open >= 0) {
                val inside = body.substring(open + 1, body.length - 1)
                val token = inside.split(' ').lastOrNull { it.isNotEmpty() } ?: ""
                val letter = token.singleOrNull()?.uppercaseChar()
                if (letter != null && letter in 'A'..'Z') {
                    group = letter - 'A' + 1
                    body = body.substring(0, open).trim()
                }
            }
        }

        var name = body
        var sets = RoutineDraft.DEFAULT_SETS
        var reps = RoutineDraft.DEFAULT_REPS
        var rest = RoutineDraft.INHERIT_REST
        targetsSplit(body)?.let { split ->
            name = split.name
            sets = split.sets
            reps = split.reps
            rest = split.rest
        }
        name = oneLine(name)
        if (name.isEmpty()) return null
        return Line(
            exerciseId = null,
            name = name,
            sets = RoutineDraft.clampSets(sets),
            reps = RoutineDraft.clampReps(reps),
            restSeconds = snapRest(rest),
            supersetGroup = group,
        )
    }

    private data class Targets(val name: String, val sets: Int, val reps: Int, val rest: Int)

    /** The last " — " / " – " / " - " whose tail starts with "sets × reps": the name before it, the targets after. */
    private fun targetsSplit(body: String): Targets? {
        var index = body.length - 2
        while (index >= 1) {
            val c = body[index]
            if ((c == '—' || c == '–' || c == '-') && body[index - 1] == ' ' && body[index + 1] == ' ') {
                val targets = parseTargets(body.substring(index + 2))
                if (targets != null) return targets.copy(name = body.substring(0, index - 1))
            }
            index -= 1
        }
        return null
    }

    /** "3 × 10, rest 1:30" → 3, 10, 90 ("x", "X" or "*" for "×"; the rest is the first m:ss). */
    private fun parseTargets(text: String): Targets? {
        var i = 0
        fun skipSpaces() {
            while (i < text.length && text[i] == ' ') i += 1
        }
        fun number(): Int? {
            val start = i
            while (i < text.length && text[i] in '0'..'9' && i - start < 4) i += 1
            return if (i > start) text.substring(start, i).toInt() else null
        }
        skipSpaces()
        val sets = number() ?: return null
        skipSpaces()
        if (i >= text.length || text[i] !in "×xX*") return null
        i += 1
        skipSpaces()
        val reps = number() ?: return null
        return Targets(name = "", sets = sets, reps = reps, rest = firstClock(text.substring(i)))
    }

    /** Seconds of the first "m:ss" in [text], 0 when there is none. */
    private fun firstClock(text: String): Int {
        for (index in text.indices) {
            if (text[index] != ':') continue
            var start = index
            while (start > 0 && text[start - 1] in '0'..'9' && index - start < 2) start -= 1
            val end = index + 3
            if (index > start && text.length >= end &&
                text[index + 1] in '0'..'9' && text[index + 2] in '0'..'9' &&
                (end == text.length || text[end] !in '0'..'9')
            ) {
                return text.substring(start, index).toInt() * 60 + text.substring(index + 1, end).toInt()
            }
        }
        return 0
    }

    /** The nearest length of the routine rest menu; 0 (the user's default) stays 0. */
    fun snapRest(seconds: Int): Int {
        if (seconds <= 0) return RoutineDraft.INHERIT_REST
        val lengths = RoutineDraft.REST_OPTIONS.filter { it > 0 }
        var best = lengths[0]
        for (length in lengths) if (abs(length - seconds) < abs(best - seconds)) best = length
        return best
    }

    private fun finished(name: String, lines: List<Line>): Shared? {
        if (lines.isEmpty()) return null
        val kept = lines.take(MAX_LINES)
        val groups = Superset.normalized(kept.map { it.supersetGroup })
        return Shared(name, kept.mapIndexed { index, line -> line.copy(supersetGroup = groups[index]) })
    }

    // MARK: - Matching to the library

    /** The user's exercises, looked up by id and by name (English and Polish, [WorkoutImport.matchKeys]). */
    class Catalog {
        data class Entry(val id: String, val name: String, val primaryMuscle: String?)

        private val byId = mutableMapOf<String, Entry>()
        private val byKey = mutableMapOf<String, Entry>()

        /** [names] are what an exercise is matched on; [Entry.name] is what the preview shows. */
        fun add(entry: Entry, names: List<String>) {
            byId.putIfAbsent(entry.id, entry)
            for (name in names) for (key in WorkoutImport.matchKeys(name)) byKey.putIfAbsent(key, entry)
        }

        fun byId(id: String): Entry? = byId[id]

        fun match(name: String): Entry? = WorkoutImport.matchKeys(name).firstNotNullOfOrNull { byKey[it] }
    }

    /**
     * One line of the import preview: a library exercise ([exerciseId]) or a custom exercise "Add
     * routine" creates ([exerciseId] `null`).
     */
    data class Planned(
        val index: Int,
        val exerciseId: String?,
        val name: String,
        val primaryMuscle: String?,
        val sets: Int,
        val reps: Int,
        val restSeconds: Int,
        val supersetGroup: Int?,
    ) {
        val isNew: Boolean get() = exerciseId == null
    }

    /**
     * Matches every line: by id, else by name (as the workout import does); a name the library
     * does not know becomes one new custom exercise (a second line with the same name is dropped,
     * as the editor allows an exercise once). A line with an unknown id and no name is left out.
     */
    fun plan(shared: Shared, catalog: Catalog): List<Planned> {
        val planned = mutableListOf<Planned>()
        val seenIds = mutableSetOf<String>()
        val seenNew = mutableSetOf<String>()
        for (line in shared.lines) {
            var entry = line.exerciseId?.let(catalog::byId)
            if (entry == null && line.name != null) entry = catalog.match(line.name)
            if (entry != null) {
                if (!seenIds.add(entry.id)) continue
                planned += Planned(
                    planned.size, entry.id, entry.name, entry.primaryMuscle,
                    line.sets, line.reps, line.restSeconds, line.supersetGroup,
                )
            } else if (!line.name.isNullOrEmpty()) {
                val key = WorkoutImport.matchKeys(line.name).firstOrNull() ?: line.name.lowercase(Locale.ROOT)
                if (!seenNew.add(key)) continue
                planned += Planned(
                    planned.size, null, line.name, null,
                    line.sets, line.reps, line.restSeconds, line.supersetGroup,
                )
            }
        }
        val groups = Superset.normalized(planned.map { it.supersetGroup })
        return planned.mapIndexed { index, item -> item.copy(supersetGroup = groups[index]) }
    }
}
