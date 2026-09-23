package app.notomorrow.di

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.staticCompositionLocalOf
import app.notomorrow.app.AppState
import app.notomorrow.app.AppVisibility
import app.notomorrow.app.StoreLoader
import app.notomorrow.data.db.DatabaseModule
import app.notomorrow.data.db.NoTomorrowDatabase
import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.data.prefs.SecureStore
import app.notomorrow.net.MockStrings
import app.notomorrow.push.PushRegistrar
import app.notomorrow.rest.RestTimerController
import app.notomorrow.service.AIBarcodeGrounding
import app.notomorrow.service.AIEstimateLocalizer
import app.notomorrow.service.AIEstimateProviders
import app.notomorrow.service.AIEstimateSpec
import app.notomorrow.service.AppConfig
import app.notomorrow.service.AppConfigStore
import app.notomorrow.service.AttendanceOutbox
import app.notomorrow.service.AttendanceReporter
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.AuthStore
import app.notomorrow.service.BroService
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.service.FoodSearchService
import app.notomorrow.service.HealthService
import app.notomorrow.service.PrefsMockPairingStore
import app.notomorrow.service.RecordService
import app.notomorrow.service.RoutineSeeder
import app.notomorrow.service.TargetCalculator
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.LocaleProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual DI container — the Android replacement for iOS's `@Environment` graph. One instance,
 * created in `NoTomorrowApp.onCreate`, read through [LocalAppContainer].
 *
 * No Hilt: the graph is small, entirely hand-written, and manual DI keeps build times and
 * parallel-work friction down (`docs/android-architecture.md`, "Environment / DI").
 *
 * **Everything below the preference stores is `by lazy`.** `Application.onCreate` runs on the main
 * thread and `DatabaseModule.build` opens the SQLite file eagerly; [load] opens it through [store]
 * on the start-up coroutine in `NoTomorrowApp`, not in `onCreate`. Until it is open, [db] throws and
 * `RootScreen` shows the store error screen, so none of the lazies below that read it is created
 * with a store that failed — after Try again or Start with empty data they resolve normally.
 * Laziness is also what resolves the `AuthStore` ⇄ `AppConfig` cycle: `AppConfig` takes the session
 * store as a `() -> SessionStore`.
 *
 * [load] must run once, off the main thread, before the first authenticated request or the first
 * frame that depends on `hasOnboarded`.
 */
class AppContainer(val app: Application) {

    /** Long-lived, process-scoped work: preference write-through, start-up seeding. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MARK: - Storage

    /** Opens the database, or reports why it cannot (`StoreLoader.swift`); never falls back to memory. */
    val store: StoreLoader by lazy {
        StoreLoader(
            databaseFile = app.getDatabasePath(NoTomorrowDatabase.NAME),
            shareDir = File(app.cacheDir, "export/store"),
            openDatabase = { DatabaseModule.build(app) },
        )
    }

    /** The open database; throws while [store] is not open (the UI shows the error screen then). */
    val db: NoTomorrowDatabase get() = store.database()

    val appPrefs: AppPrefs by lazy { AppPrefs(app) }

    val secureStore: SecureStore by lazy { SecureStore(app) }

    // MARK: - Session and configuration

    /** Also **is** the `SessionStore` the transport reads on every authenticated request. */
    val authStore: AuthStore by lazy { AuthStore(secureStore, scope) }

    /** `nt.mock.paired`, so the demo backend remembers the pairing across relaunches. */
    val mockPairingStore: PrefsMockPairingStore by lazy { PrefsMockPairingStore(appPrefs, scope) }

    /**
     * Owns `makeBackendClient()`, which caches one client per process and rebuilds it only when
     * `useMockBackend` flips or `backendBaseURL` changes — so the Settings "Use demo data" toggle
     * takes effect without a relaunch, exactly as on iOS.
     */
    val appConfig: AppConfig by lazy {
        AppConfig(
            prefs = AppConfigStore.from(appPrefs),
            sessionStore = { authStore },
            mockStrings = { MockStrings.from(app) },
            mockPairing = { mockPairingStore },
            scope = scope,
        )
    }

    // MARK: - Controllers

    /**
     * Always through [RestTimerController.get]: the alarm and notification receivers resolve the
     * same instance in a possibly container-less process, and two instances would open the same
     * DataStore file twice.
     */
    val restTimer: RestTimerController by lazy {
        RestTimerController.get(app).also { controller ->
            // How a rest that runs out reaches the user depends on where they are.
            controller.connect(
                isAppInForeground = { AppVisibility.isForeground },
                // `store.isOpen` first: the session reads the database, which a failed store does not have.
                isWorkoutOnScreen = { store.isOpen && workoutSession.showsActiveWorkout.value },
            )
        }
    }

    /** The single source of truth for the workout in progress (`nt.activeWorkoutId`). */
    val workoutSession: WorkoutSessionController by lazy {
        WorkoutSessionController(WorkoutSessionController.prefsStore(appPrefs), db.workoutDao())
    }

    // MARK: - Services

    /** Process-global singleton, the analogue of iOS's `BroShared.service`. */
    val broService: BroService by lazy {
        BroService(
            clientProvider = { appConfig.makeBackendClient() },
            scheduleDao = db.scheduleDao(),
            attendanceDao = db.attendanceDao(),
            headsUpDao = db.headsUpDao(),
            pairingDao = db.broPairingDao(),
            attendanceOutbox = AttendanceOutbox(AttendanceOutbox.prefsStore(appPrefs)),
            // The demo backend, else the signed-in account (`!AuthStore.needsSignIn`), read fresh on
            // every use: queued attendance is tagged with it and only ever sent to it.
            syncTargetProvider = {
                if (appConfig.useMockBackend.value) {
                    AttendanceOutbox.Target.Demo
                } else {
                    authStore.session()?.userId?.let(AttendanceOutbox.Target::remote)
                }
            },
        )
    }

    /**
     * `AttendanceSync.report` — my attendance writes (Finish, a workout edited or deleted) go to the
     * backend on the process scope, so leaving the screen that made them does not cancel the send.
     */
    val attendanceReporter: AttendanceReporter = AttendanceReporter { day, status ->
        scope.launch { broService.reportAttendance(day, status) }
    }

    val healthService: HealthService by lazy { HealthService(app) }

    val exerciseLibrary: ExerciseLibrary by lazy {
        ExerciseLibrary(app, db.exerciseDao(), ExerciseLibrary.VersionStamp.prefs(appPrefs))
    }

    val routineSeeder: RoutineSeeder by lazy { RoutineSeeder(db.routineDao(), db.exerciseDao()) }

    val recordService: RecordService by lazy { RecordService(db.workoutDao()) }

    val attendanceService: AttendanceService by lazy {
        AttendanceService(
            attendanceDao = db.attendanceDao(),
            scheduleDao = db.scheduleDao(),
            profileDao = db.profileDao(),
            sweepCursor = AttendanceService.SweepCursor.prefs(appPrefs),
        )
    }

    /** Stateless; exposed through the container so features never reach for the object directly. */
    val targetCalculator: TargetCalculator = TargetCalculator

    /** A failed Open Food Facts request reads "no connection" only when the phone really has none. */
    val foodSearchService: FoodSearchService by lazy { FoodSearchService(isOnline = ::hasInternet) }

    /** An active network that claims internet access (`ACCESS_NETWORK_STATE`, merged in by WorkManager). */
    private fun hasInternet(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Resolves the provider **per call** from `appConfig` and the stored BYOK keys: demo backend →
     * mock, "Claude with your key" + a key → direct Anthropic, "Gemini with your key" + a key →
     * direct Google, otherwise the Fly.io backend (whose Gemini key is for whitelisted users).
     */
    val aiEstimateService: AIEstimateProviders by lazy {
        AIEstimateProviders(
            config = appConfig,
            authStore = authStore,
            mockStrings = AIEstimateLocalizer.from(app),
            // The shared spec (`backend/data/ai/estimate-spec.json`), read from assets once, off main.
            spec = { withContext(Dispatchers.IO) { AIEstimateSpec.shared(app) } },
            grounding = AIBarcodeGrounding.openFoodFacts(foodSearchService) { LocaleProvider.current().language },
        )
    }

    /**
     * The backend supplier is resolved per call, so the demo toggle needs no relaunch and the
     * registrar is a no-op while signed out.
     */
    val pushRegistrar: PushRegistrar by lazy {
        PushRegistrar.install(app) {
            if (authStore.session() != null) appConfig.makeBackendClient() else null
        }
    }

    // MARK: - Shell state

    val appState: AppState by lazy { AppState(appPrefs, scope) }

    // MARK: - Lifecycle

    /**
     * Seeds every mirrored store from disk. Order matters exactly once: `authStore.load()` must
     * finish before anything issues an authenticated request, and `bindMockBackend` is what makes
     * `needsSignIn` false while the demo backend is on.
     */
    suspend fun load() {
        appConfig.load()
        authStore.load()
        authStore.bindMockBackend(appConfig.useMockBackend)
        mockPairingStore.load()
        // Before `isLoaded` flips, so the first frame already knows whether to show the app or
        // the store error screen.
        store.open()
        appState.load()
    }

    /**
     * The store error screen's "Try again". On success the app replaces the error screen and the
     * start-up seeding runs (`RootView`'s `.task(id: store.generation)`).
     */
    suspend fun retryStore(): Boolean {
        if (!store.open()) return false
        scope.launch { seed() }
        return true
    }

    /**
     * "Start with empty data": the files that cannot be opened move into a backup folder, and an
     * empty store starts from setup, the same as after deleting the account.
     */
    suspend fun startFreshStore(): Boolean {
        if (!store.moveAside()) return false
        appState.setHasOnboarded(false)
        return retryStore()
    }

    /**
     * The `RootView.task` equivalent, only once the store is open. The seeder is a no-op until the
     * 15 library ids exist, so the import has to come first; both are idempotent, and the import
     * runs once per library version (`nt.exerciseLibrary.version`). Then, once, the
     * routine items still holding the fixed rest older builds seeded start following the user's
     * Rest length (`RoutineSeeder.inheritDefaultRestIfNeeded`).
     */
    suspend fun seed() {
        if (!store.isOpen) return
        runCatching { exerciseLibrary.importIfNeeded() }
        runCatching { routineSeeder.seedIfNeeded() }
        runCatching {
            if (!appPrefs.routinesInheritRestOnce()) {
                routineSeeder.inheritDefaultRest()
                appPrefs.setRoutinesInheritRest(true)
            }
        }
    }
}

/**
 * The one CompositionLocal. Provided in `MainActivity.setContent`; every feature reads its
 * dependencies through it, usually via [ntViewModel].
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided — MainActivity must wrap content in CompositionLocalProvider(LocalAppContainer provides …).")
}
