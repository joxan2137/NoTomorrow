package app.notomorrow.designsystem.effects

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

/**
 * The clock behind the libraries.dev effects ([ThinkingOrb], [BorderBeam], [LiquidMetalSurface]) —
 * SwiftUI's `TimelineView(.animation)`: seconds of the shared frame clock, so every instance on
 * screen stays in phase, and nothing ticks once the composable leaves the composition.
 *
 * Read the returned state **inside a draw lambda** so a tick only redraws, never recomposes.
 */
@Composable
internal fun rememberEffectTime(running: Boolean = true): State<Double> {
    val time = remember { mutableDoubleStateOf(0.0) }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { time.doubleValue = it / 1_000_000_000.0 }
        }
    }
    return time
}

/**
 * `\.accessibilityReduceMotion`: Android has no such switch, the nearest is "Remove animations"
 * (animator duration scale 0) — the same signal `resolveGlassTier` uses.
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) == 0f
    }
}
