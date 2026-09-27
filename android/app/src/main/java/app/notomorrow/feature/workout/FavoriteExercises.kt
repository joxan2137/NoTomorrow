package app.notomorrow.feature.workout

import app.notomorrow.data.prefs.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Starred exercises (the picker's long-press "Add to favorites") — port of `FavoriteExercises.swift`:
 * a set of exercise ids in [AppPrefs] (`nt.favoriteExercises`), so the Room schema stays as it
 * is. The delete-account wipe clears it with the rest of the user's data.
 */
object FavoriteExercises {

    /** Where the ids live; [prefs] in the app, [InMemory] in tests. */
    interface Store {
        val ids: Flow<Set<String>>

        /** Replaces the ids with `transform(current)` in one edit and returns the result. */
        suspend fun update(transform: (Set<String>) -> Set<String>): Set<String>

        class InMemory(initial: Set<String> = emptySet()) : Store {
            private val state = MutableStateFlow(initial)
            override val ids: Flow<Set<String>> = state
            val value: Set<String> get() = state.value

            override suspend fun update(transform: (Set<String>) -> Set<String>): Set<String> {
                state.value = transform(state.value)
                return state.value
            }
        }

        companion object {
            fun prefs(prefs: AppPrefs): Store = object : Store {
                override val ids: Flow<Set<String>> = prefs.favoriteExercises
                override suspend fun update(transform: (Set<String>) -> Set<String>): Set<String> =
                    prefs.updateFavoriteExercises(transform)
            }
        }
    }

    /** [ids] with [id] starred, or unstarred when it already was. */
    fun toggled(ids: Set<String>, id: String): Set<String> = if (id in ids) ids - id else ids + id

    /** Stars or unstars [id]; returns the favorites afterwards. */
    suspend fun toggle(store: Store, id: String): Set<String> = store.update { toggled(it, id) }

    /** The picker's results split for display. */
    data class Sections<T>(val favorites: List<T>, val others: List<T>)

    /**
     * With no search text, the favorites among [results] go in their own section on top (in the
     * results' own order, so the muscle and equipment filters still apply) and leave the rest;
     * while searching, everything stays in one list.
     */
    fun <T> sections(
        results: List<T>,
        favorites: Set<String>,
        isSearching: Boolean,
        id: (T) -> String,
    ): Sections<T> {
        if (isSearching || favorites.isEmpty()) return Sections(emptyList(), results)
        val (starred, others) = results.partition { id(it) in favorites }
        return Sections(starred, others)
    }
}
