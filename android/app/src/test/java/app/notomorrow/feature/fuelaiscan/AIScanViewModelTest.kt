package app.notomorrow.feature.fuelaiscan

import android.app.Application
import app.notomorrow.R
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.feature.fuel.AIScanPhase
import app.notomorrow.feature.fuel.AIScanViewModel
import app.notomorrow.feature.fuelhome.FakeFoodDao
import app.notomorrow.feature.fuelhome.FakeMealDao
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.AIFood
import app.notomorrow.net.dto.AIPer100
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.service.AIEstimateError
import app.notomorrow.service.AIEstimateProviders
import app.notomorrow.service.AIEstimateService
import app.notomorrow.service.FoodSearchService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * `AIScanModel`'s request flow (`AIScanCorrectionTests.swift`, the model half): a refine sends the
 * corrections line and keeps the user's fixes, a failed refine keeps the result, and each first
 * failure lands on its own message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIScanViewModelTest {

    private lateinit var main: TestDispatcher

    @Before
    fun setUp() {
        main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pierogi() = AIFood.mock(
        "Pierogi ruskie", AIPer100(200.0, 6.5, 30.0, 6.0), count = 6.0, unit = "szt.", perUnit = 35.0, confidence = 0.65,
    )

    private fun oil() = AIFood.mock(
        "Olej", AIPer100(884.0, 0.0, 0.0, 100.0), count = 1.0, unit = "łyżka", perUnit = 10.0, confidence = 0.3,
        isGuess = true,
    )

    /** A model that went through the real intake (photo → first answer) and shows its outcome. */
    private fun analysed(vararg answers: () -> AIEstimate): Pair<AIScanViewModel, StubEstimateService> {
        val service = StubEstimateService(answers.toList())
        val foods = FakeFoodDao()
        val model = AIScanViewModel(
            application = Application(),
            prefs = mockk<AppPrefs>(),
            providers = mockk<AIEstimateProviders>(),
            mealDao = FakeMealDao(foods),
            foodDao = foods,
            foodSearch = FoodSearchService(engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }),
            needsSignIn = MutableStateFlow(false),
            initialMeal = MealSlot.Dinner,
            locale = { Locale.forLanguageTag("pl-PL") },
            io = main,
            cpu = main,
            service = service,
        )
        model.capturedPhoto(byteArrayOf(1, 2, 3))
        return model to service
    }

    @Test
    fun `a refine keeps the user's corrections`() {
        val refined = AIEstimate(
            foods = listOf(
                AIFood.mock("Pierogi ruskie", AIPer100(210.0, 7.0, 30.0, 7.0), count = 6.0, unit = "szt.", perUnit = 36.0, confidence = 0.6),
                AIFood.mock("Olej", AIPer100(884.0, 0.0, 0.0, 100.0), count = 1.0, unit = "łyżka", perUnit = 10.0, confidence = 0.3),
                AIFood.mock("Śmietana", AIPer100(185.0, 2.5, 3.6, 18.0), count = 1.0, unit = "łyżka", perUnit = 15.0, confidence = 0.4),
            ),
            overallConfidence = 0.6,
        )
        val (model, service) = analysed(
            answer(AIEstimate(listOf(pierogi(), oil()), overallConfidence = 0.6)),
            answer(refined),
        )
        assertEquals(AIScanPhase.Result, model.state.value.phase)
        val p = model.state.value.foods[0]
        val o = model.state.value.foods[1]
        model.setGrams(p.id, 280.0)
        model.remove(o.id)

        model.analyze()

        val state = model.state.value
        assertEquals(AIScanPhase.Result, state.phase)
        assertEquals(listOf("Pierogi ruskie", "Śmietana"), state.foods.map { it.name })
        assertEquals("the corrected grams survive the refine", 280.0, state.foods[0].grams, 0.0)
        assertEquals(p.id, state.foods[0].id)
        assertEquals(
            listOf("", "User corrections (authoritative): Pierogi ruskie = 280 g (6 szt.); removed: Olej"),
            service.notes,
        )
    }

    @Test
    fun `typed details go with the first request and lead the refine`() {
        val (model, service) = analysed(
            answer(AIEstimate(listOf(pierogi(), oil()), overallConfidence = 0.6)),
            answer(AIEstimate(listOf(pierogi()), overallConfidence = 0.6)),
        )
        model.remove(model.state.value.foods[1].id)
        model.setNotes("  z okrasą ")
        model.analyze()
        assertEquals("z okrasą\nUser corrections (authoritative): removed: Olej", service.notes[1])
    }

    @Test
    fun `a failed refine keeps the result and says why`() {
        val (model, service) = analysed(
            answer(AIEstimate(listOf(pierogi()), overallConfidence = 0.6)),
            failure(AIEstimateError.Busy),
        )
        val before = model.state.value.foods
        model.analyze()
        assertEquals(2, service.notes.size)
        assertEquals(AIScanPhase.Result, model.state.value.phase)
        assertEquals(before, model.state.value.foods)
        assertEquals(R.string.fuel_ai_error_busy, model.state.value.toast)
    }

    @Test
    fun `a failed refine's toast stays up long enough to read`() {
        val (model, _) = analysed(
            answer(AIEstimate(listOf(pierogi()), overallConfidence = 0.6)),
            failure(AIEstimateError.Busy),
        )
        model.analyze()
        assertTrue(AIScanViewModel.TOAST_MILLIS >= 5_000)
        main.scheduler.advanceTimeBy(AIScanViewModel.TOAST_MILLIS - 1)
        main.scheduler.runCurrent()
        assertEquals(R.string.fuel_ai_error_busy, model.state.value.toast)
        main.scheduler.advanceTimeBy(1)
        main.scheduler.runCurrent()
        assertNull(model.state.value.toast)
    }

    @Test
    fun `with every item removed there is nothing to log or recalculate`() = runTest {
        val (model, _) = analysed(answer(AIEstimate(listOf(pierogi(), oil()), overallConfidence = 0.6)))
        assertTrue(model.state.value.hasItems)
        model.state.value.foods.map { it.id }.forEach(model::remove)
        assertFalse(model.state.value.hasItems)
        assertEquals("Log writes nothing", 0, model.log(LocalDate.of(2026, 9, 23)))
    }

    @Test
    fun `first analysis errors are distinct`() {
        val (timeout, _) = analysed(failure(AIEstimateError.Timeout))
        assertEquals(AIScanPhase.Failed(R.string.fuel_ai_error_timeout), timeout.state.value.phase)
        val (offline, _) = analysed(failure(AIEstimateError.Offline))
        assertEquals(AIScanPhase.Failed(R.string.error_network), offline.state.value.phase)
        val (provider, _) = analysed(failure(AIEstimateError.ProviderError(502, "ai_upstream_error")))
        assertEquals(AIScanPhase.Failed(R.string.fuel_ai_error_provider), provider.state.value.phase)
        assertNull(provider.state.value.toast)
    }

    @Test
    fun `not allowed shows its screen`() {
        val (model, _) = analysed(failure(AIEstimateError.NotAllowed))
        assertEquals(AIScanPhase.NotAllowed, model.state.value.phase)
    }

    @Test
    fun `an empty answer is no food`() {
        val (model, _) = analysed(answer(AIEstimate(emptyList(), overallConfidence = 0.0)))
        assertEquals(AIScanPhase.Failed(R.string.fuel_ai_failed), model.state.value.phase)
    }

    @Test
    fun `retake forgets the corrections`() {
        val (model, service) = analysed(
            answer(AIEstimate(listOf(pierogi(), oil()), overallConfidence = 0.6)),
            answer(AIEstimate(listOf(pierogi()), overallConfidence = 0.6)),
        )
        model.remove(model.state.value.foods[1].id)
        model.retake()
        assertEquals(AIScanPhase.PickSource, model.state.value.phase)
        assertNull(model.state.value.edits.correctionsLine)
        model.capturedPhoto(byteArrayOf(4))
        assertEquals("a new photo is a first request", "", service.notes[1])
    }
}

private fun answer(estimate: AIEstimate): () -> AIEstimate = { estimate }

private fun failure(error: AIEstimateError): () -> AIEstimate = { throw error }

/** Answers from a queue and records the notes each request sent. */
private class StubEstimateService(answers: List<() -> AIEstimate>) : AIEstimateService {
    private val queue = ArrayDeque(answers)
    val notes = mutableListOf<String>()

    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate {
        this.notes += notes
        return (queue.removeFirstOrNull() ?: throw AIEstimateError.Busy)()
    }

    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading = throw AIEstimateError.Busy
}
