package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NumericText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * Bottom sheet that sizes a portion of one food and logs it as a `MealEntry` —
 * the port of `Features/Fuel/PortionSheet.swift`.
 *
 * Fixed 376 dp detent, its own [Grabber], `surface` background. The kcal readout is a
 * `display(40)` numeric-text counter; the stepper moves in 10 g steps and never goes
 * below 5 g.
 */
@Composable
fun PortionSheet(
    food: PortionFood,
    meal: MealSlot,
    day: LocalDate,
    onDismiss: () -> Unit,
    onAdded: () -> Unit = onDismiss,
) {
    val model = ntViewModel(key = "portion") { container ->
        PortionViewModel(
            foodDao = container.db.foodDao(),
            mealDao = container.db.mealDao(),
            foodSearch = container.foodSearchService,
        )
    }
    LaunchedEffect(food.id) { model.bind(food) }
    // iOS builds a fresh `PortionSheet` per presentation; the view model is reused, so it
    // has to forget the last portion when the sheet leaves the composition.
    DisposableEffect(Unit) { onDispose { model.unbind() } }
    val state by model.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.surface,
        height = PORTION_SHEET_HEIGHT,
        // `PortionSheet.swift:52` `.presentationCornerRadius(24)`.
        cornerRadius = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Grabber()

            PortionTitleRow(food = food, kcal = state.kcal)

            PortionStepperRow(
                gramsText = state.gramsText,
                onGramsText = model::setGramsText,
                onMinus = {
                    focusManager.clearFocus()
                    model.step(-FuelDerive.PORTION_STEP)
                },
                onPlus = {
                    focusManager.clearFocus()
                    model.step(FuelDerive.PORTION_STEP)
                },
                onFocusField = { runCatching { focus.requestFocus() } },
                focusRequester = focus,
            )

            PortionChips(
                grams = state.grams,
                servingSizeG = food.servingSizeG,
                servingLabel = food.servingLabel,
                onSelect = {
                    focusManager.clearFocus()
                    model.setGrams(it)
                },
            )

            PortionMacroRow(protein = state.protein, carbs = state.carbs, fat = state.fat)

            PrimaryButton(
                title = stringResource(S.fuel_addTo, stringResource(NtKeys.meal(meal))),
                enabled = state.grams > 0,
                onClick = { model.add(meal = meal, day = day, onAdded = onAdded) },
            )
        }
    }
}

/** `.presentationDetents([.height(376)])`. */
val PORTION_SHEET_HEIGHT = 376.dp

@Composable
private fun PortionTitleRow(food: PortionFood, kcal: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NtText(
                text = food.name,
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 2,
            )
            val source = stringResource(NtKeys.foodSource(food.source))
            NtText(
                text = food.brand?.let { "$it · $source" } ?: source,
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.widthIn(min = 8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BaselineAnchored(
                style = NT.Fonts.display(40).tabular(),
                modifier = Modifier.alignByBaseline(),
            ) {
                NumericText(
                    text = Fmt.kcal(kcal, withUnit = false),
                    style = NT.Fonts.display(40).tabular(),
                    color = NT.Colors.ink,
                )
            }
            NtText(
                text = stringResource(S.unit_kcal),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/**
 * Publishes a real `FirstBaseline` for content that does not carry one.
 *
 * `NumericText` is a `Row` of `AnimatedContent` slots, and that chain does not surface the
 * digits' baseline, so `Modifier.alignByBaseline()` on the neighbouring "kcal" would resolve
 * against the top of the ticker and stack the two lines. A zero-size ruler glyph in the same
 * style is measured (never placed) and its baseline republished for the parent `Row`.
 */
@Composable
private fun BaselineAnchored(
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            content()
            NtText(text = "0", style = style, color = NT.Colors.ink, maxLines = 1)
        },
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints)
        val ruler = measurables[1].measure(Constraints())
        val baseline = ruler[FirstBaseline]
        val lines: Map<AlignmentLine, Int> = if (baseline == AlignmentLine.Unspecified) {
            emptyMap()
        } else {
            mapOf<AlignmentLine, Int>(FirstBaseline to baseline)
        }
        layout(body.width, body.height, lines) {
            body.place(0, 0)
            // The ruler is measured for its baseline only — never placed, so never drawn.
        }
    }
}

/** −/+ 48 dp circles around a 48 dp `ground` field with a 1.5 dp `ink` border. */
@Composable
private fun PortionStepperRow(
    gramsText: String,
    onGramsText: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onFocusField: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(NtIcons.Minus, onClick = onMinus)

        val shape = NtShapes.rounded(14.dp)
        // `HStack(alignment: .firstTextBaseline, spacing: 6) { … }.frame(maxWidth: .infinity)
        //  .frame(height: 48)` — the *stack* is baseline-aligned, and the 48 pt frame then
        // centres that whole stack inside itself. A Compose `Row` cannot do both at once: the
        // moment a child asks for `alignByBaseline()` the row anchors the baseline group to its
        // own top edge, which parked "40 g" 29.5 px (≈10 dp) above the field centre on
        // `design/parity/android-r1/18-portion-sheet.png` where iOS measures dead centre. So the
        // 48 dp box is a Box that centres, and the baseline row inside it wraps its content.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .background(NT.Colors.ground, shape)
                .border(1.5.dp, NT.Colors.ink, shape)
                .ntClickable(onClick = onFocusField),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                // `.fixedSize(horizontal: true).frame(minWidth: 40)`: the field hugs its own text
                // so the number + unit pair centres as a unit inside the 48 dp box. The box also
                // sizes to the "fuel.grams" placeholder while the field is empty, as the SwiftUI
                // `TextField` does.
                // The 40 dp minimum lives on the box, not on the field: `width(IntrinsicSize.Min)`
                // fixes the field to its own text width and would swallow a `widthIn` sitting
                // outside it, so the numeral ended up centred on the field instead of the 40 pt
                // box SwiftUI centres (`.fixedSize(horizontal:).frame(minWidth: 40)`).
                Box(
                    modifier = Modifier
                        .alignByBaseline()
                        .widthIn(min = 40.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (gramsText.isEmpty()) {
                        NtText(
                            text = stringResource(S.fuel_grams),
                            style = NT.Fonts.title2.tabular(),
                            color = NT.Colors.ink3,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = gramsText,
                        onValueChange = onGramsText,
                        modifier = Modifier
                            .widthIn(min = 40.dp)
                            .width(IntrinsicSize.Min)
                            .focusRequester(focusRequester),
                        textStyle = NT.Fonts.title2.tabular().copy(
                            color = NT.Colors.ink,
                            textAlign = TextAlign.End,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(NT.Colors.ink),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {}),
                    )
                }
                NtText(
                    text = stringResource(S.unit_g),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }

        StepButton(NtIcons.Plus, onClick = onPlus)
    }
}

@Composable
private fun StepButton(icon: NtIcons, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(onClick = onClick)
            .background(NT.Colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtIcon(icon, size = sfIconSize(17f), tint = NT.Colors.ink)
    }
}

/** 100 g, plus the pack's serving when it declares one that is not 100 g. */
@Composable
private fun PortionChips(
    grams: Double,
    servingSizeG: Double?,
    servingLabel: String?,
    onSelect: (Double) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortionChip(
            value = 100.0,
            label = null,
            selected = FuelDerive.isPortionSelected(grams, 100.0),
            onSelect = onSelect,
        )
        if (FuelDerive.showsServingChip(servingSizeG)) {
            val serving = servingSizeG ?: 0.0
            PortionChip(
                value = serving,
                label = servingLabel ?: stringResource(S.fuel_serving),
                selected = FuelDerive.isPortionSelected(grams, serving),
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PortionChip(
    value: Double,
    label: String?,
    selected: Boolean,
    onSelect: (Double) -> Unit,
) {
    val text = label?.let { "${Fmt.grams(value)} · $it" } ?: Fmt.grams(value)
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = NT.Size.control)
            .pressScale(onClick = { onSelect(value) }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(
                    if (selected) NT.Colors.surface3 else NT.Colors.surface2,
                    CircleShape,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            TabularText(text = text, style = NT.Fonts.footnote, color = NT.Colors.ink)
        }
    }
}

@Composable
private fun PortionMacroRow(protein: Double, carbs: Double, fat: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PortionMacro(stringResource(S.macro_protein), protein)
        PortionMacro(stringResource(S.macro_carbs), carbs)
        PortionMacro(stringResource(S.macro_fat), fat)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PortionMacro(label: String, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(text = label, color = NT.Colors.ink3)
        TabularText(
            text = Fmt.grams(value),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
        )
    }
}
