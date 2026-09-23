package app.notomorrow.feature.fuelaiscan

import app.notomorrow.R
import app.notomorrow.data.prefs.AiConsentTarget
import app.notomorrow.feature.fuel.LabelPhotoReader
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIEstimate
import app.notomorrow.net.dto.LabelReading
import app.notomorrow.service.AIEstimateError
import app.notomorrow.service.AIEstimateService
import app.notomorrow.service.AIUpload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Photograph the label" (`LabelPhotoReader.swift`): the same one-time consent as the photo
 * estimate, the reading handed to the form, and what the caption says afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabelPhotoReaderTest {

    private val serek = LabelReading(
        legible = true, energyFrom = "kcal", name = "Serek wiejski", brand = "",
        per100 = LabelReading.Per100(kcal = 97.0, protein = 11.0, carbs = 2.0, fat = 5.0),
        servingSizeG = 200.0, confidence = 0.9,
    )

    private class Harness(
        scope: TestScope,
        upload: AIUpload,
        private val answer: () -> LabelReading,
        consented: Boolean,
    ) {
        val consents = mutableListOf<AiConsentTarget>()
        val languages = mutableListOf<String>()
        var readings = 0
        var sent = 0
        private var granted = consented

        val reader = LabelPhotoReader(
            scope = scope,
            upload = { upload },
            service = { _ ->
                object : AIEstimateService {
                    override suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String): AIEstimate =
                        throw AIEstimateError.Busy

                    override suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading {
                        sent++
                        languages += locale
                        return answer()
                    }
                }
            },
            consentOnce = { granted },
            recordConsent = {
                consents += it
                granted = true
            },
            language = { "pl" },
        )

        fun read(photo: ByteArray? = byteArrayOf(1)) = reader.read({ photo }) { readings++ }
    }

    @Test
    fun `a first read asks for consent and reads once accepted`() = runTest {
        val harness = Harness(this, AIUpload.Google, { serek }, consented = false)
        harness.read()
        advanceUntilIdle()
        assertEquals(AIUpload.Google, harness.reader.state.value.consent)
        assertEquals(0, harness.sent)

        // NtAlert runs its dismissal before the tapped button.
        harness.reader.dismissConsent()
        harness.reader.acceptConsent()
        advanceUntilIdle()

        assertNull(harness.reader.state.value.consent)
        assertEquals(listOf(AiConsentTarget.Google), harness.consents)
        assertEquals(listOf("pl"), harness.languages)
        assertEquals(1, harness.readings)
        assertEquals(LabelPhotoReader.Status.Filled(needsReview = false), harness.reader.state.value.status)
    }

    @Test
    fun `dismissing the consent sends nothing`() = runTest {
        val harness = Harness(this, AIUpload.Anthropic, { serek }, consented = false)
        harness.read()
        advanceUntilIdle()
        harness.reader.dismissConsent()
        advanceUntilIdle()
        assertEquals(0, harness.sent)
        assertEquals(LabelPhotoReader.Status.Idle, harness.reader.state.value.status)
        assertTrue(harness.consents.isEmpty())
    }

    @Test
    fun `the demo backend reads without consent and flags a reading to check`() = runTest {
        val harness = Harness(this, AIUpload.None, { serek.copy(needsReview = true) }, consented = false)
        harness.read()
        advanceUntilIdle()
        assertEquals(1, harness.sent)
        assertEquals(LabelPhotoReader.Status.Filled(needsReview = true), harness.reader.state.value.status)
    }

    @Test
    fun `an illegible label is an answer, not an error`() = runTest {
        val harness = Harness(this, AIUpload.Google, { LabelReading(legible = false, name = "Serek") }, consented = true)
        harness.read()
        advanceUntilIdle()
        assertEquals("the name can still fill in", 1, harness.readings)
        assertEquals(LabelPhotoReader.Status.Unreadable, harness.reader.state.value.status)
    }

    @Test
    fun `failures say why, in the label's words`() = runTest {
        val unreadable = Harness(this, AIUpload.Google, { throw AIEstimateError.Unreadable }, consented = true)
        unreadable.read()
        advanceUntilIdle()
        assertEquals(LabelPhotoReader.Status.Failed(R.string.fuel_label_unreadable), unreadable.reader.state.value.status)
        assertEquals(0, unreadable.readings)

        val offline = Harness(this, AIUpload.Google, { throw AIEstimateError.Offline }, consented = true)
        offline.read()
        advanceUntilIdle()
        assertEquals(LabelPhotoReader.Status.Failed(R.string.error_network), offline.reader.state.value.status)

        val timeout = Harness(this, AIUpload.Gemini, { throw AIEstimateError.Timeout }, consented = true)
        timeout.read()
        advanceUntilIdle()
        assertEquals(LabelPhotoReader.Status.Failed(R.string.fuel_ai_error_timeout), timeout.reader.state.value.status)
    }

    @Test
    fun `a photo that cannot be prepared fails without a request`() = runTest {
        val harness = Harness(this, AIUpload.Google, { serek }, consented = true)
        harness.read(photo = null)
        advanceUntilIdle()
        assertEquals(0, harness.sent)
        assertEquals(LabelPhotoReader.Status.Failed(R.string.fuel_label_unreadable), harness.reader.state.value.status)
    }
}
