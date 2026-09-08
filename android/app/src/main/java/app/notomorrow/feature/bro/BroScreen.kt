package app.notomorrow.feature.bro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.service.TogetherCount
import app.notomorrow.util.NtStrings
import app.notomorrow.util.S

/** How far the refresh spinner travels with the pull before the gesture trips. */
private val PULL_TRAVEL = 64.dp

/**
 * "Bro" — the port of `BroView` (`Features/Bro/BroView.swift`).
 *
 * Three-way state: signed out of the real backend → [BroSignedOutView]; paired → the shared
 * week, the heads-up chips and the log; otherwise [BroUnpairedView]. Pull-to-refresh is the
 * only one in the app, and a 15 s loop keeps the partner fresh while the tab is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroScreen() {
    val model = ntViewModel { container ->
        BroViewModel(
            profileDao = container.db.profileDao(),
            scheduleDao = container.db.scheduleDao(),
            attendanceDao = container.db.attendanceDao(),
            headsUpDao = container.db.headsUpDao(),
            workoutDao = container.db.workoutDao(),
            routineDao = container.db.routineDao(),
            pairingDao = container.db.broPairingDao(),
            bro = container.broService,
            attendance = container.attendanceService,
            needsSignIn = container.authStore.needsSignIn,
            strings = NtStrings.from(container.app),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()
    var showCantMakeIt by remember { mutableStateOf(false) }

    // The `.task` analogue: sweep stale planned days, then refresh every 15 s while visible.
    LaunchedEffect(Unit) { model.runLifecycle() }

    val pullState = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = model::refresh,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            state = pullState,
            indicator = {
                // UIKit's refresh control travels down with the pull and keeps spinning;
                // `NtSpinner` already rotates forever, so only the offset is ours.
                val progress = if (state.isRefreshing) {
                    1f
                } else {
                    pullState.distanceFraction.coerceIn(0f, 1f)
                }
                BroSpinner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer { translationY = progress * PULL_TRAVEL.toPx() },
                    alpha = progress,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // `.scrollDismissesKeyboard(.interactively)`
                    .ntDismissKeyboardOnScroll()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 8.dp, bottom = 40.dp + LocalTabBarHeight.current),
                horizontalAlignment = Alignment.Start,
            ) {
                BroHeader(state)
                // `combine` cannot emit before Room does; iOS reads its `@Query`s synchronously,
                // so nothing below the header is drawn until the first real snapshot arrives.
                when {
                    !state.loaded -> Unit
                    state.needsSignIn -> BroSignedOutView(onSignedIn = model::refreshQuietly)
                    state.isPaired -> BroPairedContent(
                        state = state,
                        onCantMakeIt = { showCantMakeIt = true },
                        onSend = model::sendHeadsUp,
                    )
                    else -> BroUnpairedView(
                        state = state,
                        onCodeEntry = model::setCodeEntry,
                        onPair = model::pair,
                    )
                }
            }
        }
    }

    if (showCantMakeIt) {
        CantMakeItSheet(onDismiss = { showCantMakeIt = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BroHeader(state: BroUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Eyebrow(
                text = if (state.isPaired) {
                    "${state.myName} & ${state.partnerName}"
                } else {
                    stringResource(S.bro_title)
                }
            )
            NtText(
                text = stringResource(if (state.isPaired) S.bro_title else S.bro_unpaired_title),
                style = NT.Fonts.largeTitle,
                color = NT.Colors.ink,
            )
        }
        if (state.isPaired) {
            // `Spacer(minLength: 8)` between two 12 pt HStack gaps = a 32 pt minimum.
            BroStreakChip(state.together, Modifier.padding(start = 20.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun BroStreakChip(together: TogetherCount, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(32.dp)
            .background(NT.Colors.surface, CircleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Flame, size = sfIconSize(13f), tint = NT.Colors.ember)
        TabularText(
            text = stringResource(S.bro_together_n_n, together.together, together.total),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paired
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BroPairedContent(
    state: BroUiState,
    onCantMakeIt: () -> Unit,
    onSend: (app.notomorrow.model.HeadsUpKind, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BroSharedWeekCard(
            week = state.week,
            partnerName = state.partnerName,
            session = state.session,
            modifier = Modifier.padding(top = 20.dp),
        )
        BroHeadsUpRow(
            partnerName = state.partnerName,
            onCantMakeIt = onCantMakeIt,
            onSend = onSend,
            modifier = Modifier.padding(top = NT.Spacing.section),
        )
        BroLogSection(
            rows = state.logRows,
            partnerName = state.partnerName,
            modifier = Modifier.padding(top = NT.Spacing.section),
        )
    }
}
