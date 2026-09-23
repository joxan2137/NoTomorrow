package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NumericText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.MealSlot
import app.notomorrow.net.dto.AIFood
import app.notomorrow.util.Fmt
import app.notomorrow.util.LocaleProvider
import app.notomorrow.util.Parsing
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * `AIScanItemSheet` (`Features/Fuel/AIScanItemSheet.swift`) — edits one item of an AI estimate
 * before it is logged: name, count, grams (per unit or total) or a swap for a food-database
 * product. kcal and macros follow the item's per-100 g values; they are never typed here.
 *
 * Only the field being typed in drives the other: the total re-derives the grams per unit, the
 * per-unit weight re-derives the total. Done with nothing changed records no correction.
 */
@Composable
fun AIScanItemSheet(
    food: AIFood,
    meal: MealSlot,
    onDone: (AIFood) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocaleProvider.current()
    val text: (Double) -> String = { FuelDerive.portionText(it, locale) }
    var draft by remember { mutableStateOf(food) }
    var name by remember { mutableStateOf(food.name) }
    var perUnitText by remember { mutableStateOf(text(food.unitGrams)) }
    var totalText by remember { mutableStateOf(text(food.grams)) }
    var showsSearch by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val trimmedName = name.trim()
    val canSave = trimmedName.isNotEmpty() && draft.grams > 0
    val syncTexts = {
        perUnitText = text(draft.unitGrams)
        totalText = text(draft.grams)
    }
    val step: (Boolean) -> Unit = { up ->
        focusManager.clearFocus()
        draft = draft.withCount(AIScanCorrections.steppedCount(draft.units, up))
        syncTexts()
    }

    NtSheet(onDismiss = onDismiss, containerColor = NT.Colors.ground) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            AIScanItemHeader(onCancel = onDismiss)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // `.scrollDismissesKeyboard(.interactively)`.
                    .ntDismissKeyboardOnScroll()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AIScanItemSummary(draft = draft, modifier = Modifier.padding(bottom = 6.dp))
                AIScanItemTextRow(
                    label = stringResource(S.fuel_foodName),
                    value = name,
                    onValue = { name = it },
                )
                AIScanItemCountRow(draft = draft, locale = locale, onStep = step)
                if (draft.unitName != null) {
                    AIScanItemNumberRow(
                        label = stringResource(S.fuel_ai_item_gramsPerUnit),
                        value = perUnitText,
                        onValue = { typed ->
                            perUnitText = typed
                            val value = Parsing.nonNegative(typed)
                            if (value != null && value > 0) {
                                draft = draft.withGramsPerUnit(value)
                                totalText = text(draft.grams)
                            }
                        },
                    )
                }
                AIScanItemNumberRow(
                    label = stringResource(S.fuel_ai_item_totalGrams),
                    value = totalText,
                    onValue = { typed ->
                        totalText = typed
                        val value = Parsing.nonNegative(typed)
                        if (value != null && value > 0) {
                            draft = draft.scaled(value)
                            perUnitText = text(draft.unitGrams)
                        }
                    },
                )
                GhostButton(
                    title = stringResource(S.fuel_ai_item_findInDatabase),
                    modifier = Modifier.padding(top = 6.dp),
                    icon = NtIcons.MagnifyingGlass,
                    onClick = {
                        focusManager.clearFocus()
                        showsSearch = true
                    },
                )
                AIScanItemRemoveButton(onClick = onRemove)
            }

            PrimaryButton(
                title = stringResource(S.common_done),
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 12.dp),
                enabled = canSave,
                onClick = {
                    if (canSave) onDone(draft.copy(name = trimmedName))
                },
            )
        }
    }

    if (showsSearch) {
        FoodSearchSheet(
            meal = meal,
            day = LocalDate.now(),
            onDismiss = { showsSearch = false },
            pick = FoodSearchPick(
                mode = FoodSearchPick.Mode.Replace,
                title = stringResource(S.fuel_ai_item_findInDatabase),
            ) { picked, _ ->
                draft = draft.replacedBy(picked)
                name = draft.name
                syncTexts()
            },
        )
    }
}

@Composable
private fun AIScanItemHeader(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(horizontal = NT.Spacing.screenH)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = stringResource(S.fuel_ai_item_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = NT.Size.control)
                .ntPlainClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_cancel),
                style = NT.Fonts.body,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** Live kcal and macros for the draft, and its density ("200 kcal na 100 g"), which the portion scales. */
@Composable
private fun AIScanItemSummary(draft: AIFood, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NumericText(
                text = Fmt.kcal(draft.kcal, withUnit = false),
                style = NT.Fonts.display(40).tabular(),
                color = NT.Colors.ink,
                durationMs = 150,
            )
            NtText(
                text = stringResource(S.unit_kcal),
                modifier = Modifier.padding(bottom = 6.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            TabularText(
                text = stringResource(
                    S.fuel_ai_macrosTotal,
                    AIScanFormat.wholeGrams(draft.protein),
                    AIScanFormat.wholeGrams(draft.carbs),
                    AIScanFormat.wholeGrams(draft.fat),
                ),
                modifier = Modifier.padding(bottom = 6.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink,
            )
        }
        draft.kcalPer100?.let { per100 ->
            NtText(
                text = stringResource(S.fuel_ai_item_per100, Fmt.kcal(per100, withUnit = false)),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** 52 dp `surface` row: label on the left, the right-aligned name on the right. */
@Composable
private fun AIScanItemTextRow(label: String, value: String, onValue: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .ntPlainClickable(onClick = { runCatching { focus.requestFocus() } })
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .semantics { contentDescription = label },
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink, textAlign = TextAlign.End),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ink),
            // `.submitLabel(.done)`.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
    }
}

/**
 * "Count   − 6 szt. +": whole units, half a unit at the bottom, 99 at the top. One TalkBack node,
 * like iOS's adjustable element, with −1 / +1 as custom actions.
 */
@Composable
private fun AIScanItemCountRow(draft: AIFood, locale: java.util.Locale, onStep: (Boolean) -> Unit) {
    val label = stringResource(S.fuel_ai_item_count)
    val unit = draft.unitName.orEmpty()
    val count = FuelDerive.portionText(draft.units, locale)
    val countText = draft.unitName?.let { "$count $it" } ?: count
    val canDecrease = draft.units > 0.5
    val canIncrease = draft.units < AIScanCorrections.MAX_COUNT
    val minusLabel = stringResource(S.fuel_ai_item_minusOne, unit)
    val plusLabel = stringResource(S.fuel_ai_item_plusOne, unit)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .padding(start = 14.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = countText
                customActions = listOfNotNull(
                    if (canDecrease) CustomAccessibilityAction(minusLabel) { onStep(false); true } else null,
                    if (canIncrease) CustomAccessibilityAction(plusLabel) { onStep(true); true } else null,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        AIScanItemStepButton(icon = NtIcons.Minus, enabled = canDecrease, onClick = { onStep(false) })
        Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.Center) {
            ShrinkingText(
                text = countText,
                style = NT.Fonts.headline.tabular(),
                color = NT.Colors.ink,
                minScale = 0.8f,
            )
        }
        AIScanItemStepButton(icon = NtIcons.Plus, enabled = canIncrease, onClick = { onStep(true) })
    }
}

/** The stepper's 44 × 44 button: 15 sp glyph in `ink`, `ink3 @ 0.4` when disabled. */
@Composable
private fun AIScanItemStepButton(icon: NtIcons, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(NT.Size.control)
            .ntPlainClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(
            icon = icon,
            size = sfIconSize(15f),
            tint = if (enabled) NT.Colors.ink else NT.Colors.ink3.copy(alpha = 0.4f),
        )
    }
}

/** A 52 dp decimal row ("Gramy łącznie   210 g"); a tap anywhere on it focuses the field. */
@Composable
private fun AIScanItemNumberRow(label: String, value: String, onValue: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NT.Colors.surface, NtShapes.field)
            .ntPlainClickable(onClick = { runCatching { focus.requestFocus() } })
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(text = label, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) {
                NtText(text = "0", style = itemFieldStyle(NT.Colors.ink3), color = NT.Colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = label },
                textStyle = itemFieldStyle(NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
        }
        NtText(text = stringResource(S.unit_g), style = NT.Fonts.footnote, color = NT.Colors.ink2, maxLines = 1)
    }
}

/** "Usuń" in `bad`, full width, 44 dp. */
@Composable
private fun AIScanItemRemoveButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .ntPlainClickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Trash, size = sfIconSize(15f), tint = NT.Colors.bad)
        NtText(
            text = stringResource(S.fuel_ai_removeItem),
            style = NT.Fonts.subheadlineBold,
            color = NT.Colors.bad,
            maxLines = 1,
        )
    }
}

private fun itemFieldStyle(color: Color): TextStyle =
    NT.Fonts.body.tabular().copy(color = color, textAlign = TextAlign.End)
