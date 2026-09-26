package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTPressScale
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.effects.LiquidMetalPreset
import app.notomorrow.designsystem.effects.LiquidMetalSurface
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.util.Locale

private val ExerciseDetailsWidth = 44.dp

/** Trailing inset of the last-set column inside a result card: details button, the 26 dp ring, spacing. */
internal val ExerciseLastColumnTrailing = ExerciseDetailsWidth + 26.dp + 12.dp

/**
 * One search result as a card (`ExerciseResultCard`, `ExercisePickerRow.swift`): the picker row and
 * a details button on a `surface` tile. A selected exercise's tile turns liquid metal — a silver
 * edge from [LiquidMetalSurface] — so the picks stand out while scrolling.
 */
@Composable
fun ExerciseResultCard(
    entry: ExercisePickerEntry,
    state: ExercisePickerRowState,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val detailsLabel = stringResource(S.exercises_details)
    val haptics = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val menuItems = listOfNotNull(
        onEdit?.let { NtMenuItem(title = stringResource(S.customExercise_edit), onClick = it, icon = NtIcons.Pencil) },
        onDelete?.let {
            NtMenuItem(
                title = stringResource(S.customExercise_delete),
                onClick = it,
                destructive = true,
                icon = NtIcons.Trash,
            )
        },
    )
    // `.contextMenu` on a custom exercise's card: Edit exercise and, while it is unused, Delete exercise.
    val content: @Composable () -> Unit = {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExercisePickerRow(
                    entry = entry,
                    state = state,
                    onClick = onToggle,
                    onLongClick = if (menuItems.isEmpty()) {
                        null
                    } else {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuExpanded = true
                        }
                    },
                    unit = unit,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
                Box(
                    modifier = Modifier
                        .width(ExerciseDetailsWidth)
                        .height(64.dp)
                        .pressScale(onClick = onDetails)
                        .semantics { contentDescription = detailsLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    NtIcon(icon = NtIcons.InfoCircle, size = sfIconSize(17f), tint = NT.Colors.ink2)
                }
            }
            NtMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }, items = menuItems)
        }
    }
    if (state == ExercisePickerRowState.Selected) {
        LiquidMetalSurface(
            cornerRadius = NT.Radius.tile,
            modifier = modifier.fillMaxWidth(),
            preset = LiquidMetalPreset.Silver,
            fill = NT.Colors.surface,
        ) { content() }
    } else {
        Box(modifier.fillMaxWidth().background(NT.Colors.surface, NtShapes.tile)) { content() }
    }
}

/**
 * 64 pt picker row: name + muscles, last set (or "Never done"), then a selection ring or "In"
 * (`NoTomorrow/Features/Workout/ExercisePickerRow.swift`).
 */
@Composable
fun ExercisePickerRow(
    entry: ExercisePickerEntry,
    state: ExercisePickerRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
    /** The long press (a custom exercise's menu); it works on a row already in the workout too. */
    onLongClick: (() -> Unit)? = null,
) {
    val alreadyIn = state == ExercisePickerRowState.AlreadyIn
    val press = if (onLongClick == null) {
        Modifier.pressScale(enabled = !alreadyIn, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = NTPressScale,
            role = Role.Button,
            onClick = { if (!alreadyIn) onClick() },
            onLongClick = onLongClick,
        )
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(press)
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NtText(
                    text = entry.exercise.localizedName(),
                    style = NT.Fonts.headline,
                    color = if (alreadyIn) NT.Colors.ink2 else NT.Colors.ink,
                    maxLines = 1,
                )
                NtText(
                    text = exerciseSubtitle(entry.exercise),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            val last = entry.lastSet
            if (last != null) {
                TabularText(
                    text = Fmt.set(last.weightKg, last.reps, unit),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            } else {
                NtText(
                    text = stringResource(S.exercises_neverDone),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier.width(26.dp).height(NT.Size.control),
                contentAlignment = Alignment.Center,
            ) {
                ExercisePickerRowTrailing(state)
            }
        }
    }
}

/** "In", a filled check, or an empty ring. */
@Composable
private fun ExercisePickerRowTrailing(state: ExercisePickerRowState) {
    when (state) {
        ExercisePickerRowState.AlreadyIn -> NtText(
            text = stringResource(S.exercises_inWorkout),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink3,
            maxLines = 1,
        )

        ExercisePickerRowState.Selected -> Box(
            modifier = Modifier.size(26.dp).background(NT.Colors.ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(
                icon = NtIcons.Checkmark,
                size = sfIconSize(13f),
                tint = NT.Colors.onPrimary,
            )
        }

        ExercisePickerRowState.Available -> Box(
            Modifier.size(26.dp).border(1.5.dp, NT.Colors.ink3, CircleShape),
        )
    }
}

/**
 * `+ Create "bench" as a new exercise` — 48 pt row under the results
 * (`ExercisePickerRow.swift:79`).
 */
@Composable
fun CreateExerciseRow(
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(onClick = onClick)
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(icon = NtIcons.Plus, size = sfIconSize(14f), tint = NT.Colors.ink2)
        NtText(
            text = stringResource(S.exercises_create, query),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

/**
 * `WorkoutStrings.subtitle(for:)` — "Chest · Triceps, Shoulders": primary muscles, then the first
 * two secondaries, or the equipment when there are none.
 */
@Composable
internal fun exerciseSubtitle(exercise: ExerciseEntity): String {
    val primary = localizedMuscles(exercise.primaryMuscles)
    val secondary = localizedMuscles(exercise.secondaryMuscles.take(2))
    val parts = ArrayList<String>(2)
    if (primary.isNotEmpty()) parts.add(primary)
    if (secondary.isNotEmpty()) {
        parts.add(secondary)
    } else {
        val equipment = exercise.equipment
        if (!equipment.isNullOrEmpty()) parts.add(equipmentName(equipment))
    }
    return parts.joinToString(" · ")
}

/** `map(muscle)` — a plain loop, because `joinToString`'s transform is not an inline lambda. */
@Composable
private fun localizedMuscles(raw: List<String>): String {
    val names = ArrayList<String>(raw.size)
    for (value in raw) names.add(muscleName(value))
    return names.joinToString(", ")
}

/** `"muscleName.\(slug)"`, falling back to the capitalized raw value. */
@Composable
private fun muscleName(raw: String): String {
    val id = NtKeys.muscle(raw)
    return if (id != null) stringResource(id) else capitalizedWords(raw)
}

/** `"equipment.\(slug)"`, same fallback. */
@Composable
private fun equipmentName(raw: String): String {
    val id = NtKeys.equipment(raw)
    return if (id != null) stringResource(id) else capitalizedWords(raw)
}

/**
 * Swift's `String.capitalized(with:)` — every word title-cased under the app locale, and the rest
 * of each word lower-cased ("BODY ONLY" -> "Body Only").
 */
private fun capitalizedWords(raw: String, locale: Locale = Locale.getDefault()): String =
    raw.split(' ').joinToString(" ") { word ->
        word.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
    }
