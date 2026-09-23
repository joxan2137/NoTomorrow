package app.notomorrow.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the app is on screen — `UIApplication.applicationState == .active` for the one question
 * Android has to ask by hand: how a rest that runs out should reach the user
 * (`RestTimerController`). `MainActivity` counts itself in on `onStart` and out on `onStop`, so a
 * locked screen or another app in front reads as "away" even while the Activity is alive.
 */
object AppVisibility {

    private val started = AtomicInteger(0)

    val isForeground: Boolean get() = started.get() > 0

    fun onStart() {
        started.incrementAndGet()
    }

    fun onStop() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
