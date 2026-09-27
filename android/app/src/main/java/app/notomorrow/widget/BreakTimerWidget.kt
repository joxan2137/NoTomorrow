package app.notomorrow.widget

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
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
 * Break timer (`nt.widget.rest`, `docs/widgets.md`). Idle: the default rest and three lengths that
 * start a rest with no app launch (`RestTimerController.start`, so the ongoing notification, the
 * end alert and the chime all follow). Running: the ember ring with a live, system-rendered
 * countdown (a `Chronometer`), `+15` / Skip (and `−15` on medium). For two minutes after a rest
 * runs out, the idle layout says "Rest is over". Anywhere else opens the workout (and its rest).
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
    val height = size.height.value - 2 * W.margin.value
    val background = if (justEnded) R.drawable.widget_background_go else R.drawable.widget_background
    WidgetFrame(onClick = W.open(context, AppState.Route.RestTimer), background = background) {
        when {
            medium -> Medium(context, data, end, justEnded, height)
            end != null -> SmallRunning(context, data, end, height)
            else -> SmallIdle(context, data, justEnded)
        }
    }
}

private fun caption(context: Context, justEnded: Boolean): String =
    context.getString(if (justEnded) R.string.timer_notification_title else R.string.widget_rest_start)

/** The caption under the length: quiet while idle, bold `ink` once the rest just ended. */
private fun captionStyle(justEnded: Boolean) = if (justEnded) W.subheadlineBold else W.footnote

/** The idle ring: a faint ember track waiting to be filled; full ember once the rest just ended. */
private fun idleRing(context: Context, sizeDp: Float, text: String, justEnded: Boolean) =
    WidgetBitmaps.progressRing(
        context, sizeDp, 6f,
        fraction = if (justEnded) 1f else 0f,
        track = NT.Colors.ember.copy(alpha = 0.22f),
        center = text, centerSp = 34f * sizeDp / 110f,
    )

private fun exerciseName(context: Context, data: RestData): String =
    data.state.exerciseName.ifEmpty { context.getString(R.string.timer_rest) }

@Composable
private fun SmallIdle(context: Context, data: RestData, justEnded: Boolean) {
    Column(GlanceModifier.fillMaxSize()) {
        Eyebrow(context.getString(R.string.timer_rest), color = if (justEnded) W.ember else W.ink2, icon = R.drawable.ic_clock)
        val text = Fmt.clock(data.defaultRestSeconds)
        BitmapImage(WidgetBitmaps.displayText(context, text, 44f), description = text)
        Text(caption(context, justEnded), style = captionStyle(justEnded), maxLines = 2)
        Spacer(GlanceModifier.defaultWeight())
        PresetRow(data.defaultRestSeconds)
    }
}

@Composable
private fun SmallRunning(context: Context, data: RestData, end: Long, heightDp: Float) {
    // Exercise line 16, buttons 36, two 6 dp gaps: the ring gets the rest, up to 84 dp.
    val ring = (heightDp - 16f - W.buttonHeight.value - 12f).coerceIn(56f, 84f)
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
            Countdown(context, data, end, ring, 6f, 26f * ring / 84f)
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(exerciseName(context, data), style = W.footnote.copy(color = W.ink), maxLines = 1)
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            LabelCapsule(PLUS_15, adjust(15), GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            LabelCapsule(context.getString(R.string.common_skip), skip(), GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun Medium(context: Context, data: RestData, end: Long?, justEnded: Boolean, heightDp: Float) {
    val ring = heightDp.coerceIn(64f, 110f)
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(ring.dp), contentAlignment = Alignment.Center) {
            if (end != null) {
                Countdown(context, data, end, ring, 6f, 34f * ring / 110f)
            } else {
                val text = Fmt.clock(data.defaultRestSeconds)
                BitmapImage(idleRing(context, ring, text, justEnded), description = text)
            }
        }
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(context.getString(R.string.timer_rest), color = if (end != null || justEnded) W.ember else W.ink2, icon = R.drawable.ic_clock)
            Spacer(GlanceModifier.height(2.dp))
            if (end != null) {
                Text(exerciseName(context, data), style = W.headline, maxLines = 1)
                if (data.state.nextSetLabel.isNotEmpty()) {
                    Text(data.state.nextSetLabel, style = W.footnote, maxLines = 1)
                }
            } else {
                Text(caption(context, justEnded), style = captionStyle(justEnded), maxLines = 2)
            }
            Spacer(GlanceModifier.height(8.dp))
            if (end != null) {
                Row(GlanceModifier.fillMaxWidth()) {
                    LabelCapsule(MINUS_15, adjust(-15), GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    LabelCapsule(PLUS_15, adjust(15), GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    LabelCapsule(context.getString(R.string.common_skip), skip(), GlanceModifier.defaultWeight())
                }
            } else {
                PresetRow(data.defaultRestSeconds)
            }
        }
    }
}

/** `1:00`, the default (white) and `2:00` — `1:00 · 1:30 · 2:00` when the default is one of the ends. */
@Composable
private fun PresetRow(defaultSeconds: Int) {
    val presets = RestPresets.of(defaultSeconds)
    Row(GlanceModifier.fillMaxWidth()) {
        presets.seconds.forEachIndexed { index, seconds ->
            if (index > 0) Spacer(GlanceModifier.width(6.dp))
            LabelCapsule(
                label = Fmt.clock(seconds),
                onClick = actionRunCallback<StartRestAction>(actionParametersOf(StartRestAction.SECONDS to seconds)),
                modifier = GlanceModifier.defaultWeight(),
                primary = seconds == presets.primary,
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
