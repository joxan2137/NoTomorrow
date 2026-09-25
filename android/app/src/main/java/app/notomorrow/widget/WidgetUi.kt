package app.notomorrow.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.notomorrow.MainActivity
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.designsystem.NT
import app.notomorrow.push.NtPushIntents
import app.notomorrow.util.LocaleProvider

/**
 * The widgets' shared look (`docs/widgets.md`, "Look"): `ground` background, 16 dp margins,
 * `11 pt semibold` uppercase eyebrows, `surface2` capsules (the primary one white on `ground`), at
 * least 36 dp tall. Glance has no semibold: `Medium` stands in for it, `Bold` for the bold styles.
 */
internal object W {
    val ground = ColorProvider(NT.Colors.ground)
    val ink = ColorProvider(NT.Colors.ink)
    val ink2 = ColorProvider(NT.Colors.ink2)
    val ink3 = ColorProvider(NT.Colors.ink3)
    val ember = ColorProvider(NT.Colors.ember)
    val good = ColorProvider(NT.Colors.good)
    val hairline = ColorProvider(NT.Colors.hairline)

    fun color(color: Color): ColorProvider = ColorProvider(color)

    val eyebrow = TextStyle(color = ink2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val headline = TextStyle(color = ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val subheadlineBold = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val footnote = TextStyle(color = ink2, fontSize = 13.sp)
    val caption = TextStyle(color = ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)

    val margin: Dp = 16.dp
    val buttonHeight: Dp = 36.dp

    fun text(style: TextStyle, color: ColorProvider? = null, size: TextUnit? = null): TextStyle =
        style.copy(color = color ?: style.color, fontSize = size ?: style.fontSize)

    /** Opens the app on [route] (`notomorrow://…` on iOS), through `MainActivity.handlePushIntent`. */
    fun open(context: Context, route: AppState.Route): Action = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NtPushIntents.EXTRA_ROUTE, route.wire),
    )
}

/** The widget surface: `ground`, rounded, 16 dp content margins, [onClick] anywhere outside buttons. */
@Composable
internal fun WidgetFrame(
    onClick: Action,
    padding: Dp = W.margin,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background))
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(onClick)
            .padding(padding),
    ) {
        content()
    }
}

/** `widget.setup` — no store, or not onboarded yet. The tap opens the app. */
@Composable
internal fun SetupContent(context: Context) {
    WidgetFrame(onClick = W.open(context, AppState.Route.Today)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(context.getString(R.string.widget_setup), style = W.footnote, maxLines = 4)
        }
    }
}

/** An eyebrow: uppercase, `ink2` unless [color] says otherwise (the next session, the countdown). */
@Composable
internal fun Eyebrow(text: String, color: ColorProvider = W.ink2, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = text.uppercase(LocaleProvider.current()),
        style = W.eyebrow.copy(color = color),
        maxLines = 1,
        modifier = modifier,
    )
}

/** A `surface2` capsule button (white with `ground` content when [primary]). */
@Composable
internal fun Capsule(
    onClick: Action,
    modifier: GlanceModifier = GlanceModifier,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(W.buttonHeight)
            .background(ImageProvider(if (primary) R.drawable.widget_capsule_primary else R.drawable.widget_capsule))
            .clickable(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** A capsule holding one short label (`1:00`, `+15`, Skip). */
@Composable
internal fun LabelCapsule(label: String, onClick: Action, modifier: GlanceModifier = GlanceModifier, primary: Boolean = false) {
    Capsule(onClick, modifier, primary) {
        Text(
            text = label,
            style = W.subheadlineBold.copy(color = if (primary) W.ground else W.ink),
            maxLines = 1,
        )
    }
}

/** One of the app's 24 dp glyphs (`ic_plus`, `ic_checkmark`), tinted. */
@Composable
internal fun Glyph(res: Int, size: Dp, color: ColorProvider) {
    Image(
        provider = ImageProvider(res),
        contentDescription = null,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

/** A rendered bitmap at its own dp size. */
@Composable
internal fun BitmapImage(sized: WidgetBitmaps.Sized, description: String? = null, modifier: GlanceModifier = GlanceModifier) {
    Image(
        provider = ImageProvider(sized.bitmap),
        contentDescription = description,
        modifier = modifier.size(sized.widthDp.dp, sized.heightDp.dp),
    )
}
