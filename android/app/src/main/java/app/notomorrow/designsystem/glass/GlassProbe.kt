package app.notomorrow.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The verification harness (`docs/android-glass.md` §4). Debug builds only — reachable from a long
 * press on the dead strip beside the tab-bar capsule, and from nowhere else.
 *
 * Pixel-diffing iOS against Android is impossible (402 dp vs 360 dp changes every layout), so
 * verification runs on derived quantities measured the same way on both. This screen makes that
 * possible: it renders the **same engineered backdrops the iOS probe harness used** — a 10 dp grid
 * with 1 dp lines, a hard black/white edge at the horizontal centre, 8 dp stripes and the flat
 * greys — under a replica of the chrome, at a forced **402 × 874 dp** logical size so an
 * `adb exec-out screencap -p` is numerically comparable to `design/ios26-reference/probe/tabs.png`,
 * `edgeTabs.png`, `grayTabs.png`, `whiteTabs.png` and `hstripeTabs.png` pixel for pixel.
 *
 * What to check, and the acceptance from §4:
 *  1. transfer — body value over flat 0 / 10 / 128 / 255 within ±2/255 of 51 / 28 / 89 / 253;
 *  2. edge spread — 10-90 % width across the hard edge, 7.0 ± 1.0 pt;
 *  3. interior displacement — the ramp's 50 % point within 0.5 pt of the true edge;
 *  4. rim — 59 / 51 / 42 / body inward, ≤ 3/255 asymmetry between the four edges;
 *  6. pill lift — +31 ± 3 over every backdrop, with no rim (a hard step);
 *  7. no shadow — the ground pixel outside the rim equals the backdrop exactly.
 *
 * The tier selector re-runs all of that on `Blur` and `Tint` without an API 30 device.
 */
@Composable
fun GlassProbeScreen(onClose: () -> Unit) {
    val widthPx = LocalWindowInfo.current.containerSize.width
    // Force the iOS logical size so the capture lines up with the iOS probe PNGs 1:1.
    val forced = remember(widthPx) {
        Density(density = widthPx / IOS_PROBE_WIDTH_DP, fontScale = 1f)
    }

    var backdropIndex by remember { mutableIntStateOf(0) }
    val pattern = ProbeBackdrop.entries[backdropIndex % ProbeBackdrop.entries.size]
    val probeBackdrop = rememberNtBackdrop()
    val tier = GlassDebug.forcedTier ?: LocalGlassTier.current

    CompositionLocalProvider(
        LocalDensity provides forced,
        LocalNtBackdrop provides probeBackdrop,
        LocalGlassTier provides tier,
    ) {
        Box(Modifier.fillMaxSize()) {
            // The engineered backdrop is the captured subtree; everything after it samples it.
            Box(Modifier.fillMaxSize().ntBackdropSource(probeBackdrop)) {
                ProbePattern(pattern, Modifier.fillMaxSize())
            }

            ProbeChrome(Modifier.align(Alignment.Center))

            ProbeControls(
                pattern = pattern,
                tier = tier,
                onCyclePattern = { backdropIndex++ },
                onCycleTier = { GlassDebug.forcedTier = nextTier(tier) },
                onClose = {
                    GlassDebug.forcedTier = null
                    onClose()
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** 402 pt — the iPhone 17 Pro logical width every reference capture was taken at. */
private const val IOS_PROBE_WIDTH_DP = 402f

private fun nextTier(current: GlassTier): GlassTier = when (current) {
    GlassTier.Full -> GlassTier.Blur
    GlassTier.Blur -> GlassTier.Tint
    GlassTier.Tint -> GlassTier.Full
}

/** The five engineered backdrops from the iOS probe harness, plus this app's real ground. */
enum class ProbeBackdrop(val label: String) {
    Grid10("grid 10dp"),
    EdgeBlackWhite("hard edge"),
    Stripes8("stripes 8dp"),
    FlatBlack("flat #000"),
    Ground("flat ground"),
    Gray50("flat 50% grey"),
    White("flat #FFF"),
}

@Composable
private fun ProbePattern(pattern: ProbeBackdrop, modifier: Modifier) {
    Box(
        modifier.drawBehind {
            when (pattern) {
                ProbeBackdrop.Grid10 -> {
                    drawRect(Color.Black)
                    val step = 10.dp.toPx()
                    val line = 1.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawRect(Color.White, Offset(x, 0f), Size(line, size.height))
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawRect(Color.White, Offset(0f, y), Size(size.width, line))
                        y += step
                    }
                }

                ProbeBackdrop.EdgeBlackWhite -> {
                    drawRect(Color.Black)
                    drawRect(
                        Color.White,
                        Offset(size.width / 2f, 0f),
                        Size(size.width / 2f, size.height),
                    )
                }

                ProbeBackdrop.Stripes8 -> {
                    drawRect(Color.Black)
                    val step = 16.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawRect(Color.White, Offset(0f, y), Size(size.width, step / 2f))
                        y += step
                    }
                }

                ProbeBackdrop.FlatBlack -> drawRect(Color.Black)
                ProbeBackdrop.Ground -> drawRect(NT.Colors.ground)
                ProbeBackdrop.Gray50 -> drawRect(Color(0xFF808080))
                ProbeBackdrop.White -> drawRect(Color.White)
            }
        },
    )
}

/**
 * A replica of the chrome, not the live components: the probe must stay independent of `AppTab`,
 * the nav graph and the window insets so it can force its own geometry. The numbers mirror
 * `NtTabBarTokens` (§1.2) and `NtGlassButtonTokens` (§1.9) — if either moves, move these too.
 */
@Composable
private fun ProbeChrome(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ProbePlatter()

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NtGlassButton(onClick = {}, title = "Done")
            Box(
                Modifier
                    .size(44.dp)
                    .liquidGlass(CapsuleShape, GlassStyle.Regular),
            )
        }

        // The two large-surface presets, side by side: a menu DARKENS its backdrop (89 -> 48)
        // where a panel lifts it. That inversion is the fastest way to spot a wrong preset.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(140.dp, 96.dp)
                    .liquidGlass(RoundedCornerShape(33.dp), GlassStyle.Menu),
            )
            Box(
                Modifier
                    .size(140.dp, 96.dp)
                    .liquidGlass(RoundedCornerShape(34.dp), GlassStyle.Alert),
            )
        }

        // The wheel band: flat #EBEBF5 @ 8.5 %, not glass. Must measure 21 over #000 and 44 over
        // `surface`. This is the SHIPPING `NtWheelBand`, not a `GlassStyle.WheelBand` replica —
        // the probe verified a code path the app never executed, so a regression in the real band
        // would have passed §4 row 9. `NtWheelBand` fills its parent's width, so the 320 dp box
        // reproduces the picker it lives in and the band lands at 302 × 34 inside it.
        Box(Modifier.width(320.dp)) { NtWheelBand() }
    }
}

/** The 402 dp / 5-tab platter: 360 × 62, r 31, items 77 × 54 at pitch 68.75, pill on item 0. */
@Composable
private fun ProbePlatter() {
    val platterWidth = IOS_PROBE_WIDTH_DP.dp - 42.dp
    val pitch = (platterWidth - 16.25.dp) / 5
    val itemWidth = pitch + 8.25.dp
    Box(
        Modifier
            .width(platterWidth)
            .height(62.dp)
            .liquidGlass(CapsuleShape, GlassStyle.Regular)
            .drawBehind {
                drawRoundRect(
                    color = Color(0.129f, 0.129f, 0.129f, 1f),
                    topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                    size = Size(itemWidth.toPx(), 54.dp.toPx()),
                    cornerRadius = CornerRadius(27.dp.toPx()),
                    blendMode = BlendMode.Plus,
                )
            },
    )
}

@Composable
private fun ProbeControls(
    pattern: ProbeBackdrop,
    tier: GlassTier,
    onCyclePattern: () -> Unit,
    onCycleTier: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(top = 48.dp, start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProbeChip(pattern.label, onCyclePattern)
        ProbeChip(tier.name, onCycleTier)
        ProbeChip("close", onClose)
    }
}

@Composable
private fun ProbeChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xCC000000), CapsuleShape)
            .ntPlainClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        NtText(
            text = label,
            style = NT.Fonts.caption.copy(fontSize = 11.sp),
            color = Color.White,
            maxLines = 1,
        )
    }
}
