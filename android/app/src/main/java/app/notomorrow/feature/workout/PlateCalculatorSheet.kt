package app.notomorrow.feature.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Chip
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.feature.progress.OneRepMaxCalculatorSheet
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Plate calculator for one set's weight — 1:1 port of `PlateCalculatorSheet.swift` (the "Plates"
 * accessory over the keyboard in the active workout): bar choice, a drawing of the loaded bar, the
 * plates per side, and, when the plates cannot make the weight, the closest load with Use.
 *
 * [weightKg] is the set's stored weight; [onUse] replaces it (kg) with a load the plates can make.
 * The bar is remembered per unit (`nt.plates.barKg` / `nt.plates.barLb`).
 */
@Composable
fun PlateCalculatorSheet(
    weightKg: Double,
    unit: WeightUnit,
    onUse: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = LocalAppContainer.current.appPrefs
    val scope = rememberCoroutineScope()
    val defaultBar = PlateMath.bars(unit).first()
    val bar by remember(unit) { prefs.plateBar(unit) }.collectAsState(initial = defaultBar)
    val target = SetInput.display(weightKg, unit)
    val load = PlateMath.load(target, bar, PlateMath.plates(unit), limit = PlateMath.maxTarget(unit))
    var showsOneRepMax by rememberSaveable { mutableStateOf(false) }

    NtSheet(
        onDismiss = onDismiss,
        showsHandle = true,
        containerColor = NT.Colors.ground,
        minHeight = ntMediumDetent(),
        skipPartiallyExpanded = false,
    ) {
        Header(onDismiss = onDismiss)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Spacing.section),
        ) {
            TabularText(Fmt.plate(target, unit), style = NT.Fonts.display(44), color = NT.Colors.ink)
            BarPicker(
                unit = unit,
                bar = bar,
                modifier = Modifier.padding(top = 16.dp),
                onSelect = { value -> scope.launch { prefs.setPlateBar(unit, value) } },
            )
            BarbellDrawing(
                perSide = load.perSide,
                unit = unit,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .height(120.dp)
                    .clearAndSetSemantics {},
            )
            PerSideList(load = load, unit = unit, modifier = Modifier.padding(top = 20.dp))
            if (load.isOverMax) {
                Note(stringResource(S.plates_overMax_s, Fmt.plate(PlateMath.maxTarget(unit), unit)))
            } else if (load.isBelowBar) {
                Note(stringResource(S.plates_belowBar))
            } else if (!load.isExact) {
                val label = Fmt.plate(load.total, unit)
                Note(stringResource(S.plates_notExact_s, label))
                SecondaryButton(
                    title = stringResource(S.plates_use_s, label),
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        onUse(SetInput.kg(load.total, unit))
                        onDismiss()
                    },
                )
            }
            GhostButton(
                title = stringResource(S.onerm_calculator),
                modifier = Modifier.padding(top = NT.Spacing.section),
                onClick = { showsOneRepMax = true },
            )
        }
    }
    if (showsOneRepMax) {
        OneRepMaxCalculatorSheet(unit = unit, onDismiss = { showsOneRepMax = false }, initialWeight = target)
    }
}

@Composable
private fun Header(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(stringResource(S.plates_title), style = NT.Fonts.title2, color = NT.Colors.ink, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .heightIn(min = NT.Size.control)
                .ntPlainClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            NtText(stringResource(S.common_done), style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
        }
    }
}

@Composable
private fun BarPicker(unit: WeightUnit, bar: Double, modifier: Modifier, onSelect: (Double) -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(S.plates_bar))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in PlateMath.bars(unit)) {
                Chip(title = Fmt.plate(value, unit), selected = value == bar, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun PerSideList(load: PlateMath.Load, unit: WeightUnit, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(S.plates_perSide))
        if (load.perSide.isEmpty()) {
            NtText(stringResource(S.plates_emptyBar), style = NT.Fonts.body, color = NT.Colors.ink2)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NT.Colors.surface, NtShapes.tile)
                    .padding(horizontal = 16.dp),
            ) {
                load.groups.forEachIndexed { index, group ->
                    if (index > 0) Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NT.Size.control)
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(PlateStyle.color(group.plate, unit), CircleShape))
                        TabularText(Fmt.plate(group.plate, unit), style = NT.Fonts.body, color = NT.Colors.ink)
                        Spacer(Modifier.weight(1f))
                        TabularText(
                            text = Fmt.TIMES + " " + group.count,
                            style = NT.Fonts.headline,
                            color = NT.Colors.ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    NtText(
        text = text,
        modifier = Modifier.padding(top = 16.dp),
        style = NT.Fonts.footnote,
        color = NT.Colors.ink2,
    )
}

// MARK: - Drawing

/**
 * A barbell seen from the front: the sleeve on each side with its plates, heaviest nearest the
 * middle, taller for heavier plates, in the usual competition colours.
 */
@Composable
private fun BarbellDrawing(perSide: List<Double>, unit: WeightUnit, modifier: Modifier) {
    val shaft = NT.Colors.surface3
    val collar = NT.Colors.ink3
    val maxPlate = PlateMath.plates(unit).first()
    Canvas(modifier) {
        val midY = size.height / 2
        // Shaft and sleeves.
        drawRoundRect(
            color = shaft,
            topLeft = Offset(0f, midY - 4.dp.toPx()),
            size = Size(size.width, 8.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        for (x in listOf(size.width * 0.3f, size.width * 0.7f)) {
            drawRect(
                color = collar,
                topLeft = Offset(x - 3.dp.toPx(), midY - 13.dp.toPx()),
                size = Size(6.dp.toPx(), 26.dp.toPx()),
            )
        }
        side(perSide, unit, maxPlate, leading = true)
        side(perSide, unit, maxPlate, leading = false)
    }
}

private fun DrawScope.side(perSide: List<Double>, unit: WeightUnit, maxPlate: Double, leading: Boolean) {
    val midY = size.height / 2
    val collarX = if (leading) size.width * 0.3f else size.width * 0.7f
    val room = size.width * 0.3f - 4.dp.toPx()
    val plateWidth = min(16.dp.toPx(), max(5.dp.toPx(), room / max(perSide.size, 1) - 2.dp.toPx()))
    val radius = CornerRadius(3.dp.toPx())
    perSide.forEachIndexed { index, plate ->
        val offset = 6.dp.toPx() + (plateWidth + 2.dp.toPx()) * index + plateWidth / 2
        val ratio = max(0.35, sqrt(plate / maxPlate)).toFloat()
        val height = size.height * ratio
        val centerX = if (leading) collarX - offset else collarX + offset
        val topLeft = Offset(centerX - plateWidth / 2, midY - height / 2)
        drawRoundRect(PlateStyle.color(plate, unit), topLeft, Size(plateWidth, height), radius)
        // `strokeBorder(.black.opacity(0.35), lineWidth: 1)` — drawn inside the plate's edge.
        val inset = 0.5.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
            size = Size(plateWidth - 2 * inset, height - 2 * inset),
            cornerRadius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Competition plate colours by weight (red 25 · blue 20 · yellow 15 · green 10 · white 5 · then small plates). */
internal object PlateStyle {
    fun color(plate: Double, unit: WeightUnit): Color {
        val kg = if (unit == WeightUnit.Kg) plate else plate / Fmt.LB_PER_KG
        return when {
            kg >= 24 -> Color(0xFFE5484D)
            kg >= 19 -> Color(0xFF3E7BFA)
            kg >= 14 -> Color(0xFFF5C04A)
            kg >= 9 -> Color(0xFF30A46C)
            kg >= 4 -> Color(0xFFEDEDED)
            kg >= 2 -> Color(0xFF6E6E73)
            else -> Color(0xFFB8B8BD)
        }
    }
}
