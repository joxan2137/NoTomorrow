package app.notomorrow.designsystem

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService

/**
 * How much of the material this device can actually render (`docs/android-glass.md` §2.2).
 *
 * | Tier | API | Blur | Lens | Rim | Transfer |
 * |---|---|---|---|---|---|
 * | [Full] | 33+ | `createBlurEffect` under the AGSL pass | AGSL SDF | AGSL | exact |
 * | [Blur] | 31-32 | `createBlurEffect` | none | static 1 dp stroke | exact, via `ColorMatrix` |
 * | [Tint] | 26-30 | none | none | static 1 dp stroke | `GlassStyle.flatFill` |
 *
 * `ColorMatrix` is the reason tiers [Full] and [Blur] share their colours exactly and differ only
 * in the rim and the lens. Only [Tint] is visibly different, and over `NT.Colors.ground` it lands
 * within 1/255 of the real thing.
 *
 * `minSdk` is 26, so all three ship. The developer's own phone is API 36 and will only ever
 * exercise [Full]: the other two are a policy/screenshot-test concern, not a manual-testing one.
 */
enum class GlassTier { Full, Blur, Tint }

/** The tier every `Modifier.liquidGlass` in this composition renders at. */
val LocalGlassTier = staticCompositionLocalOf { defaultGlassTier() }

/** API-level ceiling, before any runtime downgrade. */
internal fun defaultGlassTier(): GlassTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassTier.Full
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlassTier.Blur
    else -> GlassTier.Tint
}

/**
 * The tier to actually provide, recomputed (never cached once) for the runtime conditions that
 * must degrade the material: battery saver, animations switched off, and — the Android analogue of
 * iOS's Reduce Transparency / Reduce Motion (§1.10) — a zero animator duration scale.
 *
 * [GlassTier.Tint] is the target of every downgrade.
 */
@Composable
fun rememberGlassTier(): State<GlassTier> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(resolveGlassTier(context)) }
    DisposableEffect(context) {
        // Battery saver flips at runtime and is the single most likely downgrade in the field.
        val filter = android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                state.value = resolveGlassTier(context)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

internal fun resolveGlassTier(context: Context): GlassTier {
    if (GlassDebug.forcedTier != null) return GlassDebug.forcedTier!!
    val power = context.getSystemService<PowerManager>()
    if (power?.isPowerSaveMode == true) return GlassTier.Tint
    val animatorScale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    if (animatorScale == 0f) return GlassTier.Tint
    return defaultGlassTier()
}

/**
 * Debug-only switches. Nothing here is read in a release build except [forcedTier], which is only
 * ever written by [GlassProbeScreen].
 */
object GlassDebug {
    /** Overrides [LocalGlassTier] so the probe can shoot all three tiers on one device. */
    var forcedTier: GlassTier? by mutableStateOf(null)

    /** Shows [GlassProbeScreen] over the tab shell. Debug builds only. */
    var showProbe: Boolean by mutableStateOf(false)

    /**
     * `false` makes the tab bar draw its rest-state additive pill instead of the moving lens, on
     * every tier. Only the debug intent in `MainActivity` writes it; it exists to price the lens.
     */
    var pillLens: Boolean by mutableStateOf(true)

    /**
     * Pricing switches for the AGSL pass, set from the debug intent before the first glass draw:
     * [shaderPassthrough] swaps the material for `content.eval(coord)` (needs a fresh process — the
     * compiled shader is process-wide), [noAggregate] pins the lift to `liftLo` (drops the 9-tap
     * loop), [noBlur] leaves the blur out of the effect chain.
     */
    @Volatile var shaderPassthrough: Boolean = false
    @Volatile var noAggregate: Boolean = false
    @Volatile var noBlur: Boolean = false
}
