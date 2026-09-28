package app.notomorrow.widget

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.designsystem.NT
import app.notomorrow.util.Fmt

/**
 * Break timer (`nt.widget.rest`, `docs/widgets.md`). Idle: the default rest, big, and three lengths
 * that start a rest with no app launch (`RestTimerController.start`, so the ongoing notification,
 * the end alert and the chime all follow). Running: the ember ring as big as the widget allows with
 * a live, system-rendered countdown (a `Chronometer`), `+15` / Skip (and `−15` on medium). For two
 * minutes after a rest runs out, the widget glows and says "Rest is over. Go." Anywhere else opens
 * the workout (and its rest).
 */
class BreakTimerWidget : GlanceAppWidget() {

    override val sizeMode = WidgetSizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = preload(WidgetKind.Rest) { WidgetData.rest(context) }
        provideContent {
            val data = rememberWidgetData(WidgetKind.Rest, first) { WidgetData.rest(context) }
            if (data == null) SetupContent(context) else BreakTimerContent(data)
        }
    }
}

class BreakTimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BreakTimerWidget()
}

@Composable
internal fun BreakTimerContent(data: RestData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val now = System.currentTimeMillis()
    val end = data.state.endAt?.takeIf { it > now }
    val justEnded = end == null && data.endedAt != null && now - data.endedAt in 0 until WidgetUpdater.JUST_ENDED_MS
    val medium = size.width.value >= MEDIUM_MIN_WIDTH_DP
    val width = size.width.value - 2 * W.margin.value
    val height = size.height.value - 2 * W.margin.value
    val background = if (justEnded) R.drawable.widget_background_go else R.drawable.widget_background
    WidgetFrame(onClick = W.open(context, AppState.Route.RestTimer), background = background) {
        when {
            medium -> Medium(context, data, end, justEnded, height)
            end != null -> SmallRunning(context, data, end, width, height)
            else -> SmallIdle(context, data, justEnded, width)
        }
    }
}

/** The idle ring: a faint ember track waiting to be filled; full ember once the rest just ended. */
private fun idleRing(context: Context, sizeDp: Float, text: String, justEnded: Boolean) =
    WidgetBitmaps.progressRing(
        context, sizeDp, RING_LINE_DP,
        fraction = if (justEnded) 1f else 0f,
        track = NT.Colors.ember.copy(alpha = 0.22f),
        center = text, centerSp = 0.32f * sizeDp,
    )

private fun exerciseName(context: Context, data: RestData): String =
    data.state.exerciseName.ifEmpty { context.getString(R.string.timer_rest) }

@Composable
private fun SmallIdle(context: Context, data: RestData, justEnded: Boolean, widthDp: Float) {
    Column(GlanceModifier.fillMaxSize()) {
        Eyebrow(context.getString(R.string.timer_rest), color = if (justEnded) W.ember else W.ink2, icon = R.drawable.ic_clock)
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.CenterStart) {
            if (justEnded) {
                Text(context.getString(R.string.timer_notification_title), style = W.title, maxLines = 2)
            } else {
                // The default length, big; the white capsule under it starts it.
                val text = Fmt.clock(data.defaultRestSeconds)
                BitmapImage(WidgetBitmaps.displayText(context, text, 60f, maxWidthDp = widthDp), description = text)
            }
        }
        PresetRow(data.defaultRestSeconds, W.buttonHeight)
    }
}

@Composable
private fun SmallRunning(context: Context, data: RestData, end: Long, widthDp: Float, heightDp: Float) {
    // The ring gets everything above the buttons.
    val ring = minOf(widthDp, heightDp - W.buttonHeight.value - 8f).coerceIn(56f, 150f)
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            Countdown(context, data, end, ring, RING_LINE_DP, 0.3f * ring)
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            LabelCapsule(PLUS_15, adjust(15), GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            LabelCapsule(context.getString(R.string.common_skip), skip(), GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun Medium(context: Context, data: RestData, end: Long?, justEnded: Boolean, heightDp: Float) {
    val ring = heightDp.coerceIn(64f, 160f)
    // Taller buttons when the right column has the room (the idle and "Go" states say little else).
    val buttons = if (end == null) 48.dp else 44.dp
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(ring.dp), contentAlignment = Alignment.Center) {
            if (end != null) {
                Countdown(context, data, end, ring, RING_LINE_DP, 0.3f * ring)
            } else {
                val text = Fmt.clock(data.defaultRestSeconds)
                BitmapImage(idleRing(context, ring, text, justEnded), description = text)
            }
        }
        Spacer(GlanceModifier.width(16.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(context.getString(R.string.timer_rest), color = if (end != null || justEnded) W.ember else W.ink2, icon = R.drawable.ic_clock)
            when {
                end != null -> {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(exerciseName(context, data), style = W.title, maxLines = 1)
                    if (data.state.nextSetLabel.isNotEmpty()) {
                        Text(data.state.nextSetLabel, style = W.footnote, maxLines = 1)
                    }
                }
                justEnded -> {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(context.getString(R.string.timer_notification_title), style = W.title, maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(12.dp))
            if (end != null) {
                Row(GlanceModifier.fillMaxWidth()) {
                    LabelCapsule(MINUS_15, adjust(-15), GlanceModifier.defaultWeight(), height = buttons)
                    Spacer(GlanceModifier.width(8.dp))
                    LabelCapsule(PLUS_15, adjust(15), GlanceModifier.defaultWeight(), height = buttons)
                    Spacer(GlanceModifier.width(8.dp))
                    LabelCapsule(context.getString(R.string.common_skip), skip(), GlanceModifier.defaultWeight(), height = buttons)
                }
            } else {
                PresetRow(data.defaultRestSeconds, buttons)
            }
        }
    }
}

private const val RING_LINE_DP = 9f

/** `1:00`, the default (white) and `2:00` — `1:00 · 1:30 · 2:00` when the default is one of the ends. */
@Composable
private fun PresetRow(defaultSeconds: Int, height: Dp) {
    val presets = RestPresets.of(defaultSeconds)
    Row(GlanceModifier.fillMaxWidth()) {
        presets.seconds.forEachIndexed { index, seconds ->
            if (index > 0) Spacer(GlanceModifier.width(6.dp))
            LabelCapsule(
                label = Fmt.clock(seconds),
                onClick = actionRunCallback<StartRestAction>(actionParametersOf(StartRestAction.SECONDS to seconds)),
                modifier = GlanceModifier.defaultWeight(),
                primary = seconds == presets.primary,
                height = height,
            )
        }
    }
}

/**
 * The draining ember ring (a bitmap, redrawn by [WidgetUpdater] every few seconds) with the live
 * countdown over it: a `Chronometer` counting down to the rest's end, rendered by the launcher.
 */
@Composable
private fun Countdown(context: Context, data: RestData, end: Long, ringDp: Float, lineDp: Float, textSp: Float) {
    val now = System.currentTimeMillis()
    Box(GlanceModifier.size(ringDp.dp), contentAlignment = Alignment.Center) {
        BitmapImage(WidgetBitmaps.progressRing(context, ringDp, lineDp, data.state.fractionRemaining(now).toFloat()))
        val views = RemoteViews(context.packageName, R.layout.widget_rest_countdown).apply {
            setChronometer(R.id.widget_rest_countdown, SystemClock.elapsedRealtime() + (end - now), null, true)
            setChronometerCountDown(R.id.widget_rest_countdown, true)
            setTextViewTextSize(R.id.widget_rest_countdown, TypedValue.COMPLEX_UNIT_SP, textSp)
        }
        AndroidRemoteViews(views)
    }
}

private fun adjust(delta: Int) =
    actionRunCallback<AdjustRestAction>(actionParametersOf(AdjustRestAction.DELTA to delta))

private fun skip() = actionRunCallback<SkipRestAction>()

/** Not in the catalog on either platform: the rest sheet's buttons read the same. */
private const val PLUS_15 = "+15"
private const val MINUS_15 = "−15"
