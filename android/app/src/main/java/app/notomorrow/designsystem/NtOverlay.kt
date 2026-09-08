package app.notomorrow.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import android.view.View

/**
 * The in-composition presentation layer that menus, alerts and action sheets are drawn into
 * (`docs/android-glass.md` §3.6 `NtMenu`: *"Material 3's `DropdownMenu` renders in a `Popup`, i.e. a
 * separate window with its own `GraphicsContext`, so it cannot sample our `GraphicsLayer`. Render
 * the menu inside the root `Box` instead"*).
 *
 * A `Popup`/`Dialog` is a second window: it has its own `GraphicsContext`, so glass drawn inside it
 * can never sample [NtBackdrop] and always degrades to a flat fill — and a `Dialog` additionally
 * swallows every touch, which is the opposite of iOS 26, where an inline action sheet "**also lets
 * people interact with other parts of the interface**" (Apple, *Adopting Liquid Glass*).
 *
 * So the app hosts one of these in `RootScreen`, as a sibling drawn **after** the `NavHost` that
 * records the backdrop. Anything registered through [NtOverlayContent] is composed there, above
 * every screen and above the tab bar, in the same window and therefore able to sample the backdrop.
 *
 * **A host only serves its own window.** [currentNtOverlayHost] returns `null` for a caller inside a
 * sheet (a `ModalBottomSheet` is its own window, stacked *above* this host — a panel drawn here
 * would be hidden behind it), and those call sites keep the `Popup`/`Dialog` path, which renders
 * the same measured flat fill.
 */
@Stable
class NtOverlayState internal constructor(internal val owner: View) {

    internal val entries = mutableStateListOf<NtOverlayEntry>()

    /** Where the host sits in window coordinates, so an anchored panel can be placed from it. */
    internal var origin: Offset by mutableStateOf(Offset.Zero)
        private set

    /** The host's own size, for clamping a panel inside it. */
    internal var size: IntSize by mutableStateOf(IntSize.Zero)
        private set

    internal fun onPositioned(coordinates: LayoutCoordinates) {
        origin = coordinates.positionInWindow()
        size = coordinates.size
    }
}

internal class NtOverlayEntry(val content: @Composable () -> Unit)

/** No host by default: a component composed outside the shell falls back to its own window. */
val LocalNtOverlayHost = staticCompositionLocalOf<NtOverlayState?> { null }

/** Creates the app's single overlay host state. Call it once, in `RootScreen`. */
@Composable
fun rememberNtOverlayState(): NtOverlayState {
    val view = LocalView.current
    return remember(view) { NtOverlayState(view) }
}

/**
 * The nearest host that lives in **this** window, or `null` when the caller is inside a `Popup` or
 * `Dialog` (a sheet) — in which case the caller must present itself the old way.
 */
@Composable
fun currentNtOverlayHost(): NtOverlayState? {
    val host = LocalNtOverlayHost.current ?: return null
    return if (host.owner === LocalView.current) host else null
}

/**
 * Draws everything registered with [NtOverlayContent]. Place it last in the root `Box` — after the
 * node that wears [ntBackdropSource], so its glass may legally sample the recording.
 *
 * The host itself is inert: an empty `Box` is not a hit-test target, so with nothing registered the
 * UI underneath stays exactly as interactive as it was.
 */
@Composable
fun NtOverlayHost(state: NtOverlayState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { state.onPositioned(it) },
    ) {
        state.entries.forEach { entry -> key(entry) { entry.content() } }
    }
}

/**
 * Composes [content] in [host] instead of here, for as long as this call site is in composition.
 *
 * The lambda is re-read through [rememberUpdatedState], so state the caller captures still drives
 * recomposition; what changes is only *where* the nodes are emitted. Composition locals resolve
 * against the host, which is the point — the panel needs the host's `LocalNtBackdrop`, not a
 * `Popup`'s.
 */
@Composable
internal fun NtOverlayContent(host: NtOverlayState, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(content)
    val entry = remember { NtOverlayEntry { current() } }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}
