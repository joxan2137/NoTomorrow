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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import app.notomorrow.R
import app.notomorrow.app.AppState

/**
 * Fuel calendar (`nt.widget.history`, `docs/widgets.md`): a GitHub-style contribution graph under
 * one label, "No Tomorrow calendar" — the weeks up to this one, each day a small square in its Fuel `heat` colour with
 * a dot on the days you trained. The widget's size decides how many weeks show. The whole widget
 * opens Fuel on today.
 */
class FuelCalendarWidget : GlanceAppWidget() {

    override val sizeMode = WidgetSizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = preload(WidgetKind.Calendar) { WidgetData.calendar(context) }
        provideContent {
            val data = rememberWidgetData(WidgetKind.Calendar, first) { WidgetData.calendar(context) }
            if (data == null) SetupContent(context) else CalendarContent(data)
        }
    }
}

class FuelCalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FuelCalendarWidget()
}

@Composable
internal fun CalendarContent(data: CalendarData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val width = size.width.value - 2 * W.margin.value
    val content = size.height.value - 2 * W.margin.value
    // At the smallest heights the squares get the whole widget.
    val showsLabel = content >= LABEL_MIN_CONTENT_DP
    val height = if (showsLabel) content - LABEL_DP - LABEL_GAP_DP else content
    WidgetFrame(onClick = W.open(context, AppState.Route.Fuel)) {
        Column(GlanceModifier.fillMaxSize()) {
            if (showsLabel) {
                Box(GlanceModifier.fillMaxWidth().height(LABEL_DP.dp), contentAlignment = Alignment.CenterStart) {
                    Eyebrow(context.getString(R.string.widget_history_label))
                }
                Spacer(GlanceModifier.height(LABEL_GAP_DP.dp))
            }
            Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                BitmapImage(
                    WidgetCharts.contributions(context, width, height.coerceAtLeast(40f), data),
                    description = context.getString(R.string.widget_history_description),
                )
            }
        }
    }
}

private const val LABEL_DP = 16f
private const val LABEL_GAP_DP = 8f

/** Below this content height (the 4×2 minimum on small launchers) the label makes way for the squares. */
private const val LABEL_MIN_CONTENT_DP = 110f
