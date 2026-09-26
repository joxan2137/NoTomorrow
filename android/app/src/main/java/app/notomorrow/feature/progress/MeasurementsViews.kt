package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.MeasurementKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Progress › Body, under the weight card — `MeasurementsSection`: the latest of each measurement
 * and its change since the first one, and Log measurements.
 */
@Composable
fun MeasurementsSection(unit: WeightUnit, modifier: Modifier = Modifier) {
    val dao = LocalAppContainer.current.db.bodyMeasurementDao()
    val rows by remember(dao) { dao.observeAll() }.collectAsState(initial = emptyList())
    val summaries = Measurements.summaries(Measurements.readings(rows))
    var showsLog by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(S.measure_title), modifier = Modifier.padding(bottom = 4.dp))
        if (summaries.isEmpty()) {
            NtText(
                text = stringResource(S.measure_empty),
                modifier = Modifier.padding(vertical = 12.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            summaries.forEachIndexed { index, summary ->
                if (index > 0) Hairline()
                SummaryRow(summary, unit)
            }
        }
        SecondaryButton(
            title = stringResource(S.measure_log),
            modifier = Modifier.padding(top = 12.dp),
            icon = NtIcons.Plus,
            height = NT.Size.cardButton,
            onClick = { showsLog = true },
        )
    }

    if (showsLog) {
        LogMeasurementsSheet(
            unit = unit,
            last = summaries.associate { it.kind to it.latest.value },
            onDismiss = { showsLog = false },
        )
    }
}

@Composable
private fun SummaryRow(summary: Measurements.Summary, unit: WeightUnit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NtText(stringResource(NtKeys.measure(summary.kind)), style = NT.Fonts.subheadline, color = NT.Colors.ink)
            NtText(
                text = Fmt.dayMonth(Days.date(summary.latest.day)),
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
            )
        }
        val change = summary.change
        if (change != null && abs(change) >= 0.05) {
            TabularText(
                text = Measurements.label(change, summary.kind, unit, signed = true),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
        NtText(
            text = Measurements.label(summary.latest.value, summary.kind, unit),
            modifier = Modifier.widthIn(min = 84.dp),
            style = NT.Fonts.headline.tabular(),
            color = NT.Colors.ink,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * `LogMeasurementsSheet` — one field per measurement, last values as placeholders; Save writes
 * today's readings for the filled fields. [last] is the latest stored value per kind (cm / %).
 */
@Composable
private fun LogMeasurementsSheet(
    unit: WeightUnit,
    last: Map<MeasurementKind, Double>,
    onDismiss: () -> Unit,
) {
    val dao = LocalAppContainer.current.db.bodyMeasurementDao()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val texts = remember { mutableStateMapOf<MeasurementKind, String>() }
    val parsed = Measurements.parsed(texts, unit)

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                NtText(stringResource(S.common_cancel), style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
            }
            NtText(
                text = stringResource(S.measure_log),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(enabled = parsed.isNotEmpty()) {
                        val values = parsed
                        scope.launch {
                            Measurements.save(values, dao)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.common_save),
                    style = NT.Fonts.headline,
                    color = if (parsed.isEmpty()) NT.Colors.ink3 else NT.Colors.ember,
                    maxLines = 1,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .ntDismissKeyboardOnScroll()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp, bottom = NT.Spacing.section),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NtText(Fmt.longDay(LocalDate.now()), style = NT.Fonts.footnote, color = NT.Colors.ink2)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NT.Colors.surface, NtShapes.tile)
                    .padding(horizontal = 16.dp),
            ) {
                MeasurementKind.entries.forEachIndexed { index, kind ->
                    if (index > 0) Hairline()
                    MeasurementField(
                        kind = kind,
                        unit = unit,
                        text = texts[kind].orEmpty(),
                        last = last[kind],
                        onText = { texts[kind] = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasurementField(
    kind: MeasurementKind,
    unit: WeightUnit,
    text: String,
    last: Double?,
    onText: (String) -> Unit,
) {
    val placeholder = last?.let { Measurements.number(Measurements.display(it, kind, unit)) } ?: "—"
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NT.Size.control),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = stringResource(NtKeys.measure(kind))
        val fieldLabel = title + ", " + Measurements.unitLabel(kind, unit)
        NtText(title, modifier = Modifier.clearAndSetSemantics {}, style = NT.Fonts.body, color = NT.Colors.ink, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Box(Modifier.width(90.dp), contentAlignment = Alignment.CenterEnd) {
            if (text.isEmpty()) {
                NtText(placeholder, style = NT.Fonts.body.tabular(), color = NT.Colors.ink3, maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel },
                textStyle = NT.Fonts.body.tabular().copy(color = NT.Colors.ink, textAlign = TextAlign.End),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        NtText(
            text = Measurements.unitLabel(kind, unit),
            modifier = Modifier.widthIn(min = 24.dp).clearAndSetSemantics {},
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}
