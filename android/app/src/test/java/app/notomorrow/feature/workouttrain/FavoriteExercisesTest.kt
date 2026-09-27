package app.notomorrow.feature.workouttrain

import app.notomorrow.feature.workout.FavoriteExercises
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Starred exercises (`FavoriteExercisesTests.swift`): the id set and the picker's Favorites section. */
class FavoriteExercisesTest {

    @Test
    fun `toggle stars and unstars`() = runTest {
        val store = FavoriteExercises.Store.InMemory()
        assertEquals(setOf("bench"), FavoriteExercises.toggle(store, "bench"))
        FavoriteExercises.toggle(store, "squat")
        assertEquals(setOf("bench", "squat"), store.ids.first())
        assertEquals(setOf("squat"), FavoriteExercises.toggle(store, "bench"))
        FavoriteExercises.toggle(store, "squat")
        assertEquals(emptySet(), store.value)
    }

    @Test
    fun `toggled is pure`() {
        val ids = setOf("a")
        assertEquals(setOf("a", "b"), FavoriteExercises.toggled(ids, "b"))
        assertEquals(emptySet(), FavoriteExercises.toggled(ids, "a"))
        assertEquals(setOf("a"), ids)
    }

    @Test
    fun `favorites go on top in result order without search`() {
        val sections = FavoriteExercises.sections(listOf("a", "b", "c", "d"), setOf("d", "b", "zz"), isSearching = false) { it }
        assertEquals(listOf("b", "d"), sections.favorites, "results order; a favorite filtered out stays out")
        assertEquals(listOf("a", "c"), sections.others, "no row shows twice")
    }

    @Test
    fun `searching keeps one list`() {
        val sections = FavoriteExercises.sections(listOf("a", "b"), setOf("b"), isSearching = true) { it }
        assertEquals(emptyList(), sections.favorites)
        assertEquals(listOf("a", "b"), sections.others)
    }

    @Test
    fun `no favorites keeps one list`() {
        val sections = FavoriteExercises.sections(listOf("a", "b"), emptySet(), isSearching = false) { it }
        assertEquals(emptyList(), sections.favorites)
        assertEquals(listOf("a", "b"), sections.others)
    }
}
