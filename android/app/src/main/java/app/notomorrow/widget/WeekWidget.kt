package app.notomorrow.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.service.DayState
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.NtKeys

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
private fun WeekContent(data: WeekData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val width = size.width.value - 2 * W.margin.value
    WidgetFrame(onClick = W.open(context, AppState.Route.Today)) {
        if (size.width.value >= MEDIUM_MIN_WIDTH_DP) {
            val stripWidth = width * 0.58f
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    NextSessionBlock(context, data)
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.width(stripWidth.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(GlanceModifier.fillMaxWidth()) {
                        for (day in data.days) {
                            Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                                Text(context.getString(day.labelRes), style = W.text(W.caption, W.ink3), maxLines = 1)
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    BitmapImage(
                        WidgetCharts.weekStrip(
                            context, stripWidth, 26f, data.days, data.isPaired,
                            numbers = true, dots = true, locale = LocaleProvider.current(),
                        ),
                    )
                    Spacer(GlanceModifier.height(8.dp))
                    val gymDays = data.days.count { it.isGymDay }
                    val attended = data.days.count { it.myState is DayState.Attended }
                    Text(
                        context.getString(R.string.widget_week_done_n_n, attended, maxOf(gymDays, attended)),
                        style = W.footnote,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize()) {
                Column(GlanceModifier.fillMaxWidth().defaultWeight()) { NextSessionBlock(context, data) }
                Spacer(GlanceModifier.height(8.dp))
                BitmapImage(
                    WidgetCharts.weekStrip(
                        context, width, 12f, data.days, data.isPaired,
                        numbers = false, dots = false, locale = LocaleProvider.current(),
                    ),
                )
            }
        }
    }
}

/** Next session (ember eyebrow), the routine, the time in the display face and the relative day. */
@Composable
private fun NextSessionBlock(context: Context, data: WeekData) {
    val next = data.next
    if (!data.hasSchedule || next == null) {
        Text(context.getString(R.string.widget_week_noSchedule), style = W.footnote, maxLines = 3)
        return
    }
    Eyebrow(context.getString(R.string.dashboard_nextSession), color = W.ember)
    Spacer(GlanceModifier.height(2.dp))
    data.routineName?.takeIf { it.isNotEmpty() }?.let { Text(it, style = W.headline, maxLines = 1) }
    val time = Fmt.time(next.minuteOfDay)
    BitmapImage(WidgetBitmaps.displayText(context, time, 40f), description = time)
    val relative = when (next.day) {
        data.today -> context.getString(R.string.day_today)
        data.today.plusDays(1) -> context.getString(R.string.day_tomorrow)
        else -> context.getString(NtKeys.weekdayShort(Fmt.isoWeekday(next.day)))
    }
    Text(relative, style = W.footnote, maxLines = 1)
}
