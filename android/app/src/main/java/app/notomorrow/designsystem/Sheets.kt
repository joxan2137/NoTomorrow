package app.notomorrow.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// docs/android-glass.md §1.5 / §3.6.
//
// WWDC25 323: "On iOS 26, partial height sheets are inset by default with a
// Liquid Glass background. At smaller heights, the bottom edges pull in, nesting
// in the curved edges of the display."  [view tree] on a `.medium` sheet:
//
//   UIDropShadowView  f = (8, 415.03, 386, 450.97)   ⇒ inset 8 left, right, BOTTOM
//   _UIGrabber        f = (183, 5, 36, 5)  r 2.5
//   UIDimmingView     bg = sRGB(0,0,0, 0.48)         ⇒ the scrim, not 0.40
//   _UIRoundedRectShadowView  alpha = 0.0            ⇒ no drop shadow
//
// The `.large` detent is `(0, 62, 402, 812)` — full width, top at the safe-area
// top inset, opaque, top corners ≈ 38 continuous.
//
// The MATERIAL is deliberately not reproduced: every one of this app's detented
// sheets sets `presentationBackground`, which replaces the system glass with an
// opaque colour, so `NtSheet` is a flat panel on iOS too. What must be copied is
// the GEOMETRY: a partial-height sheet is an inset floating card, not a
// bottom-anchored panel.
// ─────────────────────────────────────────────────────────────────────────────

/** The `.large` detent's top corners — ≈38 pt, **not** the 62 pt display radius. */
val NtSheetShape: Shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)

/**
 * Any partial detent: an inset floating card, **23 pt on all four corners**.
 *
 * The 37 / 54 this used to carry came from reading WWDC25 323's "the bottom edges pull in, nesting
 * in the curved edges of the display" as `displayCornerRadius − 8`. Circle fits on the rendered
 * iOS capture say otherwise: `design/parity/ios/19-cant-make-it.png` gives a left-edge inset of
 * 68 / 28 / 11 / 1 px at dy 0 / 12 / 30 / 60 on the top corner and 69 / 28 / 11 / 1 on the bottom
 * one — the same arc at both ends, r ≈ 68 px = 22.7 pt. The Android capture at 37 / 54 measured
 * r ≈ 110 px and r = 160 px, and the bottom pair was the single most visible mismatch in the whole
 * parity sheet (`design/parity/diff/19-cant-make-it-corners.png`).
 */
val NtSheetPartialShape: Shape = RoundedCornerShape(
    topStart = 23.dp,
    topEnd = 23.dp,
    bottomStart = 23.dp,
    bottomEnd = 23.dp,
)

/** The partial detent's side/bottom inset and bottom radius, at the two ends of the transition. */
private val NtSheetPartialInset: Dp = 8.dp
private val NtSheetPartialBottomRadius: Dp = 23.dp
private val NtSheetPartialTopRadius: Dp = 23.dp
private val NtSheetLargeTopRadius: Dp = 38.dp

/**
 * The `.large` detent's top edge — the file header's own view-tree dump, `(0, 62, 402, 812)`.
 *
 * **62 is not a constant, it is `safeAreaTop + 3`.** The device those captures come from has a
 * 59 pt top safe area (Dynamic Island), and iOS parks the large detent 3 pt under it — near enough
 * that the status bar reads as sitting on the dimmed backdrop rather than on the sheet, which is
 * the whole point of the inset.
 *
 * Shipping the 62 as a literal reproduced iOS's *pixel* on this emulator and broke the
 * *relationship*: `nt402`'s status bar is 136 px = 45.33 dp (`dumpsys window`:
 * `InsetsSource type=statusBars frame=[0,0][1206,136]`), so a 62 dp card left 17 dp of the
 * presenting Today screen showing under it, and that screen's eyebrow — `SATURDAY, 5 SEPTEMBER`,
 * y 165→195 px — poked out over the sheet's top edge at 186. [measured] on
 * `design/parity/android-r2/11-settings.png`: 25 rows of dimmed eyebrow glyphs above the card,
 * against nothing at all on `design/parity/ios/11-settings.png`, where it is under the sheet.
 *
 * So the inset tracks the safe area the way iOS's does — 45.33 + 3 = 48.33 dp here, card top at
 * y 145 px — and lands back on 62 on any device with a 59 pt top inset.
 */
private val NtSheetLargeTopGap: Dp = 3.dp

/**
 * The bottom safe area a fixed detent is measured *above*.
 *
 * `.presentationDetents([.height(376))` does **not** produce a 376 pt card: on
 * `design/parity/ios/18-portion-sheet.png` the card runs y 1417 → 2598 px, i.e. 393.67 pt, and on
 * `19-cant-make-it.png` (`.height(660)`) y 599 → 2598 = 666.33 pt. In both the card's *content* box
 * — card top down to the top of the home-indicator safe area (874 − 34 = 840 pt) — is the detent
 * minus the card's own bottom inset: 367.67 for 376, 640.33 for 660.
 *
 * So the detent is a **visible** height above the home indicator, and the card additionally bleeds
 * down through the indicator's inset to its own 8 pt bottom edge. That bleed is what the Android
 * card was missing: `height(376)` with `navigationBarsPadding()` *inside* it left only 376 − 24 =
 * 352 dp of content and put the card top 53 px below iOS's.
 */
private val NtSheetDetentBleed: Dp
    @Composable get() {
        val nav = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(LocalDensity.current).toDp()
        }
        return (nav - NtSheetPartialInset).coerceAtLeast(0.dp)
    }

/**
 * How many [NtSheet]s are on screen, and how deep the one being composed sits.
 *
 * ### Why a process-global counter and not a `CompositionLocal`
 *
 * The one stacked pair in the app — `PortionSheet` over `FoodSearchSheet` — declares the inner
 * sheet as a **sibling** of the outer `NtSheet(…)` call (`FoodSearchSheet.kt:130`, outside the
 * outer sheet's content lambda), so nothing the outer sheet provides *inside* its content can reach
 * it. The counter is read once per sheet, in `remember`, before that sheet pushes itself, which is
 * exactly its depth and is correct on the sheet's very first frame.
 *
 * ### What the shell may do with it
 *
 * **Nothing, deliberately.** iOS 26 does *not* push the presenter back behind a sheet — measured,
 * not assumed: fitting `design/parity/ios/06-dashboard.png` → `19-cant-make-it.png` over the
 * 114 k content pixels above the sheet gives a pure `× 0.5188` with a 0.56/255 mean residual, and
 * `17-fuel-search.png` → `18-portion-sheet.png` gives `× 0.7114` at 0.19/255 over 600 k pixels.
 * A residual that small on text-dense rows rules out any scale or translation — the presenter is
 * pixel-for-pixel where it was, only dimmed. Any `graphicsLayer` push-back on the shell would be an
 * iOS 17 behaviour that iOS 26 dropped.
 */
object NtSheetPresenter {
    /** Number of [NtSheet]s currently in the composition. */
    var presented: Int by mutableIntStateOf(0)
        private set

    internal fun push() { presented++ }
    internal fun pop() { presented-- }
}

/**
 * SwiftUI's sheet dim, per stacking depth — both ends fitted off the captures as a plain sRGB
 * multiply (see [NtSheetPresenter]):
 *
 *  * over the app: `k = 0.5188` ⇒ **α 0.48**, the `UIDimmingView bg = sRGB(0,0,0,0.48)` of the file
 *    header's view-tree dump. Android already matched this (measured 0.479 on 19).
 *  * over another sheet: `k = 0.7114` ⇒ **α 0.29**. UIKit lightens the dim for a stacked sheet so
 *    two scrims do not compound. Android stacked two 0.48 scrims and rendered the food-search card
 *    behind the portion sheet at 0.478 instead of 0.289 — the "much darker than iOS" deviation.
 */
private fun ntSheetScrimAlpha(depth: Int): Float = if (depth == 0) NtDialogScrim else 0.2886f

/** SwiftUI's sheet dim — measured `ground` 10 → 5 behind the Settings sheet. */
val NtSheetScrim: Color = Color.Black.copy(alpha = NtDialogScrim)

/**
 * The system drag indicator: 36 × 5 pt, r 2.5, centred, **5 pt from the sheet top** [view tree]
 * `_UIGrabber f = (183, 5, 36, 5)`.
 *
 * Deliberately not `Grabber()`, whose `padding(top = 8.dp)` is `Components.swift:249-253`'s
 * *in-content* grabber. The only two sheets that pass `showsHandle` (ExercisePicker, WorkoutDetail)
 * are exactly the two that on iOS show the **system** indicator, which sits 3 pt higher.
 */
@Composable
private fun NtSheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 5.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(NT.Colors.ink3, CircleShape))
    }
}

/**
 * SwiftUI's `.medium` detent — half the screen. material3 has no detent API, so the three sheets
 * that ask for `.medium` (OBTargetEditor, OBDayTime, AIScanResult) size their content to this.
 * One definition, so they cannot drift apart.
 */
@Composable
fun ntMediumDetent(): Dp = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

/**
 * SwiftUI's `.scrollDismissesKeyboard(…)`: put the keyboard away the moment a scroll *gesture*
 * starts, so the IME never sits on top of the sheet's own button. Apply to the scrollable of any
 * screen that also owns a text field — every one of the nine `.scrollDismissesKeyboard` call
 * sites in the iOS source, `.interactively` and `.immediately` alike, since both react only to
 * the user's drag.
 *
 * The [NestedScrollSource.UserInput] guard is what makes that true: without it a *programmatic*
 * scroll (a list animating a newly focused field into view) would dismiss the keyboard the user
 * just opened.
 */
@Composable
fun Modifier.ntDismissKeyboardOnScroll(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val connection = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * The app's one bottom-sheet wrapper — the Compose stand-in for SwiftUI's
 * `.sheet` + `.presentationDetents` + `.presentationDragIndicator`.
 *
 * **No feature may call [ModalBottomSheet] directly.** material3 has no
 * fixed-height detent API (`SheetValue` is only Hidden/PartiallyExpanded/
 * Expanded), so iOS's `.presentationDetents([.height(N)])` becomes a content
 * `heightIn(min = N)` — pass it as [minHeight].
 *
 * A sheet with **any** partial detent ([minHeight] set, or
 * `skipPartiallyExpanded = false` for `.medium`) is drawn as the iOS 26 **inset
 * floating card**: 8 dp clear of the left, right and bottom screen edges, top
 * corners 37, bottom corners 54. A full-height sheet stays edge-to-edge from the
 * top safe-area inset with 38 dp top corners, exactly like the `.large` detent.
 *
 * A sheet that iOS gives a **second** detent (`[.height(660), .large]`,
 * `[.medium, .large]` — pass it as [minHeight]) animates the §1.5 transition as
 * it crosses: inset 8 → 0, bottom radius 54 → 0, top radius 37 → 38.
 *
 * Defaults mirror the eight iOS sheets that hide the drag indicator; the two
 * that show one (ExercisePicker, WorkoutDetail) pass `showsHandle = true` and
 * get the **system** indicator's geometry (36 × 5, r 2.5, 5 dp from the sheet
 * top), never Material's and never the in-content [Grabber]'s 8 dp offset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NtSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showsHandle: Boolean = false,
    containerColor: Color = NT.Colors.surface,
    minHeight: Dp? = null,
    /**
     * `.presentationDetents([.height(N)])` with a **single** detent: the card sits at exactly
     * this height and does not grow with its content. Use [minHeight] instead when iOS lists a
     * second detent (`[.height(660), .large]`), where the sheet really is draggable taller.
     */
    height: Dp? = null,
    /** iOS `.medium` / `.large` detents want the partial stop; fixed heights do not. */
    skipPartiallyExpanded: Boolean = true,
    /**
     * SwiftUI `.presentationCornerRadius(N)`: **all four** corners at N, instead of the system's
     * inset-card profile (top 37 / bottom 54). The three sheets that set it on iOS
     * (`PortionSheet`, `CantMakeItSheet`, `SignInView` — all `24`) render a plainly rounder card
     * here without it: measured corner drop 89 px top / 135 px bottom against iOS's 55 / 55.
     */
    cornerRadius: Dp? = null,
    shape: Shape = NtSheetShape,
    /**
     * Whether the sheet's **viewport** stops at the navigation bar.
     *
     * iOS puts the home indicator *on* the card: the card reaches the bottom edge and only its
     * content clears it. For a body that is a plain stack that clearing is a viewport inset, which
     * is the default here. For a body that is a **scroll view** it is a scroll *content* inset
     * instead — the list keeps drawing all the way down to the card's edge and merely rests 34 pt
     * short of it — so those sheets pass `false` and add `WindowInsets.navigationBars` to their own
     * scroll content (see `SettingsScaffold.StScrollContent`, which is already written to resolve
     * to 0 while an ancestor consumes the inset and to 24 dp once it stops).
     *
     * Measured: with the viewport inset, `11-settings` ends its content at y = 2545 px and leaves
     * 77 px of bare card ground under it, where iOS draws content to 2622.
     *
     * A [height] sheet must keep the default — its `height + `[NtSheetDetentBleed] card is sized so
     * that this very padding brings the *content* back to iOS's `N − 8`.
     */
    clearsNavigationBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val partial = minHeight != null || height != null || !skipPartiallyExpanded

    // How many sheets were already up when this one appeared. Read in `remember` — i.e. during the
    // first composition, before the `DisposableEffect` below pushes this sheet — so the scrim is
    // right on the first frame instead of flashing 0.48 for one.
    val depth = remember { NtSheetPresenter.presented }
    DisposableEffect(Unit) {
        NtSheetPresenter.push()
        onDispose { NtSheetPresenter.pop() }
    }

    /** The sheet asks for a detent of its own, so its height is a *card* height. */
    val sized = height != null || minHeight != null

    // §1.5: "The medium -> large transition is the distinctive part: inset 8 -> 0, bottom radius
    // 54 -> 0, and glass -> opaque, all animated together." A `minHeight` sheet is iOS's
    // `[.height(660), .large]` / `[.medium, .large]` — one that really can reach full height — and
    // there it must anchor to the screen edges instead of staying a floating card.
    //
    // `SheetValue` cannot say when that happened: with `skipPartiallyExpanded = true` (every call
    // site) the only states are Hidden and Expanded, and a material sheet is "Expanded" the moment
    // it opens at 340 dp. So the crossing is read from the card's own height against the window.
    // The feedback is monotone — crossing only makes the card 16 dp taller, which keeps it across —
    // so there is no oscillation, and the 24 dp threshold is three times the inset it removes.
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val topInset = WindowInsets.statusBars.getTop(density)
    val detentBleed = NtSheetDetentBleed
    var cardHeight by remember { mutableIntStateOf(0) }
    val expanded = partial && windowHeight > 0 && cardHeight > 0 &&
        cardHeight >= windowHeight - topInset - with(density) { 24.dp.roundToPx() }
    val t by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = NT.Anim.easeOut25,
        label = "sheetExpand",
    )
    val inset = if (!partial) 0.dp else NtSheetPartialInset * (1f - t)
    val topRadius = if (!partial) {
        NtSheetLargeTopRadius
    } else {
        NtSheetPartialTopRadius + (NtSheetLargeTopRadius - NtSheetPartialTopRadius) * t
    }
    val bottomRadius = if (!partial) 0.dp else NtSheetPartialBottomRadius * (1f - t)
    // Only override the default; a caller that passed its own shape keeps it.
    val cardShape = when {
        shape !== NtSheetShape -> shape
        // `.presentationCornerRadius(N)` — uniform and continuous on all four corners. The three
        // sheets that ask for it are single-detent, so there is no partial → large crossing to
        // animate the radius through.
        cornerRadius != null -> NtShapes.rounded(cornerRadius)
        else -> RoundedCornerShape(
            topStart = topRadius,
            topEnd = topRadius,
            bottomStart = bottomRadius,
            bottomEnd = bottomRadius,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // A full-height sheet is iOS's `.large` detent: 3 pt below the top safe area, which is
        // 62 pt on the captured device and 27 dp on a 24 dp status bar. See [NtSheetLargeTopGap].
        modifier = if (partial) {
            modifier
        } else {
            modifier.padding(top = with(density) { topInset.toDp() } + NtSheetLargeTopGap)
        },
        sheetState = sheetState,
        // The card — its inset, its corners and its fill — is drawn below, so the
        // Material container itself contributes nothing but the gesture and scrim.
        shape = RectangleShape,
        containerColor = Color.Transparent,
        contentColor = NT.Colors.ink,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = ntSheetScrimAlpha(depth)),
        // The app draws its own grabber inside the content, so the sheet never
        // contributes Material's handle (or the height it would eat).
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A **detent** is a height of card, so the keyboard has to raise the whole
                // detent — as `.presentationDetents([.height(N)])` does on iOS — instead of
                // eating into it. That means `imePadding()` must sit OUTSIDE the height, which
                // is only right for a sized sheet: a full-height card keeps the old chain so
                // its fill still reaches behind the keyboard while it animates in.
                //
                // It goes above `onSizeChanged` on purpose: `cardHeight` drives the
                // partial → expanded crossing below, and a card measured with the keyboard's
                // inset added would cross it just by being typed into.
                .then(if (sized) Modifier.imePadding() else Modifier)
                .onSizeChanged { cardHeight = it.height }
                .then(
                    if (partial) {
                        Modifier.padding(start = inset, end = inset, bottom = inset)
                    } else {
                        Modifier
                    },
                )
                .clip(cardShape)
                .background(containerColor)
                .then(
                    when {
                        // `.presentationDetents([.height(N)])`: N is the height the card shows
                        // ABOVE the home indicator, so the card itself is N plus the bleed that
                        // carries it down through the indicator's inset — see [NtSheetDetentBleed].
                        // `navigationBarsPadding()` below then takes that bleed back off the
                        // content, leaving exactly N − 8 of it, which is what iOS renders
                        // (367.67 pt for `.height(376)`).
                        height != null -> Modifier.height(height + detentBleed)
                        // A `minHeight` sheet is iOS's two-detent `[.height(N), .large]`, and
                        // there the floor behaves like a CARD height, not a content one: the
                        // measured `[.height(660), .large]` card is 666.33 pt, 6 pt over its
                        // detent, against the 17.67 pt a single fixed detent gains. Adding the
                        // bleed here would overshoot iOS by 29 px instead of undershooting by 19.
                        minHeight != null -> Modifier.heightIn(min = minHeight)
                        else -> Modifier
                    },
                )
                // The home indicator sits ON the card on iOS: the card reaches the
                // edge, only its content clears the navigation bar. Below the `imePadding()`
                // above, so with the keyboard up — where the bar is behind the keyboard and
                // the card already clears both — Compose's inset consumption reduces this to
                // zero rather than adding a second gap.
                .then(if (clearsNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                .then(if (sized) Modifier else Modifier.imePadding()),
        ) {
            if (showsHandle) NtSheetHandle()
            content()
        }
    }
}

/**
 * SwiftUI `fullScreenCover`: an opaque `ground` layer that slides up over the
 * whole window and slides back down, `easeInOut` 0.3 s.
 *
 * Research §5.2 row 31 prefers a nav destination with the same transition where
 * the cover is a real screen (that is what `nav/` does for ActiveWorkout); this
 * exists for the covers that are presented from a leaf view and own no route.
 */
@Composable
fun NtFullScreenCover(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = NT.Colors.ground,
    content: @Composable () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    if (transition.currentState || transition.targetState) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(
                    visibleState = transition,
                    enter = slideInVertically(
                        animationSpec = tween(300, easing = NT.Ease.inOut),
                        initialOffsetY = { it },
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(300, easing = NT.Ease.inOut),
                        targetOffsetY = { it },
                    ),
                ) {
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .background(background),
                        contentAlignment = Alignment.TopStart,
                    ) { content() }
                }
            }
        }
    }
}
