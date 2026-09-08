package app.notomorrow.data.prefs

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.notomorrow.net.dto.Session
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(name = "nt_secure")

/**
 * The Keychain replacement (`AuthStore.KeychainHelper`): the [Session] and the user's
 * own Anthropic / Gemini keys, encrypted with an AES-256-GCM keyset that is itself wrapped by an
 * Android Keystore key (Tink `AndroidKeysetManager`, API 26+), with the ciphertext
 * base64'd into a DataStore file of its own.
 *
 * Rules, ported from iOS:
 * * An **undecryptable blob is a signed-out state, never an error** — a key rotated by
 *   a device restore, a wiped keystore or a corrupt file all read back as `null`, and
 *   the bad record is dropped so it cannot poison the next write.
 * * Nothing here is backed up (`data_extraction_rules.xml` excludes every domain).
 * * The record name is the AEAD associated data, so a session blob cannot be replayed
 *   into the Anthropic slot.
 */
class SecureStore(context: Context) {

    private val app = context.applicationContext
    private val store: DataStore<Preferences> = app.secureDataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Built on first use, on an IO dispatcher; `null` when the platform refuses. */
    @Volatile private var cachedAead: Aead? = null

    // MARK: - Session

    /** Emits the stored session, or `null` when there is none (or it cannot be read). */
    val sessionFlow: Flow<Session?> = data.map { prefs -> decodeSession(prefs[Keys.session]) }

    suspend fun session(): Session? = sessionFlow.first()

    suspend fun setSession(session: Session?) {
        if (session == null) {
            remove(Keys.session)
            return
        }
        write(Keys.session, ACCOUNT_SESSION, json.encodeToString(session))
    }

    // MARK: - Anthropic key (BYOK)

    suspend fun anthropicKey(): String? {
        val raw = data.first()[Keys.anthropicKey] ?: return null
        return decrypt(raw, ACCOUNT_ANTHROPIC_KEY)
    }

    val anthropicKeyFlow: Flow<String?> =
        data.map { prefs -> prefs[Keys.anthropicKey]?.let { decrypt(it, ACCOUNT_ANTHROPIC_KEY) } }

    /** A blank key removes the record, matching `AuthStore.anthropicKey`'s setter. */
    suspend fun setAnthropicKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrEmpty()) {
            remove(Keys.anthropicKey)
            return
        }
        write(Keys.anthropicKey, ACCOUNT_ANTHROPIC_KEY, trimmed)
    }

    // MARK: - Gemini key (BYOK)

    suspend fun geminiKey(): String? {
        val raw = data.first()[Keys.geminiKey] ?: return null
        return decrypt(raw, ACCOUNT_GEMINI_KEY)
    }

    val geminiKeyFlow: Flow<String?> =
        data.map { prefs -> prefs[Keys.geminiKey]?.let { decrypt(it, ACCOUNT_GEMINI_KEY) } }

    /** A blank key removes the record, matching `AuthStore.geminiKey`'s setter. */
    suspend fun setGeminiKey(key: String?) {
        val trimmed = key?.trim()
        if (trimmed.isNullOrEmpty()) {
            remove(Keys.geminiKey)
            return
        }
        write(Keys.geminiKey, ACCOUNT_GEMINI_KEY, trimmed)
    }

    // MARK: - Clearing

    suspend fun clearSession() = remove(Keys.session)

    suspend fun clearAnthropicKey() = remove(Keys.anthropicKey)

    suspend fun clearGeminiKey() = remove(Keys.geminiKey)

    /** Every record — the delete-account path. */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(Keys.session)
            prefs.remove(Keys.anthropicKey)
            prefs.remove(Keys.geminiKey)
        }
    }

    // MARK: - Internals

    private fun decodeSession(raw: String?): Session? {
        val plaintext = raw?.let { decrypt(it, ACCOUNT_SESSION) } ?: return null
        return runCatching { json.decodeFromString<Session>(plaintext) }.getOrNull()
    }

    private suspend fun write(key: Preferences.Key<String>, account: String, plaintext: String) {
        val encrypted = withContext(Dispatchers.IO) { encrypt(plaintext, account) }
        if (encrypted == null) {
            Log.w(TAG, "Secure store unavailable — dropping '$account'")
            remove(key)
            return
        }
        store.edit { it[key] = encrypted }
    }

    private suspend fun remove(key: Preferences.Key<String>) {
        store.edit { it.remove(key) }
    }

    private fun encrypt(plaintext: String, account: String): String? = runCatching {
        val aead = aead() ?: return null
        Base64.encodeToString(
            aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), account.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }.getOrNull()

    private fun decrypt(encoded: String, account: String): String? = runCatching {
        val aead = aead() ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        String(aead.decrypt(bytes, account.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }.getOrNull()

    /**
     * The AEAD primitive. The keyset lives in its own `SharedPreferences` file and is
     * wrapped by a Keystore key; if the Keystore refuses (an emulator without one, a
     * device whose provider is broken) Tink is retried without it, so the app still
     * runs — one level weaker, never a crash.
     */
    private fun aead(): Aead? {
        cachedAead?.let { return it }
        synchronized(this) {
            cachedAead?.let { return it }
            val built = buildAead(useKeystore = true) ?: buildAead(useKeystore = false)
            cachedAead = built
            return built
        }
    }

    private fun buildAead(useKeystore: Boolean): Aead? = runCatching {
        AeadConfig.register()
        val builder = AndroidKeysetManager.Builder()
            .withSharedPref(app, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        if (useKeystore) builder.withMasterKeyUri(MASTER_KEY_URI) else builder.doNotUseKeystore()
        builder.build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }.onFailure {
        Log.w(TAG, "Tink keyset unavailable (keystore=$useKeystore)", it)
    }.getOrNull()

    private object Keys {
        val session = stringPreferencesKey(ACCOUNT_SESSION)
        val anthropicKey = stringPreferencesKey(ACCOUNT_ANTHROPIC_KEY)
        val geminiKey = stringPreferencesKey(ACCOUNT_GEMINI_KEY)
    }

    companion object {
        private const val TAG = "SecureStore"

        /** Same account names as the iOS Keychain items. */
        const val ACCOUNT_SESSION = "session"
        const val ACCOUNT_ANTHROPIC_KEY = "anthropic-key"
        const val ACCOUNT_GEMINI_KEY = "gemini-key"

        private const val KEYSET_NAME = "nt_secure_keyset"
        private const val KEYSET_PREF_FILE = "nt_secure_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://nt_secure_store_master_key"
    }
}
