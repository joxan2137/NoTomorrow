package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings

/**
 * Gym week (`nt.widget.week`, `docs/widgets.md`): the next session — its day, the routine the
 * Dashboard suggests and the time, big — and the Mon…Sun strip (`WeekStripView`). Small: the
 * session over a 16 dp strip; medium: the session on top, the full strip with dates (and, when
 * paired, who-trained dots) across the width in a faint tray. Opens Today.
 */
class WeekWidget : GlanceAppWidget() {

    override val sizeMode = WidgetSizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = preload(WidgetKind.Week) { WidgetData.week(context) }
        provideContent {
            val data = rememberWidgetData(WidgetKind.Week, first) { WidgetData.week(context) }
            if (data == null) SetupContent(context) else WeekContent(data)
        }
    }
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

@Composable
internal fun WeekContent(data: WeekData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val width = size.width.value - 2 * W.margin.value
    val locale = LocaleProvider.current()
    val letters = data.days.map { context.getString(it.labelRes) }
    WidgetFrame(onClick = W.open(context, AppState.Route.Today)) {
        if (size.width.value >= MEDIUM_MIN_WIDTH_DP) {
            // Top the next session (day and routine left, the time big on the right); under it the
            // whole week across the width.
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) { SessionLabel(context, data, maxLines = 2) }
                    data.next?.takeIf { data.hasSchedule }?.let { next ->
                        Spacer(GlanceModifier.width(12.dp))
                        val time = Fmt.time(next.minuteOfDay)
                        BitmapImage(WidgetBitmaps.displayText(context, time, 50f, maxWidthDp = width * 0.55f), description = time)
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                Tray {
                    BitmapImage(
                        WidgetCharts.weekStrip(
                            context, width - 2 * TRAY_PADDING_DP, 30f, data.days, data.isPaired,
                            numbers = true, dots = data.hasSchedule && data.isPaired, locale = locale, labels = letters,
                        ),
                    )
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize()) {
                SessionLabel(context, data, maxLines = 3)
                data.next?.takeIf { data.hasSchedule }?.let { next ->
                    val time = Fmt.time(next.minuteOfDay)
                    BitmapImage(WidgetBitmaps.displayText(context, time, 44f, maxWidthDp = width), description = time)
                }
                Spacer(GlanceModifier.defaultWeight())
                BitmapImage(
                    WidgetCharts.weekStrip(
                        context, width, 16f, data.days, data.isPaired,
                        numbers = false, dots = false, locale = locale, labels = letters,
                    ),
                )
            }
        }
    }
}

/**
 * The next session's day as the ember eyebrow ("Today", "Tomorrow", the weekday) over the routine
 * the Dashboard suggests (or "Next session" without one). With no gym days: the widget's name and
 * `widget.week.noSchedule`.
 */
@Composable
private fun SessionLabel(context: Context, data: WeekData, maxLines: Int) {
    val next = data.next
    if (!data.hasSchedule || next == null) {
        Eyebrow(context.getString(R.string.widget_week_name), icon = R.drawable.ic_fitness_center)
        Spacer(GlanceModifier.height(6.dp))
        Text(context.getString(R.string.widget_week_noSchedule), style = W.headline, maxLines = maxLines)
        return
    }
    val day = Fmt.relativeDay(next.day, NtStrings(context.resources), today = data.today)
    Eyebrow(day, color = W.ember, icon = R.drawable.ic_fitness_center)
    Spacer(GlanceModifier.height(2.dp))
    val routine = data.routineName?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.dashboard_nextSession)
    Text(routine, style = W.title, maxLines = 1)
}

/** The week strip's tray: a faint rounded well across the width (`widget_tray.xml`). */
@Composable
private fun Tray(content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxWidth()
            .background(ImageProvider(R.drawable.widget_tray))
            .cornerRadius(16.dp)
            .padding(horizontal = TRAY_PADDING_DP.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private const val TRAY_PADDING_DP = 6f
