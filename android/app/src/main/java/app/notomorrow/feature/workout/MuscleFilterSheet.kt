package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.R
import app.notomorrow.designsystem.*
import app.notomorrow.util.S

/**
 * `MuscleFilterSheet`: the exercise picker's body-map filter. Tap a muscle on the figure (or its
 * chip), see how many exercises train it, then show them. "Clear" goes back to the group chips.
 */
@Composable
internal fun MuscleFilterSheet(
    initial: String?,
    counts: Map<String, Int>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val regions = rememberMuscleRegions()
    val muscles = remember(regions) { regions.map { it.muscle }.filter { it != "outline" }.distinct() }
    var current by remember { mutableStateOf(initial) }

    NtSheet(onDismiss = onDismiss, showsHandle = true, containerColor = NT.Colors.ground) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NT.Spacing.screenH).height(NT.Size.control),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(stringResource(R.string.common_cancel), color = NT.Colors.ink2,
                modifier = Modifier.ntPlainClickable(onClick = onDismiss))
            Spacer(Modifier.weight(1f))
            NtText(stringResource(S.exercises_bodyMap), style = NT.Fonts.headline)
            Spacer(Modifier.weight(1f))
            NtText(
                stringResource(S.exercises_clearFilter),
                color = if (initial != null) NT.Colors.ink else NT.Colors.ink3,
                modifier = Modifier.alpha(if (initial != null) 1f else 0f)
                    .ntPlainClickable(enabled = initial != null) { onPick(null); onDismiss() },
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BodyMapCanvas(
                fill = { if (it == current) NT.Colors.ember else NT.Colors.surface3 },
                modifier = Modifier.heightIn(max = 290.dp),
                bodyColor = NT.Colors.surface2,
                gap = NT.Colors.ground,
                onTap = { muscle -> if (muscle != null) current = muscle },
            )
            val picked = current
            NtText(
                if (picked != null) workoutMuscleName(picked) + " · " + workoutExerciseCount(counts[picked] ?: 0)
                else stringResource(S.exercises_tapMuscle),
                style = NT.Fonts.subheadline,
                color = if (picked != null) NT.Colors.ink else NT.Colors.ink3,
            )
            NtFlowLayout(Modifier.fillMaxWidth(), spacing = 6.dp) {
                muscles.forEach { muscle ->
                    val on = muscle == current
                    Box(
                        Modifier.height(30.dp).clip(CircleShape)
                            .background(if (on) NT.Colors.ink else NT.Colors.surface)
                            .ntClickable { current = muscle }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NtText(
                            workoutMuscleName(muscle),
                            style = if (on) NT.Fonts.subheadlineBold else NT.Fonts.subheadline,
                            color = if (on) NT.Colors.onPrimary else NT.Colors.ink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        val picked = current
        PrimaryButton(
            title = if (picked != null) {
                stringResource(S.exercises_showMatching, workoutExerciseCount(counts[picked] ?: 0))
            } else {
                stringResource(S.exercises_pickMuscle)
            },
            enabled = picked != null,
            onClick = { onPick(picked); onDismiss() },
            modifier = Modifier.padding(horizontal = NT.Spacing.screenH, vertical = 8.dp),
        )
    }
}
