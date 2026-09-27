package app.notomorrow.feature.workout

/**
 * Superset groups over an ordered list of exercises — 1:1 port of `Superset.swift`, as
 * `List<Int?>` (one group id per exercise, `null` = on its own). A superset is a run of
 * neighbouring exercises with the same id; [normalized] keeps the ids consistent after any edit
 * (a lone member leaves its group, ids renumber from 1 in list order).
 */
object Superset {

    fun normalized(groups: List<Int?>): List<Int?> {
        val result = MutableList<Int?>(groups.size) { null }
        var next = 1
        var i = 0
        while (i < groups.size) {
            val id = groups[i]
            if (id == null) {
                i += 1
                continue
            }
            var end = i
            while (end + 1 < groups.size && groups[end + 1] == id) end += 1
            if (end > i) {
                for (j in i..end) result[j] = next
                next += 1
            }
            i = end + 1
        }
        return result
    }

    /** Joins exercise [index] and the one after it (and the rest of that one's superset). */
    fun linkWithNext(groups: List<Int?>, index: Int): List<Int?> {
        if (index !in groups.indices || index + 1 !in groups.indices) return groups
        val result = groups.toMutableList()
        val id = groups[index] ?: groups[index + 1] ?: ((groups.filterNotNull().maxOrNull() ?: 0) + 1)
        val absorbed = groups[index + 1]
        result[index] = id
        var j = index + 1
        while (j < result.size && (j == index + 1 || (absorbed != null && groups[j] == absorbed))) {
            result[j] = id
            j += 1
        }
        return normalized(result)
    }

    /** Takes exercise [index] out of its superset. */
    fun unlink(groups: List<Int?>, index: Int): List<Int?> {
        if (index !in groups.indices) return groups
        val result = groups.toMutableList()
        result[index] = null
        return normalized(result)
    }

    fun isLinkedToNext(groups: List<Int?>, index: Int): Boolean {
        if (index + 1 !in groups.indices) return false
        val id = groups[index] ?: return false
        return groups[index + 1] == id
    }

    /** "A", "B"… per superset in list order, `null` for exercises on their own. */
    fun letters(groups: List<Int?>): List<String?> = normalized(groups).map { id ->
        when {
            id == null -> null
            id in 1..26 -> ('A' + (id - 1)).toString()
            else -> id.toString()
        }
    }
}
