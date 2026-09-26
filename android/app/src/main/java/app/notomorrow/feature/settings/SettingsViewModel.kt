package app.notomorrow.feature.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.app.AppState
import app.notomorrow.data.db.NoTomorrowDatabase
import app.notomorrow.data.db.wipeUserData
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.di.AppContainer
import app.notomorrow.model.AIProvider
import app.notomorrow.model.BodyWeightSource
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import app.notomorrow.push.PushRegistrar
import app.notomorrow.service.AppConfig
import app.notomorrow.service.AuthStore
import app.notomorrow.service.BroService
import app.notomorrow.service.Days
import app.notomorrow.service.HealthService
import app.notomorrow.service.TargetCalculator
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.service.toDto
import app.notomorrow.util.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Everything Settings needs that is not a Room row: the secure-store session, Health Connect,
 * the bro service, the rest-timer auto-start flag, the language override, export and account
 * deletion — the port of `SettingsModel` (`Features/Settings/SettingsModel.swift`).
 *
 * One immutable [SettingsUiState] for the whole sheet: the root and all twelve editors read the
 * same state object, exactly as the Swift editors all bind to the one `@Bindable` model.
 */
class SettingsViewModel(
    private val app: Application,
    private val db: NoTomorrowDatabase,
    private val prefs: AppPrefs,
    private val authStore: AuthStore,
    private val appConfig: AppConfig,
    private val bro: BroService,
    private val health: HealthService,
    private val appState: AppState,
    private val pushRegistrar: PushRegistrar,
    /** Deleting the account lets go of the workout in progress (`session.end()` on iOS). */
    private val workoutSession: WorkoutSessionController,
    /** `restTimer.skip()`: no alarm or rest notification outlives the account. */
    private val stopRestTimer: () -> Unit,
) : ViewModel() {

    private val profileDao = db.profileDao()
    private val scheduleDao = db.scheduleDao()

    private val busy = MutableStateFlow(BusyState())
    private val healthState = MutableStateFlow(HealthState())
    private val exportState = MutableStateFlow(ExportState())

    private val core = combine(
        profileDao.observeProfile(),
        scheduleDao.observeSchedule(),
        db.broPairingDao().observePairing(),
    ) { profile, schedule, pairing -> Triple(profile, schedule, pairing) }

    // `combine` tops out at five flows, so the two BYOK keys travel as one pair.
    private val byokKeys = combine(authStore.maskedAnthropicKey, authStore.maskedGeminiKey, ::Pair)

    private val account = combine(
        authStore.isSignedIn,
        byokKeys,
        bro.me,
        bro.partner,
        bro.myCode,
    ) { signedIn, keys, me, partner, code ->
        AccountState(
            isSignedIn = signedIn,
            maskedAnthropicKey = keys.first,
            maskedGeminiKey = keys.second,
            accountName = me?.let { it.username.ifEmpty { it.displayName } },
            partnerName = partner?.name,
            pairedAt = partner?.pairedAt?.toEpochMilli(),
            myCode = code,
        )
    }

    private val config = combine(
        appConfig.aiProvider,
        appConfig.useMockBackend,
        prefs.restAutoStart,
        appState.languageOverride,
        busy,
    ) { provider, mock, autoStart, language, busyState ->
        ConfigState(provider, mock, autoStart, language, busyState)
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(core, account, config, healthState, exportState) { c, a, cfg, h, export ->
            SettingsUiState(
                profile = c.first,
                schedule = c.second,
                pairing = c.third,
                isSignedIn = a.isSignedIn,
                accountName = a.accountName,
                maskedAnthropicKey = a.maskedAnthropicKey,
                maskedGeminiKey = a.maskedGeminiKey,
                partnerName = a.partnerName ?: c.third?.partnerName,
                pairedAt = a.pairedAt ?: c.third?.pairedAt,
                myCode = a.myCode ?: c.third?.myCode,
                aiProvider = cfg.aiProvider,
                useDemoData = cfg.useDemoData,
                restAutoStart = cfg.restAutoStart,
                languageOverride = cfg.languageOverride,
                isDeleting = cfg.busy.isDeleting,
                isUnpairing = cfg.busy.isUnpairing,
                isSigningOut = cfg.busy.isSigningOut,
                health = h,
                export = export,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // MARK: - Lifecycle

    /** `SettingsView.task` — pull who we are paired with (and our code) when the sheet opens. */
    fun loadBro() {
        viewModelScope.launch {
            bro.refresh()
            if (bro.myCode.value == null) bro.createCode()
        }
    }

    // MARK: - You

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val profile = ensureProfile()
            if (profile.name == trimmed) return@launch
            profileDao.upsert(profile.copy(name = trimmed))
        }
    }

    /**
     * `BodyWeightEditor.save` — the profile weight **and** today's `BodyWeightEntry`, which is a
     * day-keyed upsert (a second log on the same day replaces the first).
     */
    fun saveBodyWeight(kg: Double) {
        if (kg <= 0) return
        viewModelScope.launch {
            profileDao.upsert(ensureProfile().copy(bodyWeightKg = kg))
            db.bodyWeightDao().upsert(
                BodyWeightEntryEntity(
                    day = Days.millis(LocalDate.now()),
                    kg = kg,
                    source = BodyWeightSource.Manual,
                )
            )
        }
    }

    fun setGoal(goal: TrainingGoal) {
        viewModelScope.launch {
            val profile = ensureProfile()
            if (profile.goal == goal) return@launch
            profileDao.upsert(profile.copy(goal = goal))
        }
    }

    /**
     * `DailyTargetEditor.suggest()` — the four numbers `TargetCalculator` derives from the stored
     * body weight and goal. The editor only copies them into its drafts; nothing is written until
     * the user hits Save, exactly as on iOS.
     */
    fun suggestTargets(): TargetCalculator.Targets {
        val state = uiState.value
        return TargetCalculator.targets(state.profile?.bodyWeightKg, state.goal)
    }

    fun saveTargets(kcal: Int, protein: Int, carbs: Int, fat: Int) {
        viewModelScope.launch {
            profileDao.upsert(
                ensureProfile().copy(
                    calorieGoal = kcal,
                    proteinGoalG = protein,
                    carbsGoalG = carbs,
                    fatGoalG = fat,
                )
            )
        }
    }

    // MARK: - Training

    fun setUnits(units: WeightUnit) {
        viewModelScope.launch {
            val profile = ensureProfile()
            if (profile.units == units) return@launch
            profileDao.upsert(profile.copy(units = units))
        }
    }

    fun setRestSeconds(seconds: Int) {
        viewModelScope.launch {
            val profile = ensureProfile()
            if (profile.defaultRestSeconds == seconds) return@launch
            profileDao.upsert(profile.copy(defaultRestSeconds = seconds))
        }
    }

    fun setRestAutoStart(value: Boolean) {
        viewModelScope.launch { prefs.setRestAutoStart(value) }
    }

    /** `ScheduleEditor.apply` — an empty day set is ignored, and stale overrides are dropped. */
    fun saveSchedule(weekdays: Set<Int>, minuteOfDay: Int) {
        if (weekdays.isEmpty()) return
        viewModelScope.launch {
            val schedule = ensureSchedule()
            scheduleDao.save(
                schedule.copy(
                    weekdays = weekdays.sorted(),
                    defaultMinuteOfDay = minuteOfDay,
                    overrides = schedule.overrides.filterKeys { weekdays.contains(it) },
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** `ScheduleEditor.push` — the bro sees the same schedule, best effort. */
    fun pushSchedule() {
        if (!bro.isPaired) return
        viewModelScope.launch {
            val schedule = scheduleDao.schedule() ?: return@launch
            runCatching { bro.client.pushSchedule(schedule.toDto()) }
        }
    }

    fun setRemindHourBefore(value: Boolean) {
        viewModelScope.launch {
            val schedule = ensureSchedule()
            scheduleDao.save(schedule.copy(remindHourBefore = value, updatedAt = System.currentTimeMillis()))
        }
    }

    fun setAskIfSkipped(value: Boolean) {
        viewModelScope.launch {
            val schedule = ensureSchedule()
            scheduleDao.save(schedule.copy(askIfSkippedAt21 = value, updatedAt = System.currentTimeMillis()))
        }
    }

    // MARK: - App

    /**
     * `setLanguage` — writes `nt.language` and applies the per-app locale. AppCompat recreates the
     * Activity, so Android has no "relaunch" hint.
     */
    fun setLanguage(code: String?) {
        appState.setLanguageOverride(code)
    }

    fun setAiProvider(provider: AIProvider) {
        appConfig.setAiProvider(provider)
    }

    fun saveAnthropicKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { authStore.setAnthropicKey(trimmed) }
    }

    fun removeAnthropicKey() {
        viewModelScope.launch { authStore.removeAnthropicKey() }
    }

    fun saveGeminiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { authStore.setGeminiKey(trimmed) }
    }

    fun removeGeminiKey() {
        viewModelScope.launch { authStore.removeGeminiKey() }
    }

    /**
     * "Use demo data (offline)": swaps the backend and forgets account-bound bro state. Queued
     * attendance stays with the backend it was made on (`AttendanceOutbox.Target`).
     */
    fun setUseDemoData(value: Boolean) {
        if (appConfig.useMockBackend.value == value) return
        appConfig.setUseMockBackend(value)
        bro.resetSession()
    }

    /** Re-reads Health Connect availability and the granted permissions. */
    fun refreshHealth() {
        viewModelScope.launch {
            val authorized = runCatching { health.refreshAuthorization() }.getOrDefault(false)
            healthState.value = HealthState(
                isAvailable = health.isAvailable,
                needsProviderUpdate = health.needsProviderUpdate,
                isAuthorized = authorized,
                error = health.lastError.value,
            )
        }
    }

    /** The permission set the Health Connect contract is launched with. */
    val healthPermissions: Set<String> get() = health.permissions

    // MARK: - Export

    fun prepareExport() {
        if (exportState.value.isPreparing) return
        exportState.value = exportState.value.copy(isPreparing = true)
        viewModelScope.launch {
            val result = runCatching {
                SettingsExportFiles.build(app.cacheDir, db.workoutDao(), db.mealDao())
            }.getOrNull()
            exportState.value = ExportState(
                isPreparing = false,
                files = result?.files.orEmpty(),
                counts = result?.counts ?: SettingsExportFiles.Counts(),
            )
        }
    }

    // MARK: - Bro

    fun unpair(onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = busy.value.copy(isUnpairing = true)
            runCatching { bro.unpair() }
            busy.value = busy.value.copy(isUnpairing = false)
            onDone()
        }
    }

    // MARK: - Account

    /** Server-side revoke (best effort), secure store cleared, local data kept. */
    fun signOut() {
        viewModelScope.launch {
            busy.value = busy.value.copy(isSigningOut = true)
            val refreshToken = authStore.session()?.refreshToken
            if (refreshToken != null) {
                runCatching { appConfig.makeBackendClient().logout(refreshToken) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            // Android-only, and it must happen *before* the session goes: `DELETE push/token` is
            // an authenticated route, and `AppContainer`'s backend supplier returns null once
            // `AuthStore` is empty — so a post-clear unregister would silently skip the server
            // and leave this install on the old account's FCM fan-out. iOS has no equivalent
            // because its APNs token is not account-bound.
            runCatching { pushRegistrar.unregister() }
                .onFailure { if (it is CancellationException) throw it }
            authStore.clearAwait()
            bro.resetSession()
            busy.value = busy.value.copy(isSigningOut = false)
        }
    }

    /**
     * Backend first (best effort), then the rest timer, the workout session and every user-owned
     * row ([AccountDataReset]), the CSV exports, then both secure-store records, then back to
     * onboarding. The bundled exercise library survives; custom exercises do not.
     */
    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = busy.value.copy(isDeleting = true)
            // Read before the session goes: the queued attendance of this account goes with it.
            val account = bro.syncTarget
            runCatching { appConfig.makeBackendClient().deleteAccount() }
                .onFailure { if (it is CancellationException) throw it }

            val wiped = AccountDataReset.run(
                stopRestTimer = stopRestTimer,
                session = workoutSession,
                wipe = { db.wipeUserData() },
                // Routines were wiped: the next onboarding seeds the starter ones again.
                afterWipe = {
                    prefs.removeRoutinesSeeded()
                    // Starred exercises are the user's too (custom ones among them are gone).
                    prefs.removeFavoriteExercises()
                },
            )
            if (!wiped) Log.w(TAG, "Delete account: the local wipe failed and was rolled back")
            // Exported CSVs (and a shared copy of a broken store) are the old account's data too.
            runCatching { File(app.cacheDir, SettingsExportFiles.EXPORT_DIR).deleteRecursively() }

            authStore.clearAwait()
            authStore.removeAnthropicKey()
            authStore.removeGeminiKey()
            bro.resetSession()
            // Queued attendance of the account that is gone; another account's waits for it.
            runCatching { bro.clearAttendanceOutbox(account) }
                .onFailure { if (it is CancellationException) throw it }
            prefs.removeRestAutoStart()
            appState.setHasOnboarded(false)
            busy.value = busy.value.copy(isDeleting = false)
            onDone()
        }
    }

    // MARK: - Fetch-or-create (`SettingsModel.profile(in:)` / `schedule(in:)`)

    private suspend fun ensureProfile(): UserProfileEntity =
        profileDao.profile() ?: UserProfileEntity(name = app.getString(S.bro_you)).also {
            profileDao.upsert(it)
        }

    private suspend fun ensureSchedule(): GymScheduleEntity =
        scheduleDao.schedule() ?: GymScheduleEntity().also { scheduleDao.save(it) }

    companion object {
        private const val TAG = "Settings"

        fun create(container: AppContainer): SettingsViewModel = SettingsViewModel(
            app = container.app,
            db = container.db,
            prefs = container.appPrefs,
            authStore = container.authStore,
            appConfig = container.appConfig,
            bro = container.broService,
            health = container.healthService,
            appState = container.appState,
            pushRegistrar = container.pushRegistrar,
            workoutSession = container.workoutSession,
            stopRestTimer = { container.restTimer.skip() },
        )
    }
}
