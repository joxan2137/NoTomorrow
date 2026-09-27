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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.service.DayState
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtStrings

/**
 * Gym week (`nt.widget.week`, `docs/widgets.md`): the next session with the routine the Dashboard
 * suggests, and the Mon…Sun strip (`WeekStripView`). Small: the session block over a 12 dp strip;
 * medium: the session block beside the full strip with who-trained dots and "n of m done".
 * Opens Today.
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
            // Left the next session; right the week on a tile: letters, circles, who-trained dots
            // and how many of this week's gym days are done.
            val tileWidth = width * 0.56f
            val stripWidth = tileWidth - 20f
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    NextSessionBlock(context, data, timeSp = 40f)
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(
                    GlanceModifier.width(tileWidth.dp).fillMaxHeight()
                        .background(ImageProvider(R.drawable.widget_tile)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BitmapImage(
                        WidgetCharts.weekStrip(
                            context, stripWidth, 26f, data.days, data.isPaired,
                            numbers = true, dots = data.hasSchedule, locale = locale, labels = letters,
                        ),
                    )
                    if (data.hasSchedule) {
                        Spacer(GlanceModifier.height(10.dp))
                        val gymDays = data.days.count { it.isGymDay }
                        val attended = data.days.count { it.myState is DayState.Attended }
                        val total = maxOf(gymDays, attended)
                        BitmapImage(WidgetBitmaps.bar(context, stripWidth, 4f, if (total > 0) attended / total.toFloat() else 0f))
                        Spacer(GlanceModifier.height(5.dp))
                        Text(context.getString(R.string.widget_week_done_n_n, attended, total), style = W.caption, maxLines = 1)
                    }
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize()) {
                Column(GlanceModifier.fillMaxWidth().defaultWeight()) { NextSessionBlock(context, data, timeSp = 36f) }
                Spacer(GlanceModifier.height(6.dp))
                BitmapImage(
                    WidgetCharts.weekStrip(
                        context, width, 15f, data.days, data.isPaired,
                        numbers = false, dots = false, locale = locale, labels = letters,
                    ),
                )
            }
        }
    }
}

/**
 * Next session (ember eyebrow), the routine, the time in the display face and the day ("Today",
 * "Tomorrow", the weekday). With no gym days: the eyebrow and `widget.week.noSchedule`.
 */
@Composable
private fun NextSessionBlock(context: Context, data: WeekData, timeSp: Float) {
    val next = data.next
    if (!data.hasSchedule || next == null) {
        Eyebrow(context.getString(R.string.widget_week_name), icon = R.drawable.ic_fitness_center)
        Spacer(GlanceModifier.height(6.dp))
        Text(context.getString(R.string.widget_week_noSchedule), style = W.subheadlineBold, maxLines = 3)
        return
    }
    Eyebrow(context.getString(R.string.dashboard_nextSession), color = W.ember, icon = R.drawable.ic_fitness_center)
    Spacer(GlanceModifier.height(3.dp))
    data.routineName?.takeIf { it.isNotEmpty() }?.let { Text(it, style = W.headline, maxLines = 1) }
    val time = Fmt.time(next.minuteOfDay)
    BitmapImage(WidgetBitmaps.displayText(context, time, timeSp), description = time)
    val relative = Fmt.relativeDay(next.day, NtStrings(context.resources), today = data.today)
    Text(relative, style = W.footnote, maxLines = 1)
}
