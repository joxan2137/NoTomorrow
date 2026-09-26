package app.notomorrow.feature

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import app.notomorrow.R
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.GlassTier
import app.notomorrow.designsystem.LocalGlassTier
import app.notomorrow.designsystem.LocalNtOverlayHost
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTTheme
import app.notomorrow.designsystem.NtOverlayHost
import app.notomorrow.designsystem.rememberNtOverlayState
import app.notomorrow.di.AppContainer
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.di.ntViewModel
import app.notomorrow.feature.progress.ExerciseProgressScreen
import app.notomorrow.feature.progress.MilestonesScreen
import app.notomorrow.feature.progress.OneRepMaxCalculatorSheet
import app.notomorrow.feature.progress.ProgressHomeScreen
import app.notomorrow.feature.progress.RecordsScreen
import app.notomorrow.feature.settings.ImportEditor
import app.notomorrow.feature.settings.SettingsUiState
import app.notomorrow.feature.workout.ActiveWorkoutScreen
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.CustomExerciseEditor
import app.notomorrow.feature.workout.ExerciseHistorySheet
import app.notomorrow.feature.workout.ExercisePickerSheet
import app.notomorrow.feature.workout.PlateCalculatorSheet
import app.notomorrow.feature.workout.ProgramBrowserSheet
import app.notomorrow.feature.workout.RoutineEditRequest
import app.notomorrow.feature.workout.RoutineEditorPresenter
import app.notomorrow.feature.workout.RoutineImportSheet
import app.notomorrow.feature.workout.RoutineStore
import app.notomorrow.feature.workout.WorkoutDoneScreen
import app.notomorrow.feature.workout.WorkoutShareCard
import app.notomorrow.feature.workout.WorkoutShareLine
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Locale

/**
 * Renders the gym screens — the real composables and view models over a real Room database seeded
 * by [GymSeed] — to PNG, for checking layouts without a device. Off by default; run with
 * `./gradlew :app:testDebugUnitTest --tests '*GymScreenshots*' -PgymShots=<dir>`.
 *
 * Sheets and popups are separate windows, so [shoot] draws every window root, not only the activity's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-night-xhdpi")
class GymScreenshots {

    private val outDir: File? = System.getProperty("gymShots")?.let(::File)

    /** Skips before the activity is launched, so a normal test run pays nothing. */
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("pass -PgymShots=<dir> to render", outDir != null)
                base.evaluate()
            }
        }
    }

    @Suppress("DEPRECATION")
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private lateinit var container: AppContainer
    private lateinit var seed: GymSeed

    @After
    fun tearDown() {
        if (::container.isInitialized) runCatching { container.db.close() }
    }

    private fun prepare() {
        // Robolectric's native runtime aborts the JVM ("JniConstants: Class not found") when a
        // worker thread (Room's executor, the render thread) is the first to call into it; touch
        // SQLite and a pixel buffer here, on the main thread, first.
        SQLiteDatabase.create(null).use { db -> db.rawQuery("SELECT 1", null).use { it.moveToFirst() } }
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsFromBuffer(ByteBuffer.allocate(4))
        val app = RuntimeEnvironment.getApplication()
        container = AppContainer(app)
        seed = GymSeed(container)
        seed.writePhotos()
        onWorker {
            container.load()
            container.seed()
            seed.seed()
        }
    }

    // MARK: - Shots

    private fun string(id: Int): String = compose.activity.getString(id)

    /** A library exercise's name as the current locale shows it. */
    private fun exerciseName(id: String): String =
        onWorker { container.db.exerciseDao().byId(id)!!.localizedName(LocaleProvider.current()) }

    @Test fun routineEditor() = routineEditor("")

    @Test @Config(qualifiers = "+pl")
    fun routineEditorPl() = polish { routineEditor("-pl") }

    private fun routineEditor(tag: String) {
        prepare()
        show { RoutineEditorPresenter(request = RoutineEditRequest.Edit(GymSeed.ROUTINE_ID), host = "shots", onDismiss = {}) }
        waitForText("Upper strength")
        shoot("routine-editor$tag")
        scrollTo(exerciseName(GymSeed.LATERAL))
        shoot("routine-editor-superset$tag")
    }

    @Test
    fun programs() {
        prepare()
        show {
            ProgramBrowserSheet(
                loadExercises = { ids -> container.db.exerciseDao().byIds(ids.toList()).associateBy { it.id } },
                onAdd = { _, _ -> },
                onDismiss = {},
            )
        }
        waitForText(string(R.string.program_fullBody_name))
        shoot("program-browser")
        click(string(R.string.program_ppl_name))
        waitForText("Squat")
        shoot("program-detail")
    }

    @Test fun activeWorkout() = activeWorkout("")

    @Test @Config(qualifiers = "+pl")
    fun activeWorkoutPl() = polish { activeWorkout("-pl") }

    private fun activeWorkout(tag: String) {
        prepare()
        val id = onWorker { seed.seedActiveWorkout() }
        show {
            val model = ntViewModel(key = "activeWorkout/$id") { c ->
                ActiveWorkoutViewModel(
                    workoutId = id,
                    workoutDao = c.db.workoutDao(),
                    recordService = c.recordService,
                    attendanceService = c.attendanceService,
                    restTimer = c.restTimer,
                    session = c.workoutSession,
                    appPrefs = c.appPrefs,
                    strings = NtStrings.from(c.app),
                    units = { WeightUnit.Kg },
                    routines = { c.db.routineDao().routinesWithItems() },
                    defaultRest = { 120 },
                )
            }
            ActiveWorkoutScreen(model = model, scroll = rememberScrollState(), onMinimize = {})
        }
        waitForText("Pause the first rep")
        shoot("active-workout$tag")
        click(exerciseName(GymSeed.INCLINE))
        shoot("active-workout-superset$tag")
        compose.onAllNodesWithContentDescription(string(R.string.common_moreOptions)).onFirst().performClick()
        compose.waitForIdle()
        shoot("active-workout-menu$tag")
        click(string(R.string.workout_restTimer), substring = true)
        shoot("active-workout-rest-menu$tag")
    }

    @Test
    fun plateCalculator() {
        prepare()
        show { PlateCalculatorSheet(weightKg = 102.5, unit = WeightUnit.Kg, onUse = {}, onDismiss = {}) }
        waitForText("102.5")
        shoot("plate-calculator")
    }

    @Test
    fun oneRepMax() {
        prepare()
        show { OneRepMaxCalculatorSheet(unit = WeightUnit.Kg, onDismiss = {}, initialWeight = 100.0) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("5")
        compose.waitForIdle()
        shoot("onerm-calculator")
    }

    @Test
    fun exerciseHistory() {
        prepare()
        show { ExerciseHistorySheet(GymSeed.BENCH, "Barbell Bench Press - Medium Grip", WeightUnit.Kg, null, onDismiss = {}) }
        waitForText("×")
        shoot("exercise-history")
    }

    @Test fun customExercise() = customExercise("")

    @Test @Config(qualifiers = "+pl")
    fun customExercisePl() = polish { customExercise("-pl") }

    private fun customExercise(tag: String) {
        prepare()
        val exercise = ExerciseEntity(
            id = "custom-landmine",
            name = "Landmine press",
            primaryMuscles = listOf("shoulders"),
            equipment = "barbell",
            isCustom = true,
        )
        onWorker { container.db.exerciseDao().upsert(exercise) }
        show { CustomExerciseEditor(exercise = exercise, onDismiss = {}) }
        waitForText("Landmine")
        shoot("custom-exercise$tag")
    }

    @Test fun exercisePicker() = exercisePicker("")

    @Test @Config(qualifiers = "+pl")
    fun exercisePickerPl() = polish { exercisePicker("-pl") }

    private fun exercisePicker(tag: String) {
        prepare()
        onWorker { container.appPrefs.updateFavoriteExercises { setOf(GymSeed.BENCH, GymSeed.SQUAT, GymSeed.PULLUP) } }
        show { ExercisePickerSheet(onDismiss = {}, workoutId = null, onAdd = {}) }
        waitForText(string(R.string.exercises_results).substringAfter("%d").trim().ifEmpty { string(R.string.exercises_results).substringBefore("%d").trim() })
        shoot("exercise-picker-opened$tag")
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        repeat(lists.fetchSemanticsNodes().size) { lists[it].performScrollToIndex(0) }
        compose.mainClock.advanceTimeBy(500)
        shoot("exercise-picker$tag")
    }

    @Test fun progressLifts() = progressLifts("")

    @Test @Config(qualifiers = "+pl")
    fun progressLiftsPl() = polish { progressLifts("-pl") }

    private fun progressLifts(tag: String) {
        prepare()
        show { ProgressHomeScreen(onExercise = {}, onRecords = {}, onMilestones = {}) }
        waitForText(string(R.string.calendar_title), timeoutMs = 20_000)
        shoot("progress-lifts$tag")
        scrollTo(string(R.string.calendar_title))
        shoot("progress-calendar$tag")
        scrollTo(string(R.string.stats_title))
        shoot("progress-weekly-stats$tag")
        scrollTo(string(R.string.milestones_seeAll))
        shoot("progress-milestones$tag")
    }

    @Test fun progressBody() = progressBody("")

    @Test @Config(qualifiers = "+pl")
    fun progressBodyPl() = polish { progressBody("-pl") }

    private fun progressBody(tag: String) {
        prepare()
        show { ProgressHomeScreen(onExercise = {}, onRecords = {}, onMilestones = {}) }
        waitForText(string(R.string.progress_body))
        click(string(R.string.progress_body))
        waitForText(string(R.string.measure_log))
        shoot("progress-body$tag")
        scrollTo(string(R.string.measure_log))
        shoot("progress-measurements$tag")
        scrollTo(string(R.string.photos_compare))
        shoot("progress-photos$tag")
        click(string(R.string.measure_log))
        shoot("measurements-log-sheet$tag")
    }

    @Test
    fun photoCompare() {
        prepare()
        show { ProgressHomeScreen(onExercise = {}, onRecords = {}, onMilestones = {}) }
        waitForText(string(R.string.progress_body))
        click(string(R.string.progress_body))
        waitForText(string(R.string.photos_compare))
        scrollTo(string(R.string.photos_compare))
        click(string(R.string.photos_compare))
        shoot("progress-photos-compare")
    }

    @Test
    fun records() {
        prepare()
        show { RecordsScreen(onExercise = {}, onBack = {}) }
        waitForText("Bench")
        shoot("records")
    }

    @Test fun milestones() = milestones("")

    @Test @Config(qualifiers = "+pl")
    fun milestonesPl() = polish { milestones("-pl") }

    private fun milestones(tag: String) {
        prepare()
        show { MilestonesScreen(onBack = {}) }
        waitForText(string(R.string.milestones_section_strength))
        shoot("milestones$tag")
    }

    @Test fun liftPage() = liftPage("")

    @Test @Config(qualifiers = "+pl")
    fun liftPagePl() = polish { liftPage("-pl") }

    private fun liftPage(tag: String) {
        prepare()
        show { ExerciseProgressScreen(exerciseId = GymSeed.BENCH, onBack = {}) }
        waitForText(string(R.string.repmax_title))
        shoot("lift-page$tag")
        scrollTo(string(R.string.repmax_title))
        shoot("lift-page-repmax$tag")
        scrollTo(string(R.string.onerm_percentages))
        shoot("lift-page-percentages$tag")
    }

    @Test
    fun settingsImport() {
        prepare()
        val csv = File(compose.activity.cacheDir, "strong-export.csv")
        csv.writeText(STRONG_CSV)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Uri.fromFile(csv))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ImportEditor(state = SettingsUiState(), onBack = {})
            }
        }
        waitForText(string(R.string.import_choose))
        shoot("settings-import-empty")
        click(string(R.string.import_choose))
        waitForText(string(R.string.import_chooseAnother))
        shoot("settings-import-preview")
    }

    @Test fun workoutDone() = workoutDone("")

    @Test @Config(qualifiers = "+pl")
    fun workoutDonePl() = polish { workoutDone("-pl") }

    private fun workoutDone(tag: String) {
        prepare()
        show { WorkoutDoneScreen(workoutId = GymSeed.LAST_WORKOUT, onDone = {}) }
        waitForText("Pull A")
        shoot("workout-done$tag")
    }

    @Test fun shareCard() = shareCard("")

    @Test @Config(qualifiers = "+pl")
    fun shareCardPl() = polish { shareCard("-pl") }

    private fun shareCard(tag: String) {
        prepare()
        show {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WorkoutShareCard(
                    title = "Pull A",
                    subtitle = "Friday, 25 September · 18:05",
                    volume = "14,860 kg",
                    time = "1:04",
                    sets = "16",
                    prs = 2,
                    lines = listOf(
                        WorkoutShareLine("Barbell Deadlift", 4, "167.5 kg × 3"),
                        WorkoutShareLine("Bent Over Barbell Row", 4, "72.5 kg × 6"),
                        WorkoutShareLine("Pullups", 4, "× 12"),
                        WorkoutShareLine("Barbell Curl", 4, "35 kg × 6"),
                    ),
                )
            }
        }
        compose.waitForIdle()
        shoot("share-card$tag")
    }

    @Test fun routineImport() = routineImport("")

    @Test @Config(qualifiers = "+pl")
    fun routineImportPl() = polish { routineImport("-pl") }

    private fun routineImport(tag: String) {
        prepare()
        val store = RoutineStore(container.db.routineDao(), container.db.exerciseDao())
        show {
            RoutineImportSheet(
                loadCatalog = { store.shareCatalog(LocaleProvider.current()) },
                onAdd = { _, _, _ -> },
                onDismiss = {},
            )
        }
        waitForText(string(R.string.routine_import_paste))
        shoot("routine-import-empty$tag")
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("routine", ROUTINE_TEXT))
        click(string(R.string.routine_import_paste))
        waitForText(exerciseName(GymSeed.RDL))
        shoot("routine-import-preview$tag")
    }

    /** [block] with the app's own locale lookups (`LocaleProvider`) in Polish, as `+pl` does for resources. */
    private fun polish(block: () -> Unit) {
        val previous = Locale.getDefault()
        val pl = Locale.forLanguageTag("pl-PL")
        Locale.setDefault(pl)
        LocaleProvider.override = { pl }
        try {
            block()
        } finally {
            LocaleProvider.override = null
            Locale.setDefault(previous)
        }
    }

    // MARK: - Harness

    private fun show(content: @Composable () -> Unit) {
        // Frames only advance when a shot asks: the liquid-metal search field and other effects
        // tick on every frame, so an auto-advancing clock would never go idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                NTTheme {
                    val overlay = rememberNtOverlayState()
                    CompositionLocalProvider(
                        LocalNtOverlayHost provides overlay,
                        LocalGlassTier provides GlassTier.Tint,
                    ) {
                        Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
                            content()
                            NtOverlayHost(overlay)
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs [block] off the main thread while this (the Robolectric main) thread keeps its looper
     * turning — parts of the container resume on `Dispatchers.Main`, which a `runBlocking` here
     * would deadlock.
     */
    private fun <T> onWorker(block: suspend () -> T): T {
        var result: Result<T>? = null
        val worker = Thread { result = runCatching { runBlocking { block() } } }
        worker.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (worker.isAlive) {
            check(System.currentTimeMillis() < deadline) { "seeding timed out" }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result!!.getOrThrow()
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        try {
            compose.waitUntil(timeoutMs) {
                compose.mainClock.advanceTimeBy(100)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                compose.onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shoot("failed-" + text.filter { it.isLetterOrDigit() }.take(20))
            throw e
        }
    }

    private fun click(text: String, substring: Boolean = false) {
        compose.onAllNodesWithText(text, substring = substring, ignoreCase = true, useUnmergedTree = true).onFirst().performClick()
        compose.mainClock.advanceTimeBy(500)
    }

    /**
     * Drags the topmost window's content until [text] sits near the top. Not `performScrollTo`:
     * with the paused clock its scroll never settles against the screens' own scroll handlers.
     */
    private fun scrollTo(text: String) {
        val root = compose.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }
        val height = root.fetchSemanticsNode().size.height.toFloat()
        var last = Float.NaN
        repeat(12) {
            val target = compose.onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull { it.size.height > 0 }
            // Not composed yet (further down a lazy list): keep going down.
            val top = target?.positionInRoot?.y ?: (height * 2)
            val wanted = height * 0.18f
            if (top in (wanted - 60f)..(wanted + 60f) || top == last) return
            last = top
            val delta = (top - wanted).coerceIn(-height * 0.6f, height * 0.6f)
            root.performTouchInput {
                val x = width * 0.5f
                val from = if (delta > 0) height * 0.8f else height * 0.2f
                swipe(Offset(x, from), Offset(x, from - delta), durationMillis = 800)
            }
            compose.mainClock.advanceTimeBy(1_000)
        }
    }

    /** Every window root (the activity, then sheets and popups above it) drawn into one bitmap. */
    private fun shoot(name: String) {
        // Let sheet and menu enter animations finish — on Compose's clock, and on the looper's, which
        // drives the traversals of a window (a sheet) that opened after the first frame.
        repeat(15) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (root in windowRoots()) {
            if (root.width == 0 || root.height == 0 || root.visibility != View.VISIBLE) continue
            val at = IntArray(2)
            root.getLocationOnScreen(at)
            canvas.drawBitmap(renderHardware(root), at[0].toFloat(), at[1].toFloat(), null)
        }
        val dir = outDir!!
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * [root] drawn through `HardwareRenderer` into an `ImageReader`: the liquid-metal and glass
     * effects are `RuntimeShader`s, which a software `Canvas` refuses.
     */
    private fun renderHardware(root: View): Bitmap {
        val w = root.width
        val h = root.height
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val node = RenderNode("shot").apply { setPosition(0, 0, w, h) }
        val recording = node.beginRecording()
        root.draw(recording)
        node.endRecording()
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage().use { image ->
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowPixels = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                return Bitmap.createBitmap(padded, 0, 0, w, h)
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(instance) as List<View>).toList()
    }

    private companion object {
        /** A two-workout Strong export (semicolons, a warm-up, an RPE, a rest-timer row). */
        val STRONG_CSV = """
            Workout #;Date;Workout Name;Duration (sec);Exercise Name;Set Order;Weight (kg);Reps;RPE;Distance (meters);Seconds;Notes;Workout Notes
            1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);W;40;10;;;;;
            1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);1;80;8;8,5;;;Wide grip;
            1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);2;80;7;9;;;;
            1;2026-08-10 18:00:00;Push day;3600;Bench Press (Barbell);Rest Timer;;;;;90;;
            1;2026-08-10 18:00:00;Push day;3600;Cable Crossover Deluxe;1;20;12;;;;;
            2;2026-08-12 07:30:00;Legs;2700;Squat (Barbell);1;100;5;;;;;
            2;2026-08-12 07:30:00;Legs;2700;Squat (Barbell);2;100;5;;;;;
            2;2026-08-12 07:30:00;Legs;2700;Leg Press;1;160;10;;;;;
        """.trimIndent()

        /** A shared routine as numbered lines only (no `nt1:` line), one exercise unknown to the library. */
        val ROUTINE_TEXT = """
            No Tomorrow routine: Leg day (heavy)
            1. Barbell Squat — 5 × 5, rest 3:00
            2. Romanian Deadlift — 3 × 8, rest 2:00
            3. Leg Press — 3 × 12 [Superset A]
            4. Standing Calf Raises — 4 × 15 [Superset A]
            5. Sissy squat on the Smith machine — 3 × 10
        """.trimIndent()
    }
}
