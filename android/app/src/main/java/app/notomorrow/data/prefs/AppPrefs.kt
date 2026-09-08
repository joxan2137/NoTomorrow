package app.notomorrow.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.notomorrow.model.AIProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nt_prefs")

/**
 * The `UserDefaults` half of the iOS app, key for key, so the two ports stay legible
 * side by side (`AppState.swift`, `AppConfig.swift`, `SettingsModel.swift`,
 * `AIScanModel.swift`).
 *
 * Reads are `Flow`s (a missing key yields the documented default); writes are `suspend`.
 * Every flow swallows `IOException` and falls back to empty preferences, so a corrupt
 * file degrades to defaults rather than crashing a collector.
 *
 * `nt.rest.endDate` / `nt.rest.total` / `nt.rest.exercise` / `nt.rest.next` /
 * `nt.rest.workout` are **not** here — they belong to `RestTimerPrefs` (`rest/`).
 */
class AppPrefs(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.appPrefsDataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // MARK: - Onboarding

    val hasOnboarded: Flow<Boolean> = data.map { it[Keys.hasOnboarded] ?: false }

    suspend fun hasOnboardedOnce(): Boolean = hasOnboarded.first()

    suspend fun setHasOnboarded(value: Boolean) = put(Keys.hasOnboarded, value)

    // MARK: - Language

    /** `null` = follow the system. */
    val language: Flow<String?> = data.map { it[Keys.language] }

    suspend fun languageOnce(): String? = language.first()

    suspend fun setLanguage(code: String?) {
        store.edit { prefs ->
            if (code.isNullOrBlank()) prefs.remove(Keys.language) else prefs[Keys.language] = code
        }
    }

    // MARK: - AI provider and consent

    val aiProvider: Flow<AIProvider> =
        data.map { AIProvider.from(it[Keys.aiProvider]) ?: AIProvider.Standard }

    suspend fun aiProviderOnce(): AIProvider = aiProvider.first()

    suspend fun setAiProvider(provider: AIProvider) = put(Keys.aiProvider, provider.raw)

    /** One consent per upload destination, asked once (`AIScanModel.Upload.consentKey`). */
    fun aiConsent(target: AiConsentTarget): Flow<Boolean> =
        data.map { it[target.key] ?: false }

    suspend fun aiConsentOnce(target: AiConsentTarget): Boolean = aiConsent(target).first()

    suspend fun setAiConsent(target: AiConsentTarget, granted: Boolean) = put(target.key, granted)

    // MARK: - Backend

    /**
     * Default `https://notomorrow-api.fly.dev`. A stored copy of the pre-backend
     * placeholder is discarded on read, exactly as `AppConfig.init` does.
     */
    val backendBaseUrl: Flow<String> = data.map { prefs ->
        val stored = prefs[Keys.backendBaseUrl]
        if (stored.isNullOrBlank() || stored == LEGACY_BACKEND_URL) DEFAULT_BACKEND_URL else stored
    }

    suspend fun backendBaseUrlOnce(): String = backendBaseUrl.first()

    suspend fun setBackendBaseUrl(url: String) = put(Keys.backendBaseUrl, url)

    /** "Use demo data (offline)" — swaps the whole backend for the in-memory mock. */
    val useMockBackend: Flow<Boolean> = data.map { it[Keys.useMockBackend] ?: false }

    suspend fun useMockBackendOnce(): Boolean = useMockBackend.first()

    suspend fun setUseMockBackend(value: Boolean) = put(Keys.useMockBackend, value)

    /** Mock pairing survives a relaunch (`MockBackendClient`). */
    val mockPaired: Flow<Boolean> = data.map { it[Keys.mockPaired] ?: false }

    suspend fun mockPairedOnce(): Boolean = mockPaired.first()

    suspend fun setMockPaired(value: Boolean) = put(Keys.mockPaired, value)

    // MARK: - Rest timer / workout session

    /** **Default true when absent** (`SettingsModel.init`). */
    val restAutoStart: Flow<Boolean> = data.map { it[Keys.restAutoStart] ?: true }

    suspend fun restAutoStartOnce(): Boolean = restAutoStart.first()

    suspend fun setRestAutoStart(value: Boolean) = put(Keys.restAutoStart, value)

    /** The delete-account wipe removes the key so it returns to its `true` default. */
    suspend fun removeRestAutoStart() {
        store.edit { it.remove(Keys.restAutoStart) }
    }

    /** The active workout's UUID, not an opaque row id. */
    val activeWorkoutId: Flow<String?> = data.map { it[Keys.activeWorkoutId] }

    suspend fun activeWorkoutIdOnce(): String? = activeWorkoutId.first()

    suspend fun setActiveWorkoutId(id: String?) {
        store.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(Keys.activeWorkoutId) else prefs[Keys.activeWorkoutId] = id
        }
    }

    // MARK: - Internals

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    private object Keys {
        val hasOnboarded = booleanPreferencesKey("nt.hasOnboarded")
        val language = stringPreferencesKey("nt.language")
        val aiProvider = stringPreferencesKey("nt.aiProvider")
        val backendBaseUrl = stringPreferencesKey("nt.backendBaseURL")
        val useMockBackend = booleanPreferencesKey("nt.useMockBackend")
        val restAutoStart = booleanPreferencesKey("nt.rest.autoStart")
        val activeWorkoutId = stringPreferencesKey("nt.activeWorkoutId")
        val mockPaired = booleanPreferencesKey("nt.mock.paired")
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://notomorrow-api.fly.dev"

        /** Placeholder from before the backend existed; a stored copy of it is ignored. */
        const val LEGACY_BACKEND_URL = "https://api.notomorrow.app"
    }
}

/**
 * Where an AI photo would go. `nt.aiConsent.google` covers the standard (backend →
 * Gemini) path, `nt.aiConsent.anthropic` the BYOK path; the offline mock uploads
 * nothing and asks nothing, so it has no target.
 */
enum class AiConsentTarget(internal val key: Preferences.Key<Boolean>) {
    Google(booleanPreferencesKey("nt.aiConsent.google")),
    Anthropic(booleanPreferencesKey("nt.aiConsent.anthropic")),
}
