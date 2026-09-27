package app.notomorrow.feature.workout

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * "Browse programs" from the Train tab — `NoTomorrow/Features/Workout/ProgramBrowserSheet.swift`:
 * the built-in programs ([ProgramLibrary]) with their description, level, days a week and routine
 * count; tapping one shows its routines and exercises and "Add N routines", which writes them
 * through [RoutineStore.addProgram] (unique names, appended to the routine list).
 *
 * @param loadExercises library id → exercise, for the detail's localized names.
 * @param onAdd adds the program; the lambda it gets resolves a routine's catalog key to its name.
 */
@Composable
fun ProgramBrowserSheet(
    loadExercises: suspend (Collection<String>) -> Map<String, ExerciseEntity>,
    onAdd: (TrainingProgram, (String) -> String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val programs by produceState(emptyList<TrainingProgram>()) { value = ProgramLibrary.load(context) }
    val exercises by produceState(emptyMap<String, ExerciseEntity>(), programs) {
        if (programs.isNotEmpty()) value = loadExercises(programs.flatMapTo(mutableSetOf()) { it.exerciseIds })
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = programs.firstOrNull { it.id == selectedId }

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        ProgramBrowserHeader(showsBack = selected != null, onBack = { selectedId = null }, onDone = onDismiss)

        // A new scroll position for the list and for each program (`.id(selected?.id)`).
        key(selectedId) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = NT.Spacing.section),
            ) {
                if (selected == null) {
                    ProgramList(programs = programs, onSelect = { selectedId = it.id })
                } else {
                    ProgramDetail(program = selected, exercises = exercises)
                }
            }
        }

        if (selected != null) {
            val count = selected.routines.size
            PrimaryButton(
                title = stringResource(NtKeys.addRoutines(count), count),
                modifier = Modifier.padding(horizontal = NT.Spacing.screenH).padding(vertical = 8.dp),
                onClick = {
                    onAdd(selected) { key -> ProgramKeys.text(key)?.let(context::getString) ?: key }
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDismiss()
                },
            )
        }
    }
}

/** A catalog key of `programs.json` in the app's language (the key itself if unknown). */
@Composable
private fun programText(key: String): String {
    val id = ProgramKeys.text(key) ?: return key
    return stringResource(id)
}

/** Back (on a program) · Programs · Done. */
@Composable
private fun ProgramBrowserHeader(showsBack: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(text = stringResource(S.program_title), style = NT.Fonts.headline, color = NT.Colors.ink, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showsBack) {
                Row(
                    modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onBack),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtIcon(NtIcons.ChevronLeft, size = sfIconSize(15f), tint = NT.Colors.ink2)
                    NtText(text = stringResource(S.common_back), style = NT.Fonts.body, color = NT.Colors.ink2)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.heightIn(min = NT.Size.control).ntPlainClickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                NtText(text = stringResource(S.common_done), style = NT.Fonts.body, color = NT.Colors.ink2)
            }
        }
    }
}

/** The intro line, then one card per program. */
@Composable
private fun ProgramList(programs: List<TrainingProgram>, onSelect: (TrainingProgram) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NtText(
            text = stringResource(S.program_intro),
            modifier = Modifier.padding(bottom = 6.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
        programs.forEach { program ->
            key(program.id) {
                NTCard(modifier = Modifier.pressScale(onClick = { onSelect(program) })) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        NtText(
                            text = programText(program.name),
                            modifier = Modifier.weight(1f),
                            style = NT.Fonts.headline,
                            color = NT.Colors.ink,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        NtIcon(NtIcons.ChevronRight, size = sfIconSize(13f), tint = NT.Colors.ink3)
                    }
                    NtText(
                        text = programText(program.summary),
                        modifier = Modifier.padding(top = 6.dp),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                    ProgramMeta(program, Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/** The level badge, then "3× a week · 3 routines". */
@Composable
private fun ProgramMeta(program: TrainingProgram, modifier: Modifier = Modifier) {
    val count = program.routines.size
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(text = programText(program.levelKey))
        TabularText(
            text = stringResource(S.program_perWeek, program.daysPerWeek) + " · " +
                stringResource(NtKeys.routineCount(count), count),
            style = NT.Fonts.caption,
            color = NT.Colors.ink2,
        )
    }
}

/** The program's name, description and meta, then each routine with its exercises. */
@Composable
private fun ProgramDetail(program: TrainingProgram, exercises: Map<String, ExerciseEntity>) {
    Column(Modifier.padding(top = 4.dp)) {
        NtText(text = programText(program.name), style = NT.Fonts.title1, color = NT.Colors.ink)
        NtText(
            text = programText(program.summary),
            modifier = Modifier.padding(top = 6.dp),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
        ProgramMeta(program, Modifier.padding(top = 10.dp))
        program.routines.forEach { day ->
            SectionHeader(
                title = programText(day.name),
                modifier = Modifier.padding(top = NT.Spacing.section, bottom = 4.dp),
                trailing = stringResource(NtKeys.exerciseCount(day.items.size), day.items.size),
            )
            day.items.forEachIndexed { index, line ->
                if (index > 0) Hairline()
                ProgramLine(line = line, exercise = exercises[line.exercise])
            }
        }
    }
}

/** "Bench Press ······ 3 × 8  2:00". */
@Composable
private fun ProgramLine(line: TrainingProgram.Line, exercise: ExerciseEntity?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = exercise?.localizedName() ?: line.exercise.replace('_', ' '),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        TabularText(
            text = "${line.sets} ${Fmt.TIMES} ${line.reps}",
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
        TabularText(
            text = if (line.rest <= 0) stringResource(S.routine_restDefault) else Fmt.clock(line.rest),
            modifier = Modifier.widthIn(min = 34.dp),
            style = NT.Fonts.caption.copy(textAlign = TextAlign.End),
            color = NT.Colors.ink3,
        )
    }
}
