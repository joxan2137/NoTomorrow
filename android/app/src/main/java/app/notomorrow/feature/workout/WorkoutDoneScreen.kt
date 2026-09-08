package app.notomorrow.feature.workout

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.Avatar
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.StatTile
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "DONE." summary after finishing: volume hero, delta vs the last workout with the same name,
 * Time / Sets / Exercises tiles, records list, bro card when paired, Done + Edit sets — 1:1 port of
 * `NoTomorrow/Features/Workout/WorkoutDoneView.swift`.
 *
 * `ActiveWorkoutScreen` swaps this in **in place** (not a new destination), so the screen owns no
 * navigation of its own: [onDone] and [onEditSets] are the two ways out.
 *
 * Nothing but the ground is drawn until the Room flow has answered
 * ([WorkoutDoneUiState.loaded]) — iOS is handed the `Workout` object itself and never renders a
 * zeroed summary.
 *
 * @param endedAt provisional end while the workout is still technically active — the finish flow
 *   stamps `endedAt` on Done, exactly as iOS does.
 * @param onEditSets `null` hides the "Edit sets" link.
 */
@Composable
fun WorkoutDoneScreen(
    workoutId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    endedAt: Long? = null,
    onEditSets: (() -> Unit)? = null,
) {
    val model = ntViewModel(key = workoutId) { container ->
        WorkoutDoneViewModel(container, workoutId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val strings = rememberNtStrings()

    val duration = remember(state.startedAt, state.endedAt, endedAt) {
        workoutDuration(state.startedAt, endedAt ?: state.endedAt)
    }

    if (!state.loaded) {
        Box(modifier.fillMaxSize().background(NT.Colors.ground))
        return
    }

    Box(modifier.fillMaxSize().background(NT.Colors.ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            WorkoutDoneHeader(
                title = state.name + " · " + Fmt.relativeDay(
                    Instant.ofEpochMilli(state.startedAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                    strings,
                    today = LocalDate.now(),
                ) + " " + Fmt.time(Instant.ofEpochMilli(state.startedAt)),
                onShare = {
                    share(
                        context,
                        strings.string(
                            S.workout_done_shareText,
                            state.name,
                            Fmt.duration(duration, strings),
                            Fmt.volume(state.volumeKg),
                        ),
                    )
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH),
            ) {
                Spacer(Modifier.height(24.dp))
                WorkoutDoneHero(state = state)

                Spacer(Modifier.height(NT.Spacing.section))
                WorkoutDoneTiles(state = state, duration = duration)

                Spacer(Modifier.height(NT.Spacing.section))
                WorkoutDoneRecords(state = state)

                state.partnerName?.takeIf { it.isNotEmpty() }?.let { partner ->
                    Spacer(Modifier.height(18.dp))
                    BroCard(partner)
                }

                Spacer(Modifier.height(16.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryButton(title = stringResource(S.common_done), onClick = onDone)
                if (onEditSets != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(NT.Size.control)
                            .ntPlainClickable(onClick = onEditSets),
                        contentAlignment = Alignment.Center,
                    ) {
                        NtText(
                            text = stringResource(S.workout_done_editSets),
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink2,
                        )
                    }
                }
            }
        }
    }
}

/** Ember eyebrow "Push A · Today 19:12" and the share action. */
@Composable
private fun WorkoutDoneHeader(title: String, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 8.dp)
            .height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(title, color = NT.Colors.ember)
        Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
        Box(
            modifier = Modifier
                .height(NT.Size.control)
                .ntPlainClickable(onClick = onShare),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_share),
                style = NT.Fonts.body,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** "DONE." over the volume hero and the delta chip. */
@Composable
private fun WorkoutDoneHero(state: WorkoutDoneUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NtText(
            text = stringResource(S.workout_done_title),
            style = NT.Fonts.display(72),
            color = NT.Colors.ink,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabularText(
                text = Fmt.weight(
                    kg = Fmt.roundHalfAwayFromZero(state.volumeKg),
                    withUnit = false,
                ),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.display(56),
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.workout_done_kgMoved),
                modifier = Modifier.alignByBaseline(),
                style = NT.Fonts.title2,
                color = NT.Colors.ink2,
            )
        }
        val previous = state.previousVolumeKg
        if (previous != null && previous != state.volumeKg) {
            DeltaChip(delta = state.volumeKg - previous, workoutName = state.name)
        }
    }
}

/** "+1 200 kg more than last Push A" — ember when up, grey when down. */
@Composable
private fun DeltaChip(delta: Double, workoutName: String) {
    val up = delta > 0
    val tint = if (up) NT.Colors.ember else NT.Colors.ink2
    Row(
        modifier = Modifier
            .height(30.dp)
            .background(
                if (up) NT.Colors.emberTint else NT.Colors.surface2,
                CircleShape,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(
            icon = if (up) NtIcons.ArrowUp else NtIcons.ArrowDown,
            size = sfIconSize(12f),
            tint = tint,
        )
        TabularText(
            text = if (up) {
                stringResource(S.workout_done_moreThanLast_s_s, Fmt.volume(delta), workoutName)
            } else {
                stringResource(S.workout_done_lessThanLast_s_s, Fmt.volume(-delta), workoutName)
            },
            style = NT.Fonts.footnoteBold,
            color = tint,
        )
    }
}

/** Time / Sets / Exercises. */
@Composable
private fun WorkoutDoneTiles(state: WorkoutDoneUiState, duration: Double) {
    val strings = rememberNtStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            label = stringResource(S.workout_time),
            value = Fmt.duration(duration, strings),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_sets),
            value = state.completedSetCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_exercises),
            value = state.exerciseCount.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Shared with Kuba" — only when a pairing exists. */
@Composable
private fun BroCard(partner: String) {
    NTCard(padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(initial = partner, size = 32.dp, background = NT.Colors.surface3)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                NtText(
                    text = stringResource(S.workout_done_sharedWith_s, partner),
                    style = NT.Fonts.subheadlineBold,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                NtText(
                    text = stringResource(S.workout_done_sharedDetail),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Box(Modifier.size(8.dp).background(NT.Colors.good, CircleShape))
        }
    }
}

/** SwiftUI `ShareLink(item:)` — the plain-text half of the platform table (research §6). */
private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
