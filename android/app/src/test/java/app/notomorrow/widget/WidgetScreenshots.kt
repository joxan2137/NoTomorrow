package app.notomorrow.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import org.robolectric.RuntimeEnvironment
import app.notomorrow.feature.fuel.FuelCalendar
import app.notomorrow.feature.fuel.FuelGoals
import app.notomorrow.model.TrainingGoal
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.DayState
import app.notomorrow.service.NextSession
import app.notomorrow.service.WeekDay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders every widget size to PNG through Glance's real RemoteViews, for checking layouts
 * against `docs/widgets.md` without a device. Off by default; run with
 * `./gradlew :app:testDebugUnitTest --tests '*WidgetScreenshots*' -PwidgetShots=<dir>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalGlanceApi::class)
class WidgetScreenshots {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val outDir: File? = System.getProperty("widgetShots")?.let(::File)
    private val today: LocalDate = LocalDate.now()

    private val small = DpSize(170.dp, 170.dp)
    private val medium = DpSize(360.dp, 170.dp)
    private val large = DpSize(360.dp, 380.dp)

    @Test
    fun quickLog() {
        val foods = listOf(
            QuickFood("food:1", "1", "Skyr, natural", 300.0, 186.0, 33.0, 12.0, 0.6, false),
            QuickFood("name:oats", null, "Oats with banana", 0.0, 412.0, 13.0, 72.0, 7.0, false),
            QuickFood("food:3", "3", "Chicken, rice, peppers", 480.0, 655.0, 52.0, 78.0, 12.0, true),
        )
        val data = QuickLogData(1240.0, 96.0, 130.0, 38.0, FuelGoals(2400.0, 180.0, 260.0, 80.0), foods)
        shoot("quicklog-small", small) { QuickLogContent(data) }
        shoot("quicklog-medium", medium) { QuickLogContent(data) }
        val empty = data.copy(kcal = 0.0, protein = 0.0, carbs = 0.0, fat = 0.0, foods = emptyList())
        shoot("quicklog-small-empty", small) { QuickLogContent(empty) }
        shoot("quicklog-medium-empty", medium) { QuickLogContent(empty) }
        val over = data.copy(kcal = 2650.0, protein = 170.0, carbs = 280.0, fat = 85.0)
        shoot("quicklog-medium-over", medium) { QuickLogContent(over) }
    }

    @Test
    fun calendar() {
        val kcal = (1..120L).filter { it % 5 != 0L }.associate { today.minusDays(it) to 1800.0 + (it * 97 % 900) }
        val trained = (1..120L).filter { it % 3 == 0L }.map { today.minusDays(it) }.toSet()
        val data = CalendarData(
            today = today,
            kcalByDay = kcal + (today to 900.0),
            trainedDays = trained,
            kcalGoal = 2400.0,
            goal = TrainingGoal.Maintain,
            stats = FuelCalendar.Stats(avg7 = 2210.0, avg30 = 2150.0, onTarget30 = 11),
            sessions30 = 10,
        )
        shoot("calendar-medium", medium) { CalendarContent(data) }
        shoot("calendar-large", large) { CalendarContent(data) }
    }

    @Test
    fun week() {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val days = (0..6).map { i ->
            val date = monday.plusDays(i.toLong())
            val gym = i in setOf(0, 2, 4)
            val mine = when {
                !gym -> DayState.Rest
                date < today -> if (i == 0) DayState.Attended else DayState.Planned
                else -> DayState.Planned
            }
            WeekDay(date, i + 1, date == today, gym, mine, if (gym && date < today) DayState.Attended else DayState.Rest)
        }
        val nextDay = days.firstOrNull { it.isGymDay && it.date >= today }?.date ?: monday.plusDays(7)
        val next = NextSession(nextDay.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant(), nextDay, 18 * 60)
        val data = WeekData(days, isPaired = true, today = today, next = next, routineName = "Push A")
        shoot("week-small", small) { WeekContent(data) }
        shoot("week-medium", medium) { WeekContent(data) }
        val solo = data.copy(isPaired = false)
        shoot("week-medium-solo", medium) { WeekContent(solo) }
        val none = WeekData(days.map { it.copy(isGymDay = false, myState = DayState.Rest, partnerState = DayState.Rest) }, false, today, null, null)
        shoot("week-small-noschedule", small) { WeekContent(none) }
        shoot("week-medium-noschedule", medium) { WeekContent(none) }
    }

    @Test
    fun breakTimer() {
        val idle = RestData(RestTimerState(), endedAt = null, defaultRestSeconds = 90)
        val now = System.currentTimeMillis()
        val running = RestData(
            RestTimerState(endAt = now + 58_000, totalSeconds = 90, exerciseName = "Bench press", nextSetLabel = "Set 3 of 4 · 80 × 8"),
            endedAt = null,
            defaultRestSeconds = 90,
        )
        shoot("break-small-idle", small) { BreakTimerContent(idle) }
        shoot("break-medium-idle", medium) { BreakTimerContent(idle) }
        shoot("break-small-running", small) { BreakTimerContent(running) }
        shoot("break-medium-running", medium) { BreakTimerContent(running) }
        val ended = RestData(RestTimerState(), endedAt = now - 5_000, defaultRestSeconds = 90)
        shoot("break-small-ended", small) { BreakTimerContent(ended) }
        shoot("break-medium-ended", medium) { BreakTimerContent(ended) }
    }

    private fun shoot(name: String, size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = outDir
        assumeTrue("pass -PwidgetShots=<dir> to render", dir != null)
        val widget = object : GlanceAppWidget() {
            override val sizeMode = SizeMode.Exact
            override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { content() }
        }
        val views = runBlocking { widget.compose(context, size = size) }
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        val parent = FrameLayout(context)
        val view = views.apply(context, parent)
        parent.addView(view)
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        // On a wallpaper-like backdrop, the way a launcher shows it.
        val pad = (20 * density).toInt()
        val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val backdrop = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                intArrayOf(0xFF3B5A7A.toInt(), 0xFF6F8FA8.toInt(), 0xFF2E3F56.toInt()), null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), backdrop)
        canvas.translate(pad.toFloat(), pad.toFloat())
        parent.draw(canvas)
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
