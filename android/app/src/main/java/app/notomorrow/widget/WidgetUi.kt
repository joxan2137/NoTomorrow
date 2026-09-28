package app.notomorrow.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
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
 * `12 pt bold` uppercase eyebrows, `surface2` capsules (the primary one white on `ground`), 40 dp
 * tall. Glance has no semibold: `Bold` carries every strong style so the widgets read at a glance.
 */
internal object W {
    val ground = ColorProvider(NT.Colors.ground)
    val ink = ColorProvider(NT.Colors.ink)
    val ink2 = ColorProvider(NT.Colors.ink2)
    val ink3 = ColorProvider(NT.Colors.ink3)
    val ember = ColorProvider(NT.Colors.ember)
    val good = ColorProvider(NT.Colors.good)

    fun color(color: Color): ColorProvider = ColorProvider(color)

    val eyebrow = TextStyle(color = ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    val subheadlineBold = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val button = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val footnote = TextStyle(color = ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium)

    val margin: Dp = 16.dp
    val buttonHeight: Dp = 40.dp

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
    background: Int = R.drawable.widget_background,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(background))
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

/**
 * An eyebrow: uppercase, `ink2` unless [color] says otherwise (the next session, the countdown),
 * after an [icon] in [iconColor] (the text's colour unless given).
 */
@Composable
internal fun Eyebrow(
    text: String,
    color: ColorProvider = W.ink2,
    modifier: GlanceModifier = GlanceModifier,
    icon: Int? = null,
    iconColor: ColorProvider = color,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Glyph(icon, 14.dp, iconColor)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = text.uppercase(LocaleProvider.current()),
            style = W.eyebrow.copy(color = color),
            maxLines = 1,
        )
    }
}

/** A `surface2` capsule button (white with `ground` content when [primary]). */
@Composable
internal fun Capsule(
    onClick: Action,
    modifier: GlanceModifier = GlanceModifier,
    primary: Boolean = false,
    horizontalPadding: Dp = 14.dp,
    height: Dp = W.buttonHeight,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(height)
            .background(ImageProvider(if (primary) R.drawable.widget_capsule_primary else R.drawable.widget_capsule))
            .clickable(onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** A capsule holding one short label (`1:00`, `+15`, Skip). */
@Composable
internal fun LabelCapsule(
    label: String,
    onClick: Action,
    modifier: GlanceModifier = GlanceModifier,
    primary: Boolean = false,
    height: Dp = W.buttonHeight,
) {
    // The label is centred, so it needs little padding: three of these share a small widget's width.
    Capsule(onClick, modifier, primary, horizontalPadding = 2.dp, height = height) {
        Text(
            text = label,
            style = W.button.copy(color = if (primary) W.ground else W.ink),
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
