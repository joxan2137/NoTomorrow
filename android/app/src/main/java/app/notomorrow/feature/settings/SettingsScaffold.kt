package app.notomorrow.feature.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtGlassButton
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.util.S

// The Settings sheet's own navigation chrome — the large-title root bar with the glass "Done"
// capsule (`07-sheet-settings.png`) and the inline editor bar with the glass back circle
// (`08-toggle-resttimer-editor.png`).
//
// Measured on the iOS 26 captures (402 × 874 pt, sheet top at the 62 pt safe-area inset):
//   nav row      44 pt tall, 16 pt above it, 16 pt of horizontal inset ⇒ button centre at y 100
//   large title  34/41, 16 pt leading inset (UIKit's layout margin, 4 pt tighter than the
//                content inset), 12 pt below the bar, 8 pt above the content
//   root content 20 pt horizontal (screenH), 12 pt top, 32 pt bottom
//   editor       same bar, but the first card sits 22 pt under it (144.0 pt on
//                `08-toggle-resttimer-editor.png`, 143.5 pt on `11-wheel-schedule-editor.png`)

/**
 * Both scaffolds hand their title to `.navigationTitle(_:)`, which paints it in the system
 * primary label — pure white in dark mode, not the app's `ink` (#F2F2F4). Measured at
 * 255,255,255 on `13-settings-schedule.png`.
 */
private val StNavTitleInk: Color = Color.White

/** Height of the row the Done / back button sits in. */
private val StNavBarHeight: Dp = NT.Size.control

/** Clearance between the sheet's top edge and the nav row. */
private val StNavBarTopInset: Dp = 16.dp

/** The nav bar's own horizontal inset — narrower than the 20 pt content inset. */
private val StNavBarInset: Dp = 16.dp

/**
 * The large title's leading inset. UIKit lays the navigation bar's large title out on its own
 * 16 pt layout margin, so it sits 4 pt further left than the 20 pt group cards — measured at
 * x 15.4 pt on `07-sheet-settings.png` once the 34 pt SF Bold side bearing is taken off.
 */
private val StLargeTitleInset: Dp = 16.dp

/**
 * `.navigationBarTitleDisplayMode(.large)` hands the title over to the bar across one large-title
 * line of scroll: the big title fades out as it travels up, the inline one fades in beside Done.
 */
private val StTitleCollapse: Dp = 34.dp

/** Keeps the collapsed title clear of the Done capsule while staying centred in the bar. */
private val StInlineTitleInset: Dp = 80.dp

/**
 * The Settings root: `ground`, a glass **Done** capsule, the large title **inside the scroll**
 * (so it collapses into the bar the way `.large` does) and the group stack.
 */
@Composable
fun StRootScaffold(
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val distance = with(LocalDensity.current) { StTitleCollapse.toPx() }
    val collapse = if (distance <= 0f) 1f else (scrollState.value / distance).coerceIn(0f, 1f)

    Column(modifier.fillMaxSize().background(NT.Colors.ground).imePadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StNavBarTopInset)
                .height(StNavBarHeight)
                .padding(horizontal = StNavBarInset),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StInlineTitleInset)
                    .alphaLayer(collapse),
                style = NT.Fonts.headline,
                color = StNavTitleInk,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            NtGlassButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterEnd),
                title = stringResource(S.common_done),
            )
        }
        // The root stacks its groups 12 pt apart (`SettingsView.content`).
        StScrollContent(
            spacing = 12.dp,
            scrollState = scrollState,
            header = {
                NtText(
                    text = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = StLargeTitleInset)
                        .padding(bottom = 8.dp)
                        .alphaLayer(1f - collapse),
                    style = NT.Fonts.largeTitle,
                    color = StNavTitleInk,
                    maxLines = 1,
                )
            },
            content = content,
        )
    }
}

/**
 * `STEditorScreen`: ground background, inline title with the glass back button, scrolling content
 * with screen padding.
 */
@Composable
fun StEditorScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(NT.Colors.ground).imePadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StNavBarTopInset)
                .height(StNavBarHeight)
                .padding(horizontal = StNavBarInset),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StNavBarHeight + 8.dp),
                style = NT.Fonts.headline,
                color = StNavTitleInk,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            NtGlassButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
                icon = NtIcons.ChevronLeft,
                // Icon-only: iOS's toolbar back button gets a system label, TalkBack does not.
                contentDescription = stringResource(S.common_back),
            )
        }
        // `STEditorScreen` uses a 20 pt rhythm between its blocks, 22 pt under the bar.
        StScrollContent(spacing = 20.dp, top = 22.dp, content = content)
    }
}

/**
 * The scroll body both scaffolds share: 20 pt sides, 32 pt bottom, and
 * `.scrollDismissesKeyboard(.interactively)`. [top] is the clearance under the nav bar — 12 pt
 * for the root (where it lands above the large title) and 22 pt for the editors. [header] rides
 * inside the scroll but outside the 20 pt content inset, so the large title keeps its own 16 pt
 * margin.
 */
@Composable
private fun ColumnScope.StScrollContent(
    spacing: Dp,
    top: Dp = 12.dp,
    scrollState: ScrollState = rememberScrollState(),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            // `.scrollDismissesKeyboard(.interactively)`.
            .ntDismissKeyboardOnScroll()
            .verticalScroll(scrollState)
            .padding(top = top, bottom = 32.dp)
            // iOS's scroll view adds the home-indicator inset to its *content*, so the last card
            // passes under the gesture bar instead of being cut 24 pt short of it. Inset-aware:
            // this resolves to zero for as long as an ancestor still consumes the navigation
            // bars (`NtSheet`'s `.navigationBarsPadding()`), and to 24 dp once it stops.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        header?.invoke(this)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                // The title's own 8 pt tail plus this 12 pt puts the first eyebrow 20 pt under it.
                .padding(top = if (header == null) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}
