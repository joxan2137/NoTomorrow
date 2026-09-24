package app.notomorrow.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.min
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Rows
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Settings-style list row: optional leading visual, label, value, chevron.
 *
 * `Components.swift:236-259`. The `Spacer(minLength: 8)` sits *between* two
 * 12 dp `HStack` gaps, so the minimum label→value gap is 32 dp — reproduced
 * here with a weighted spacer that will not collapse below 8 dp.
 *
 * [chevron] defaults to `true`, matching `showsChevron` in `Components.swift:262`;
 * pass `chevron = false` for a read-only row that does not navigate.
 *
 * The SwiftUI row carries `.contentShape(Rectangle())` and lets the caller attach
 * the tap; [onClick] folds that in, and picks up `NTPressScale` through
 * [ntClickable].
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = NT.Colors.ink2,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NT.Size.control)
            .let { if (onClick != null) it.ntClickable(onClick = onClick) else it },
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        NtText(label, style = NT.Fonts.body, color = NT.Colors.ink)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        TabularText(value, style = NT.Fonts.body, color = valueColor, maxLines = 1)
        if (chevron) {
            NtIcon(
                icon = NtIcons.ChevronRight,
                size = sfIconSize(13f),
                tint = NT.Colors.ink3,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rings & bars
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `Components.swift:184-200`.
 *
 * SwiftUI's `Shape.stroke` is **centred on the path and is not clipped to the
 * frame**: `Circle().stroke(lineWidth: 6)` inside a frame of D renders an outer
 * diameter of D + 6. Insetting the arc rect (the naive port) would draw it one
 * stroke width too small on every ring in the app, so the geometry below is
 * derived from `size.minDimension` and the stroke is left centred.
 */
@Composable
fun ProgressRing(
    progress: Double,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 6.dp,
    color: Color = NT.Colors.ember,
    track: Color = NT.Colors.surface2,
    animated: Boolean = true,
) {
    val clamped = progress.coerceIn(0.0, 1.0).toFloat()
    val p = if (animated) {
        animateFloatAsState(
            targetValue = clamped,
            animationSpec = NT.Anim.easeOut60,
            label = "ProgressRing",
        ).value
    } else {
        clamped
    }
    Canvas(modifier) {
        val w = lineWidth.toPx()
        val d = size.minDimension
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        drawCircle(color = track, radius = d / 2f, center = center, style = Stroke(w))
        if (p > 0f) {
            drawArc(
                color = color,
                // `.rotationEffect(.degrees(-90))` — 12 o'clock.
                startAngle = -90f,
                sweepAngle = 360f * p,
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/** One arc of [MacroRing], as fractions of the circle from 12 o'clock. `macro` is 0 protein, 1 carbs, 2 fat. */
data class MacroArc(val start: Float, val end: Float, val macro: Int)

/**
 * `MacroRing.arcs` in `Components.swift`: each macro's share of the kcal goal (4 / 4 / 9 kcal per gram), laid
 * end to end clockwise, scaled down together past the goal so the ring reads full rather than wrapping. [gap] is
 * the fraction of the circumference left clear between arcs (between the round caps).
 */
fun macroRingArcs(protein: Double, carbs: Double, fat: Double, kcalGoal: Double, gap: Float): List<MacroArc> {
    if (kcalGoal <= 0.0) return emptyList()
    val shares = listOf(protein * 4, carbs * 4, fat * 9).map { (maxOf(0.0, it) / kcalGoal).toFloat() }
    val total = shares.sum()
    val scale = if (total > 1f) 1f / total else 1f
    val arcs = mutableListOf<MacroArc>()
    var cursor = 0f
    shares.forEachIndexed { index, share ->
        val length = share * scale
        if (length > gap) arcs += MacroArc(cursor, cursor + length - gap, index)
        cursor += length
    }
    return arcs
}

/**
 * `Components.swift` `MacroRing` (v2): the kcal ring split by where the calories came from — protein,
 * carbs and fat arcs in [NT.Colors.protein] / [NT.Colors.carbs] / [NT.Colors.fat] over [track].
 * Same geometry as [ProgressRing] (stroke centred on a circle of `size.minDimension`); the gap between
 * arcs is `lineWidth + 3` dp of arc length.
 */
@Composable
fun MacroRing(
    protein: Double,
    carbs: Double,
    fat: Double,
    kcalGoal: Double,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 6.dp,
    track: Color = NT.Colors.surface2,
) {
    val p by animateFloatAsState(protein.toFloat(), NT.Anim.easeOut60, label = "MacroRingP")
    val c by animateFloatAsState(carbs.toFloat(), NT.Anim.easeOut60, label = "MacroRingC")
    val f by animateFloatAsState(fat.toFloat(), NT.Anim.easeOut60, label = "MacroRingF")
    val colors = listOf(NT.Colors.protein, NT.Colors.carbs, NT.Colors.fat)
    Canvas(modifier) {
        val w = lineWidth.toPx()
        val d = size.minDimension
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        drawCircle(color = track, radius = d / 2f, center = center, style = Stroke(w))
        val circumference = (Math.PI * d).toFloat()
        val gap = if (circumference > 0f) (w + 3.dp.toPx()) / circumference else 0f
        macroRingArcs(p.toDouble(), c.toDouble(), f.toDouble(), kcalGoal, gap).forEach { arc ->
            drawArc(
                color = colors[arc.macro],
                startAngle = -90f + 360f * arc.start,
                sweepAngle = 360f * (arc.end - arc.start),
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * `Components.swift:202-228`. Label + "value / goal unit" over a 4 dp capsule.
 *
 * ⚠️ The value line is **not localised on iOS** (plain `Int` interpolation, an
 * ASCII slash and a hardcoded unit). Keep it that way, or Android and iOS will
 * disagree on a screen that sits side by side in the design.
 */
@Composable
fun MacroBar(
    label: String,
    value: Double,
    goal: Double,
    modifier: Modifier = Modifier,
    unit: String = "g",
    fill: Color = NT.Colors.ink2,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtText(label, style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            TabularText(
                text = "${value.roundToInt()} / ${goal.roundToInt()} $unit",
                style = NT.Fonts.footnote,
                color = NT.Colors.ink,
            )
        }
        val fraction = if (goal > 0.0) min(1.0, value / goal).toFloat() else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(NT.Colors.surface2, CircleShape)
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(fill, CircleShape)
                )
            }
        }
    }
}

/**
 * Three 10 × 4 dp bars — the AI-estimate confidence readout.
 * (Contract row `ConfidenceDots`.)
 *
 * @param level 0–3 lit bars.
 */
@Composable
fun ConfidenceDots(level: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(width = 10.dp, height = 4.dp)
                    .background(
                        color = if (i < level) NT.Colors.ink else NT.Colors.ink.copy(alpha = 0.2f),
                        shape = NtShapes.rounded(2.dp),
                    )
            )
        }
    }
}

/** Filled square used as a legend swatch / status dot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** A ring with something centred inside it — the fuel summary and rest pill shape. */
@Composable
fun ProgressRingBox(
    progress: Double,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 6.dp,
    color: Color = NT.Colors.ember,
    track: Color = NT.Colors.surface2,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ProgressRing(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            lineWidth = lineWidth,
            color = color,
            track = track,
        )
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Design system", showBackground = true, backgroundColor = 0xFF0A0A0B)
@Composable
private fun NtComponentsPreview() {
    NTTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .background(NT.Colors.ground)
                .padding(NT.Spacing.screenH),
            verticalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        ) {
            SectionHeader("Section header", trailing = "trailing")
            PrimaryButton("Primary", icon = NtIcons.Plus) {}
            SecondaryButton("Secondary", height = NT.Size.cardButton) {}
            GhostButton("Ghost", icon = NtIcons.Plus) {}
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Selected", selected = true) {}
                Chip("Plain", icon = NtIcons.Flame, tint = NT.Colors.ember) {}
            }
            NTCard {
                Eyebrow("Next session")
                MacroBar("Protein", value = 122.0, goal = 180.0)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Volume", "12 480 kg", modifier = Modifier.weight(1f))
                StatTile("Sets", "24", modifier = Modifier.weight(1f))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(0.62, Modifier.size(64.dp))
                Avatar("m")
                Badge("PR")
                ConfidenceDots(2)
            }
            Hairline()
            ValueRow("Rest", "90 s", onClick = {})
            ValueRow("Weight", "82.4 kg", chevron = false)
        }
    }
}
