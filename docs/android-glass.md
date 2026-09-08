# No Tomorrow — Liquid Glass on Android

The visual spec and implementation contract for `designsystem/glass/`. Read it with `docs/android-architecture.md` — this file expands the non-negotiable bullet "**Liquid Glass is part of 'identical'**" and **supersedes** three rows of that document (the `NtAlert` / `NtActionSheet` geometry in §"Component contract", and the `NT.Colors.tabBar` / `NT.Size.tabBar` tokens, see §1.11).

Everything below is either **[measured]** from a real capture, **[view tree]** from a live UIKit hierarchy dump, **[Apple]** quoted from documentation, or **[estimated]** — extrapolated, and flagged so it can be re-measured rather than trusted. Nothing here is decorative: if a number has no provenance tag it is wrong and should be reported.

---

## 0. Provenance and reference material

Two independent bodies of evidence sit behind this document.

**A. The real app on the real OS.** `design/ios26-reference/*.png` — No Tomorrow itself, built with Xcode 26.1.1 (17B100), running on an **iOS 26.1 (23B86) iPhone 17 Pro simulator**, 1206 × 2622 px = **402 × 874 pt @3x**, `safeAreaInsets = (top 62, bottom 34)`, `UIScreen._displayCornerRadius = 62`. Every number tagged [measured/app] comes from these.

| File | Shows | Used for |
|---|---|---|
| `00-current-screen.png` | Today tab, content stops above the bar | tab-bar fill, rim, pill, geometry. **Byte-identical to `01-tabbar-dashboard.png`** — one capture, not two |
| `02-tabbar-train.png` · `03-tabbar-fuel.png` · `04-tabbar-progress.png` · `05-tabbar-bro.png` | the other four tabs | pill travel, per-tab fill |
| `06a/06b-tabbar-rubberband.png` | identical to 05 — **failed captures** | — |
| `07-sheet-settings.png` · `zoom-sheet-top-corners.png` | `.large` sheet | sheet top inset, corner profile, scrim |
| `08-toggle-resttimer-editor.png` (= `09-toggle-off.png`) · `zoom-toggle-on.png` · `zoom-toggle-states.png` | a real `Toggle` in the ON state | track/knob geometry and colours |
| `10a/10b-toggle-middrag.png` | identical to each other — **no mid-drag frame was actually captured** | — |
| `11-wheel-schedule-editor.png` | real `DatePicker(.wheel)` | selection band |
| `12-probe-grid-tabbar-by-agent.png` | a 10 pt grid scrolled under a glass tab bar | **the only in-app lens/blur evidence** |
| `zoom-navbar-back-glass.png` · `zoom-navbar-done-glass.png` | toolbar button platters at 4 px/pt | `NtGlassButton` |
| `apple-docs/` (244 files) + `SOURCES.txt` | Apple's own iOS 26 renders, dark variants | qualitative cross-checks only — these are marketing art, never a source of numbers |

**B. A UIKit probe harness.** `/private/tmp/claude-501/-Users-joxan-projects-NoTomorrow/51af4a00-90b1-429c-9244-9c53aa360df8/scratchpad/lg/` — `App.swift` (20 scenes), `Probe.swift` (recursive view-tree dump with `frame`, `cornerRadius`, `alpha`, `backgroundColor`), `tree_*.txt`, and screenshots over engineered backdrops (10 pt grid, hard black/white edge, 8 pt stripes, flat greys, pure white) on the same simulator plus one 440 × 956 pt iPhone 17 Pro Max run (`tree_tabs_promax440.txt`). All **[view tree]** frames come from here. ⚠️ This is a scratch directory — copy anything you still need into `design/ios26-reference/` before it is cleaned.

Two harness caveats, both material:

- `App.swift:286` reads `case .tabsSolid: TabsScreen(solid: false)` — the "does `UITabBarAppearance` win?" A/B test **rendered the same view twice**. Its zero-pixel-difference result proves nothing. The question is settled instead by the shape argument in §1.11, which is decisive.
- The mid-drag toggle and the "content lensing on Train" claims that circulated in earlier drafts are **not** supported by these files. See §5.

**Apple sources.** There is no Liquid Glass HIG page (`/design/human-interface-guidelines/liquid-glass` → 404). The guidance lives on [Materials](https://developer.apple.com/design/human-interface-guidelines/materials) (metadata `alert-date: 2025-09-09`, `alert-text: "Updated guidance for Liquid Glass."`), [Technology Overviews → Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass), and WWDC25 [219 Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/), [284 UIKit](https://developer.apple.com/videos/play/wwdc2025/284/), [323 SwiftUI](https://developer.apple.com/videos/play/wwdc2025/323/), [356 New design system](https://developer.apple.com/videos/play/wwdc2025/356/). Component pages: [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars), [Sheets](https://developer.apple.com/design/human-interface-guidelines/sheets), [Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts), [Action sheets](https://developer.apple.com/design/human-interface-guidelines/action-sheets), [Menus](https://developer.apple.com/design/human-interface-guidelines/menus), [Toolbars](https://developer.apple.com/design/human-interface-guidelines/toolbars). API: [`Glass`](https://developer.apple.com/documentation/swiftui/glass), [`glassEffect(_:in:)`](https://developer.apple.com/documentation/swiftui/view/glasseffect(_:in:)), [`TabBarMinimizeBehavior.automatic`](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior/automatic), [`ScrollEdgeEffectStyle`](https://developer.apple.com/documentation/swiftui/scrolledgeeffectstyle).

---

## 1. What iOS 26.1 actually renders

### 1.1 The material — one equation

This is the most important thing in the document. Liquid Glass is **not** an alpha composite over a blurred backdrop. It is a **dynamic-range compression**: contrast is scaled to ~29 % and the whole range is lifted. In 8-bit sRGB, `regular` glass in dark appearance:

```
out = 0.294 · blur(backdrop) + L(aggregate)          [measured]
```

`L` is a lift that depends on the **aggregate** (mean) backdrop luminance under the whole element, not on the local pixel. Fit against five independent captures:

| Backdrop under the element | aggregate | local | glass output | source |
|---|---:|---:|---:|---|
| `NT.Colors.ground` #0A0A0B — the real app, Today | 10 | 10 | **28** (#1C1C1E) | `00-current-screen.png` |
| `NT.Colors.ground` — the real app, Train | 11 | 11 | **29** | `02-tabbar-train.png` |
| 10 pt grid, 1 pt white lines on black | 48 | ~48 | **~30–38** | `tabs.png`, `12-probe-…png` |
| flat 50 % grey | 127 | 127 | **89** | `grayTabs.png` |
| hard black/white edge, **black half** | 127 | 0 | **51** | `edgeTabs.png` |
| hard black/white edge, **white half** | 127 | 255 | **126** | `edgeTabs.png` |
| pure white | 254 | 254 | **253** (light-flipped) | `whiteTabs.png` |

Normalised to 0…1 the whole dark-appearance model is three constants:

```
contrast = 0.294
L(agg)   = 0.098 + 0.102 · smoothstep(0.19, 0.50, agg)      // clamp at 0.30
```

Check it: `agg 0.039` (our ground) → `L = 0.098` → `out = 0.1095` = **27.9/255**, measured 28. `agg 0.50, local 0.0` → **51.0**, measured 51. `agg 0.50, local 1.0` → **126.0**, measured 126. `agg 0.50, local 0.50` → **88.5**, measured 89. Four exact hits from three constants.

**Why this matters more than anything else here.** An earlier draft carried `out = 0.294·bd + 51` as *the* formula. Over `#0A0A0B` that renders the tab bar at (54,54,54); the real app renders (28,28,30). Nearly 2× too bright, and instantly wrong beside an iOS screenshot. The `51` intercept is only correct at `agg ≈ 127`.

**Blur.** The hard black/white content edge appears inside the glass as a smooth ramp; 10–90 % width **7.0–7.5 pt** → Gaussian **σ ≈ 2.8 pt** [measured, two independent fits at 1/3-pt resolution]. There is **no lateral displacement in the interior** — the ramp's 50 % point lands within 0.2 pt of the true edge. Refraction is a **rim-only** effect.

⚠️ Cross-check that does *not* agree: the 9.33 pt grid in `12-probe-grid-tabbar-by-agent.png` survives inside the glass with a peak-to-peak residual of **18–19/255**, where a true Gaussian at σ = 2.8 pt combined with `contrast = 0.294` predicts ~5. Apple's blur is evidently not a single Gaussian (a multi-stage down-sample/box chain passes far more high-frequency energy at the same edge width). **Match the edge width, not the grid residual** — real content is edges and text, not gratings. Expect our fine texture to be very slightly softer than iOS.

**Rim** [measured/app, `00-current-screen.png`]. Walking inward from the capsule boundary over a uniform `#0A0A0B` backdrop, on **all four edges identically**:

```
top    (x=600): 10 | 59  51  42 | 28 28 28 …
bottom (x=600): … 28 28 | 42  51  59 | 10
left   (y=2465):10 | 59  51  42 | 28 …
right  (y=2465):… 28 | 42  51  59 | 10
```

That is a **1.22 pt inner stroke of white with alpha ramping 0.165 → 0.00 linearly inward**, and it is **symmetric**

> **Re-fit (supersedes the 1 pt / 0.14 this section used to round to).** Solving each of the four samples for the white alpha over the 0.1098 body gives 0.1366 / 0.1013 / 0.0617 / 0 at x = 0.5 / 1.5 / 2.5 / 3.5 px; least squares through those four points is `a(x) = 0.165 · (1 − x / 3.667 px)`, i.e. **width 1.22 pt, start 0.165**. That renders 60 / 50 / 40 / 30 against the measured 59 / 51 / 42 / 28 — max error 2/255, inside §4's ±4. The rounded 1 pt / 0.14 renders 54 / 44 / 33 and is visibly thin. Verified on a Galaxy S22 GPU through the shipping pipeline. — over a uniform backdrop there is no directional "light from above". (A previous draft reported a +35 top / +12 bottom asymmetry; that was measured on an 8 pt-striped backdrop where the stripe phase differs top and bottom, and it does not reproduce here. Keep `rimAniso = 0` unless someone re-measures it on a uniform backdrop.) Over bright content Apple inverts the rim to a **dark contour** rather than a bright one — visible in `apple-docs/materials__materials-ios-liquid-glass-over-light_2x.png` (edge luminance 125 against a 216 interior).

**Lens band.** In `12-probe-grid-tabbar-by-agent.png` the grid visibly bunches inside a narrow band at the capsule's caps before flattening. Band width ≈ **4–5 pt**, compression ≈ **2.7×** at the extreme edge [estimated — the profile was fitted on the probe's stripe backdrop, not re-derived here].

**Shadow.** Over 50 % grey: **no shadow above the bar at all**; below it, a ~1.6 % darkening extending ~7 pt. Over pure white, ~2 %. This is an order of magnitude subtler than a Material elevation shadow, and over `#0A0A0B` it is invisible — `00-current-screen.png` reads exactly (10,10,11) in the pixel adjacent to the rim. **Ship no shadow.** [measured]

**Light/dark flip.** Over an all-white backdrop the platter flips to a light appearance (253) and inverts its glyphs to near-black, *ignoring the app's forced dark colour scheme* [measured]. Apple: "**Small elements like navbars and tabbars constantly adapt their appearance depending on what's behind them. They also flip from light to dark based on the background.** Bigger elements, like menus or sidebars also adapt based on context, but **they don't flip**" (WWDC25 219). §2.1 explains why we implement the tint adaptation but not the flip.

**Rules we inherit as constraints, not pixels** [Apple]:

- Glass belongs to the **chrome layer only**. "Don't use it in the content layer." The one exception is a content-layer control whose knob becomes glass *while being touched* — that is exactly `NtToggle`.
- **Glass cannot sample glass.** `GlassEffectContainer` exists so neighbouring glass shares one sampling region (WWDC25 323/284). Our equivalent rule is in §3.3.
- Glass **never animates `alpha`** — "Liquid Glass objects materialize in and out by gradually modulating the light bending and lensing" (219). UIKit: "Always prefer setting the `effect` property over the `alpha`" (284).
- Larger surfaces are **more opaque**, smaller ones clearer (219/284). Our menu (`agg 89 → 48`, a *darkening*) versus our tab bar (`agg 89 → 89`) is that rule in numbers.

### 1.2 Tab bar — a floating glass capsule

[view tree, 402 × 874 pt, 5 tabs]

```
UITabBar               f = (0, 791, 402, 83)          = 49 + bottomSafeArea(34)
  _UITabBarPlatterView f = (21,  0, 360, 62)          the visible glass capsule, pinned to the TOP of the bar
    _UITabButton       f = (4, 4, 77, 54)  ×5, pitch 68.75
    _UILiquidLensView  f = (4, 4, 77, 54)             the selection pill
```

| Metric | Value | Provenance |
|---|---|---|
| Platter width | `min(intrinsic, screenWidth − 42)`; **21 pt inset each side**. There is **no 360 pt cap** | [view tree] 402 pt→360; 440 pt→**398**; 3 tabs @402→274 centred |
| Platter height / radius | **62 pt**, r = **31** = h/2, a true capsule | [view tree] + chord fit at 7 depths, ≤0.8 px error |
| Vertical position | platter top = screen bottom − (49 + bottomSafeArea) | [view tree] |
| Gap below platter | `bottomSafeArea − 13` (= 21 pt on iPhone) | derived from the frames above — see §3.6 |
| Content bottom safe-area inset | **83** (= 49 + 34) — content scrolls fully under the capsule | [view tree] |
| Item box | `pitch = (platterWidth − 16.25) / n`, `itemWidth = pitch + 8.25`, height **54**, padding **4** all round | [view tree] ×3 configurations, ≤0.17 pt |
| — verification | 402/5 → 68.75 / 77.00 · 440/5 → 76.35 / 84.60 · 402/3 → 86.00 / 94.00 | matches the dumps exactly |
| Icon | 28 pt box, top at item-y **6** (platter-y 10) | [view tree] |
| Label | 12 pt line box at item-y **35** (platter-y 39) → **10 pt font** | [view tree] |
| Selection pill | **= the item box**, r = 27; rendered ~1 pt wider than its frame (soft SDF edge) | [view tree] + [measured/app] 234 px w × 161.9 px h = 78.0 × **53.97** pt |
| Pill optics | **uniform additive lift, no rim, no separate lens**: `+0.129 − 0.032·bar` | [measured/app] 28→61 (+33); [measured] 51→83 (+32), 89→120 (+31), 126→155 (+29) |
| Pill, light-flipped | 253 → **235** (−18, ≈7 % black) | [measured] `whiteTabs.png` |
| Scroll edge effect | **none around the capsule** — the grid outside it is at full contrast right up to the rim | [measured] `12-probe-…png` |

Items deliberately **overlap by ~8.25 pt**; they do not tile. Never hard-code 77 pt — that is only the 402 pt/5-tab answer and it is 12 % too wide on the S22.

**Minimise-on-scroll: no.** `TabBarMinimizeBehavior.automatic` is the default and Apple documents it verbatim: "On iOS, iPadOS, tvOS, and watchOS, the tab bar does not minimize." `UITabBarController.tabBarMinimizeBehavior` default `rawValue == 0` [view tree]; a 350 pt scroll left the platter at exactly y = 791–853 [measured]. No Tomorrow never calls `.tabBarMinimizeBehavior`. **Do not implement it on Android.**

**On the S22 (360 × 780 dp):** platter **318 × 62 dp**, r 31, x = 21; pitch **60.35**, item **68.60 × 54**; container height `49 + navInset`; gap `navInset − 13`.

### 1.3 Toggle — the 51 × 31 era is over

[view tree] + independently re-measured on `08-toggle-resttimer-editor.png` at native 3x, sub-pixel:

```
UISwitch  bounds 63 × 28   (intrinsic 61 × 28; the switch overhangs its SwiftUI host by 1 pt each side)
  track (off)  63 × 28  r 14  bg = tertiaryLabelColor
  track (on)   63 × 28  r 14  bg = tint
    UIImageView 630 × 31   ← a 10×-wide fill strip, x = 0 when ON, x = −567 when OFF
  _UILiquidLensView (knob)  ON (24, 2, 37, 24) · OFF (2, 2, 37, 24)
```

| | iOS 18 | **iOS 26.1** | measured on the app |
|---|---|---|---|
| Track | 51 × 31, r 15.5 | **63 × 28, r 14** | 188 px = **62.67 pt** × 84 px = **28.0 pt** ✅ |
| Knob | 27 pt **circle** | **37 × 24 capsule, r 12** | 110 px = **36.67** × 72 px = **24.0 pt** ✅ |
| Inset / travel | 2 / 20 | **2 / 22** (x 2 → 24) | 6 px = 2.0 pt on all four sides ✅ |
| ON track colour | — | the `tint` | **#F2F2F4 = `NT.Colors.ink`** (all 3 call sites tint `ink`, not `.white`) |
| Knob colour on that track | — | glass over a solid tint | **#FFFFFF** |
| OFF track colour | — | `tertiaryLabelColor` = `#EBEBF5 @ 30 %` | 160 over a 128 grey ⇒ α·(235−128)=32 ⇒ **α = 0.30**. Over `surface` that is ≈ (90,90,95) |

Three things change the silhouette and must be got right: the knob is a **capsule, not a circle**; the ON fill **sweeps in from the left** (a 630 pt strip translating from x = −567 to 0), it does not cross-fade; and the knob is a `_UILiquidLensView`, i.e. **real glass** that stretches and shows the track through it during a drag ("the knob transforms into Liquid Glass during interaction" — Adopting Liquid Glass, verbatim).

**No mid-drag frame exists.** `10a-toggle-middrag.png` and `10b` are byte-identical to each other and show no drag. The stretch amounts in §3.6 are [estimated] and are the first thing to re-capture.

### 1.4 Wheel picker

[view tree] `UIDatePicker.intrinsicContentSize = 320 × 216` (identical to `UIPickerView`); centre `UIPickerTableViewWrapperCell` height **32.0**; 7 visible rows.

| | Value | Provenance |
|---|---|---|
| Selection band | **302 × 34 pt**, capsule **r 17**, inset **9 pt** each side of the 320 pt picker | [measured] ×2 — probe over pure black: rows 1302–1403 = 102 px; real app over `surface`: rows 1042–1143 = **102 px**, x 150–1055 = **906 px = 302.0 pt**. Identical. |
| Band fill | `#EBEBF5 @ ~8.5 %`, **flat — not a glass surface**: no rim, no lensing | [measured] (21,21,23) over #000; **(44,44,48) over `surface`** |
| Row projection | pitch 31.2 → 26.9 → 19.2 pt walking out; glyph height 17.3 → 14.5 → 11.0 → 6.8 pt; peak luminance 189 → 90 → 83 → 49 | [measured] |

Model it as a cylinder: `y' = R·sin θ`, `scale = cos θ`, `alpha = cos^k θ`, wheel spanning ≈ ±65° over 7 rows, `R ≈ 34 pt` [estimated fit].

⚠️ An intermediate review claimed 31.33 pt / r 15.7 for the band. Two independent captures both give exactly 102 px on rows well inside the caps. **34 pt stands.**

### 1.5 Sheets

[Apple, WWDC25 323] "On iOS 26, **partial height sheets are inset by default with a Liquid Glass background**. At smaller heights, the bottom edges pull in, nesting in the curved edges of the display. When transitioning to a full height sheet, the glass background gradually transitions, **becoming opaque and anchoring to the edge of the screen**."

**Medium / any partial detent** [view tree]:

```
UIDropShadowView f = (8, 415.03, 386, 450.97)   bg = nil (glass)
  _UIGrabber     f = (183, 5, 36, 5)  r 2.5
UIDimmingView    bg = sRGB(0,0,0, 0.48)
_UIRoundedRectShadowView  alpha = 0.0            ← no drop shadow
```

| | Value |
|---|---|
| Inset | **8 pt** left, right **and bottom** |
| Top corner radius | **≈ 36–38 pt, continuous** [measured] — independent circular fit on the `.large` sheet's corner profile gives r ≈ 38 at 7 depths; the ios-spec's alert-referenced fit gave 36.7 |
| Bottom corner radius | `displayCornerRadius − inset` = 54 [estimated — concentricity is Apple's stated design language, not a layer value] |
| Grabber | **36 × 5 pt, r 2.5**, centred, 5 pt from the sheet top |
| Scrim | **black @ 0.48** — [measured/app] independently: `ground` 10 → **5** behind the Settings sheet ⇒ factor 0.5 |
| Glass transfer over the dimmed backdrop | `out = 0.233 · dimmed + 30` |

**Large detent** [view tree + measured/app]: `(0, 62, 402, 812)` — full width, top at the safe-area top inset, background **opaque `systemBackgroundColor`**, no glass, no insets. Top corners ≈ 38 pt continuous (**not** the 62 pt display radius). Confirmed on `07-sheet-settings.png`: sheet top row 186 px = **62.0 pt** exactly.

**The medium → large transition is the distinctive part**: inset 8 → 0, bottom radius 54 → 0, and glass → opaque, all animated together.

**But almost none of this app's sheets show the material.** `grep` over the Swift sources:

| Sheet | Detent | `presentationBackground` | Glass? |
|---|---|---|---|
| `AIScanResultView.swift:268` | `.medium` | — | **yes — the only one** |
| `RestTimerView.swift:36` | `.large` | — | no (large is opaque by definition) |
| `SignInView.swift:47` | `.large` | `surface` | no |
| `CantMakeItSheet.swift:64` | `.height(660)` | `surface` | no |
| `PortionSheet.swift:53` | `.height(376)` | `surface` | no |
| `LogWeightSheet.swift:64` | `.height(340)` | `ground` | no |
| `OBDayTimeSheet.swift:48` | `.medium` | `ground` | no |
| `OBTargetEditorSheet.swift:53` | `.medium` | `ground` | no |
| `ExercisePickerView.swift:44` · `WorkoutDetailSheet.swift:43` | — | `ground` | no |
| the remaining 9 `.sheet(` sites | full height | content paints opaque | no |

`presentationBackground` replaces the system material exactly the way `configureWithOpaqueBackground()` was *supposed* to on the tab bar — and unlike the tab bar, on sheets it works. **So `NtSheet` is a flat panel.** What it must still copy is the **geometry**: a partial-height sheet is an inset floating card (8 dp l/r/b, ~37 dp top corners, ~54 dp bottom corners), not a bottom-anchored panel. That change applies to all six partial-height sheets regardless of material.

Drag indicator: 5 of the 8 detented sheets set `.hidden`; the only two `.visible` grabbers are on `ExercisePickerView` and `WorkoutDetailSheet`, which have **no** detents.

### 1.6 Alerts

[view tree] — this is a complete redesign versus iOS 18 and the current `NtAlert` spec is the old one.

```
_UIAlertControllerPhoneTVMacView  f = (41, 375, 320, 152)   cornerRadius = 34
  UILabel (title)    f = (30, 22.00, 260, 20.33)
  UILabel (message)  f = (30, 49.67, 260, 18.00)
  UIStackView        f = (16, 16, 288, 48)
    _UIAlertControllerFilledBackgroundView  140 × 48  r 24  bg = tertiarySystemFillColor
    (second button at x = 148 → 8 pt gap)
```

| | Value |
|---|---|
| Panel | **320 pt** wide, centred, corner radius **34** (was 14) |
| Text | h-padding **30**; title y 22, 17 pt semibold, **left-aligned**; message y 50, 13 pt |
| Buttons | **capsules, 48 pt tall, r 24**, side by side, **8 pt gap**, 140 pt each for two |
| Button fill | `tertiarySystemFillColor` — **+24/255** over the panel body |
| Separators | **none** — iOS 18's full-width dividers are gone |
| Scrim | `_alertControllerDimmingViewColor`, factor 0.517 ⇒ **≈48 % black**, same as sheets |
| Order in the measured render | `.cancel` **left**, `.destructive` **right**, both filled; destructive text `systemRed` |

Typography rule [Apple, WWDC25 356]: "now **bolder and left-aligned** to improve readability in key moments like alerts and onboarding."

### 1.7 Confirmation dialogs (action sheets)

[Apple, Adopting Liquid Glass, verbatim] "An action sheet **originates from the element that initiates the action**, instead of from the bottom edge of the display. When active, an action sheet also lets people interact with other parts of the interface."

> ⚠️ **That does not hold for this app's dialogs — they are CENTRED.** [measured] on `17-confirmationdialog-finish.png` (native 3×): the panel body spans x 82.0 → 320.0 pt, centre **201.0** = screen centre 402/2, while its anchor is the top-right *Finish* button at y ≈ 210. The `originates from` wording describes the *transition* (it scales out of the anchor), not the resting position. **Position the card at the screen centre; do not anchor it.** The scale-out origin may still be the anchor.

[view tree] A SwiftUI `.confirmationDialog` with **no explicit source anchor** already rendered as an anchored popover — SwiftUI supplies the anchor from the modified view:

```
_UIPopoverGlassBackground        f = (0, 0, 240, 292.33)
  UIPlatformGlassInteractionView f = (0, 0, 240, 292.33)
  _UIAlertControllerPhoneTVMacView
    UILabel (title)  f = (30, 22, 180, 20.33)
    UIStackView      f = (16, 16, 208, 216)
      4 × _UIAlertControllerFilledBackgroundView  208 × 48  at y = 0, 56, 112, 168
_UIPopoverDimmingView  alpha = 0                 ← no scrim; the rest of the UI stays live
```

| | Value |
|---|---|
| Panel | **240 pt** wide (a popover, not a bottom sheet), **centred**, padding 16, genuine glass |
| Corner radius | **≈ 26 pt** [7-depth circular fit on `17-confirmationdialog-finish.png`; the earlier 28 was estimated] |
| Height | **≈ 173 pt** for a title + two rows: 16 top pad, title, 18 gap, rows at an 8 pt gap, 12 bottom pad |
| Rows | **208 × 48 pt** capsules, 8 pt gaps (pitch 56) |
| Opacity | **66** over an 89 backdrop — noticeably more transparent than a menu (48). Over this app's own `ground` the body measures **(19, 21, 20) ≈ #141416** |
| Scrim | **none** |

✅ **SETTLED: iOS 26.1 DOES drop the `.cancel` row.** WWDC25 284: "Action sheets presented inline **don't have a cancel button** because the cancel action is implicit by tapping anywhere else." The captured dialog is `ActiveWorkoutView.swift:85`, which declares `workout.finish`, `workout.discard` and `common.cancel` — and the capture shows **two** rows in a ≈173 pt card (three rows would need ≈229). `NtActionSheet` therefore filters `NtAlertRole.Cancel` out of `actions` and keeps `cancel` only for its content description; dismissal is an outside tap. This closes §5, open question 3.

### 1.8 Menus

[measured] on a 4-item, text-only menu over a 35 % grey backdrop. There is **no `tree_menu.txt`**, so every menu number is pixel-derived and approximate.

| | Value |
|---|---|
| Panel | ≈ **249 × 187 pt** (bounding box 252.7 × 197.3 including shadow bleed) |
| Corner radius | **≈ 33 pt** [estimated — profile-fitted against the alert's known r = 34] |
| Item pitch | **≈ 42 pt** |
| Separators | **none** |
| Body over an 89 backdrop | **48** — the menu **darkens** its backdrop (the "larger elements are more opaque" rule) |
| Light/dark flip | **no** — menus do not flip (WWDC25 219) |
| Scrim | **none** — the backdrop outside the panel measured unchanged at 89 |
| Presentation | morph/"pop open" from the source control, anchored to it [Apple] |

### 1.9 Navigation bar / toolbar buttons

[view tree] + [measured/app] on `zoom-navbar-*.png` (4 px/pt crops):

| | Value |
|---|---|
| Lone icon button | **44 × 44 pt, r 22** — a circle. Measured 176 px / 4 = **44.0** ✅ |
| Grouped items | one shared capsule, 44 pt tall, r 22, width = sum of items. Measured "Done" platter 295 × 177 px = **73.75 × 44.25 pt** |
| Leading margin | **16 pt** |
| Bar item row | **54 pt** tall, starting at the top safe-area inset |
| Large title band | **52 pt**, label at x 16, 40.67 pt line box (34 pt bold) |
| Bar background | transparent by default; legibility comes from the scroll edge effect |
| Over `ground` | the platter renders **#1C1C1E** — exactly the tab bar's value, as the §1.1 model predicts |

Grouping rule [Apple, WWDC25 284]: image buttons share a background; "Text buttons, the system Done and Close buttons, and prominent style buttons have separate glass backgrounds." Observed deviation: in the SwiftUI probe a trailing `Button("Done")` was folded into the *same* capsule as two icon buttons. If the split matters, force it with `ToolbarSpacer(.fixed)` on iOS and mirror the result on Android.

**In this app it barely applies**: there are exactly **2** `NavigationStack`s (`SettingsView.swift:29`, `ProgressHomeView.swift:15`), and Settings forces `.toolbarBackground(NT.Colors.ground, for: .navigationBar)` at `SettingsView.swift:33` and `SettingsComponents.swift:260`. One of the two `ToolbarItem` sites is a `ToolbarItemGroup(placement: .keyboard)` inside a `fullScreenCover` (`ActiveWorkoutView.swift:79`) — a keyboard accessory, not a nav bar. So `NtGlassButton` has a **small, well-defined blast radius**, and progressive scroll-edge blur is *not* an iOS-parity item for this app.

### 1.10 Accessibility [Apple, WWDC25 219 — not measured]

- **Reduce Transparency** — "makes Liquid Glass **frostier** and obscures more of the content behind it."
- **Increase Contrast** — "makes elements **predominantly black or white** and highlights them with a **contrasting border**."
- **Reduce Motion** — "decreases the intensity of some effects and **disables any elastic properties** for the material."

### 1.11 What this supersedes in `docs/android-architecture.md`

1. **`NT.Colors.tabBar` (#161618) is a dead token on iOS 26.** `RootView.swift:28-40` configures `UITabBarAppearance().configureWithOpaqueBackground()` with `backgroundColor = tabBar` and a hairline `shadowColor`. The rendered bar is a **floating capsule inset 21 pt with r = 31, filled #1C1C1E, with no hairline and no shadow** — `configureWithOpaqueBackground()` cannot produce that shape, cannot produce that colour, and produced no separator. Confirmed to sub-pixel by a 7-depth chord fit that decisively excludes any rounded rect. Apple predicted it: "Reduce your use of custom backgrounds in controls and navigation elements… Prefer to remove custom effects and let the system determine the background appearance" (Adopting Liquid Glass, which lists `UITabBar` explicitly), and WWDC25 284: "the bar background is now transparent by default. Remove any background customization." Corroborated by the [Orange OUDS iOS team](https://github.com/Orange-OpenSource/ouds-ios/discussions/1076) and [DevForums 796052](https://developer.apple.com/forums/thread/796052). **Do not port `tabBar` #161618 or `NT.Size.tabBar = 49` as the Android bar's fill and height.** Keep both tokens (they are still the fallback fill and the container-height term) but stop treating them as the design.
2. **`NtAlert` is specified with iOS 18 geometry** ("270 dp, 14 dp radius, `surface2`, 44 dp action rows, two-up split by a vertical hairline"). Replace with §1.6: 320 dp, r 34, left-aligned, 48 dp capsule buttons with an 8 dp gap, no separators.
3. **`NtActionSheet` is specified as a bottom-anchored two-group stack.** Replace with §1.7: a 240 dp anchored popover, 208 × 48 dp rows, no scrim.
4. Unresolved but noted: `NtSheet` currently pins `skipPartiallyExpanded = true`. Six sheets need partial heights with the §1.5 inset geometry.

**Not glass, and must stay flat** — cards, rows, chips, rings, charts, the single `List`, the 7 `ShareLink` share sheets (Android's chooser is system UI and is not skinnable), both opaque nav bars, and 18 of 19 sheets.

---

## 2. The Android approach

### 2.1 Decision: our own AGSL, in one module. No glass library.

**Decided: write the shader.** `docs/android-architecture.md` already forbids a shader/blur library, and the evidence supports that call rather than merely inheriting it:

1. **No library implements our equation.** What we need is `out = 0.294·bd + L(agg)` — coefficients summing to 0.494. Every library's surface tint is an *alpha-over* (`onDrawSurface { drawRect(color) }` in Kyant backdrop, `tint`/`alpha` in Haze), and an alpha composite always has coefficients summing to 1. You cannot express a dynamic-range compression with it. To hit our numbers on top of a library you must write a `runtimeShaderEffect` anyway — at which point the library contributes only backdrop capture, which is ~120 lines (§3.3).
2. **Our surface area is small and closed.** Eight components, one modifier, one shader. The whole module is ~600 lines. A dependency that is bigger than the thing it replaces is a bad trade.
3. **We need explicit three-tier control at `minSdk 26`.** Kyant backdrop's `blur()` returns early below API 31 and `lens()` below API 33 — **silently, with no visual substitute** (`backdrop/src/androidMain/.../Platform.kt`). On API 26–32 the library hands you a transparent hole and your own scrim carries the design regardless.
4. **Library-specific hazards we would inherit.** `lens()` throws `UnsupportedOperationException` on any shape that is not `RoundedRectangularShape`/`CornerBasedShape`; glass-on-glass is a documented `SIGSEGV` in the RenderThread requiring the `exportedBackdrop` escape; and the published GitBook documents the **1.0.x** API (it still lists `exposureAdjustment`/`gammaAdjustment`, which do not exist in 2.0.1 — they were removed by the 2.0.0 "Remove Android related effects" change). Code written from those docs does not compile.
5. Our chrome is dark-on-dark with a tiny palette. The exotic parts of a general library (chromatic aberration, surface profiles, progressive optics, Fresnel ambient response) are things we would switch off anyway.

**The escape hatch, pre-approved in principle.** If §2.3's budget is missed on device or the shader proves unmaintainable, adopt:

```toml
kyant-backdrop = { module = "io.github.kyant0:backdrop", version = "2.0.1" }   # Apache-2.0, published 2026-08-26
kyant-shapes   = { module = "io.github.kyant0:shapes",   version = "1.2.1" }   # Apache-2.0, published 2026-08-26
```

behind the *same* `Modifier.liquidGlass` facade, using its public `runtimeShaderEffect(key, agsl, "content") { }` for our transfer function and letting it own capture/coordinate alignment only. Its POM pulls `org.jetbrains.compose:{ui,foundation,ui-graphics}-android:1.12.0`, which are redirect artifacts resolving to plain `androidx.compose.*:1.12.0` — exactly what BOM `2026.08.00` pins, so no duplicate classes and no KMP plugin. `com.kyant.shapes.Capsule` (there is no `Capsule` in Jetpack Compose; `androidx.graphics.shapes.RoundedPolygon` is not a `Shape` and would hit the `lens()` exception) becomes a hard dependency, and both artifacts need NOTICE entries. **This requires amending the non-negotiable in `docs/android-architecture.md` — raise it, do not do it quietly.** [chrisbanes/haze](https://github.com/chrisbanes/haze) `2.0.0-beta02` is the second alternative; its degradation matrix is the best-designed of any option and is worth stealing conceptually (we do, in §2.2), but `haze-glass` is `@ExperimentalHazeApi` and its shader pipeline is closed to us.

**Android gives us nothing native.** Material 3 Expressive's blur is SystemUI, not an app API; `androidx.compose.material3:1.4.0` has no glass material, no backdrop sampling, no refraction. The only platform pieces are the Android 12 ones: [`RenderEffect`](https://developer.android.com/reference/android/graphics/RenderEffect) (31+), [`RuntimeShader`](https://developer.android.com/reference/android/graphics/RuntimeShader) / [AGSL](https://developer.android.com/develop/ui/views/graphics/agsl) (33+), and [`Window.setBackgroundBlurRadius`](https://developer.android.com/reference/android/view/Window#setBackgroundBlurRadius(int)) (31+).

### 2.2 Fallback matrix by API level

`minSdk 26`, so all three tiers ship. The user's S22 is API 36 → tier **Full** and will never exercise the other two: **they must be caught by policy and screenshot tests, not by manual testing.**

| Tier | API | Blur | Rim refraction | Specular rim | Transfer | What the user sees |
|---|---|---|---|---|---|---|
| **Full** | **33+** | `createBlurEffect` chained under the shader | AGSL SDF, 4.5 dp band | AGSL, from the same pass | full `0.294·bd + L(agg)` | Liquid Glass |
| **Blur** | 31–32 | `createBlurEffect` only | ✗ | 1.22 dp inner stroke, `White @ 0.165 → 0` via `drawRect`/`Brush` | approximated by a `ColorMatrix` `ColorFilter` (`scale 0.294`, `offset L·255`) applied to the blurred layer — this reproduces the transfer **exactly**, it is the one thing that survives without AGSL | frosted panel, no lens. Correct-looking. |
| **Tint** | 26–30 | ✗ | ✗ | same static stroke | flat `NT.Colors.surface` (= the exact value the model yields over `ground`, §1.1) | a solid dark capsule |

`ColorMatrix` on API 31–32 is the key insight: `RenderEffect.createColorFilterEffect` is API 31, so tiers Full and Blur share the *same* colours and differ only in the rim/lens. Only tier Tint is visibly different, and on `ground` it is off by less than 1/255.

```kotlin
enum class GlassTier { Full, Blur, Tint }

val LocalGlassTier = staticCompositionLocalOf {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassTier.Full   // 33
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S        -> GlassTier.Blur   // 31
        else                                                  -> GlassTier.Tint
    }
}
```

Degrade further **at runtime** (recompute, do not cache once) for `PowerManager.isPowerSaveMode`, `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, and the reduce-transparency / high-contrast accessibility settings where readable — the Android analogue of §1.10. `Tint` is the target of every downgrade.

**Do not** enable a RenderScript blur backport for API ≤ 30. Haze's own docs describe its experimental path as always ≥1 frame behind, frame-dropping by design, and dependent on a `ViewTreeObserver` pre-draw listener that over-invalidates the window. A flat scrim is the better product decision on a 2017-era device.

### 2.3 120 Hz on a Galaxy S22

Budget is **8.33 ms**. The S22 is adaptive 48–120 Hz, so static chrome is often composited slower anyway. Reference point: Haze's published Macrobenchmark (Pixel 6, 60 Hz locked, P90 CPU frame) is 5.6 ms for one glass surface and 8.1 ms for nine — **count matters as much as area**, and at 120 Hz that 9-surface headroom does not exist.

Non-negotiable rules:

1. **One backdrop capture for the whole app**, recorded in `MainTabScaffold` (§3.3). Never one per glass node.
2. **At most two glass nodes on screen at once.** The tab bar is always one; a sheet/alert/menu is the other. The selection pill is *not* a glass node — it is an additive capsule over the bar's own output (§1.2), which is both cheaper and exactly what iOS renders.
3. **Record, then draw the layer** — `layer.record { drawContent() }; drawLayer(layer)`. Do not `drawContent()` *and* record; that rasterises the NavHost twice per frame.
4. **Clamp the recorded area.** The backdrop layer is the NavHost's own size; the *effect* layer is `elementSize + 2·pad` where `pad = ceil(2.5·σ)` ≈ 21 px. Never run a full-screen effect layer for a 62 dp bar.
5. **Cache the `RuntimeShader` object** in `drawWithCache`'s cache block and only `setFloatUniform` afterwards. AGSL compilation is not free; never construct one in a draw phase.
6. **Blur radius is the dominant term** and it inflates the recorded area linearly on the perimeter (`2r(w+h) + 4r²`). σ = 2.8 dp is the measured value; do not exceed it "for effect".
7. **No chromatic aberration, no dispersion, no depth term.** iOS shows none in the interior (§1.1), and each costs 3–7 taps per pixel instead of 1.
8. **The 3×3 aggregate is 9 taps in a uniform branch.** Enable it (`luminanceAdaptive = true`) only on the tab bar and `NtGlassButton`; leave it off for the toggle knob and wheel band, where `liftLo == liftHi` makes it a no-op anyway.
9. **No sensor-driven specular.** It is ~70 lines and tempting, but iOS drives its highlights from scroll and layout, not the accelerometer, so it would make us *diverge*; a `SENSOR_DELAY_UI` listener writes state ~60×/s and turns static glass into continuously-redrawing glass; and it must be lifecycle-managed or it drains battery. If we ever want motion in the rim, drive it from `firstVisibleItemScrollOffset`.
10. **Profile the `RenderThread`, not the UI thread**, with Perfetto — `RenderEffect` work lands there. Add a Macrobenchmark on the busiest scrolling screen with the tab bar visible, and include the glass composables in the existing `androidx.baselineprofile` run (`profileinstaller 1.4.1`, `benchmarkMacro 1.4.1` are already in the catalog).
11. **A glass node that animates gets its own `graphicsLayer`, and so does the chrome around it.** A draw invalidation climbs to the nearest layer and re-records *everything* under it. In the tab shell the nearest layer above `NtTabBar` was the root `NavHost` entry, so every spring frame of the selection pill rebuilt the page — and its backdrop capture, which draws the page a second time — on the UI thread. That was the 2026-09-08 "laggy when switching tabs" report on the S22. `NtTabBar` now carries three layers (bar, bar surface, pill); see `android-status.md` §7 for the numbers.
12. **Nothing an animation drives may be read in composition or layout.** `Modifier.liquidGlassLens` takes its geometry and `GlassStyle` from a lambda evaluated in the draw phase, so a spring frame is one draw of one small layer — never a recomposition, a `ModifierNodeElement.update`, or a relayout.
13. **Effect layers never resize per frame.** A `RenderEffect` layer is a GPU surface; re-allocating it at a new size every 8 ms is the single most expensive thing the old pill did. `liquidGlassLens` allocates once at the shape's largest extent (plus blur and lens reach) and moves the layer's origin; the shape's live size and position are the `size`/`pad` uniforms. Rule 5's "cache the `RuntimeShader`" is now process-wide: one compiled instance (`newGlassShader`), bound and snapshotted into an effect per draw.

Ordered fallback if the budget is missed: drop the lens band → drop the aggregate taps → reduce σ → fall back to tier `Blur` for that component.

---

## 3. The module — `designsystem/glass/`

### 3.1 Files

```
designsystem/glass/
  GlassStyle.kt      GlassStyle, GlassAppearance, the four presets, the measured constants
  GlassTier.kt       GlassTier, LocalGlassTier, runtime downgrades
  NtBackdrop.kt      NtBackdrop, Modifier.ntBackdropSource, LocalNtBackdrop
  LiquidGlass.kt     Modifier.liquidGlass(shape, style) — dispatches on tier
  GlassShader.kt     NT_LIQUID_GLASS_AGSL + the RuntimeShader cache and uniform binding (API 33+)
  GlassShapes.kt     CornerBasedShape -> float4 corner radii in px
  GlassProbe.kt      debug-only screen: engineered backdrops under each component (§4)
```

Nothing outside this package may touch `RenderEffect`, `RuntimeShader`, `Modifier.blur`, or `GraphicsLayer.renderEffect`. Feature code never sees any of it.

### 3.2 API

```kotlin
enum class GlassAppearance { Dark, Light }

@Immutable
data class GlassStyle(
    // --- blur ---------------------------------------------------------
    val blurSigma: Dp = 2.8.dp,          // MEASURED. sigmaPx = blurSigma.toPx()
                                         // RenderEffect radius = (sigmaPx - 0.5f) / 0.57735f
    // --- rim refraction (lens) ----------------------------------------
    val refractionHeight: Dp = 4.5.dp,   // band that bends; must be <= min corner radius
    val refractionAmount: Dp = 3.0.dp,   // max lateral displacement at the extreme edge
    // --- specular rim -------------------------------------------------
    val rimWidth: Dp = 1.22.dp,          // MEASURED (§1.1 re-fit): 1.22 pt, not 1
    val rimAlpha: Float = 0.165f,        // MEASURED: white, ramping 0.165 -> 0 inward
    val rimAngle: Float = 0f,            // degrees, 0 = from above. Only used when rimAniso > 0
    val rimAniso: Float = 0f,            // MEASURED: 0 — the rim is symmetric on all four edges
    // --- transfer -----------------------------------------------------
    val contrast: Float = 0.294f,        // MEASURED
    val liftLo: Float = 0.098f,          // MEASURED: lift at a dark aggregate
    val liftHi: Float = 0.200f,          // MEASURED: lift at aggregate 0.50
    val liftK0: Float = 0.19f,
    val liftK1: Float = 0.50f,
    val tint: Color = Color.White,       // hue of the lift, not an alpha-over colour
    val luminanceAdaptive: Boolean = true,   // 9-tap aggregate; false pins the lift to liftLo
    val appearance: GlassAppearance = GlassAppearance.Dark,
    // --- extras -------------------------------------------------------
    val overlay: Float = 0f,             // additive lift (selection pill: 0.125)
    val noise: Float = 0f,               // grain amplitude; 0 for every NT component today
    val flatFill: Color = NT.Colors.surface,  // tier Tint, and the base for tier Blur
) {
    companion object {
        /** Tab bar, nav-bar buttons — small chrome that adapts to its backdrop. */
        val Chrome = GlassStyle()
        /** Sheets and alerts — large chrome. Apple: bigger = more opaque, and it never flips.
         *  Fitted to the sheet's measured `out = 0.233*dimmed + 30/255`. The alert body lands
         *  ~7/255 lighter than this predicts; tune if the side-by-side shows it. */
        val Panel = GlassStyle(
            contrast = 0.233f, liftLo = 0.118f, liftHi = 0.118f,
            luminanceAdaptive = false, refractionHeight = 8.dp,
        )
        /** Action sheets — a popover, measurably more transparent than a menu (89 -> 66). */
        val Popover = GlassStyle(
            contrast = 0.233f, liftLo = 0.178f, liftHi = 0.178f,
            luminanceAdaptive = false, refractionHeight = 8.dp,
        )
        /** Menus — larger still, and they DARKEN their backdrop (89 -> 48). */
        val Menu = GlassStyle(
            contrast = 0.20f, liftLo = 0.118f, liftHi = 0.118f,
            luminanceAdaptive = false, refractionHeight = 8.dp,
        )
        /** The toggle knob: real glass over a solid track, no adaptation.
         *  Constrained by one data point — over the ON track (#F2F2F4 = 0.949) the knob must
         *  saturate to #FFFFFF: 0.55*0.949 + 0.48 = 1.00. Everything else here is [estimated]. */
        val Knob = GlassStyle(
            blurSigma = 2.0.dp, refractionHeight = 5.dp, refractionAmount = 4.dp,
            contrast = 0.55f, liftLo = 0.48f, liftHi = 0.48f,
            luminanceAdaptive = false, rimAlpha = 0.18f,
        )
    }
}

/**
 * Draws [shape] filled with the Liquid Glass transform of whatever the app's single
 * NtBackdrop recorded behind this node, then draws this node's own content on top.
 * The backdrop is taken from LocalNtBackdrop unless [backdrop] is given.
 *
 * Never apply this to a node that is itself inside the recorded backdrop, and never
 * nest two of them: glass cannot sample glass (see 3.3).
 */
fun Modifier.liquidGlass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Chrome,
    backdrop: NtBackdrop? = null,
): Modifier
```

`Panel` / `Popover` / `Menu` / `Knob` are [estimated] fits to one or two points each from §1.5–1.8; `Chrome` is [measured] to ±1/255 at four points and is the one to trust.

### 3.3 Backdrop capture

There is exactly **one** `NtBackdrop` per app, created in `RootScreen` and recorded around the tab NavHost inside `MainTabScaffold`. `NtTabBar` is a sibling of the NavHost in the same `Box`, drawn after it, so it is outside the capture and can sample it.

```kotlin
// app/RootScreen.kt
@Composable
fun RootScreen() {
    val backdrop = rememberNtBackdrop()
    CompositionLocalProvider(LocalNtBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
            MainTabScaffold(backdrop)
        }
    }
}

// app/MainTabScaffold.kt
Box(Modifier.fillMaxSize()) {
    MainNavHost(Modifier.fillMaxSize().ntBackdropSource(backdrop))   // captured
    NtTabBar(Modifier.align(Alignment.BottomCenter))                 // samples it
}
```

```kotlin
@Stable
class NtBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coords: LayoutCoordinates? by mutableStateOf(null)
}

@Composable
fun rememberNtBackdrop(): NtBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { NtBackdrop(layer) }
}

/** Records the subtree into the layer ONCE and draws the layer — not drawContent() twice. */
fun Modifier.ntBackdropSource(backdrop: NtBackdrop): Modifier =
    this.onGloballyPositioned { backdrop.coords = it }
        .drawWithContent {
            backdrop.layer.record(size.toIntSize()) {
                drawRect(NT.Colors.ground)          // no transparent pixels, ever
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }
```

Three rules that follow, and are the whole reason this is centralised:

- **Paint `ground` into the capture.** A transparent pixel in the source becomes a hole in the glass.
- **Never `ntBackdropSource` a node that is inside another `ntBackdropSource`,** and never put `liquidGlass` on a node inside the capture. That is a layer drawing itself — in Kyant's equivalent it is a documented `Fatal signal 11 (SIGSEGV) … (RenderThread)`, and ours would be no better.
- **Glass inside glass samples the parent glass, not the page.** For a glass control inside a glass sheet, the sheet must export its composed surface as a second `NtBackdrop`. Today no NT component needs this — the toggle lives on a flat sheet, the pill is additive, and menus contain no glass — so `liquidGlass` deliberately does **not** expose an `exportedBackdrop` parameter. Add it only when a real component needs it.

Coordinate alignment inside `liquidGlass`:

```kotlin
val origin = sourceCoords.localPositionOf(glassCoords, Offset.Zero)  // glass origin in source space
effectLayer.record(IntSize(w + 2 * pad, h + 2 * pad)) {
    translate(pad - origin.x, pad - origin.y) { drawLayer(backdrop.layer) }
}
effectLayer.topLeft = IntOffset(-pad, -pad)
drawLayer(effectLayer)                       // the shader emits its own antialiased shape alpha
```

### 3.4 The shader — `GlassShader.kt`

Complete and compilable on API 33+. It consumes an **already-blurred** input (a `BlurEffect` chained beneath it) and does refraction, the transfer function, the rim and the shape's antialiased alpha in one pass. Attribution: `sdRoundRect` is Inigo Quilez's standard rounded-box SDF; the `circleMap` rim profile follows the approach in [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0) — keep a NOTICE entry if you copy it verbatim.

```glsl
uniform shader content;          // the blurred backdrop, bound by createRuntimeShaderEffect

uniform float2 size;             // element size in px (without blur padding)
uniform float2 pad;              // (padPx, padPx) — element origin inside the effect layer
uniform float4 radii;            // TL, TR, BR, BL in px
uniform float  refractHeight;    // px; 0 disables the lens band
uniform float  refractAmount;    // px of lateral displacement at the extreme edge
uniform float  rimWidth;         // px
uniform float  rimAlpha;         // alpha at the outer edge, ramping to 0 inward
uniform float2 lightDir;         // unit vector; only used when rimAniso > 0
uniform float  rimAniso;         // 0 = isotropic (this is what iOS 26.1 measures as)
uniform float  contrast;         // 0.294
uniform float  liftLo;
uniform float  liftHi;
uniform float  liftK0;
uniform float  liftK1;
uniform float  adaptive;         // >0.5 = sample a 3x3 aggregate for the lift
uniform float  overlay;          // additive lift on top of everything (selection pill)
uniform float  noise;            // grain amplitude
layout(color) uniform half4 tint;

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float radiusFor(float2 p, float4 r) {
    float top = p.x < 0.0 ? r.x : r.y;
    float bot = p.x < 0.0 ? r.w : r.z;
    return p.y < 0.0 ? top : bot;
}

// <0 inside, 0 on the boundary, >0 outside
float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// outward unit normal of the SDF
float2 sdGrad(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    float2 g;
    if (q.x > 0.0 || q.y > 0.0) {
        g = normalize(max(q, float2(1e-4)));
    } else {
        g = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
    }
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    return s * g;
}

// convex profile: flat in the middle, accelerating hard at the very rim
float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p        = coord - pad - halfSize;
    float  r        = radiusFor(p, radii);
    float  sd       = sdRoundRect(p, halfSize, r);
    float  depth    = -min(sd, 0.0);              // 0 at the edge, grows inward

    // ---- rim refraction ------------------------------------------------
    float2 sc = coord;
    if (refractHeight > 0.0 && depth < refractHeight) {
        float t    = 1.0 - depth / refractHeight;             // 1 at the edge
        float bend = circleMap(t) * refractAmount;
        sc = coord + bend * sdGrad(p, halfSize, max(r, 1.0));
    }

    float3 src = float3(content.eval(sc).rgb);

    // ---- adaptive dynamic-range compression -----------------------------
    float agg = luma(src);
    if (adaptive > 0.5) {
        float a = 0.0;
        // Nested constant loops, NOT `i % 3` / `i / 3`: AGSL/SkSL rejects the integer
        // modulo operator. `RuntimeShader(...)` throws IllegalArgumentException
        // "error: 70: operator '%' is not allowed", then "unknown identifier 'uv'",
        // and the node silently degrades to tier Blur. Verified on a Galaxy S22, API 36.
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                float2 uv = pad + size * float2((float(i) + 0.5) / 3.0,
                                                (float(j) + 0.5) / 3.0);
                a += luma(float3(content.eval(uv).rgb));
            }
        }
        agg = a / 9.0;
    }
    float lift = mix(liftLo, liftHi, smoothstep(liftK0, liftK1, agg));

    float3 rgb = src * contrast + float3(tint.rgb) * lift + overlay;

    // ---- specular rim ---------------------------------------------------
    if (rimWidth > 0.0 && depth < rimWidth) {
        float k   = 1.0 - depth / rimWidth;                    // 1 at the edge -> 0 inward
        float dir = mix(1.0, max(0.0, dot(-sdGrad(p, halfSize, max(r, 1.0)), lightDir)), rimAniso);
        float bright = rimAlpha * k * dir * (1.0 - smoothstep(0.45, 0.85, agg));
        float dark   = rimAlpha * k * dir * smoothstep(0.55, 0.95, agg) * 0.5;
        rgb = mix(rgb, float3(1.0), bright);
        rgb = mix(rgb, float3(0.0), dark);
    }

    // ---- grain -----------------------------------------------------------
    if (noise > 0.0) {
        float n = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
        rgb += n * noise;
    }

    // ---- shape alpha (1 px antialiased); premultiplied output -------------
    float cov = clamp(0.5 - sd, 0.0, 1.0);
    rgb = clamp(rgb, 0.0, 1.0) * cov;
    return half4(half3(rgb), half(cov));
}
```

Chaining, and the one number people get wrong:

```kotlin
// AOSP frameworks/base/libs/hwui/utils/Blur.cpp:
//   static const float BLUR_SIGMA_SCALE = 0.57735f;              // 1/sqrt(3)
//   convertRadiusToSigma(radius) = radius > 0 ? 0.57735f * radius + 0.5f : 0.0f
// RenderEffect.cpp::createBlurEffect passes radiusX/radiusY through that before Skia.
// So to get sigma S px you must ASK FOR radius = (S - 0.5f) / 0.57735f.
private fun blurRadiusForSigma(sigmaPx: Float): Float =
    if (sigmaPx > 0.5f) (sigmaPx - 0.5f) / 0.57735f else 0f
// sigma 2.8 dp @ density 3.0 = 8.4 px  ->  radius 13.68 px = 4.56 dp

@RequiresApi(33)
private fun buildEffect(shader: RuntimeShader, sigmaPx: Float): ComposeRenderEffect {
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val radius = blurRadiusForSigma(sigmaPx)
    val chained = if (radius > 0f) {
        android.graphics.RenderEffect.createChainEffect(          // outer runs SECOND
            lens,
            android.graphics.RenderEffect.createBlurEffect(
                radius, radius, android.graphics.Shader.TileMode.CLAMP,
            ),
        )
    } else lens
    return chained.asComposeRenderEffect()
}
```

Padding: `pad = ceil(2.5f * sigmaPx)` ≈ 21 px at σ = 8.4 px. Colour space: AGSL runs in the destination space, which for a normal (non-wide-gamut) window is sRGB with the transfer curve applied — the same space every number in §1.1 was measured in. **Do not** put the window into F16/linear, or every constant here becomes wrong.

The `RuntimeShader` lives in `drawWithCache`'s cache block, keyed on `NT_LIQUID_GLASS_AGSL`; only `setFloatUniform`/`setColorUniform` run per frame. Uniform mutation never recompiles.

### 3.5 Tier `Blur` (API 31–32) without AGSL

```kotlin
// out = contrast * bd + lift   as a ColorMatrix, applied to the blurred layer.
// scale = contrast, translate = lift * 255 (ColorMatrix offsets are in 0..255)
val m = ColorMatrix(floatArrayOf(
    contrast, 0f, 0f, 0f, lift * 255f,
    0f, contrast, 0f, 0f, lift * 255f,
    0f, 0f, contrast, 0f, lift * 255f,
    0f, 0f, 0f, 1f, 0f,
))
effectLayer.renderEffect = android.graphics.RenderEffect.createChainEffect(
    android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m.values)),
    android.graphics.RenderEffect.createBlurEffect(radius, radius, CLAMP),
).asComposeRenderEffect()
```

`lift` is fixed at `liftLo` on this tier (no aggregate without a shader). The rim becomes a 1.22 dp `drawRect` inner stroke with a `Brush.verticalGradient(White@0.165 → Transparent)` clipped to the shape. Clip with `clipPath` here, since there is no shader to emit the alpha.

### 3.6 Retrofit contract, component by component

All dimensions in **dp** (1 pt → 1 dp), on the S22's 360 dp width unless stated.

#### `NtTabBar` — `app/NtTabBar.kt`

```
container   height = 49 + navInset       (WindowInsets.navigationBars.bottom, read at runtime)
platter     width  = min(intrinsic, screenWidth - 42)      -> 318 on the S22
            height = 62, shape = Capsule (RoundedCornerShape(50) is fine; r = 31)
            pinned to the TOP of the container -> gap below = navInset - 13
items       pitch = (platterWidth - 16.25) / 5 = 60.35 ; width = pitch + 8.25 = 68.60 ; height 54
            padding 4 all round; items OVERLAP by 8.25 — do not use SpaceEvenly or weight(1f)
icon        28 dp box, top at item-y 6
label       10 sp, 12 dp line box, top at item-y 35
pill        = the item box, r 27, animated between item centres
```

- **Glass:** `Modifier.liquidGlass(Capsule, GlassStyle.Chrome)` on the platter. `luminanceAdaptive = true`.
- **Pill:** *not* a glass node. Draw a capsule over the platter's output with `BlendMode.Plus` and colour `Color(0.125f, 0.125f, 0.125f, 1f)` — the measured lift is additive `+0.129 − 0.032·bar` and independent of the backdrop, and the pill has **no rim** (measured: a hard 28 → 61 step with a 2 px antialias ramp). Animate its x/width with `NT.Anim.spring070`; it **slides and morphs**, it never cross-fades.
- **Colours** [measured/app]: selected icon **#FFFFFF**, selected label **#FFFFFF**, unselected icon **#F4F4F6** (≈`ink`, *not* `ink2`), unselected label **#A0A0A8** (= `ink2` over the bar fill). The icon/label asymmetry is the single most likely thing to get wrong from reading `RootView.swift`. It is a measurement, not a theory — the mechanism (which appearance keys iOS 26 still honours) is unresolved and irrelevant.
- **No shadow. No hairline. No `#161618`.**
- **No minimise-on-scroll.**
- **Content inset:** screens pad their scroll content by `49 + navInset` at the bottom (iOS: 83) and scroll *under* the capsule. `enableEdgeToEdge()` is already required by the architecture doc.
- ⚠️ **Gesture-exclusion.** With `navInset − 13`, the platter's lower 13 dp sits inside Android's bottom gesture strip (24 dp with gesture nav on the S22, 48 dp with 3-button). iOS gets this free because the home indicator is a system overlay. Verify tab taps in that band on both nav modes; if 3-button nav pushes the bar too high, clamp the gap to `[8.dp, 24.dp]` and record the deviation.
- The `navInset − 13` rule is **derived**, not directly measured: the bar frame is `49 + bottomSafeArea` and the 62 dp platter is pinned to its top. Both simulators available report a 34 pt bottom inset, so a constant 21 pt gap fits the data equally well. Ship it as `NtTabBar.bottomGap` (one token, one place) and re-measure on a device with a different inset before treating either as settled.

#### `NtToggle` — `designsystem/NtToggle.kt`

Material 3's `Switch` cannot be restyled to this: its thumb is a `Box` with a fixed elevation shadow and there is no backdrop hook. Build it.

```
track   63 × 28, r 14
        OFF: #EBEBF5 @ 30 %   ON: NT.Colors.ink (#F2F2F4)
        the ON fill SWEEPS IN FROM THE LEFT — a 630 dp strip translating x -567 -> 0,
        clipped to the track. Not a colour cross-fade.
knob    37 × 24, r 12, inset 2, travel x 2 -> 24
        Modifier.liquidGlass(RoundedCornerShape(12.dp), GlassStyle.Knob)
        over a per-toggle NtBackdrop of the TRACK (so it refracts the track colour), not the page
```

- Gesture: `AnchoredDraggableState` with two anchors (`androidx.compose.foundation.gestures`, stable in Foundation 1.12, no opt-in), `semantics { role = Role.Switch }`, tap toggles and drag scrubs.
- Stretch during the drag: scale the **sampled backdrop**, not the knob — `scaleY` from 0 lengthwise is what produces the liquid squash. Blur *decreases* and the lens *appears* as the press progresses. **[estimated]** — spring ≈ `dampingRatio 0.8, stiffness 380`, stretch ≈ +4 dp of width at full press. There is no captured mid-drag frame; capture one and correct these before shipping (§5).
- Three call sites: `SettingsComponents.swift:105`, `OnboardingComponents.swift:149`, and the `OBDayToggle` wrapper. All tint `ink`.

#### `NtSheet` — `designsystem/Sheets.kt`

**Flat, not glass** (§1.5), but the geometry changes:

- Partial height (`CantMakeItSheet` 660, `PortionSheet` 376, `LogWeightSheet` 340, `OBDayTimeSheet`/`OBTargetEditorSheet` medium, `AIScanResultView` medium): an **inset floating card** — 8 dp left/right/**bottom**, top corners ≈ 37 dp continuous, bottom corners ≈ 54 dp continuous. Port the three pixel heights as fixed dp, not `SheetValue.PartiallyExpanded`.
- Full height: edge-to-edge from the top inset, opaque, top corners ≈ 38 dp continuous.
- Scrim **black @ 0.48** — this replaces the `0.40` currently in the `NtSheet` contract [measured/app: `ground` 10 → 5 behind the Settings sheet].
- Grabber 36 × 5 dp, r 2.5, 5 dp from the top — **only** on `ExercisePicker` and `WorkoutDetail`.
- No drop shadow (`_UIRoundedRectShadowView` is `alpha = 0`).
- `AIScanResultView` is the one sheet that may use `GlassStyle.Panel`. It is also the one place a bright photo can sit under glass; if it looks wrong, make it flat and note the deviation — one sheet is not worth a second capture path.

#### `NtAlert` — `designsystem/Alerts.kt`

`Modifier.liquidGlass(RoundedCornerShape(34.dp), GlassStyle.Panel)`. 320 dp wide, centred. Text h-padding 30; title 17 sp SemiBold **left-aligned** at y 22; message 13 sp at y 50. Action area padding 16; buttons **48 dp capsules, r 24, 8 dp gap**, fill = panel + 24/255 (`White @ ~0.09`); destructive label `NT.Colors.bad`, cancel `ink`. **No separators.** Scrim black @ 0.48. This replaces the 270 dp / 14 dp / hairline-split spec in `docs/android-architecture.md`.

#### `NtActionSheet` — `designsystem/Alerts.kt`

`Modifier.liquidGlass(RoundedCornerShape(28.dp), GlassStyle.Popover)` — measurably more transparent than a menu (66 over an 89 backdrop). **240 dp wide**, padding 16, rows **208 × 48 dp** capsules with 8 dp gaps. **Anchored to the source control** — position from the anchor's `LayoutCoordinates` and morph out of it, do not slide up from the bottom. **No scrim**, and the rest of the UI stays interactive. Three call sites: `SettingsView.swift:52`, `SettingsPartnerEditor.swift:31`, `ActiveWorkoutView.swift:85`.

#### `NtMenu` — `designsystem/NtMenu.kt`

Material 3's `DropdownMenu` renders in a `Popup`, i.e. a **separate window with its own `GraphicsContext`**, so it cannot sample our `GraphicsLayer`. Render the menu **inside the root `Box`** instead, positioned from the anchor's `LayoutCoordinates`, so it shares the one backdrop.

```
shape   RoundedCornerShape(33.dp) ; width = content ; item pitch 42 dp ; no separators
glass   GlassStyle.Menu   (contrast 0.20, lift 0.145 — the menu DARKENS its backdrop)
scrim   none. Dismiss on an outside tap via a transparent pointerInput layer.
enter   scale from the anchor: transformOrigin = anchor within the panel,
        spring(dampingRatio = 0.82f, stiffness = 380f); alpha tween(180, CubicBezier(0.32, 0.72, 0, 1))
```

`Window.setBackgroundBlurRadius` is **not** the tool here: AOSP's own javadoc says it "Blurs the screen behind the window **within the bounds of the window**… Note the difference with `WindowManager.LayoutParams#setBlurBehindRadius`, which blurs the whole screen behind the window", and it additionally requires a window that is both `android:windowIsTranslucent` **and** `android:windowIsFloating`, plus a runtime `WindowManager.isCrossWindowBlurEnabled()` check (it flips under battery saver — register `addCrossWindowBlurEnabledListener`). Four things to get right for a worse result than the in-composition popover. Skip it.

Four call sites (set-type menu, log-to-meal menu). Icons lead the label.

#### `NtWheelPicker` — `designsystem/Wheel.kt`

The band is **not glass**: a flat capsule, **302 × 34 dp, r 17**, inset 9 dp from each side of the 320 dp picker, filled `Color(0xFFEBEBF5).copy(alpha = 0.085f)`. Rows on a cylinder — pitch 31.2 → 26.9 → 19.2 dp outward, glyph scale 1.0 → 0.84 → 0.64 → 0.39, alpha 1.0 → 0.48 → 0.44 → 0.26. Row height 32 dp, 7 visible. Two call sites.

#### `NtGlassButton` — `designsystem/glass/` consumers in nav bars

`Modifier.liquidGlass(CircleShape, GlassStyle.Chrome)` at **44 × 44 dp** for a lone icon; a shared 44 dp-tall capsule (r 22) for a group, width = sum of items, 16 dp leading margin. Bar item row 54 dp. Over `ground` it renders **#1C1C1E** — check that first, it is the cheapest correctness signal in the whole module. Scope is two nav bars, one of which is forced opaque on iOS; do not build progressive scroll-edge blur for it.

---

## 4. Verification

Pixel-diffing iOS against Android directly is impossible — 402 dp vs 360 dp means every layout differs. So verification runs on **derived quantities measured the same way on both platforms**, plus one trick that makes them directly comparable.

**The trick: `GlassProbe.kt`.** A debug-only Android screen that renders the *same engineered backdrops the iOS probe used* — 10 dp grid with 1 dp lines, a hard black/white edge at the horizontal centre, flat #000/#0A0A0B/#808080/#FFFFFF — under each chrome component, at a forced 402 × 874 dp logical size (`LocalDensity` override). Screenshot it with `adb exec-out screencap -p`. The iOS counterparts already exist in the probe harness (`tabs.png`, `edgeTabs.png`, `grayTabs.png`, `whiteTabs.png`, `hstripeTabs.png`). Now the two are **numerically comparable pixel for pixel**.

**`scripts/glass_probe.py`** — one script, run against an iOS PNG and an Android PNG, emitting the same table. Measure with 50 % threshold crossings at 1/3-px resolution, never by eyeballing.

| # | Quantity | Method | Acceptance |
|---|---|---|---|
| 1 | **Transfer function** | glass body value over flat backdrops 0 / 10 / 128 / 255 | each within **±2/255** of the iOS value (0→51*, 10→28, 128→89, 255→253*). *at the same aggregate |
| 2 | **Edge spread** | 10–90 % width across the hard black/white edge | **7.0 ± 1.0 pt** |
| 3 | **Interior displacement** | 50 % point of that ramp vs the true edge | **≤ 0.5 pt** — no interior refraction |
| 4 | **Rim profile** | inward from the boundary over `#0A0A0B`, all four edges | **59 / 51 / 42 / body**, each ±4; max asymmetry between the four edges **≤ 3/255** |
| 5 | **Tab-bar geometry** | platter x/w/h/r; item pitch/width; pill box | **±0.5 dp**; radius = h/2 verified by chord fit at ≥5 depths |
| 6 | **Pill lift** | pill value − bar value over 4 backdrops | **+31 ± 3** in every case; **no rim** on the pill (hard step, ≤2 px ramp) |
| 7 | **No shadow** | ground pixel immediately outside the rim | equals `ground` **exactly** |
| 8 | **Toggle** | track/knob boxes, insets, ON/OFF colours | 63 × 28 / 37 × 24 / inset 2 / travel 22, **±0.5 dp**; ON track `#F2F2F4`, knob `#FFFFFF`, OFF track `#EBEBF5 @ 0.30` |
| 9 | **Wheel band** | band w/h/r/fill, row pitch and alpha ladder | 302 × 34, r 17, fill within ±3/255; pitch ladder within ±1 dp |
| 10 | **Sheet** | partial-detent insets, corner profile, scrim factor | 8 dp l/r/b ±0.5; scrim `ground` 10 → **5** ±1 |
| 11 | **Alert / action sheet** | panel width, radius, row boxes, gaps | 320/34 and 240/208 × 48/8, ±1 dp |
| 12 | **Menu** | panel radius, item pitch, body over a 128 backdrop | pitch 42 ±2; body **48 ±4**; **no scrim** (backdrop outside unchanged) |
| 13 | **Nav button** | diameter and fill over `ground` | 44 dp ±0.5; **#1C1C1E** ±2/255 |

**Tier tests.** Force `GlassTier.Blur` and `GlassTier.Tint` via a debug override and re-run rows 1, 5, 7, 13. Tier `Blur` must still pass row 1 (the `ColorMatrix` reproduces the transfer exactly); tier `Tint` must render `#1C1C1E` over `ground` and must never be invisible. Keep a Paparazzi/Roborazzi screenshot test for tier `Tint` so the API 26 path cannot silently disappear — nobody will notice on the S22.

**Performance.** Macrobenchmark on the busiest scrolling screen (Fuel home) with the tab bar visible, 120 Hz, P90 **CPU** frame duration ≤ **6 ms**, plus a Perfetto trace confirming the `RenderThread` cost is bounded. Repeat with a partial-height sheet open (two glass surfaces). If either exceeds budget, apply §2.3's ordered fallback and record which step was needed.

**Side-by-side gallery.** For each of the 8 components, one iOS capture and one Android capture at the same logical size, stacked, committed to `design/ios26-reference/android-parity/`. This is the acceptance artefact a human signs off; the numeric table is what stops it regressing.

---

## 5. Risks and open questions

1. **The mid-drag toggle is unmeasured.** `10a-toggle-middrag.png` and `10b` are byte-identical and show no drag; `08` and `09` are byte-identical and both show the ON state. So the knob stretch, the glass-during-interaction behaviour, and the OFF-state rendering in the real app are all **[estimated]**. *Action: re-capture — OFF, mid-drag at ~50 %, and a slow-motion screen recording — before `NtToggle` is signed off.*
2. **The tab bar's bottom gap is `navInset − 13` or a constant 21 dp; the data cannot separate them.** Both available simulators report a 34 pt bottom inset. On the S22 that is an 11 dp vs 21 dp difference — visible. *Action: run the probe on any device or simulator with a different bottom inset, or accept `NtTabBar.bottomGap` as a token and settle it in the side-by-side review.*
3. ~~**Does iOS 26.1 drop the `.cancel` row from an anchored `confirmationDialog`?**~~ **SETTLED — yes, it drops it**, and the card is **centred, not anchored**. `17-confirmationdialog-finish.png` captures `ActiveWorkoutView.swift:85` (which declares a `role: .cancel` button) as a 240 × ≈173 pt card at screen centre with two rows. See §1.7.
4. **Sheet corner radii are profile fits, not layer values.** Top ≈ 36–38, bottom ≈ 54 (assumed concentric with the 62 pt display radius). Nobody has captured a real **partial-height** sheet in this app — `07-sheet-settings.png` is `.large`. *Action: capture `PortionSheet` or `OBDayTimeSheet`.*
5. **The blur kernel is not a Gaussian** (§1.1): matching the 7 pt edge width leaves our fine texture slightly softer than iOS's. Accepted deliberately. If a side-by-side shows it, the fix is a two-tap kernel (a small Gaussian plus a wider low-amplitude tap), not a bigger σ.
6. **The light/dark flip is not implemented.** The shader supports the tint adaptation; the flip to a light surface with dark glyphs is not wired, because it requires a CPU-side appearance decision and it cannot fire in this app: dark-only, `ground` #0A0A0B, and the only bright full-bleed content (the AI-scan photo, the camera preview) is inside an opaque sheet or on a root destination where the tab bar is not shown. *If a future screen puts a bright photo under chrome, this becomes a real bug.* The hook is `GlassStyle.appearance`.
7. **`GlassStyle.Panel` / `.Menu` / `.Knob` constants are fits to two or three points each**, unlike `.Chrome` which is measured to ±1/255 at four. Expect to tune them during the side-by-side pass.
8. **Menu numbers are pixel-derived only** — no `tree_menu.txt` exists. Treat the 42 pt pitch and 33 pt radius as ±2.
9. **The probe harness lives in a scratch directory** that will be cleaned. Copy `App.swift`, `Probe.swift`, the `tree_*.txt` dumps and the engineered-backdrop PNGs into `design/ios26-reference/probe/` and commit them, or none of §4 is reproducible.
10. **Colour space.** Every constant assumes an sRGB (non-wide-gamut, non-F16) window on both platforms. If anyone enables `ColorMode.HDR`/wide gamut on the Android window, or the iOS captures are re-taken on a P3 device with a different render intent, the transfer constants must be re-derived.

---

## Sources

Apple — [Materials](https://developer.apple.com/design/human-interface-guidelines/materials) · [Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) · [Sheets](https://developer.apple.com/design/human-interface-guidelines/sheets) · [Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts) · [Action sheets](https://developer.apple.com/design/human-interface-guidelines/action-sheets) · [Menus](https://developer.apple.com/design/human-interface-guidelines/menus) · [Toolbars](https://developer.apple.com/design/human-interface-guidelines/toolbars) · [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass) · [Liquid Glass overview](https://developer.apple.com/documentation/technologyoverviews/liquid-glass) · [`Glass`](https://developer.apple.com/documentation/swiftui/glass) · [`glassEffect(_:in:)`](https://developer.apple.com/documentation/swiftui/view/glasseffect(_:in:)) · [`UIGlassEffect`](https://developer.apple.com/documentation/uikit/uiglasseffect) · [`TabBarMinimizeBehavior.automatic`](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior/automatic) · [`.onScrollDown`](https://developer.apple.com/documentation/swiftui/tabbarminimizebehavior/onscrolldown) · [`ScrollEdgeEffectStyle`](https://developer.apple.com/documentation/swiftui/scrolledgeeffectstyle) (the default is `automatic`, resolved per platform; `soft` is the blurred treatment it resolves to on iOS, `hard` the opaque one) · [`presentationBackground(_:)`](https://developer.apple.com/documentation/swiftui/view/presentationbackground(_:)) · [`UIDesignRequiresCompatibility`](https://developer.apple.com/documentation/bundleresources/information-property-list/uidesignrequirescompatibility) (not set in this app — verified across `project.yml`, `Info.plist` and the installed bundle) · [Design Resources](https://developer.apple.com/design/resources/)

WWDC25 — [219 Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/) · [284 UIKit](https://developer.apple.com/videos/play/wwdc2025/284/) · [323 SwiftUI](https://developer.apple.com/videos/play/wwdc2025/323/) · [356 New design system](https://developer.apple.com/videos/play/wwdc2025/356/)

Android — [`RenderEffect`](https://developer.android.com/reference/android/graphics/RenderEffect) (all `create*Effect` factories API 31; `createRuntimeShaderEffect` API 33) · [`RuntimeShader`](https://developer.android.com/reference/android/graphics/RuntimeShader) (API 33) · [AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl) · [`Window.setBackgroundBlurRadius`](https://developer.android.com/reference/android/view/Window#setBackgroundBlurRadius(int)) · [`WindowManager.LayoutParams.setBlurBehindRadius`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#setBlurBehindRadius(int)) · AOSP [`libs/hwui/utils/Blur.cpp`](https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/utils/Blur.cpp) (`BLUR_SIGMA_SCALE = 0.57735f`, `sigma = 0.57735·radius + 0.5`) and [`libs/hwui/jni/RenderEffect.cpp`](https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/jni/RenderEffect.cpp) · [compose-material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)

Libraries considered — [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0, `io.github.kyant0:backdrop:2.0.1` + `io.github.kyant0:shapes:1.2.1`) · [backdrop docs](https://kyant.gitbook.io/backdrop) (documents the **1.0.x** API — read the `kmp` branch sources for 2.x) · [chrisbanes/haze](https://github.com/chrisbanes/haze) (Apache-2.0, `dev.chrisbanes.haze:haze:2.0.0-beta02` / `1.7.3`) and its [blur platform matrix](https://github.com/chrisbanes/haze/blob/main/docs/blur/platforms.md) · [FletchMcKee/liquid](https://github.com/FletchMcKee/liquid) · [QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android)

Third-party corroboration — [Orange OUDS iOS: "it is not possible to add a color or change the material for the tab bar"](https://github.com/Orange-OpenSource/ouds-ios/discussions/1076) · [Apple DevForums 796052](https://developer.apple.com/forums/thread/796052) · [Donny Wals on iOS 26 tab bars](https://www.donnywals.com/exploring-tab-bars-on-ios-26-with-liquid-glass/) · [Donny Wals on opting out](https://www.donnywals.com/opting-your-app-out-of-the-liquid-glass-redesign-with-xcode-26/)

Repo evidence — `NoTomorrow/App/RootView.swift:28-40` · `NoTomorrow/DesignSystem/Theme.swift:9-26,70` · `NoTomorrow/Features/**` (19 `.sheet(`, 6 `.alert(`, 3 `confirmationDialog`, 4 `Menu {`, 7 `ShareLink`, 2 `DatePicker(`, 3 `Toggle(`, 2 `NavigationStack`) · `android/gradle/libs.versions.toml` (AGP 9.4.0, Kotlin 2.4.10, minSdk 26, compileSdk 37, Compose BOM 2026.08.00, graphics-shapes 1.1.0)

## 6. Decisions taken after review (2026-09-05, orchestrator)

- **Missing captures — done.** `design/ios26-reference/13c-toggle-t1.png` (Toggle OFF), `19-portionsheet-partial.png` (fixed-height partial sheet: floating inset card, grabber, opaque surface), `17-confirmationdialog-finish.png` (centred glass card, no Cancel row — the three declared `.cancel` buttons are not rendered as rows; tap-outside dismisses), `16-menu-setkind.png` (Menu). A mid-drag knob stretch could not be captured (the switch flips on the first movement under simulated touch); keep the stretch as estimated and low-amplitude.
- **Tab bar bottom gap: ship it as a token derived like iOS does.** `gap = (49 + navigationBarsInset) − 62`, i.e. the platter is pinned to the top of a `49 + inset` bar region; expose `NT.Size.tabPlatterGapOverride` for the side-by-side review to settle.
- **Glass library fallback: allowed.** Own AGSL first (§2.1). If the 120 Hz budget on the S22 is missed after the §2.3 plan, `io.github.kyant0:backdrop` may be adopted behind the same `Modifier.liquidGlass` API; the architecture doc's "no shader library" bullet was already superseded by the Liquid Glass bullet.
- **Probe harness kept.** Copied to `design/ios26-reference/probe/` (sources, `LG.app`, backdrops, view-tree dumps, `png.js`), so §4 is reproducible.
- **Menus, alerts and action sheets are hosted in-window, not in a `Popup`/`Dialog` — settled by measurement (round 2).** §3.6 already required this for `NtMenu`; it is now the rule for `NtAlert` and `NtActionSheet` too, implemented as one `NtOverlayHost` per window (`designsystem/NtOverlay.kt`, composed at `app/RootScreen.kt:97` as a sibling of the `NavHost`, **after** the subtree that records the backdrop). **A `Popup` or `Dialog` is a second window with its own `GraphicsContext`**, so glass drawn inside one can never sample this window's `GraphicsLayer` and always degrades to a flat fill — and a `Dialog` additionally swallows every touch, which is the opposite of iOS 26, where an inline action sheet "*also lets people interact with other parts of the interface*" (Apple, *Adopting Liquid Glass*). The host serves only its own window: `currentNtOverlayHost` returns `null` for a caller inside a `ModalBottomSheet` (its own window, stacked *above* the host, so a panel drawn there would be hidden behind it), and those call sites keep the `Popup`/`Dialog` path and its measured flat fill.
- **`GraphicsLayer.record` has two overloads and only one of them works here.** The backdrop must be recorded with `DrawScope`'s member-*extension* `GraphicsLayer.record(size) { }` (`glass/NtBackdrop.kt:143`), **never** the `GraphicsLayer.record(density, layoutDirection, size) { }` member. They look interchangeable and are not: the plain member records with the layer's own `CanvasDrawScope`, so a `drawContent()` inside the block still paints into the screen canvas the `ContentDrawScope` holds and the layer comes back holding only whatever the block drew directly. Every sampler then evaluates `transfer(uniform ground)` and returns a constant — glass that looks like a flat fill with zero interior variance, which is exactly what parity rounds 0 and 1 measured on the menu and the confirmation card. `LayoutNodeDrawScope` overrides the member-extension precisely to swap its canvas for the layer's first. Anything that touches this call site must keep the extension form.
