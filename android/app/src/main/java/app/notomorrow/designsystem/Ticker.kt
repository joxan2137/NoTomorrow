package app.notomorrow.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.notomorrow.rest.ticker

/**
 * The app's clock — the replacement for SwiftUI's seven `TimelineView`s (five at 1 s, two at 60 s).
 *
 * Emits `System.currentTimeMillis()` on a **wall-clock-aligned** period, so every ticking label on
 * screen flips on the same tick and a minute counter changes exactly when the minute does. It stops
 * while the screen is not `STARTED`, and the value is re-read on resume, so nothing accumulates
 * drift across a backgrounded app.
 *
 * Never derive a countdown by subtracting elapsed time from a previous value: recompute it from the
 * absolute `endAt` in `RestTimerController` every tick.
 *
 * ```kotlin
 * val now by rememberSecondTicker()
 * TabularText(Fmt.clock(state.remaining(now).toInt()))
 * ```
 *
 * @param periodMs 1000 for a seconds counter, 60000 for a minutes one.
 */
@Composable
fun rememberSecondTicker(periodMs: Long = 1_000L): State<Long> {
    val owner = LocalLifecycleOwner.current
    val initial = remember { mutableLongStateOf(System.currentTimeMillis()) }
    return produceState(initialValue = initial.longValue, periodMs, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = System.currentTimeMillis()
            ticker(periodMs).collect { value = it }
        }
    }
}
