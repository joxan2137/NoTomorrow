package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NumericText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.net.dto.AIFood
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * `AIScanFoodRow` (`Features/Fuel/AIScanFoodRow.swift`) — one recognised food: name + macros, a
 * grams cell with a rescale menu, kcal on the right. 58 dp, hairline below.
 */
@Composable
fun AIScanFoodRow(
    food: AIFood,
    onScale: (Double) -> Unit,
    onSetGrams: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    Box(modifier.fillMaxWidth().height(58.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtText(
                        text = food.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = NT.Fonts.headline,
                        color = NT.Colors.ink,
                        maxLines = 1,
                    )
                    if (AIScanDerive.showsGuess(food)) {
                        Badge(text = stringResource(S.fuel_ai_guess), color = NT.Colors.ink2)
                    }
                }
                TabularText(
                    text = stringResource(
                        S.fuel_ai_macrosRow,
                        AIScanFormat.wholeGrams(food.protein),
                        AIScanFormat.wholeGrams(food.carbs),
                        AIScanFormat.wholeGrams(food.fat),
                    ),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }

            AIScanGramsCell(
                grams = food.grams,
                onScale = onScale,
                onCustom = {
                    customText = AIScanFormat.wholeGrams(food.grams)
                    showCustom = true
                },
            )

            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                NumericText(
                    text = Fmt.kcal(food.kcal, withUnit = false),
                    style = NT.Fonts.subheadline.tabular(),
                    color = NT.Colors.ink,
                    durationMs = 200,
                )
            }
        }
        Hairline(Modifier.align(Alignment.BottomCenter))
    }

    if (showCustom) {
        NtAlert(
            title = stringResource(S.fuel_ai_grams_customTitle),
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.common_done),
                    onClick = {
                        AIScanDerive.customGrams(customText)?.let(onSetGrams)
                        customText = ""
                        showCustom = false
                    },
                ),
                NtAlertAction(
                    title = stringResource(S.common_cancel),
                    role = NtAlertRole.Cancel,
                    onClick = { showCustom = false },
                ),
            ),
            onDismiss = { showCustom = false },
            field = {
                AIScanGramsField(value = customText, onValue = { customText = it })
            },
        )
    }
}

/**
 * `AIScanFoodRow.gramsMenu` — a 36 dp `surface2` cell (grams · "g" · chevron) in a 44 dp hit
 * box. The menu rescales kcal and macros proportionally.
 */
@Composable
private fun AIScanGramsCell(
    grams: Double,
    onScale: (Double) -> Unit,
    onCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val items = AIScanDerive.ScaleDeltas.map { delta ->
        NtMenuItem(
            title = Fmt.signedPercent(delta),
            onClick = { onScale(1 + delta) },
        )
    } + NtMenuItem(
        title = stringResource(S.fuel_ai_grams_custom),
        onClick = onCustom,
        icon = NtIcons.Pencil,
        // `Divider()` between the ±% group and "Custom…" — the only one in the iOS codebase.
        separatorBefore = true,
    )

    // iOS draws the pill at 36 pt but hangs `.contentShape(Rectangle())` off the 44 pt box, so
    // the whole control height is tappable.
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = NT.Size.control)
            .ntPlainClickable { expanded = true },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .background(NT.Colors.surface2, NtShapes.rounded(8.dp))
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumericText(
                text = AIScanFormat.wholeGrams(grams),
                style = NT.Fonts.subheadline.tabular(),
                color = NT.Colors.ink,
                durationMs = 200,
            )
            NtText(text = "g", style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
            NtIcon(NtIcons.ChevronDown, size = sfIconSize(10f), tint = NT.Colors.ink3)
        }
        NtMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

/** The inline field of the "Portion in grams" alert (`.keyboardType(.decimalPad)`). */
@Composable
private fun AIScanGramsField(
    value: String,
    onValue: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(NT.Colors.ground, NtShapes.field)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            NtText(
                text = stringResource(S.fuel_ai_grams_placeholder),
                style = NT.Fonts.body,
                color = NT.Colors.ink3,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            textStyle = NT.Fonts.body.tabular().copy(
                color = NT.Colors.ink,
                textAlign = TextAlign.Start,
            ),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ember),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
        )
    }
}
