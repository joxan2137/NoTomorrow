package app.notomorrow.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persisted rest-timer truth — the port of `RestTimerController.Keys` in
 * `NoTomorrow/Services/RestTimerController.swift` (iOS keeps it in
 * `UserDefaults`).
 *
 * The only state that matters is the **absolute** `endAt`; everything else is
 * label material for the notification and the sheet. Survives process death,
 * so a reboot receiver or an alarm broadcast landing in a fresh process can
 * reconstruct the timer without the UI ever having run.
 *
 * Its own DataStore file (`nt_rest`), separate from `AppPrefs`, so the two can
 * be written from different processes/waves without contention.
 */
private val Context.restTimerDataStore: DataStore<Preferences> by preferencesDataStore(name = "nt_rest")

/** Immutable snapshot of the persisted rest timer. */
data class RestTimerRecord(
    /** Epoch millis when the rest ends, or `null` when no rest is pending. */
    val endAt: Long? = null,
    val totalSeconds: Int = DEFAULT_TOTAL_SECONDS,
    val exerciseName: String = "",
    val nextSetLabel: String = "",
    val workoutName: String = "",
) {
    companion object {
        /** iOS defaults `totalSeconds` to 90. */
        const val DEFAULT_TOTAL_SECONDS: Int = 90
    }
}

class RestTimerPrefs(context: Context) {

    private val store = context.applicationContext.restTimerDataStore

    /** Keys are the iOS `UserDefaults` keys verbatim, so the two ports stay legible together. */
    private object Keys {
        val end = longPreferencesKey("nt.rest.endDate")
        val total = intPreferencesKey("nt.rest.total")
        val exercise = stringPreferencesKey("nt.rest.exercise")
        val next = stringPreferencesKey("nt.rest.next")
        val workout = stringPreferencesKey("nt.rest.workout")
    }

    val record: Flow<RestTimerRecord> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            RestTimerRecord(
                endAt = prefs[Keys.end],
                totalSeconds = prefs[Keys.total]?.coerceAtLeast(5)
                    ?: RestTimerRecord.DEFAULT_TOTAL_SECONDS,
                exerciseName = prefs[Keys.exercise].orEmpty(),
                nextSetLabel = prefs[Keys.next].orEmpty(),
                workoutName = prefs[Keys.workout].orEmpty(),
            )
        }

    /** One-shot read — the `FetchDescriptor` analogue used by receivers. */
    suspend fun read(): RestTimerRecord = record.first()

    suspend fun write(record: RestTimerRecord) {
        store.edit { prefs ->
            val endAt = record.endAt
            if (endAt == null) prefs.remove(Keys.end) else prefs[Keys.end] = endAt
            prefs[Keys.total] = record.totalSeconds
            prefs[Keys.exercise] = record.exerciseName
            prefs[Keys.next] = record.nextSetLabel
            prefs[Keys.workout] = record.workoutName
        }
    }

    /** Clears only the end date; the labels stay so the end alert can still name the set. */
    suspend fun clearEnd() {
        store.edit { prefs -> prefs.remove(Keys.end) }
    }
}
