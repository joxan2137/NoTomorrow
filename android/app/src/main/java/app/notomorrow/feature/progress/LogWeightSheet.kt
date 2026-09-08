package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * "Log weight" — the port of `LogWeightSheet`
 * (`Features/Progress/LogWeightSheet.swift`): one number field that writes today's
 * `BodyWeightEntry` (and mirrors it to Health when allowed).
 *
 * iOS presents it at a fixed `.height(340)` detent with the drag indicator hidden and a
 * `ground` background, so the sheet draws its own [Grabber].
 */
@Composable
fun LogWeightSheet(
    unit: WeightUnit,
    suggestedKg: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val parsedKg = parseWeight(text, unit)

    LaunchedEffect(Unit) { focus.requestFocus() }

    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.ground,
        // `.presentationDetents([.height(340)])` as a *minimum*, not a fixed detent — the Polish
        // strings and a large text size can push the block past 340, where iOS's own detent
        // would scroll and this simply grows. The keyboard, which this sheet always launches
        // into (`onAppear { focused = true }`), no longer eats into it: [NtSheet] raises a sized
        // card whole, exactly as iOS raises the detent.
        minHeight = SHEET_HEIGHT,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The card is ≥ 340 dp, but a Column relaxes the minimum for its children, so
                // without this the block would wrap its content and `SpaceBetween` would have
                // no slack to distribute — the Save button would ride up under the field.
                .heightIn(min = contentMinHeight())
                .padding(horizontal = NT.Spacing.screenH),
            // Two blocks with all the slack between them: the Compose form of SwiftUI's single
            // flexible `Spacer(minLength: 16)` above the Save button. `Modifier.weight` cannot
            // do it here — a weighted child is measured against the *maximum* height, so it
            // would stretch the card to the whole screen instead of to the 340 dp minimum.
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Grabber()

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtText(
                        text = stringResource(S.progress_logWeight),
                        style = NT.Fonts.title2,
                        color = NT.Colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    // `.buttonStyle(.plain)` — tappable, no press feedback.
                    Box(
                        modifier = Modifier
                            .height(NT.Size.control)
                            .ntPlainClickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        NtText(
                            text = stringResource(S.common_cancel),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Eyebrow(stringResource(S.progress_todaysWeight))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(NT.Colors.surface, NtShapes.field)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                NtText(
                                    text = placeholder(suggestedKg, unit),
                                    style = NT.Fonts.display(44).tabular(),
                                    color = NT.Colors.ink3,
                                    maxLines = 1,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                textStyle = NT.Fonts.display(44).tabular()
                                    .copy(color = NT.Colors.ink),
                                singleLine = true,
                                cursorBrush = SolidColor(NT.Colors.ink),
                                // `.keyboardType(.decimalPad)` — a decimal pad has no return
                                // key, so Save is the only way to commit, on iOS and here.
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                ),
                            )
                        }
                        NtText(
                            text = unit.raw,
                            style = NT.Fonts.title2,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                    }
                    NtText(
                        text = Fmt.longDay(LocalDate.now()),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
            }

            PrimaryButton(
                title = stringResource(S.common_save),
                // `Spacer(minLength: 16)`: 16 dp survives even when the card is tight.
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                enabled = parsedKg != null,
                onClick = { parsedKg?.let(onSave) },
            )
        }
    }
}

/** iOS `.presentationDetents([.height(340)])`. */
private val SHEET_HEIGHT = 340.dp

/**
 * What is left of [SHEET_HEIGHT] once the navigation bar has taken its share — the content box
 * of the iOS detent. It mirrors `NtSheet`'s own inset chain, which raises a *sized* card above
 * the keyboard whole (as iOS raises the detent) and so reduces the bar's inset to zero while the
 * keyboard is up. Read from composition, not from the padding modifiers, which only lay out.
 */
@Composable
private fun contentMinHeight(): Dp {
    val density = LocalDensity.current
    val bars = WindowInsets.navigationBars.getBottom(density)
    val ime = WindowInsets.ime.getBottom(density)
    val bottom = (bars - ime).coerceAtLeast(0)
    return with(density) { (SHEET_HEIGHT.roundToPx() - bottom).coerceAtLeast(0).toDp() }
}

/**
 * `LogWeightSheet.parsedKg` — comma or dot, strictly between 0 and 500 in the *displayed*
 * unit, stored in kilograms.
 */
internal fun parseWeight(text: String, unit: WeightUnit): Double? {
    val value = text.replace(',', '.').trim().toDoubleOrNull() ?: return null
    if (value <= 0 || value >= 500) return null
    return if (unit == WeightUnit.Kg) value else value / Fmt.LB_PER_KG
}

/**
 * The last reading, or iOS's two hardcoded stand-ins ("82,5" / "180") — deliberately not
 * localized there, so they are not localized here either.
 */
internal fun placeholder(suggestedKg: Double?, unit: WeightUnit): String {
    if (suggestedKg == null) return if (unit == WeightUnit.Kg) "82,5" else "180"
    return Fmt.weight(suggestedKg, unit, withUnit = false)
}
