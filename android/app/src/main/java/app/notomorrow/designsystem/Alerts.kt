package app.notomorrow.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** SwiftUI `ButtonRole` as the alert / action-sheet layout needs it. */
enum class NtAlertRole { Default, Cancel, Destructive }

@Immutable
data class NtAlertAction(
    val title: String,
    val role: NtAlertRole = NtAlertRole.Default,
    val onClick: () -> Unit = {},
)

// ─────────────────────────────────────────────────────────────────────────────
// Measured geometry — docs/android-glass.md §1.6 / §1.7, and re-measured here at
// native 3x on design/ios26-reference/17-confirmationdialog-finish.png:
//
//   confirmationDialog panel   x 81.0 → 320.5 (239.5 pt), CENTRED on the screen
//                              y 363.8 → 536.7 (172.9 pt), corner fit r ≈ 26
//
//   ⚠️ SUPERSEDED for the radius and the two end insets — re-measured at 3x on
//   design/parity/ios/16-finish-confirm.png, the app's own dialog:
//     card   x 243 → 962 px (239.8 pt)     y 1080 → 1620 px (180.3 pt)
//     corner sub-pixel arc trace, circular fit r = 100–102 px ⇒ 33.3–34.0 pt
//     insets title cap-top 78 px (26.0) below the top; last row's fill ends
//            48 px (16.0) above the bottom — NOT the 12 this file shipped
//     rows   1261–1404 and 1429–1572 px ⇒ 48 pt each, 24 px (8 pt) apart ✓
//   rows                       208 × 48 capsules, 8 pt gaps (pitch 56), r 24
//   row fill                   (48,49,48) over a (19,21,20) body ⇒ white @ 0.12
//   title                      17 pt, CENTRED, 16 pt below the panel top
//   NO cancel row              — this settles docs/android-glass.md §5 q3:
//                                iOS 26.1 does drop `role: .cancel` from an
//                                inline action sheet, exactly as WWDC25 284 says
//   NO scrim                   — the backdrop outside the panel is untouched, and
//                                per Apple an action sheet "also lets people
//                                interact with other parts of the interface"
// ─────────────────────────────────────────────────────────────────────────────

/** `_alertControllerDimmingViewColor` — measured `ground` 10 → 5 behind a sheet. */
internal const val NtDialogScrim = 0.48f

/** `tertiarySystemFillColor` — measured +24/255 over the alert body. Flat: no falloff measured. */
private val AlertButtonFill: Brush = SolidColor(Color.White.copy(alpha = 0.11f))

/**
 * Measured (48,49,48) over the (19,21,20) body — but not a flat 0.12.
 *
 * Re-sampled down the capsule on `design/parity/ios/16-finish-confirm.png`: the fill reads 50 at
 * the top edge and falls to 45 at the bottom, i.e. white 0.131 -> 0.110 over the same body, which
 * is the top-lit falloff every iOS 26 control has. Android drew the mean as a flat 0.12 and the
 * rows read dead.
 */
private val ActionSheetRowFill = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.131f), Color.White.copy(alpha = 0.110f)),
)

/**
 * UIKit's **system** destructive red, sampled at (255,66,69) on
 * design/ios26-reference/17-confirmationdialog-finish.png. `Button(_:role: .destructive)` gets this
 * colour from the system, never an app token: `NT.Colors.bad` (#FF375F) is 26 points bluer and
 * reads pink next to it. `bad` stays for app-drawn content.
 */
private val AlertDestructive = Color(0xFFFF4245)

private val AlertWidth: Dp = 320.dp
private val AlertRadius: Dp = 34.dp
private val AlertTextPadding: Dp = 30.dp
private val AlertActionPadding: Dp = 16.dp
private val AlertButtonHeight: Dp = 48.dp
private val AlertButtonGap: Dp = 8.dp

private val ActionSheetWidth: Dp = 240.dp
private val ActionSheetShadow: Dp = 20.dp

/**
 * Re-measured on `design/parity/ios/16-finish-confirm.png` — the app's own two-row dialog, not the
 * four-row `ios26-reference` frame §1.7 fitted.
 *
 * The card body spans x 243 → 962 px and y 1080 → 1620 px at 3x. Tracing the top-left edge with a
 * sub-pixel threshold gives an inset from the card's left edge of 67.4 / 56.6 / 39.5 / 8.2 px at
 * dy 6 / 10 / 20 / 60 below the top; a circular arc of **r = 99.9 px = 33.3 dp** reproduces the
 * whole traced profile to a mean 0.3 px (r = 102 already drifts to 1.0 px, r = 105 to 2.9).
 *
 * The 26 the doc's 7-depth fit produced was 8-20 px too shallow at every depth — on
 * `design/parity/android-r2/16-finish-confirm.png` the same trace reads 34.3 / 19.0 px at dy 12
 * / 20 against iOS's 52.4 / 39.5 — and rendered a visibly boxier card.
 */
private val ActionSheetRadius: Dp = 33.3.dp

/** The card's side inset — `UIStackView f = (16, …)` in §1.7's view tree. */
private val ActionSheetPadding: Dp = 16.dp

/**
 * The title label's top, measured, not the 16 the side inset suggested.
 *
 * On `16-finish-confirm.png` the 17 pt cap-top sits **78 px = 26.0 pt** below the card top, which
 * is exactly `UILabel f = (30, 22, …)` plus SF's own `ascent − capHeight` (4.02 pt) — i.e. iOS
 * insets the *label* by 22, the same as `.alert` (§1.6), and only the glyph lands at 26. Compose's
 * rendered line box puts the cap 3.5 dp below its own top, so the padding that reproduces 26.0 is
 * 22.5, and the old 16 + 2 nudge landed the whole card 13 px short.
 */
private val ActionSheetTopPadding: Dp = 22.5.dp

/**
 * The card's bottom inset — **16, not 12**. Measured: the last row's fill ends at y 1572 and the
 * card's bottom rim at 1620.5, i.e. 48 px = 16.0 pt, matching the side inset and `.alert`'s.
 */
private val ActionSheetBottomPadding: Dp = 16.dp

/**
 * Title-block → first row. iOS runs the 17 pt cap-top to the first capsule's top in 103.5 px
 * (34.5 dp); with Compose's line box that is 9 dp under the title plus the column's own 8 dp
 * spacing. (§1.7's "18 gap" is measured from the label's bottom, which is the same distance.)
 */
private val ActionSheetTitleGap: Dp = 9.dp

/**
 * iOS 26 `.alert` — a complete redesign versus iOS 18, and nothing like M3's
 * `AlertDialog`: a **320 dp** glass panel at radius **34**, a **left-aligned**
 * 17 pt semibold title, a 13 pt message, and **capsule** action buttons 48 dp
 * tall with an 8 dp gap and no separators anywhere.
 *
 * Vertical rhythm is the measured `_UIAlertControllerPhoneTVMacView` (320 × 152
 * for a title + message + two buttons): title top 22, message top 50, action row
 * inset 16 from the sides and the bottom. `UIAlertController` renders the message
 * in the **same label colour** as the title — it differs only in size and weight,
 * so it is `ink`, not `ink2`.
 *
 * Cancel is the bold one and sits **leading** in a two-up row (SwiftUI's
 * ordering, and what the iOS 26 render shows); destructive is `bad`. Three or
 * more actions stack full width. Tapping any action runs it and then dismisses.
 *
 * The panel is drawn in the app's [NtOverlayHost] so it can sample [NtBackdrop] —
 * a `Dialog` is a second window and can never do that — behind a black @ 0.48
 * scrim that *does* block input, which is what an alert is for. A caller inside a
 * sheet has no reachable host and keeps the `Dialog` path.
 *
 * [field] is the inline `TextField` slot the two text-entry alerts need
 * (`bro.custom`, `fuel.ai.grams.customTitle`).
 */
@Composable
fun NtAlert(
    title: String,
    message: String? = null,
    actions: List<NtAlertAction>,
    onDismiss: () -> Unit,
    field: (@Composable () -> Unit)? = null,
) {
    NtPanelHost(scrim = NtDialogScrim, onDismiss = onDismiss) { transition, glass ->
        AnimatedVisibility(
            visibleState = transition,
            enter = scaleIn(tween(200, easing = NT.Ease.out), initialScale = 1.10f) +
                fadeIn(tween(200, easing = NT.Ease.out)),
            exit = scaleOut(tween(140, easing = NT.Ease.out), targetScale = 1.06f) +
                fadeOut(tween(140, easing = NT.Ease.out)),
        ) {
            val shape = NtShapes.rounded(AlertRadius)
            Column(
                modifier = Modifier
                    .width(AlertWidth)
                    .ntPanelSurface(shape, GlassStyle.Alert, glass),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = AlertTextPadding)) {
                    Spacer(Modifier.height(22.dp))
                    NtText(
                        text = title,
                        style = NT.Fonts.headline,
                        color = NT.Colors.ink,
                        textAlign = TextAlign.Start,
                    )
                    if (message != null) {
                        Spacer(Modifier.height(7.dp))
                        NtText(
                            text = message,
                            style = NT.Fonts.footnote,
                            color = NT.Colors.ink,
                            textAlign = TextAlign.Start,
                        )
                    }
                    if (field != null) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth()) { field() }
                    }
                }
                Spacer(Modifier.height(20.dp))
                NtAlertActions(actions, onDismiss)
            }
        }
    }
}

@Composable
private fun NtAlertActions(actions: List<NtAlertAction>, onDismiss: () -> Unit) {
    val ordered = orderedForAlert(actions)
    val padding = Modifier
        .fillMaxWidth()
        .padding(horizontal = AlertActionPadding)
        .padding(bottom = AlertActionPadding)
    if (ordered.size == 2) {
        Row(padding, horizontalArrangement = Arrangement.spacedBy(AlertButtonGap)) {
            ordered.forEach { action ->
                NtCapsuleButton(
                    action = action,
                    fill = AlertButtonFill,
                    onDismiss = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(padding, verticalArrangement = Arrangement.spacedBy(AlertButtonGap)) {
            ordered.forEach { action ->
                NtCapsuleButton(
                    action = action,
                    fill = AlertButtonFill,
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** SwiftUI puts the cancel role leading when an alert has exactly two buttons. */
private fun orderedForAlert(actions: List<NtAlertAction>): List<NtAlertAction> {
    if (actions.size != 2) return actions
    val cancelIndex = actions.indexOfFirst { it.role == NtAlertRole.Cancel }
    return if (cancelIndex == 1) listOf(actions[1], actions[0]) else actions
}

/**
 * iOS 26 `confirmationDialog` — no longer a bottom-anchored two-group stack.
 * It is a **centred 240 dp glass card** with a centred title and **stacked
 * full-width 208 × 48 capsules**, over **no scrim**.
 *
 * §1.7 and Apple, *Adopting Liquid Glass*: *"an action sheet also lets people
 * interact with other parts of the interface"*. So this is **not** a `Dialog`:
 * a dialog window swallows every touch even at `dimAmount = 0`, which is the
 * opposite of what iOS does. It is composed in the app's [NtOverlayHost], the
 * only pointer-consuming region is the card itself, and the screen underneath
 * stays live. Back dismisses it, as tapping outside does on iOS.
 *
 * [cancel] is kept for API stability and is deliberately **not rendered**:
 * WWDC25 284 — "Action sheets presented inline don't have a cancel button
 * because the cancel action is implicit by tapping anywhere else" — and
 * `17-confirmationdialog-finish.png` confirms it for iOS 26.1 with this app's
 * own `ActiveWorkoutView` dialog, which declares a `.cancel` button that the
 * system does not draw. Any `Cancel`-role entry in [actions] is dropped too.
 *
 * The optional [title]/[message] head the card, matching `titleVisibility: .visible`.
 * Three call sites: `SettingsView.swift:52`, `SettingsPartnerEditor.swift:31`,
 * `ActiveWorkoutView.swift:85`.
 */
@Composable
fun NtActionSheet(
    actions: List<NtAlertAction>,
    @Suppress("UNUSED_PARAMETER") cancel: String,
    onDismiss: () -> Unit,
    title: String? = null,
    message: String? = null,
) {
    val rows = remember(actions) { actions.filter { it.role != NtAlertRole.Cancel } }
    NtPanelHost(scrim = 0f, onDismiss = onDismiss) { transition, glass ->
        AnimatedVisibility(
            visibleState = transition,
            enter = scaleIn(tween(220, easing = NT.Ease.out), initialScale = 0.88f) +
                fadeIn(tween(160, easing = NT.Ease.out)),
            exit = scaleOut(tween(140, easing = NT.Ease.out), targetScale = 0.92f) +
                fadeOut(tween(140, easing = NT.Ease.out)),
        ) {
            val shape = NtShapes.rounded(ActionSheetRadius)
            Column(
                modifier = Modifier
                    .width(ActionSheetWidth)
                    // The card is the ONLY thing that eats touches: a tap on its padding must not
                    // fall through to the screen, and everything outside it must still reach it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    // iOS darkens the ground around the card — `ground` drops from (10,10,11) to
                    // (7,10,7) within ~40 px of every edge on `16-finish-confirm.png`, and the
                    // text behind it peaks at 235 instead of 242. There is deliberately no scrim
                    // (§1.7, `scrim = 0f` above), so this soft ambient is the whole separation.
                    .shadow(ActionSheetShadow, shape, clip = false)
                    .ntPanelSurface(shape, GlassStyle.Popover, glass)
                    .padding(horizontal = ActionSheetPadding)
                    .padding(top = ActionSheetTopPadding, bottom = ActionSheetBottomPadding),
                verticalArrangement = Arrangement.spacedBy(AlertButtonGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (title != null || message != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = ActionSheetTitleGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (title != null) {
                            NtText(
                                text = title,
                                // 17 pt REGULAR. `confirmationDialog`'s own title is lighter than
                                // an app `.headline`: measured stems are 4.5 px at 3x against
                                // 6-7 px for iOS's own headline text on the same frame, with
                                // identical advances — so the weight is the only difference.
                                style = NT.Fonts.body,
                                color = NT.Colors.ink,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (message != null) {
                            NtText(
                                text = message,
                                style = NT.Fonts.footnote,
                                color = NT.Colors.ink2,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                rows.forEach { action ->
                    NtCapsuleButton(
                        action = action,
                        fill = ActionSheetRowFill,
                        onDismiss = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Real glass when the panel is in the same window as the backdrop, the measured flat fill when it
 * is not. The fill is [GlassStyle.flatFill], which for both presets is what the transfer function
 * yields over this app's `ground`.
 */
private fun Modifier.ntPanelSurface(
    shape: androidx.compose.ui.graphics.Shape,
    style: GlassStyle,
    glass: Boolean,
): Modifier = if (glass) {
    this.liquidGlass(shape, style)
} else {
    this.clip(shape).ntGlassPanel(shape, style)
}

/** The 48 dp / r 24 filled capsule both the alert and the action sheet are built from. */
@Composable
private fun NtCapsuleButton(
    action: NtAlertAction,
    fill: Brush,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(AlertButtonHeight)
            .ntClickable {
                onDismiss()
                action.onClick()
            }
            .background(fill, NtShapes.capsule)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = action.title,
            style = if (action.role == NtAlertRole.Cancel) NT.Fonts.headline else NT.Fonts.body,
            color = if (action.role == NtAlertRole.Destructive) AlertDestructive else NT.Colors.ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Where a centred panel is drawn.
 *
 * Preferred: the app's [NtOverlayHost] — same window as [NtBackdrop], so `GlassStyle.Popover` /
 * `.Panel` are actually evaluated, and (for [scrim] `0`) no full-screen layer, so the rest of the
 * UI stays interactive exactly as §1.7 requires.
 *
 * Fallback: the old `Dialog`, for a caller inside a sheet (its own window, stacked above the host).
 * The content is told which it got, because glass is only possible in the first.
 */
@Composable
private fun NtPanelHost(
    scrim: Float,
    onDismiss: () -> Unit,
    content: @Composable (MutableTransitionState<Boolean>, glass: Boolean) -> Unit,
) {
    val host = currentNtOverlayHost()
    if (host == null) {
        NtDialogWindow(dimAmount = scrim, onDismiss = onDismiss) { transition ->
            content(transition, false)
        }
        return
    }
    NtOverlayContent(host) {
        val transition = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) { transition.targetState = true }
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            if (scrim > 0f) {
                // An alert is modal: the scrim both dims and blocks. It stays full-bleed —
                // only the PANEL is centred in the safe area.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim))
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
                )
            }
            // SwiftUI centres an alert/dialog in the SAFE AREA, not the display: the iOS card's
            // centre y is exactly ((177 + 2520) / 2), 39 px below the full-screen centre this
            // used to use.
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.Center,
            ) {
                content(transition, true)
            }
        }
    }
}

/**
 * The fallback window: full-bleed, centred content, an explicitly controlled dim, and a tap outside
 * that dismisses. The enter/exit transition is driven by the [MutableTransitionState] handed to
 * [content], so the exit plays before the window is torn down.
 */
@Composable
private fun NtDialogWindow(
    dimAmount: Float,
    onDismiss: () -> Unit,
    content: @Composable (MutableTransitionState<Boolean>) -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { transition.targetState = true }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val provider = LocalView.current.parent as? DialogWindowProvider
        SideEffect { provider?.window?.setDimAmount(dimAmount) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(transition) }
    }
}
