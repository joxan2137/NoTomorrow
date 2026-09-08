package app.notomorrow.app

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A back-press scope for one tab page: an [OnBackPressedDispatcherOwner] of its own, forwarded to
 * the activity's dispatcher only while the page is visible.
 *
 * Every tab stays composed (`MainTabScaffold`), and Compose back handling — `BackHandler`, an inner
 * `NavHost`'s own pop — registers on whatever `LocalOnBackPressedDispatcherOwner` it finds, visible
 * or not. Without this a hidden Progress stack would answer the Today tab's back gesture. The
 * [forwarder] relays the predictive-back events too, so the inner stack still animates.
 */
internal class TabBackScope(lifecycleOwner: LifecycleOwner) :
    OnBackPressedDispatcherOwner,
    LifecycleOwner by lifecycleOwner {

    private var visible = false
    private var hasEnabled = false

    override val onBackPressedDispatcher: OnBackPressedDispatcher =
        OnBackPressedDispatcher(null) { enabled ->
            hasEnabled = enabled
            sync()
        }

    /** Registered on the activity's dispatcher; enabled only while this page can answer. */
    val forwarder: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBackPressedDispatcher.onBackPressed()

        override fun handleOnBackStarted(backEvent: BackEventCompat) =
            onBackPressedDispatcher.dispatchOnBackStarted(backEvent)

        override fun handleOnBackProgressed(backEvent: BackEventCompat) =
            onBackPressedDispatcher.dispatchOnBackProgressed(backEvent)

        override fun handleOnBackCancelled() = onBackPressedDispatcher.dispatchOnBackCancelled()
    }

    fun setVisible(value: Boolean) {
        visible = value
        sync()
    }

    private fun sync() {
        forwarder.isEnabled = visible && hasEnabled
    }
}

/** The scope for a page that is currently [visible], registered on the activity for its lifetime. */
@Composable
internal fun rememberTabBackScope(visible: Boolean): TabBackScope {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = remember(lifecycleOwner) { TabBackScope(lifecycleOwner) }
    SideEffect { scope.setVisible(visible) }
    val activity = LocalOnBackPressedDispatcherOwner.current
    DisposableEffect(activity, scope) {
        activity?.onBackPressedDispatcher?.addCallback(lifecycleOwner, scope.forwarder)
        onDispose { scope.forwarder.remove() }
    }
    return scope
}
