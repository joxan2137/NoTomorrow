package app.notomorrow.widget

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.glance.appwidget.SizeMode
import kotlinx.coroutines.CancellationException

/**
 * The data half of every widget: loaded once before `provideContent` (so the first frame is
 * right), then again whenever [WidgetUpdater] moves the widget's revision — a running Glance
 * session recomposes on `update` but never calls `provideGlance` again.
 */
internal class Preloaded<T>(val value: T, val revision: Long)

internal suspend fun <T : Any> preload(kind: WidgetKind, load: suspend () -> T?): Preloaded<T?> {
    val revision = WidgetUpdater.revision(kind).value
    return Preloaded(guarded(kind, load), revision)
}

@Composable
internal fun <T : Any> rememberWidgetData(kind: WidgetKind, first: Preloaded<T?>, load: suspend () -> T?): T? {
    val revision by WidgetUpdater.revision(kind).collectAsState()
    val state = produceState(first.value, revision) {
        if (revision != first.revision) value = guarded(kind, load)
    }
    return state.value
}

/** A read that fails (a store gone bad under the widget) shows `widget.setup` instead of Glance's error view. */
private suspend fun <T : Any> guarded(kind: WidgetKind, load: suspend () -> T?): T? = try {
    load()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("WidgetData", "Could not load the $kind widget", e)
    null
}

/**
 * Every widget sizes to what the launcher really gives it: bitmaps (rings, grids) are drawn at
 * the exact dp size, and the small / medium / large layouts switch on it (`docs/widgets.md`).
 */
internal val WidgetSizeMode: SizeMode = SizeMode.Exact

/** Width from which a widget uses its medium (two-column) layout. */
internal const val MEDIUM_MIN_WIDTH_DP = 230f
