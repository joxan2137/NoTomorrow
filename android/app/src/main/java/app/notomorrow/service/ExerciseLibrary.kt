package app.notomorrow.service

import android.content.Context
import androidx.annotation.StringRes
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Loads the bundled free-exercise-db (The Unlicense) plus the app's own `nt_` additions
 * (989 exercises) into Room — 1:1 port of `NoTomorrow/Services/ExerciseLibrary.swift`.
 *
 * Polish names come from `assets/exercises_pl.json` (id → name). The import runs once per
 * bundled library version: [LIBRARY_VERSION] is stamped in `nt.exerciseLibrary.version` after a
 * successful import, and a later launch skips the 1 MB parse unless the stamp differs or the
 * library has no rows (a new or emptied database). A run inserts what is missing and
 * **backfills** Polish names added by a later build. Any parse failure aborts silently — a missing
 * library is an empty picker, never a crash — and leaves the stamp alone, so the next launch
 * tries again.
 *
 * It also owns the picker's derivations (search folding, muscle-group filter, custom
 * exercises), which the iOS picker view model reaches for through `ExerciseLibrary`
 * and `WorkoutStrings`.
 */
class ExerciseLibrary(
    private val dao: ExerciseDao,
    private val stamp: VersionStamp,
    /** Reads both bundled files, off the main thread; `null` when the library cannot be parsed. */
    private val load: suspend () -> Bundled?,
) {

    constructor(context: Context, dao: ExerciseDao, stamp: VersionStamp) :
        this(dao, stamp, assetLoader(context.applicationContext))

    private val lock = Mutex()
    private var didRun = false

    /** One record of `assets/exercises.json`. `images` is parsed and discarded. */
    @Serializable
    data class Record(
        val id: String,
        val name: String,
        val force: String? = null,
        val level: String? = null,
        val mechanic: String? = null,
        val equipment: String? = null,
        val primaryMuscles: List<String> = emptyList(),
        val secondaryMuscles: List<String> = emptyList(),
        val instructions: List<String> = emptyList(),
        val category: String = "strength",
        @SerialName("images") val images: List<String>? = null,
    )

    /** The two bundled files, decoded: the records and the Polish names by id. */
    class Bundled(val records: List<Record>, val polish: Map<String, String>)

    /**
     * The imported library version — `nt.exerciseLibrary.version` in the app ([prefs]),
     * [InMemory] in tests.
     */
    interface VersionStamp {
        suspend fun version(): Int?
        suspend fun setVersion(version: Int)

        class InMemory(var value: Int? = null) : VersionStamp {
            override suspend fun version(): Int? = value
            override suspend fun setVersion(version: Int) {
                value = version
            }
        }

        companion object {
            fun prefs(prefs: AppPrefs): VersionStamp = object : VersionStamp {
                override suspend fun version(): Int? = prefs.exerciseLibraryVersionOnce()
                override suspend fun setVersion(version: Int) = prefs.setExerciseLibraryVersion(version)
            }
        }
    }

    /**
     * `importIfNeeded(into:defaults:load:)`. Checks the stamp at most once per process; safe to
     * call from every screen that needs the library. The stamp is written only after every row
     * landed.
     */
    suspend fun importIfNeeded() {
        lock.withLock {
            if (didRun) return
            if (!needsImport(stamp.version(), dao.libraryCount())) {
                didRun = true
                return
            }
            val bundled = load() ?: return
            // IGNORE preserves custom rows, history and recent-use ordering on upgrades.
            dao.insertAllIgnoring(bundled.records.map { it.toEntity(bundled.polish[it.id]) })
            for (row in dao.missingPolishNames(BACKFILL_LIMIT)) {
                bundled.polish[row.id]?.let { dao.updatePolishName(row.id, it) }
            }
            stamp.setVersion(LIBRARY_VERSION)
            didRun = true
        }
    }

    // MARK: - Custom exercises

    /**
     * Inserts a custom exercise named [name], tagged with one representative muscle for
     * [group] — the picker's "Create «…»" row. `lastUsedAt` is stamped so it sorts to the
     * top immediately.
     */
    suspend fun createCustom(
        name: String,
        group: MuscleGroup = MuscleGroup.All,
        now: Long = System.currentTimeMillis(),
    ): ExerciseEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val exercise = ExerciseEntity(
            id = "custom-" + UUID.randomUUID().toString().lowercase(Locale.ROOT),
            name = trimmed,
            primaryMuscles = group.representativeMuscle?.let { listOf(it) } ?: emptyList(),
            isCustom = true,
            lastUsedAt = now,
        )
        dao.upsert(exercise)
        return exercise
    }

    /** Muscle-group filter chips, mapped onto free-exercise-db `primaryMuscles` values. */
    enum class MuscleGroup(val raw: String) {
        All("all"),
        Chest("chest"),
        Back("back"),
        Legs("legs"),
        Shoulders("shoulders"),
        Arms("arms"),
        Core("core");

        /** Empty for [All] — "no filter", not "no muscles". `neck` is deliberately in no chip. */
        val muscles: Set<String>
            get() = when (this) {
                All -> emptySet()
                Chest -> setOf("chest")
                Back -> setOf("lats", "middle back", "lower back", "traps")
                Legs -> setOf("quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors")
                Shoulders -> setOf("shoulders")
                Arms -> setOf("biceps", "triceps", "forearms")
                Core -> setOf("abdominals")
            }

        /** `"muscle.\(rawValue)"` — the chip title. */
        @get:StringRes
        val titleRes: Int
            get() = when (this) {
                All -> S.muscle_all
                Chest -> S.muscle_chest
                Back -> S.muscle_back
                Legs -> S.muscle_legs
                Shoulders -> S.muscle_shoulders
                Arms -> S.muscle_arms
                Core -> S.muscle_core
            }

        /** One free-exercise-db muscle to tag a custom exercise created under this chip. */
        val representativeMuscle: String?
            get() = when (this) {
                All -> null
                Chest -> "chest"
                Back -> "lats"
                Legs -> "quadriceps"
                Shoulders -> "shoulders"
                Arms -> "biceps"
                Core -> "abdominals"
            }

        companion object {
            fun from(raw: String?): MuscleGroup? = entries.firstOrNull { it.raw == raw }
        }
    }

    companion object {
        const val EXERCISES_ASSET = "exercises.json"
        const val EXERCISES_PL_ASSET = "exercises_pl.json"
        const val BACKFILL_LIMIT = 2000

        /**
         * The bundled library's version. **Bump it whenever `exercises.json` or
         * `exercises_pl.json` changes**, or existing installs never see the new rows or names.
         * Kept equal to iOS's `ExerciseLibrary.libraryVersion`.
         */
        const val LIBRARY_VERSION = 2

        /**
         * `needsImport(storedVersion:libraryRows:)` — import when the stamp is not this build's
         * version, or when the library (non-custom) table is empty whatever the stamp says.
         */
        fun needsImport(storedVersion: Int?, libraryRows: Int): Boolean =
            storedVersion != LIBRARY_VERSION || libraryRows == 0

        /** Both assets, parsed off the main thread; `null` when `exercises.json` cannot be read. */
        private fun assetLoader(context: Context): suspend () -> Bundled? = {
            withContext(Dispatchers.IO) {
                val records = runCatching {
                    json.decodeFromString<List<Record>>(readAsset(context, EXERCISES_ASSET))
                }.getOrNull()
                if (records == null) {
                    null
                } else {
                    val polish = runCatching {
                        json.decodeFromString<Map<String, String>>(readAsset(context, EXERCISES_PL_ASSET))
                    }.getOrElse { emptyMap() }
                    Bundled(records, polish)
                }
            }
        }

        private fun readAsset(context: Context, name: String): String =
            context.assets.open(name).bufferedReader().use { it.readText() }

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * `WorkoutStrings.fold` — case-, diacritic- and width-insensitive folding, so
         * "przysiad" matches "Przysiad" and "ławka" matches "lawka".
         *
         * Folding is locale-independent on purpose ([Locale.ROOT]): the Turkish dotless
         * ı would otherwise break matching for a user whose phone is set to `tr`.
         */
        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                .replace(DIACRITICS, "")
                // NFKD decomposes ą ć ę ń ó ś ź ż but not the stroked ł, which has no
                // decomposition (Foundation's diacriticInsensitive keeps it too, so iOS replaces it
                // the same way) — "lawka" has to find "ławka". Ł is already lower-cased above.
                .replace('ł', 'l')

        /**
         * The picker's ordering: recently used first, then alphabetical
         * (`ExercisePickerViewModel.load`).
         */
        fun sorted(exercises: List<ExerciseEntity>): List<ExerciseEntity> =
            exercises.sortedWith(
                compareByDescending<ExerciseEntity> { it.lastUsedAt ?: Long.MIN_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )

        /**
         * The picker's filter: every whitespace-separated token of [query] must appear in
         * the folded "English + Polish" name, and — unless the chip is
         * [MuscleGroup.All] — one `primaryMuscles` value must be in the chip's set.
         */
        fun filter(
            exercises: List<ExerciseEntity>,
            query: String,
            group: MuscleGroup = MuscleGroup.All,
            /** One muscle picked on the body map; replaces [group] when set. */
            muscle: String? = null,
        ): List<ExerciseEntity> {
            val tokens = fold(query.trim()).split(' ').filter { it.isNotEmpty() }
            val muscles = muscle?.let { setOf(it) } ?: group.muscles
            return exercises.filter { exercise ->
                if (muscles.isNotEmpty() && exercise.primaryMuscles.none { it in muscles }) return@filter false
                val folded = fold(exercise.name + " " + (exercise.namePL ?: ""))
                tokens.all { folded.contains(it) }
            }
        }

        private val DIACRITICS = "\\p{Mn}+".toRegex()
    }
}

/**
 * `Exercise.localizedName` — the Polish name under a Polish app locale, the English one
 * otherwise. Reads the **app** locale (`AppCompatDelegate.getApplicationLocales()`), so
 * the in-app language override applies without a relaunch; iOS reads the system locale.
 */
fun ExerciseEntity.localizedName(locale: Locale = LocaleProvider.current()): String {
    val pl = namePL
    return if (locale.language == "pl" && !pl.isNullOrEmpty()) pl else name
}

/** `Record` → the persisted row. */
private fun ExerciseLibrary.Record.toEntity(namePL: String?): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    namePL = namePL,
    primaryMuscles = primaryMuscles,
    secondaryMuscles = secondaryMuscles,
    equipment = equipment,
    category = category,
    force = force,
    mechanic = mechanic,
    level = level,
    instructions = instructions,
)
