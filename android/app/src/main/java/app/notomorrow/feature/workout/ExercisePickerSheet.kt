package app.notomorrow.feature.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.R
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.Chip
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.service.ExerciseLibrary
import app.notomorrow.util.S

/**
 * Multi-select exercise picker sheet (`design/Exercises.dc.html`): search, muscle chips, rows,
 * create row, "Add n" — 1:1 port of `NoTomorrow/Features/Workout/ExercisePickerView.swift`.
 *
 * @param workoutId when set, the chosen exercises are appended to that workout (with prefilled
 *   rows) before [onAdd] runs, and everything already in it renders as "In".
 * @param alreadyIn used only when [workoutId] is null — the plain `init(alreadyIn:onAdd:)`.
 * @param onAdd the chosen exercise ids, in tap order.
 */
@Composable
fun ExercisePickerSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    workoutId: String? = null,
    alreadyIn: Set<String> = emptySet(),
    onAdd: (List<String>) -> Unit = {},
) {
    val model = ntViewModel(key = workoutId ?: "picker") { container ->
        ExercisePickerViewModel(container, workoutId, alreadyIn)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ExerciseEntity?>(null) }
    detail?.let { ExerciseDetailSheet(it) { detail = null } }
    val keyboard = LocalSoftwareKeyboardController.current

    // `@State private var model = ExercisePickerViewModel()` is rebuilt on every `.sheet`
    // presentation; the Android view model outlives the sheet (it is scoped to the host), so the
    // sheet clears the query, the chip and the selection each time it opens.
    LaunchedEffect(Unit) { model.reset(alreadyIn) }

    NtSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        showsHandle = true,
        containerColor = NT.Colors.ground,
    ) {
        PickerHeader(onCancel = onDismiss)

        PickerSearchField(
            query = state.query,
            onQueryChange = model::setQuery,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp),
        )

        PickerChips(
            selected = state.group,
            onSelect = model::setGroup,
            modifier = Modifier.padding(top = 12.dp),
        )

        LazyColumn(
            // `.scrollDismissesKeyboard(.immediately)` (`ExercisePickerView.swift:152`) — the
            // keyboard goes the moment the 876-row list starts moving.
            modifier = Modifier.fillMaxWidth().weight(1f).ntDismissKeyboardOnScroll(),
            contentPadding = PaddingValues(
                start = NT.Spacing.screenH,
                end = NT.Spacing.screenH,
                bottom = 12.dp,
            ),
        ) {
            item(key = "header") { PickerResultsHeader(count = state.results.size) }

            items(state.results, key = { it.exercise.id }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExercisePickerRow(
                        entry = entry,
                        state = state.rowState(entry.exercise.id),
                        onClick = { model.toggle(entry.exercise.id) },
                        unit = state.unit,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { detail = entry.exercise }) {
                        NtText(stringResource(R.string.exercises_details), style = NT.Fonts.footnote)
                    }
                }
            }

            if (state.showsCreateRow) {
                item(key = "create") {
                    CreateExerciseRow(
                        query = state.trimmedQuery,
                        onClick = {
                            model.createExercise()
                            keyboard?.hide()
                        },
                    )
                }
            } else if (state.results.isEmpty()) {
                item(key = "empty") {
                    NtText(
                        text = stringResource(S.exercises_noResults),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                }
            }
        }

        // `.safeAreaInset(edge: .bottom)` — the bar appears only once something is selected.
        AnimatedVisibility(
            visible = state.selectedCount > 0,
            enter = slideInVertically(tween(200, easing = NT.Ease.out)) { it } + fadeIn(NT.Anim.easeOut20),
            exit = slideOutVertically(tween(200, easing = NT.Ease.out)) { it } + fadeOut(NT.Anim.easeOut20),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NT.Colors.ground)
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(vertical = 8.dp),
            ) {
                PrimaryButton(
                    title = stringResource(S.exercises_addCount, state.selectedCount),
                    onClick = {
                        model.add { ids ->
                            onAdd(ids)
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

/** "Add exercise" + a plain Cancel. */
@Composable
private fun PickerHeader(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = stringResource(S.exercises_add),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .heightIn(min = NT.Size.control)
                .ntPlainClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_cancel),
                style = NT.Fonts.body,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** 44 dp `surface` field: magnifier, the query, and a clear button once there is one. */
@Composable
private fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<ExerciseEntity?>(null) }
    detail?.let { ExerciseDetailSheet(it) { detail = null } }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .background(NT.Colors.surface, NtShapes.field)
            .padding(start = 14.dp, end = if (query.isEmpty()) 14.dp else 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(
            icon = NtIcons.MagnifyingGlass,
            size = sfIconSize(16f),
            tint = NT.Colors.ink2,
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                NtText(
                    text = stringResource(S.exercises_search),
                    style = NT.Fonts.body,
                    color = NT.Colors.ink3,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
                cursorBrush = SolidColor(NT.Colors.ink),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    // `ExercisePickerView.swift:76` only calls `.autocorrectionDisabled()`, so iOS
                    // keeps the default `.sentences` capitalization.
                    capitalization = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(NT.Size.control)
                    .ntPlainClickable(onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(20.dp).background(NT.Colors.ink3, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    NtIcon(
                        icon = NtIcons.Xmark,
                        size = sfIconSize(9f),
                        tint = NT.Colors.ground,
                    )
                }
            }
        }
    }
}

/** Muscle-group chips, bleeding to the screen edge. */
@Composable
private fun PickerChips(
    selected: ExerciseLibrary.MuscleGroup,
    onSelect: (ExerciseLibrary.MuscleGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NT.Spacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ExerciseLibrary.MuscleGroup.entries.toList(), key = { it.raw }) { group ->
            Chip(
                title = stringResource(group.titleRes),
                selected = group == selected,
                onClick = { onSelect(group) },
            )
        }
    }
}

/** "128 results" on the left, "Last" over the last-set column on the right. */
@Composable
private fun PickerResultsHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(stringResource(S.exercises_results, count))
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        Eyebrow(
            text = stringResource(S.workout_last),
            modifier = Modifier.padding(end = 38.dp),
        )
    }
}
