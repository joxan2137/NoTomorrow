# No Tomorrow — Android port research

Decision record for the Android version of *No Tomorrow*. Verified against the working tree and against primary sources on **2026-09-04**. Companion document: `docs/android-architecture.md` (the code spec). The iOS code spec is `docs/architecture.md`; the visual spec is `design/*.dc.html`.

Goal restated: an Android app that is **identical** to the iOS app — same screens, same design tokens to the pixel, same behaviours, same backend.

---

## 0. Answers in one page

**Do we HAVE to use a WebView?** No. There is nothing in this app Android cannot do natively, and usually does better. WebView is not even a shortcut: none of the 14,886 lines of Swift can run inside one, so Capacitor is a from-scratch HTML/CSS/JS rewrite that *additionally* surrenders scroll physics, predictive back, startup time, memory footprint, ART ahead-of-time compilation and TalkBack quality. It is the most expensive option per unit of fidelity.

**What native option wins?** **Kotlin + Jetpack Compose**, native, no cross-platform layer. It is the only option that (a) reproduces the design tokens exactly, (b) reaches every OS surface this app needs (Live Updates, foreground alarms, Health Connect, ML Kit, Credential Manager) without writing a plugin bridge first, and (c) does not require touching the shipped iOS app.

**What is lost vs iOS?** Exactly four things, three of which we *should* lose:
1. **SF Pro and SF Symbols** — Apple-licence-blocked on every option including WebView, so a fixed cost, not a differentiator. Mitigation in §6.
2. **ActivityKit Live Activity / Dynamic Island** — Android 16 Live Updates is a genuine lock-screen/status-chip equivalent but is API 36+; the universal fallback (ongoing chronometer notification) covers 100 % of devices. No Dynamic Island analogue exists.
3. **iOS rubber-band overscroll and edge-swipe-back** — deliberately not copied; Android has stretch overscroll and predictive back and copying iOS here would feel wrong.
4. **Portrait lock on large screens** — at `targetSdk 36` Android ignores `screenOrientation` on displays ≥ 600 dp wide. Needs a decision (§9.4), it has no iOS counterpart.

Everything else — every screen, token, behaviour, string, and the whole backend contract — ports exactly.

**Groundwork already banked in the repo.** `android/` already contains the Gradle wrapper (9.7.1), `app/src/main/res/values/strings.xml` + `values-pl/strings.xml` (548 `<string>` + 2 `<plurals>` each, generated), `android/strings-map.json`, `app/src/main/assets/exercises.json` + `exercises_pl.json`, `app/src/main/res/font/bigshouldersdisplay_extrabold.ttf`, and the generator `scripts/xcstrings_to_android.py`. There is no `build.gradle.kts` and zero Kotlin yet. The port starts *after* the strings/assets/font bootstrap, not from zero.

---

## 1. Evidence base — what the iOS app actually contains

Measured directly in the working tree on 2026-09-04. These numbers matter because several of them are quoted wrongly in circulation; the corrected figures are used throughout this document.

| Metric | Value |
|---|---|
| Swift source | **14,886 lines**, 110 files (`NoTomorrow/` 109 + `Shared/`) |
| `Features/` | 10,524 lines — Fuel 2,439 · Workout 2,083 · Settings 1,435 · Onboarding 1,198 · Progress 1,180 · Bro 1,167 · Dashboard 775 · Auth 247 |
| `Services/` | 3,126 lines · `DesignSystem/` + `Models/` 788 |
| Localization keys | **550** (en + pl, zero missing PL) |
| Design tokens | `Theme.swift`: 15 colours (13 literal + 2 derived), 13 system fonts + 1 custom, 14 scalars |

| SwiftUI surface | Count | Note |
|---|---|---|
| `@Model` types | **14** | not 15 |
| `@Query` / `FetchDescriptor` | 29 / 37 | |
| `@Observable` | 18 | |
| `Shape.trim(from:` | **1** | `DesignSystem/Components.swift:195` — one shared `ProgressRing`, reused at 4 sizes. The widely-quoted "79" is a grep artefact that swept up `String.trimmingCharacters`. |
| SwiftUI `Canvas` | **0** | there is no `Canvas` anywhere; sparklines are Swift Charts |
| Swift Charts marks | **8** | `LineMark` ×3, `PointMark` ×3, `AreaMark` ×1, `BarMark` ×1 — all in `Features/Progress/ProgressCharts.swift`; every `interpolationMethod` is `.linear` |
| `TimelineView(` | **7** | 5 × `by: 1`, 2 × `by: 60` (an 8th hit is a doc comment) |
| `.sheet(` / with `presentationDetents` | **19 / 8** | |
| `fullScreenCover` | 2 | active workout, camera |
| `.alert(` / `.confirmationDialog(` | **6 / 3** | two alerts embed a `TextField` |
| `.buttonStyle(PressScale())` / `.plain` | **40 / 29** | |
| `.monospacedDigit()` / `.tabular()` | 101 | |
| Distinct SF Symbols | **41** | not 24/25 — see §6.2 for the full table |
| `.easeOut(duration:)` | **27** | 0.2×11, 0.15×9, 0.3×2, 0.18×2, 0.12×1, 0.25×1, 0.6×1 |
| `.easeInOut(duration:)` | 4 | 0.2, 0.25, 0.28, 0.3 |
| `.spring(` | 2 | both `response: 0.35`, damping 0.7 and 0.85 |
| Gradients / shadows / blurs | 2 / 1 / **0** | |
| `.metal` shader files | **0** | see §5.1 |

Framework imports by file count: SwiftUI 73, **SwiftData 49**, Observation 17, UIKit 4, ActivityKit 3, VisionKit 1, Charts 1, PhotosUI 1, HealthKit 1, Security 1.

---

## 2. (a) Do we have to use a WebView?

No. Below is what a Capacitor/Ionic build would cost, against the same rewrite volume as Compose.

**Recoverable with plugins:** barcode ([`@capacitor-mlkit/barcode-scanning`](https://www.npmjs.com/package/@capacitor-mlkit/barcode-scanning) wraps the same ML Kit), Health Connect ([Capawesome Health](https://capawesome.io/docs/sdks/capacitor/health/), [Cap-go/capacitor-health](https://github.com/Cap-go/capacitor-health)), camera/filesystem/haptics/secure-storage/notifications (core plugins), edge-to-edge (StatusBar plugin + CSS `env(safe-area-inset-*)`).

**Recoverable only by writing your own Kotlin plugin** — i.e. you end up doing native work anyway: Android 16 Live Updates (`ProgressStyle` + promoted-ongoing); a Doze-surviving exact-alarm rest timer with the permission dance; Health Connect's rationale Activity and `activity-alias`.

**Not recoverable at all:**

| Loss | Why |
|---|---|
| Scroll physics | Chromium's momentum/deceleration, not Compose fling or Android stretch overscroll. |
| Predictive back | At `targetSdk 36` predictive back is on by default and `onBackPressed` is no longer called ([Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)). A WebView `popstate` is not a system cross-screen transition. |
| Startup | Cold start pays WebView process spawn + Chromium init + JS parse before the first pixel. |
| ART AOT | [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) "improve code execution speed by about 30% from the first launch" — for Java/Kotlin code paths. That optimisation simply does not apply to your JavaScript; only the thin Kotlin shell benefits. (Stated precisely: this is not a "30 % cold-start penalty", it is forfeiting ART profile-guided optimisation over the entire UI layer.) |
| Memory | The browser engine stays resident. |
| Text rendering | Chromium's text stack, different hinting/metrics from Compose, different font-scale handling. `tnum` works but not identically. |
| Accessibility | TalkBack traverses Chromium's a11y bridge instead of Compose `semantics`; focus order and custom-control exposure are materially weaker. |
| Engine control | Android System WebView is user-updatable; you inherit Chromium versions and bugs you cannot pin. |
| Code reuse | Zero. Same full rewrite as Compose. |

The one seductive argument — *"the design canvas is already HTML (`design/*.dc.html`), so a WebView could be pixel-perfect"* — does not survive contact. Those are static mock-ups, not an application, and pixel-fidelity to a canvas is not the same as behaving like an Android app.

**Verdict: rejected.** Same cost, strictly worse product, no capability recovered.

---

## 3. (b) Which native option — decision matrix

### 3.1 The candidates

**(a) Kotlin + Jetpack Compose (native).** Declarative like SwiftUI, 1:1 mental model, full platform access.

**(b) WebView / Capacitor.** §2.

**(c1) Skip Lite (Swift transpiled to Kotlin).** *Impossible for the persistence and UI layers.* Skip's transpilation reference reads verbatim: **`✓ @Observable` · `✓ @ObservationIgnored` · `✕ Other macros`** ([swiftsupport](https://skip.dev/docs/swiftsupport/)). SwiftData's `@Model` is an "other macro", and 49 files import SwiftData. (Precisely: Skip's mode is chosen *per Swift module* and mixed Fuse/Lite apps are normal — so the honest statement is "transpiled mode is unusable for the modules that matter", not "impossible for the app".)

**(c2) Skip Fuse (Swift natively compiled for Android).** Free and open source since Jan 2026, production-stable, built on the official Swift SDK for Android ([FAQ](https://skip.dev/docs/faq/), [pricing](https://skip.dev/pricing/)). Note the licences are **not** "permissive" as Skip's marketing copy says: `skiptools/skipstone` (the build engine) is **AGPL-3.0** and `skip-ui` is **MPL-2.0** — build-time-only tooling, but do not carry "permissive" into a legal review.

Documented costs: **~60 MB** added to the Android bundle for the Swift runtime + Foundation + ICU; slower builds; no Swift breakpoints from Android Studio; **not ejectable** — *"stopping Skip leaves you with a working iOS app but no standalone Kotlin codebase for Android"* ([modes](https://skip.dev/docs/modes/)).

The disqualifier is framework coverage. Skip's FAQ: Foundation, Dispatch, Observation and the stdlib come for free; *"Other frameworks native to Apple devices like Combine, CoreGraphics, and CoreAnimation, as well as the many iOS-specific 'Kit' frameworks (StoreKit, PhotoKit, HealthKit, etc.) are not available."* Skip's module index has **no Charts, no SwiftData, no HealthKit, no ActivityKit** and **no 1D/EAN barcode scanner** (it ships a *QR Codes* module, which is not what Fuel's food-barcode lookup needs). Persistence would be `SkipSQL` — raw SQLite through the C API.

SkipUI's live README returns **zero** occurrences of `Chart`, `LineMark`, `BarMark`, `TimelineView`, `PhotosPicker`, `trim`, `monospacedDigit`, `contentTransition`, `symbolEffect`, `Gauge`, `AngularGradient`, `matchedGeometryEffect`, `sensoryFeedback`. It also states verbatim that custom `Animatable`s and `Transition`s are unsupported and that *"For many SwiftUI spring animations, Skip uses Compose's simple `EaseInOutBack` easing function rather than a true spring."*

Mapped onto this app: `TimelineView` is marked non-negotiable in `docs/architecture.md:107` and drives 7 render loops; `Shape.trim` is 1 component but it is *the* progress ring at 4 sizes and animating it is impossible under SkipUI; `.tabular()` is 101 sites and would silently regress; the whole Progress tab is Swift Charts; Fuel needs PhotosPicker + VisionKit + HealthKit. Adopting Skip means **rewriting the shipped iOS app to fit the tool**, then hand-writing `ComposeView` escape hatches in Kotlin for the rings, the charts and the timer. Wrong risk.

**(d) Official Swift SDK for Android alone.** Swift 6.3 (24 Mar 2026) shipped the first official Swift SDK for Android with `swift-java` JNI interop ([release](https://www.swift.org/blog/swift-6.3-released/), [Android Workgroup](https://www.swift.org/android-workgroup/)). Foundation coverage is complete — but **it ships no UI framework**. SwiftUI-on-Android exists only through Skip (SkipFuseUI delegates to SkipUI → Compose). So (d) resolves into (c2), or into "write Compose in Kotlin and call Swift over JNI" — which is (a) plus a 60 MB JNI boundary for a domain layer (`TargetCalculator`, `RecordService`, Epley, `AttendanceService`) that is a few hundred lines of arithmetic. Not worth it.

**(e) Kotlin/Compose Multiplatform.** CMP for iOS is stable and production-ready (current release **1.12.0**). But it shares *Kotlin* UI — it cannot consume SwiftUI. Here it collapses into "Jetpack Compose for Android with multiplatform ceremony", unless we also rewrite iOS in Kotlin, which would throw away a finished native app and forfeit day-one adoption of new Apple design languages. If a shared domain layer is ever wanted, KMP for `commonMain` business logic only (no UI) can be added later without disturbing either UI.

**(f) Flutter / React Native.** Full rewrites with zero Swift reuse, exactly like Compose, so the "cross-platform saves work" argument only pays if iOS is also rewritten. Flutter draws its own widgets (own text stack, own scroll physics — good pixel control, detached from platform behaviour). React Native's New Architecture is default since 0.76 but custom native modules must be migrated to benefit. Decisive: **every OS surface this app needs** — Live Updates, exact alarms, Health Connect, ML Kit, Credential Manager — is a native Kotlin module you write yourself under either framework. Compose removes that layer; Flutter/RN add one.

### 3.2 Matrix

Scores 1–5, higher is better. "Native feel" means *feels right on Android*, which is not the same as *feels like iOS*.

| Criterion | **(a) Compose** | (b) WebView | (c1) Skip Lite | (c2) Skip Fuse | (d) Swift SDK | (e) CMP | (f) Flutter/RN |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Fidelity to the iOS design system | **5** | 4 | — | 3 | — | 4 | 4 |
| Performance (startup, frames, memory) | **5** | 2 | — | 4 | — | 4 | 3–4 |
| Native feel (scroll, back, insets, a11y) | **5** | 1 | — | 4 | — | 4 | 3 |
| Platform features (Live Updates, Health Connect, ML Kit, alarms) | **5** | 3 | — | 2 | — | 5 | 4 |
| Maintenance / iOS-Android parity effort | 3 | 4 | — | **5** | — | 3 | 4 |
| Risk, incl. risk to the shipped iOS app | **5** | 3 | 1 | 2 | 2 | 3 | 3 |
| Cost (build + run) | 3 | 3 | — | 4 | — | 3 | 3 |
| **Verdict** | **Recommended** | Rejected | Unusable for the SwiftData/UI modules | Wrong fit now | Not a UI option | Solves a problem we don't have | Rewrite, no upside |

The only axis where (a) genuinely loses is **maintenance/parity effort**: two codebases kept in step by discipline rather than by a compiler. Four things make that cheap here: `docs/architecture.md` is already a language-neutral contract; `design/*.dc.html` is already the pixel source of truth; the 550 catalog keys are already mechanically generated into `strings.xml`/`values-pl` (so translations cannot diverge); and the backend already enforces the data contract for both clients.

### 3.3 What is lost / gained, precisely

**vs iOS — lost:**
- SF Pro (12 of 13 type styles use `Font.system`) and 41 SF Symbols. Apple-licence-blocked; see §6.
- Live Activity + Dynamic Island → Live Updates on API 36+ only, no Dynamic Island analogue anywhere (nearest is Samsung's Now Bar, OEM-specific).
- Guaranteed haptic character. iPhone's Taptic Engine is uniform; Android actuators range from good LRAs to buzzy ERMs. API mapping is 100 %, felt result is not.
- Notification delivery guarantees. iOS `UNNotificationRequest`s survive reboot and are not subject to Doze or OEM battery managers. On Android, alarms die at reboot (we re-arm from `BOOT_COMPLETED`), exact alarms are user-revocable, and Xiaomi/Oppo/Samsung task-killers are real. §7 covers this.
- Keychain restore semantics. iOS Keychain restores across devices; Keystore-wrapped blobs do not survive backup/restore, so the app must treat an undecryptable token as signed-out.
- Portrait lock on ≥600 dp displays (`targetSdk 36`).
- `style: .continuous` superellipse corners at **49** call sites — Compose's `RoundedCornerShape` is a circular arc. Closeable (§8.6) but it is real work, not free.

**vs iOS — gained:**
- Per-app language changes recreate the Activity immediately, so the "relaunch to apply" hint in Settings can be dropped.
- Health Connect's `NutritionRecord` carries `name` and `mealType`, so meal slots round-trip natively (iOS uses `HKCorrelation(.food)`, which groups but does not name).
- ML Kit barcode scanning is on-device, supports 13 formats, and (via the Google code scanner) can run with no camera permission at all.
- Credential Manager handles Google **and** username/password in one API, and saves the password to the user's password manager — something iOS does not do here.
- R8 full-mode shrinking and Baseline Profiles over the whole UI layer.

**vs WebView — gained:** all of §2's "not recoverable" list.

---

## 4. Recommendation and sequencing

**Build the Android app natively in Kotlin + Jetpack Compose.**

1. WebView is dominated on every axis and costs the same rewrite.
2. Skip's gaps land exactly on this app's load-bearing surfaces, and adopting it means rewriting the *working* iOS app to fit the tool.
3. Compose loses four things, three of which we should lose; the fourth (SF Pro/Symbols) is a fixed cost under every option.
4. We already own the hard part: the code spec, the pixel spec, the translations, the tokens, and a backend that needs one bounded change.

**Sequencing** (each stage is independently shippable to an internal track):

1. Gradle + `libs.versions.toml` + `NT` tokens + `NTTheme` + design-system components + navigation shell (edge-to-edge, 49 dp tab bar). *One agent, blocks everyone.*
2. Room schema (14 entities) + DAOs + converters + seeders (`ExerciseLibrary`, `RoutineSeeder`) + `Fmt` + `TargetCalculator` + `RecordService` + `AttendanceService`. *One agent, blocks features.*
3. Then in parallel: Workout · Dashboard · Fuel · Progress · Bro/Auth · Settings · Onboarding.
4. Rest-timer service + notifications + alarms (own agent, own package, cuts across Workout).
5. Backend client + FCM + backend delta (§7.5).
6. Baseline Profile + R8 + screenshot-diff harness before the first release build.

---

## 5. (c) 1:1 replacement table for every SwiftUI visual technique

### 5.1 There are no shaders in this app — and what AGSL would be for

Verified: `find . -name "*.metal"` → **0**. `grep -rn 'colorEffect\|distortionEffect\|layerEffect\|ShaderLibrary\|Metal'` over all Swift → **0 hits**. `Canvas` → **0**. `.blur` / `Material` / `.ultraThinMaterial` → **0**.

The whole visual surface is: two `LinearGradient`s, one `.shadow`, `Circle`/`Capsule`/`RoundedRectangle`/`Rectangle`, two custom `Layout`s (`FlowLayout`, `BroFlowLayout`), and Swift Charts. Semi-transparency is plain colour alpha, never blur.

**Therefore the AGSL / `RuntimeShader` / `RenderEffect` / `Modifier.blur` / Haze branch of this port is dead code.** Documented here only so the option is understood if the design later changes:

| Technique | API | Min API | Failure mode below floor |
|---|---|---|---|
| AGSL fragment shaders | `RuntimeShader(agsl)` + `setFloatUniform` | **33** | class not found → hard crash unless `@RequiresApi`-gated |
| Shader → Compose | `android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()` → `Modifier.graphicsLayer { renderEffect = … }` | **33** | as above |
| Gaussian blur | `Modifier.blur(radius, BlurredEdgeTreatment…)` | **31** | ⚠️ **silently ignored** — no exception, no log |
| Backdrop / frosted glass | Haze (`dev.chrisbanes.haze:haze` **1.7.3** + `:haze-materials`; the `haze-blur`/`haze-glass` split is **2.0.0-beta02** only) | varies | library-handled |

Five reasons not to reach for them here:
1. **Never as a substitute for a gradient.** `Brush.verticalGradient` *is* a Skia gradient shader — hardware-accelerated, no API floor, no offscreen buffer. Writing AGSL for our two gradients would be strictly worse on every axis.
2. `Modifier.blur` fails silently below API 31 — a QA hazard, not a crash.
3. `RenderEffect` forces an offscreen buffer: Google's own docs state *"Setting a `RenderEffect` or overscroll always renders content into an offscreen buffer regardless of the `CompositingStrategy` set."* That is a full-surface texture allocation and round trip **per frame**.
4. Backdrop blur is an iOS idiom. This app is dark-only with opaque `surface`/`surface2` and zero `Material` usage; adding Haze would make Android *diverge* from iOS.
5. API-33 gating is contagious — every shader path needs a non-shader fallback, doubling the parity review surface.

### 5.2 The replacement table

Every row is against the target stack in §8 (Compose BOM 2026.08.00 → ui/foundation **1.12.0**, material3 **1.4.0**). **Min API for every stock-Compose row is 23** (compose.ui 1.12.0 declares `minSdkVersion=23`); this project's floor is 26 for other reasons (§8.2).

| # | SwiftUI (as used here) | Jetpack Compose | Fidelity |
|---|---|---|---|
| 1 | `LinearGradient(colors:startPoint:.top,endPoint:.bottom)` as `.background` | `Modifier.background(Brush.verticalGradient(colorStops))` | **Exact** |
| 2 | `LinearGradient` as `AreaMark.foregroundStyle` | same `Brush` passed to `DrawScope.drawPath(path, brush)` | **Exact** |
| 3 | *(a true fade **mask** — not used here; the Fuel bar is an opaque scrim)* | `Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)` + `drawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.DstIn) }`. Offscreen is **mandatory** — *"Many of the BlendModes that involve alpha won't work as expected without an offscreen buffer."* | Exact |
| 4 | `.shadow(color:.black.opacity(0.5), radius:12, y:8)` | `Modifier.dropShadow(shape, Shadow(radius = …, color = …, offset = DpOffset(0.dp, 8.dp)))` (compose.ui ≥1.9.0). **Not** `Modifier.shadow`. | **Close — calibrate**, see §5.3 |
| 5 | `.contentTransition(.numericText())` ×7 | per-digit `AnimatedContent` + `slideInVertically`/`fadeIn` ↔ `slideOutVertically`/`fadeOut`, `SizeTransform(clip = false)` | Close |
| 6 | `.contentTransition(.symbolEffect(.replace))` ×1 | `AnimatedContent` with `scaleIn(.7f)+fadeIn togetherWith scaleOut(.7f)+fadeOut` | Close |
| 7 | `TimelineView(.periodic(by: 1))` ×5 | `produceState` + `delay(1000 - now % 1000)` (wall-clock aligned), wrapped in `repeatOnLifecycle(STARTED)` | Close |
| 8 | `TimelineView(.periodic(by: 60))` ×2 | same at 60 s, recompute on `ON_RESUME` | Close |
| 9 | `.monospacedDigit()` / `.tabular()` ×101 | `TextStyle(fontFeatureSettings = "tnum")` — **only for the system-font styles**; see §5.4 for the display face | Exact for system font |
| 10 | `.tracking(pt)` | `letterSpacing = (pt / fontSizePt).em` — **use `em`, not `sp`** | **Exact**, table in §6.3 |
| 11 | `.textCase(.uppercase)` | `text.uppercase(locale)` where `locale` comes from the **app** locale, not `Locale.current` (the in-app language override must reach it) | Exact when wired |
| 12 | `.custom("BigShouldersDisplayThin-ExtraBold", size:)` | `FontFamily(Font(R.font.bigshouldersdisplay_extrabold))` — the repo's own static TTF, already in `res/font/` | **Exact** |
| 13 | `Circle().trim(from:0,to:p).stroke(StrokeStyle(lineWidth:w, lineCap:.round)).rotationEffect(-90°)` | `Canvas { drawArc(startAngle = -90f, sweepAngle = 360f*p, useCenter = false, style = Stroke(w, cap = StrokeCap.Round)) }` — geometry caveat in §5.5 | **Exact** with correct geometry |
| 14 | `Chart { LineMark }.interpolationMethod(.linear)` | `Canvas` + `Path.lineTo` chain, `Stroke(2.dp, Round, Round)` | **Exact** |
| 15 | `AreaMark` + gradient | closed `Path` (line → baseline → back), `drawPath(brush)` | **Exact** |
| 16 | `BarMark` + `.clipShape(RoundedRectangle(3))` | `drawRoundRect(cornerRadius = CornerRadius(3.dp.toPx()))` | Exact |
| 17 | `PointMark` + custom `.symbol` ×3 | `drawCircle` fill + `Stroke` ring | Exact |
| 18 | `PressScale: ButtonStyle` (0.97 / 0.9 / easeOut 0.12) ×40 | custom `IndicationNodeFactory` provided via `LocalIndication` — see §5.6 | **Exact** |
| 19 | `.buttonStyle(.plain)` ×29 | `clickable(indication = null)` | Exact |
| 20 | `.easeOut(duration: d)` | `tween((d*1000).toInt(), easing = EaseOut)` | **Exact** — same Bézier |
| 21 | `.easeInOut(duration: d)` | `tween((d*1000).toInt(), easing = EaseInOut)` | **Exact** |
| 22 | `.spring(response: r, dampingFraction: f)` | `spring(dampingRatio = f, stiffness = (2π/r)²)` | **Exact** |
| 23 | `.transition(.opacity)` | `fadeIn() togetherWith fadeOut()` | Exact |
| 24 | `.transition(.move(edge:.bottom).combined(with:.opacity))` ×3 | `(slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())` | Exact |
| 25 | `.transition(.move(edge:.trailing).combined(with:.opacity))` ×1 | `(slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())` | Exact |
| 26 | `.transition(.scale(0.9, anchor:.leading).combined(with:.opacity))` ×1 | `(scaleIn(0.9f, transformOrigin = TransformOrigin(0f, .5f)) + fadeIn()) togetherWith (scaleOut(…) + fadeOut())` | Exact |
| 27 | onboarding `.asymmetric(insertion: .move(edge:).combined(with:.opacity), removal: .opacity)` | `(slideInHorizontally { ±it } + fadeIn()) togetherWith fadeOut()` | Exact |
| 28 | `.presentationDetents([.height(N)])` | `ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true))` + content `Modifier.heightIn(min = N.dp)`; subtract the handle/bottom-inset height | Close, §6.5 |
| 29 | `.presentationDetents([.medium])` ×3 | default `rememberModalBottomSheetState()` resting at `PartiallyExpanded`, **or** `skipPartiallyExpanded = true` + `fillMaxHeight(0.5f)` for closer parity | Approx |
| 30 | `.presentationDragIndicator(.hidden)` ×8 of 10 | `dragHandle = null` — **this must be the default** (Compose supplies one otherwise) | Exact |
| 31 | `fullScreenCover` ×2 | nav destination with `slideInVertically` enter / `slideOutVertically` exit — not a `Dialog` | Exact |
| 32 | `UIImpactFeedbackGenerator(.light)` | `HapticFeedbackType.ContextClick` (or `SegmentTick`) | Approx, §6.8 |
| 33 | `UINotificationFeedbackGenerator(.success)` | `HapticFeedbackType.Confirm` | Approx |
| 34 | `.ignoresSafeArea()` | simply don't apply `windowInsetsPadding` | Exact |
| 35 | `.safeAreaInset(edge:.bottom) { bar }` ×4 | `Modifier.windowInsetsPadding(WindowInsets.navigationBars)` on the bar; content adds bottom `contentPadding` | Exact |
| 36 | 49 pt opaque tab bar, content scrolls under | custom `Row(Modifier.height(49.dp))` — **never `NavigationBar`**, which is 80 dp with a selection pill | Exact |
| 37 | dark-only literal RGB tokens | plain `object NT`; do not route through M3 defaults | **Exact** |
| 38 | `Color.white.opacity(0.12)` hairline | `Color.White.copy(alpha = 0.12f)`, drawn at `1.dp` for parity (a true 1-px line is *thinner* than iOS's 1 pt) | Exact |
| 39 | `.swipeActions(edge:.trailing, allowsFullSwipe: true)` ×1 | `SwipeToDismissBox` for full-swipe; a custom `AnchoredDraggableState` row for the rest-open-at-button behaviour | §6.7 |
| 40 | custom `Layout` (`FlowLayout`, `BroFlowLayout`) | `FlowRow` (`androidx.compose.foundation.layout`) or a custom `Layout` — same measure/place model | Exact |
| 41 | `style: .continuous` rounded rects ×49 | custom superellipse `Shape` (or `androidx.graphics:graphics-shapes` `RoundedPolygon` with `smoothing`) | §8.6 |

### 5.3 The one shadow

`RestPillView.swift:52` — `.shadow(color: .black.opacity(0.5), radius: 12, y: 8)`.

Use `Modifier.dropShadow(shape, Shadow(...))`, not `Modifier.shadow`. Two blockers with `Modifier.shadow`, both from AOSP: its blur is a function of `elevation` (no radius parameter, so "radius 12, y+8, 50 % black" is not expressible), and its `ambientColor`/`spotColor` carry the kdoc *"only supported on Android 9 (Pie) and above. On older versions, this property always returns `Color.Black`."* `dropShadow` explicitly *"does not introduce a graphicsLayer to render elevation based shadows… rendered without a single light source and will render consistently regardless of the on screen position of the content"* — exactly SwiftUI's model.

**Do not assume `radius = 12.dp` matches.** Compose's Android drop shadow goes through `BlurMaskFilter(radius, NORMAL)`, and Skia converts a `BlurMaskFilter` radius to a Gaussian sigma as `0.57735 * radius + 0.5`, whereas SwiftUI's `radius` behaves as the sigma. Start at **`radius ≈ 20.dp`** (12 / 0.57735) and calibrate against a side-by-side screenshot of the rest pill. Import is `androidx.compose.ui.graphics.shadow.Shadow`, not the `TextStyle` one.

### 5.4 Tabular figures — the trap

`fontFeatureSettings = "tnum"` works for the system-font styles. It is a **no-op on the display face**: the shipped `BigShouldersDisplay-ExtraBold.ttf` has no `tnum` feature in GSUB and its digits are strongly proportional (at UPM 2000: `0`=947, `1`=511, `2`=914, … — "1" is barely half the width of "0"). iOS's `.monospacedDigit()` is equally inert there, so *static* parity holds — but the per-digit `AnimatedContent` counters (`display(40)`–`display(56)` kcal/pair-code) will reflow horizontally on every tick.

Fix: for display-face counters either animate the whole number as one `AnimatedContent`, or measure the widest digit once and give each digit column a fixed `Modifier.width`.

### 5.5 The progress ring — geometry

SwiftUI's `Shape.stroke` is **centred on the path and not clipped to the frame**: `Circle().stroke(lineWidth: 6)` in a frame of D renders an outer diameter of **D + 6**. The naive Compose port (inset the arc rect by `w/2`) inscribes the stroke and produces an outer diameter of D — one stroke width too small, visible on all four ring sizes.

Correct: derive both track and arc from `size.minDimension` (SwiftUI's `Circle()` is always circular and centred, so a non-square canvas must not produce an ellipse):

```kotlin
val w = lineWidth.toPx()
val d = size.minDimension                       // stroke centred on this circle → outer d + w
val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
drawCircle(track, radius = d / 2f, center = size.center, style = Stroke(w))
drawArc(color, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
        topLeft = tl, size = Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
```

`startAngle = -90f` replaces `.rotationEffect(.degrees(-90))`. Animate `p` with `tween(600, easing = EaseOut)`.

### 5.6 Easing, springs, press feedback

**Easing is exact in both directions.** Apple's `UnitCurve` docs give `easeOut` control points (0,0)/(0.58,1), `easeIn` (0.42,0)/(1,1), `easeInOut` (0.42,0)/(0.58,1); AOSP `EasingFunctions.kt` defines `EaseOut = CubicBezierEasing(0f,0f,0.58f,1f)`, `EaseIn = (0.42f,0f,1f,1f)`, `EaseInOut = (0.42f,0f,0.58f,1f)`. Byte-identical.

⚠️ **Never write a bare `tween(n)`.** Its default is `FastOutSlowInEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)` — Material's curve, not iOS's. Ship a lint rule.

**Springs.** AOSP `SpringSimulation.kt`: `naturalFreq = sqrt(stiffness)`, `k = naturalFreq²`, `c = 2·naturalFreq·dampingRatio`, mass fixed at 1 — so `stiffness ≡ ω₀²`. SwiftUI defines `response = 2π√(m/k)` ⇒ `ω₀ = 2π/response`. Therefore **`stiffness = (2π/response)²`** and `dampingRatio = dampingFraction`. For this app's `response 0.35`: **stiffness = 322.3** with dampingRatio 0.70 and 0.85. Compose's named constants (`StiffnessLow` 200, `StiffnessMediumLow` 400, `DampingRatioLowBouncy` 0.75, `DampingRatioNoBouncy` 1.0) do not fit — pass literals.

**Press feedback.** `PressScale` (`Components.swift:76-83`) is scale 0.97 + **opacity 0.9** + `easeOut(0.12)`. Both halves must be applied — dropping the alpha is the commonest port bug. Implement once as an `IndicationNodeFactory` that drives a `graphicsLayer` with `scaleX/scaleY/alpha`, install it app-wide with `LocalIndication provides NTPressScale`, and use `indication = null` at the 29 `.plain` sites. `LocalIndication` is the *supported* theme-wide route; `LocalRippleConfiguration` is documented as *"experimental, and only intended for per-component customization. Global/theme-wide customization is not supported with these APIs"* — so don't put it in the theme.

**Duration map** (27 easeOut + 4 easeInOut, verified counts):

| SwiftUI | Compose | Sites |
|---|---|---|
| `.easeOut(0.12)` | `tween(120, EaseOut)` | 1 — PressScale |
| `.easeOut(0.15)` | `tween(150, EaseOut)` | 9 |
| `.easeOut(0.18)` | `tween(180, EaseOut)` | 2 |
| `.easeOut(0.2)` | `tween(200, EaseOut)` | 11 |
| `.easeOut(0.25)` | `tween(250, EaseOut)` | 1 |
| `.easeOut(0.3)` | `tween(300, EaseOut)` | 2 |
| `.easeOut(0.6)` | `tween(600, EaseOut)` | 1 — ProgressRing |
| `.easeInOut(0.2/0.25/0.28/0.3)` | `tween(200/250/280/300, EaseInOut)` | 1 each |
| `.spring(0.35, 0.7)` / `(0.35, 0.85)` | `spring(0.70f, 322.3f)` / `spring(0.85f, 322.3f)` | 1 each |

### 5.7 Charts: hand-drawn Canvas, no library

All 8 marks live in one file and every interpolation is `.linear`. Vico 3.3.1 is a capable library, but: it is Compose-Multiplatform-first (pulls `org.jetbrains.compose.material3` and `kotlinx-datetime`, whose version resolution against our pins needs babysitting), it declares `minCompileSdk=37`, and every one of our axes, PR-dot styles and the "this week" label is bespoke — a chart library becomes something to override rather than something to use.

**Decision: draw all four charts in Compose `Canvas`.** The four are `SparklineChart` (72×24 and larger), `BodyWeightChart` (raw + 7-day MA + trailing point + optional axes), `E1RMChart` (area + line + filled/hollow PR markers), `WeeklyVolumeChart` (8 rounded bars + baseline + two axis labels). Roughly 30–60 lines each, and they must match iOS exactly.

---

## 6. (d) Platform framework replacement table

| iOS | Android | Coordinate / API | Permission / manifest | Notes |
|---|---|---|---|---|
| SwiftData | **Room** | `androidx.room:room-runtime:2.8.4` + `room-ktx` + `ksp(room-compiler)` | — | §6.1 |
| ActivityKit Live Activity | **Ongoing `NotificationCompat` chronometer** + `AlarmManager`; **Live Update** promotion on API 36 | `androidx.core:core-ktx:1.19.0` | `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `POST_PROMOTED_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` | §7 |
| `UNUserNotificationCenter` | `NotificationCompat` + channels + `AlarmManager` / `WorkManager` | `androidx.work:work-runtime-ktx:2.11.2` | `POST_NOTIFICATIONS` (runtime, API 33+) | §7.3 |
| VisionKit `DataScannerViewController` | **ML Kit Barcode Scanning + CameraX** (custom UI) | `com.google.mlkit:barcode-scanning:17.3.0` + `androidx.camera:*:1.6.2` | `CAMERA` | §6.6 |
| `PhotosPicker` | `ActivityResultContracts.PickVisualMedia` | `androidx.activity:activity-compose:1.13.0` | **none** | never declare `READ_MEDIA_IMAGES` |
| `UIImagePickerController` (camera) | `ActivityResultContracts.TakePicture` + `FileProvider`, or CameraX `ImageCapture` | camera artifacts above | `CAMERA` | `TakePicturePreview` returns a thumbnail — not usable for AI estimation |
| HealthKit | **Health Connect** | `androidx.health.connect:connect-client:1.1.0` | `android.permission.health.*` + rationale Activity + `activity-alias` | §6.4 |
| Keychain (Security.framework) | **Android Keystore + Tink + DataStore** | `androidx.datastore:datastore-preferences:1.2.1`, `com.google.crypto.tink:tink-android:1.23.0` | — | §6.3 |
| APNs | **FCM** | `firebase-bom:34.18.0` → `firebase-messaging` | `POST_NOTIFICATIONS`; `MESSAGING_EVENT` service | §7.5 — backend change required |
| Google sign-in (server-only today) | **Credential Manager** | `androidx.credentials:credentials:1.6.0` + `:credentials-play-services-auth:1.6.0` + `com.google.android.libraries.identity.googleid:googleid:1.2.0` | — | §6.2 |
| Sign in with Apple | Web OAuth in Chrome Custom Tabs | `androidx.browser:browser:1.10.0` | — | **defer**, §6.2 |
| `ShareLink` ×7 | `ShareCompat` / `Intent.ACTION_SEND` + `FileProvider` | `androidx.core:core-ktx` | `<provider>` + `res/xml/file_paths.xml` | CSV export to `cacheDir` |
| `UIImpactFeedbackGenerator` | `LocalHapticFeedback` / `View.performHapticFeedback` | platform | none (`VIBRATE` only for raw `Vibrator`) | §6.8 |
| Swift Charts | Compose `Canvas` | — | — | §5.7 |
| `.xcstrings` (550 keys) | `res/values/strings.xml` + `values-pl` + `<plurals>` | generated by `scripts/xcstrings_to_android.py` | — | §6.9, already done |
| `AppLocale` language override | `AppCompatDelegate.setApplicationLocales` + `android:localeConfig` | `androidx.appcompat:appcompat:1.8.0` | `android:localeConfig` | §6.9 |
| `UIAppFonts` custom TTF | `res/font/*.ttf` + `FontFamily` | — | — | already in the repo |
| App Attest (planned) | Play Integrity | `com.google.android.play:integrity:1.6.0` | — | optional, later |

### 6.1 SwiftData → Room

| SwiftData | Room |
|---|---|
| `@Model final class Workout` | `@Entity data class WorkoutEntity` |
| `@Attribute(.unique) var id: UUID` | `@PrimaryKey val id: String` (UUID as text) |
| `@Relationship(deleteRule: .cascade) var sets: [SetEntry]` | `@ForeignKey(onDelete = CASCADE)` on the **child** + `@Relation` on a read DTO |
| `@Relationship(deleteRule: .nullify)` (Exercise→WorkoutExercise) | `@ForeignKey(onDelete = SET_NULL)` + nullable FK column |
| `@Query(sort:)` in a view | `@Query("… ORDER BY …") fun observe(): Flow<List<…>>` |
| `FetchDescriptor` in a model | `suspend fun` DAO method |
| `modelContext.insert/delete` + autosave | explicit `@Insert(onConflict = REPLACE)` / `@Delete` inside `withTransaction` |
| implicit migration | `Migration` objects or `@AutoMigration` with committed `schemas/` JSON |

Four caveats that bite:

1. **No lazy graph traversal.** `RecordService` walks `exercise.usages → sets` in one expression. In Room that is an explicit join or a `@Transaction` + `@Relation` DTO, and it must be written to avoid N+1.
2. **`@Relation` is a read construct and does not cascade writes.** Deleting a `Workout` only removes its sets if the child declares the FK *and* FK enforcement is on. Room enables `PRAGMA foreign_keys=ON` for entities with declared FKs — which also means inserting a child with a dangling parent id throws, where SwiftData never did. Seed order matters (`RoutineSeeder`).
3. **Singletons.** `UserProfile`/`GymSchedule` are "fetch first, create if missing" on iOS. In Room use a fixed `@PrimaryKey val id: Int = 0` row and a `Flow<…?>`; do not replicate the "fetch first row" idiom.
4. **Missing constraints to add.** SwiftData has zero indexes. `AttendanceRecord`'s logical key `(day, participant)` is unenforced on iOS and hand-filtered; add a real unique index on Android. `BodyWeightEntry.day` is already unique. Store every `Date` as epoch millis and normalise "day" fields to local midnight with the device `ZoneId`.

Gradle notes: the `androidx.room` plugin marker is published **only to Google's Maven**, not `plugins.gradle.org` — add `google()` to `pluginManagement.repositories`, declare the version once in the root file with `apply false`, and set `room { schemaDirectory("$projectDir/schemas") }` (required when the plugin is applied). Commit the generated `schemas/` JSON.

### 6.2 Sign-in

**Google → Credential Manager.** `GetGoogleIdOption.Builder().setServerClientId(WEB_CLIENT_ID).setFilterByAuthorizedAccounts(false).setNonce(serverNonce)` → `CredentialManager.getCredential(...)` → `GoogleIdTokenCredential.createFrom(...).idToken` → `POST /auth/google`. The backend endpoint already exists (`backend/src/routes/auth.ts`) and verifies `aud` against `GOOGLE_CLIENT_IDS`, which `backend/src/env.ts` builds from a comma-separated env var — so this is a **config addition** (append the Android project's Web client id), not code.

Credential Manager also covers the **username/password** path (`GetPasswordOption` / `CreatePasswordRequest`), which gives Android something iOS does not have: the password manager saves the credential. Use it for all three flows.

**Sign in with Apple: defer to a later release.** There is no Apple SDK for Android; the only route is a web OAuth flow in a Chrome Custom Tab (never a WebView — Google blocks embedded-WebView OAuth), requiring a separate **Services ID**, a backend redirect endpoint, a client-secret JWT signed with the `.p8`, and an app-link hand-back. Google Play imposes no requirement to offer it (the "offer SIWA alongside other logins" rule is an App Store review rule for iOS apps). Note also that **the iOS app itself does not currently use Sign in with Apple** — there is no `AuthenticationServices` import — so there is no installed base of Apple-created accounts to strand. Ship v1 with Google + username/password; keep `Session` platform-agnostic so adding it later is additive.

### 6.3 Keychain → Keystore

`androidx.security:security-crypto` is a dead end: `EncryptedSharedPreferences` reads *"Added in 1.0.0, **Deprecated in 1.1.0**. This class is deprecated. Use `android.content.SharedPreferences` instead."*, 1.1.0 was the terminal release, and AndroidX ships **no replacement encryption layer**.

The 2026 pattern: Android Keystore `KeyGenParameterSpec` AES-256-GCM (`setUserAuthenticationRequired(false)`, StrongBox where available) + Tink (`AesGcmKeyManager`) or a hand-rolled `Cipher("AES/GCM/NoPadding")` with a per-record IV + DataStore storing base64 ciphertext. DataStore is Flow/coroutines-based so all I/O is off the main thread — one of the two reasons `EncryptedSharedPreferences` was retired (the other being keyset-corruption crashes on some OEMs).

Two carve-outs from the iOS design:
- **Exclude from Auto Backup** (`data_extraction_rules.xml` / `full_backup_content`). iOS's `kSecAttrAccessible…ThisDeviceOnly` has no restore path; on Android a restored blob is undecryptable. The app must treat "token present but undecryptable" as a clean sign-out.
- The **Anthropic BYOK key** lives in the same store and must be wiped by Settings → Delete account and data, exactly as `SettingsModel.deleteAccount` does on iOS.

### 6.4 HealthKit → Health Connect

| iOS `HKQuantityType` | Health Connect record | Permission |
|---|---|---|
| `bodyMass` (read + write) | `WeightRecord(time, zoneOffset, weight, metadata)` | `health.READ_WEIGHT`, `health.WRITE_WEIGHT` |
| `dietaryEnergyConsumed`, `dietaryProtein`, `dietaryCarbohydrates`, `dietaryFatTotal` (write, wrapped in one `HKCorrelation(.food)`) | **one** `NutritionRecord` per meal entry (`energy`, `protein`, `totalCarbohydrate`, `totalFat`, `name`, `mealType`) | `health.WRITE_NUTRITION` |
| workouts (write) | `ExerciseSessionRecord(startTime, endTime, EXERCISE_TYPE_STRENGTH_TRAINING, title)` | `health.WRITE_EXERCISE` |
| **`activeEnergyBurned` (write)** | **`ActiveCaloriesBurnedRecord`** sharing the session's time range | **`health.WRITE_ACTIVE_CALORIES_BURNED`** |

⚠️ That last row is missed by every summary of this app that reads `docs/architecture.md:79` instead of the source. `NoTomorrow/Services/HealthKitService.swift:37` declares `HKQuantityType(.activeEnergyBurned)` in `writeTypes` and `saveWorkout(start:end:kcal:)` adds the sample to the `HKWorkoutBuilder`. Health Connect has no notion of a sample nested inside a session, so it becomes a sibling record. Omitting it silently loses workout calories *and* makes the Play health declaration incomplete.

Parity note, not an upgrade: iOS already groups the four nutrients via `HKCorrelation(.food)`. What Health Connect adds is `name` and `mealType` — populate them and the two platforms will render differently in their respective health apps. Decide deliberately.

Availability: SDK supports **API 26+**; the Health Connect *app* requires API 28+; from Android 14 it is a platform module with no install step. Probe with `HealthConnectClient.getSdkStatus(context)` → `SDK_UNAVAILABLE` / `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED` / `SDK_AVAILABLE` and drive the Settings "Health app" row's three states off it. Reads are limited to 30 days before the grant unless you also hold `PERMISSION_READ_HEALTH_DATA_HISTORY` (we don't need it).

Manifest is mandatory and easy to get wrong:

```xml
<uses-permission android:name="android.permission.health.READ_WEIGHT"/>
<uses-permission android:name="android.permission.health.WRITE_WEIGHT"/>
<uses-permission android:name="android.permission.health.WRITE_NUTRITION"/>
<uses-permission android:name="android.permission.health.WRITE_EXERCISE"/>
<uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"/>
<queries><package android:name="com.google.android.apps.healthdata"/></queries>
<!-- Android 13 and below -->
<activity android:name=".health.PermissionsRationaleActivity" android:exported="true">
  <intent-filter><action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"/></intent-filter>
</activity>
<!-- Android 14+ -->
<activity-alias android:name="ViewPermissionUsageActivity" android:exported="true"
    android:targetActivity=".health.PermissionsRationaleActivity"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
  <intent-filter>
    <action android:name="android.intent.action.VIEW_PERMISSION_USAGE"/>
    <category android:name="android.intent.category.HEALTH_PERMISSIONS"/>
  </intent-filter>
</activity-alias>
```

The rationale Activity *"must display the same privacy policy you provide for your app in the Google Play Console."*

**Play Console gate — the largest schedule risk in the port.** Before *any* release with Health Connect: User-data + sensitive-permissions policy compliance, the Data safety section, and the **Health apps declaration form** with a per-data-type justification for each of the five types above, plus a matching privacy policy on the store listing. Fill the form before the first internal-track upload, not before launch.

### 6.5 Sheets

The API is in flux and the version pin matters. **Compose BOM 2026.08.00 resolves material3 to 1.4.0**, where `rememberBottomSheetState(initialValue, enabledValues)`, `sheetGesturesEnabled` and `BottomSheetDefaults.modalWindowInsets` **do not exist** and `rememberModalBottomSheetState` is **not** deprecated. Those are material3 1.5.0-alpha APIs. Write against 1.4.0:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)   // ModalBottomSheet is still experimental in 1.4.0
ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = NT.Colors.surface,
    tonalElevation = 0.dp,
    scrimColor = Color.Black.copy(alpha = 0.40f),
    dragHandle = null,                     // 8 of 10 iOS sheets hide the indicator
    contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
) { Box(Modifier.fillMaxWidth().heightIn(min = 376.dp).imePadding()) { … } }
```

`SheetValue` has only `Hidden` / `PartiallyExpanded` / `Expanded` — **there is no fixed-height detent API**. Fixed detents become content heights, and note the rendered sheet is taller than the content by the drag handle (when shown) plus the bottom inset. The eight detented sheets: 376 (Portion), 660+large (CantMakeIt), 340 (LogWeight), medium ×3 (AIScanAddMissed, OBDayTime, OBTargetEditor), large ×2 (SignIn, RestTimer). Only ExercisePicker and WorkoutDetail show a handle.

Keyboard: `android:windowSoftInputMode="adjustResize"` plus `Modifier.imePadding()` **inside** the sheet content; screenshot-test PortionSheet and QuickAddSheet with the keyboard up specifically, since `ModalBottomSheet` is a separate window and IME insets in dialogs have a long history of glitches.

`fullScreenCover` → a nav destination with `slideInVertically(initialOffsetY = { it }, animationSpec = tween(500, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)))`, not a `Dialog`.

### 6.6 Barcode: ML Kit + CameraX, not the Google code scanner

| | **ML Kit + CameraX (chosen)** | Google Code Scanner |
|---|---|---|
| Coordinate | `com.google.mlkit:barcode-scanning:17.3.0` (bundled) or `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1` | `com.google.android.gms:play-services-code-scanner:16.1.0` |
| UI | **ours** — CameraX `PreviewView` inside the Compose screen, NT tokens, our reticle | Google's full-screen UI, unstyleable |
| CAMERA permission | required | **not required** |
| minSdk (documented) | 23 | 23 |
| GMS required | no (bundled) | yes |

`BarcodeScannerView` on iOS is a styled sheet with a manual-code fallback; the Code Scanner's system UI would be the single most visible divergence from the iOS app. Google's own guidance agrees: *"For more complex use cases that require a custom UI, we recommend using the ML Kit Barcode Scanning API directly."*

Options `FORMAT_EAN_13, FORMAT_EAN_8, FORMAT_UPC_E` (+ `FORMAT_UPC_A`), `enableAllPotentialBarcodes()`. Keep the iOS `barcodeForms` normalisation (13-digit leading-zero → 12-digit UPC-A, 12-digit → prefix "0") as a belt-and-braces path against Open Food Facts. The bundled artifact needs **no** `com.google.mlkit.vision.DEPENDENCIES` meta-data — that tag is only for the unbundled Play-services path. Emulator fallback: gate on `packageManager.hasSystemFeature(FEATURE_CAMERA_ANY)` and show the manual-code field, mirroring the iOS simulator path.

### 6.7 Swipe-to-delete

Exactly one site (`FuelHomeSubviews.swift:80`, meal entries, `allowsFullSwipe: true`, tint `bad`). `SwipeToDismissBox` has no resting-open value, so iOS's "rest at the button width, delete on full swipe" needs a custom row driven by `AnchoredDraggableState` with three anchors (`Closed = 0`, `Open = -88.dp`, `Dismissed = -width`). Cheap version: `SwipeToDismissBox(enableDismissFromStartToEnd = false, …)` — full swipe works, tapping the revealed button does not.

### 6.8 Haptics

Three effects, **8 call sites** (5 via `Haptics.*` in `RestTimerController`/`ActiveWorkoutView`, plus 3 direct `UINotificationFeedbackGenerator().notificationOccurred(.success)` in `BarcodeScannerView:58`, `AIScanView:104`, `SignInView:140`). `Haptics.warning()` is declared and **never called** — don't port it.

| iOS | Compose `HapticFeedbackType` | Platform constant | API |
|---|---|---|---|
| `.light` impact | `ContextClick` (or `SegmentTick`) | `CONTEXT_CLICK` = 6 | 23 |
| `.success` | `Confirm` | `CONFIRM` = 16 | 30 |
| wheel tick (`UISelectionFeedback`) | `SegmentFrequentTick` | `SEGMENT_FREQUENT_TICK` = 27 | 34 |
| — | `Reject` | `REJECT` = 17 | 30 |

(`CLOCK_TICK` = **4**, not 3 — 3 is `KEYBOARD_TAP`.) Compose's `HapticFeedbackCompat` degrades unrecognised constants to `NO_HAPTICS` rather than throwing, so old devices are safe, but add explicit fallbacks for the wheel (`SegmentFrequentTick` → `ContextClick` below API 34).

Two correctness notes: grab `LocalHapticFeedback.current` in composition and fire it **in the event callback** (a `@Composable fun tap()` whose body performs the effect fires during composition and re-fires on recomposition); and prefer `performHapticFeedback` over raw `Vibrator`/`VibrationEffect` so the user's haptic setting is respected — which also means all haptics vanish for users who turn touch vibration off, where iOS's generators are not gated the same way.

### 6.9 Localization

**Already built.** `scripts/xcstrings_to_android.py` generates `android/app/src/main/res/values/strings.xml`, `values-pl/strings.xml` and `android/strings-map.json` from `Localizable.xcstrings`. Current output: 548 `<string>` + 2 `<plurals>` per language.

Naming rule, exactly as implemented: `"dashboard.nextSession"` → `dashboard_nextSession`; `"progress.prOn %@"` → `progress_prOn_s`; `"in %lld h %lld min"` → `in_n_h_n_min`. Every `%lld`/`%d` becomes `n`, every `%@` becomes `s`, other non-`[A-Za-z0-9_]` runs collapse to `_`, leading digits get a `k_` prefix.

Format conversion: `%lld`/`%ld`/`%d` → `%d`, `%@` → `%s`, `%.1f` kept, positional `%1$lld` → `%1$d`. Strings with more than one non-positional specifier are **auto-indexed**, because aapt2 rejects *"multiple substitutions specified in non-positional format"*.

Plurals: the two `variations.plural` keys become `<plurals>` with `one/few/many/other`. Polish CLDR requires all four (`one`: i=1,v=0; `few`: v=0 and i%10 in 2–4 and i%100 not in 12–14; `many`: the rest of the integers; `other`: fractions). Read with `pluralStringResource(id, count, count)`. ⚠️ Android's plural APIs take an **Int**, so the `other` (fractional) category is unreachable through `<plurals>` — keep the item as the required fallback, but format decimal weights with `NumberFormat` + a plain string, and port the hand-rolled Polish plural helper in `WorkoutStrings.swift` to real `<plurals>` resources.

**The language override.** iOS `AppLocale.effective` keeps the device **region** and swaps only the language, so "English" on a Polish phone yields `en_PL` — 24-hour clock, decimal comma, Monday-first. Reproduce exactly:

```kotlin
AppCompatDelegate.setApplicationLocales(
    LocaleListCompat.forLanguageTags(if (lang == null) "" else "$lang-$deviceRegion")
)
```

Three requirements the docs are explicit about: the Compose host must extend `AppCompatActivity` (otherwise setting the app locale silently doesn't work); on API 32 and below call it with the **Activity** context and add the `AppLocalesMetadataHolderService` manifest entry; and declare `android:localeConfig="@xml/locale_config"` (or `androidResources { generateLocaleConfig = true }` + `res/resources.properties`) or the app will not appear in the system per-app-language screen at all.

`setApplicationLocales` **recreates the Activity**, which is why the iOS "relaunch to apply" hint can be dropped — not because nothing restarts. Formatters must read the locale from `AppCompatDelegate.getApplicationLocales()` (or `LocalConfiguration.current.locales[0]`) on **every** call, mirroring the live-locale fix already made on iOS (commit `00478b3`), and the `uppercase()` in eyebrow labels must use that same locale.

### 6.10 Font

Ship the repo's own files, do not re-download:

- **Display face:** `NoTomorrow/Resources/Fonts/BigShouldersDisplay-ExtraBold.ttf` is a **static** instance (no `fvar`, `usWeightClass` 800; PostScript name `BigShouldersDisplayThin-ExtraBold`). It is already copied to `android/app/src/main/res/font/bigshouldersdisplay_extrabold.ttf`. Do not source a variable file from Google Fonts and instance it: the catalogue has retired the "Big Shoulders Display" family in favour of a redrawn `Big Shoulders` superfamily with an `opsz` axis, so a fresh download is a *different* typeface. (Licence is fine either way — OFL 1.1, ship `OFL.txt` in the licences screen.) One housekeeping item: reconcile the two copies so both platforms rasterise from one byte-identical source.
- **Sans face:** SF Pro cannot ship on Android (Apple licence). Measured against SF Pro (`SFNS.ttf`, UPM 2048): x-height 0.508, cap 0.705, natural line height 1.178. **Roboto Flex** is 0.514 / 0.711 / 1.172 (+1.2 %, +0.9 %, −0.5 %) — the closest available. **Inter** is 0.546 / 0.728 / 1.210, i.e. ~7 % taller x-height, so Inter at 17 sp reads noticeably bigger than SF Pro at 17 pt and would need ~16.5 sp to match cap-height, which then breaks every hardcoded row height. **Decision: bundle Roboto Flex** (OFL, variable, `opsz` 8–144).

⚠️ **Do not double-bold.** `Font(resId, variationSettings = …)` defaults to `weight = FontWeight.Normal`, so a `TextStyle(fontWeight = Bold)` against it triggers **synthetic bold on top of** the variable axis. Declare the weight on the `Font` (`Font(res, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontWeight(w), FontStyle.Normal, FontVariation.opticalSizing(size.sp)))`) and/or set `fontSynthesis = FontSynthesis.None`. Variable fonts need API 26+ — which our floor already is.

---

## 7. Notifications, alarms and the rest timer — the hardest part

`docs/architecture.md:107` makes this non-negotiable: rest-timer state lives only in `RestTimerController`, whose truth is an absolute `endDate`; the UI never runs its own `Timer`. That rule survives the port intact. What does not survive is the delivery mechanism — there is no single Android API that does what ActivityKit + `UNNotificationRequest` do together. Decompose into four independent pieces.

### 7.1 State
`restEndAtEpochMillis: Long?` + `totalSeconds`, `exerciseName`, `nextSetLabel`, `workoutName` in DataStore (survives process death), mirrored into a `StateFlow`. Compose renders from `endAt - System.currentTimeMillis()` via a 1 Hz wall-clock-aligned ticker. The ticker is a rendering detail, not state — exactly the `TimelineView` model.

### 7.2 Lock-screen countdown (no service, no ticking)
One ongoing `NotificationCompat` with `setUsesChronometer(true)` + `setChronometerCountDown(true)` (API 24+) + `setWhen(endAt)`. **The system renders the countdown**; the app posts once per state change (start, ±15, skip, end) — structurally the same "publish an absolute end date, let the OS render it" contract as the Live Activity. `+15` / `Skip` actions go to a `BroadcastReceiver` via `PendingIntent.getBroadcast(FLAG_IMMUTABLE)`; never trampoline through an Activity (blocked since Android 12).

Two channels: `CH_REST` at `IMPORTANCE_LOW` (silent ongoing) and `CH_REST_DONE` at `IMPORTANCE_HIGH` with a sound — the practical analogue of iOS's `interruptionLevel = .timeSensitive`. `IMPORTANCE_MIN` would disqualify the notification from Live Update promotion.

⚠️ **From Android 14, `setOngoing(true)` no longer prevents the user swiping the notification away.** A dismissed notification must **not** cancel the pending alarm, or a stray swipe silently kills the end-of-rest chime. State lives in DataStore; the notification is a view.

### 7.3 The end-of-rest alarm

```kotlin
if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)
else
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi)   // degraded
```

⚠️ `setExact()` is **not** a permission-free fallback: *"The SCHEDULE_EXACT_ALARM permission is required to initiate exact alarms via the following APIs or a SecurityException will be thrown: setExact(), setExactAndAllowWhileIdle(), setAlarmClock()"* — the only exemption is the `OnAlarmListener` overload, which only fires while the process is alive and is therefore useless here. The permission-free degraded path is `setAndAllowWhileIdle`.

**Declare `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`.** `USE_EXACT_ALARM` is auto-granted and unrevocable, but Play restricts it: *"Apps must only declare this permission if their core functionality supports the need for an exact alarm… The app is an alarm or timer app. The app is a calendar app that shows event notifications"*, reviewed, and non-conforming apps *"will be disallowed from publishing on Google Play."* No Tomorrow is a gym log with a rest timer, not an alarm app — and `SCHEDULE_EXACT_ALARM` grants identical capability for zero review risk. It is **not pre-granted** to fresh installs targeting 33+ and is denied after a backup/restore onto Android 14, so gate every schedule on `canScheduleExactAlarms()`, listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to re-arm on grant, and design a Settings row for the denied case (fall back to inexact + the in-app ticker, which covers the overwhelmingly common "phone in hand between sets" case). Note the revoke semantics: revoking the permission stops the app and cancels all future exact alarms.

**Do not use a full-screen intent.** `USE_FULL_SCREEN_INTENT` is auto-granted on Android 14+ only to alarm and call apps.

### 7.4 Live Updates — decoration, not foundation
Promotion requirements (Android 16): style ∈ {Standard, BigText, Call, **ProgressStyle**, Metric}; manifest `android.permission.POST_PROMOTED_NOTIFICATIONS`; `setRequestPromotedOngoing(true)`; `setOngoing(true)`; a `contentTitle`; **no** `customContentView`; not a group summary; not `setColorized(true)`; channel importance above `IMPORTANCE_MIN`. Detect with `Notification.hasPromotableCharacteristics()`, `NotificationManager.canPostPromotedNotifications()`, `Notification.FLAG_PROMOTED_ONGOING`; deep-link with `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`.

`NotificationCompat.ProgressStyle` is in `androidx.core:core` **since 1.17.0** and states: *"ProgressStyle Notifications are supported on Android 36 and above. If the SDK version is below 36, the ProgressStyle will fall back to the default notification style."* The chip countdown is driven by the same `when` + chronometer we already set — so §7.2's builder is already chip-ready; promotion adds the permission, the flag, and `setShortCriticalText("REST")`.

Version nuance: `ProgressStyle` is API 36, but `Notification.Builder.setRequestPromotedOngoing` / `EXTRA_REQUEST_PROMOTED_ONGOING` landed in **36.1**. `NotificationCompat.Builder.setRequestPromotedOngoing` handles this for us; on the raw platform builder at compileSdk 36 you would set the extra by key. Google's usage criteria list "starting a workout" under user-initiated activities, so a rest timer is inside the intended envelope. Rendering is Android 16 QPR1+ and OEM-dependent (Samsung routes Live Updates into the Now Bar) — Google's doc says only *"OEMs can enforce additional criteria for Live update eligibility."* **Treat the whole promotion layer as optional decoration; §7.2 + §7.3 must be complete and correct alone.**

### 7.5 Foreground service? No.

| Candidate type | Runtime prerequisite | Verdict |
|---|---|---|
| `health` | `HIGH_SAMPLING_RATE_SENSORS` in manifest, or one of `READ_HEART_RATE` / `READ_SKIN_TEMPERATURE` / `READ_OXYGEN_SATURATION` / `ACTIVITY_RECOGNITION` granted (`BODY_SENSORS` only on API 35 and lower) | ❌ we read no sensors; requesting one to unlock an FGS type is exactly the over-request Play penalises |
| `shortService` | none | ❌ ~3-minute cap, not sticky, cannot start other FGS — covers a 90 s rest, not a 5 min rest |
| `dataSync` | none | ❌ semantically wrong; ~6 h/24 h cap with `Service.onTimeout()` from Android 15 |
| `specialUse` | `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | ⚠️ the only honest option, reviewed case-by-case in Play Console |

Since Android 14 every FGS must declare a type and hold the matching `FOREGROUND_SERVICE_<TYPE>` permission. Because the chronometer is rendered by the system and the chime is delivered by `AlarmManager`, there is nothing for a service to do. **Measure before adding one**: verify Doze and aggressive OEM battery managers (Xiaomi, Oppo, Samsung) on real hardware, and consider a battery-optimisation-exemption prompt if the field data demands it.

### 7.6 The 21:00 attendance check and gym-day reminders

The real 21:00 check is already a **backend cron** (`backend/src/jobs.ts`, `scan-due-notifications` every 5 min, `reminder` when `sessionMinute-60 <= minuteOfDay < sessionMinute`, `skipCheck` when `1260 <= minuteOfDay < 1320`, deduped via `notifications_sent`). The phone only sweeps past days (`AttendanceService.markPastPlannedAsMissed`). That split is unchanged on Android: derivation runs locally (WorkManager or on-appear, as today), pushes come from the server.

### 7.7 Behavioural gaps to close in code (iOS gives these free)

| # | Gap | Fix |
|---|---|---|
| 1 | Alarms do not survive reboot (`UNNotificationRequest`s do) | `RECEIVE_BOOT_COMPLETED` receiver re-arms rest timer + reminders from persisted state |
| 2 | Ongoing notifications are user-dismissible from Android 14 | state in DataStore; dismissal must not cancel the alarm |
| 3 | Exact alarms can be denied and revoked | `canScheduleExactAlarms()` gate + state-changed receiver + a Settings row |
| 4 | Keystore blobs do not survive backup/restore | exclude from Auto Backup; undecryptable ⇒ signed out |
| 5 | `POST_NOTIFICATIONS` is a runtime permission (33+) | ask it in the onboarding Schedule step, beside the two reminder toggles |
| 6 | Health Connect may be absent or need an update | `getSdkStatus()` drives the Settings row's three states |
| 7 | Live Update promotion is unavailable on most devices | feature-detect; never depend on it |
| 8 | Edge-to-edge + predictive back are mandatory at targetSdk 36 | `enableEdgeToEdge()`, `WindowInsets` everywhere, `BackHandler`, never `onBackPressed` |

### 7.8 Backend delta (the only server work)

`backend/src/push.ts` is APNs-only via `@parse/node-apn`. Android needs FCM. Four blockers that a one-line "add a platform column" plan misses:

1. `backend/src/routes/push.ts:8` validates the token as `/^[0-9a-fA-F]{32,512}$/`. FCM registration tokens contain `:`, `-`, `_` and mixed case → **every Android registration 400s**. Branch the schema per platform.
2. The same route lowercases the token on insert and delete (`body.token.toLowerCase()`, lines 22 and 30) — harmless for hex APNs tokens, **destructive** for case-sensitive FCM tokens.
3. `backend/src/push.ts:63` selects `where user_id = … and environment = <sandbox|production>` and `migrations/001_init.sql:42-47` constrains `environment` to those two values. Adding a `platform` column alone still filters Android rows out of every send.
4. `routes/push.ts:17` throws 503 `push_unavailable` when `deps.env.apns` is unset, so an FCM-only deployment cannot accept tokens at all.

Concretely:
```sql
ALTER TABLE push_tokens ADD COLUMN platform TEXT NOT NULL DEFAULT 'ios'; -- 'ios' | 'android'
ALTER TABLE push_tokens DROP CONSTRAINT push_tokens_environment_check;   -- or widen it
```
plus: per-platform token schema; drop `toLowerCase()` for FCM; make the recipient query platform-aware; gate `/push/token` on `apns || fcm`; map FCM `UNREGISTERED`/404 onto the existing dead-token cleanup that currently keys on APNs 410/`BadDeviceToken`; add `GOOGLE_SERVICE_ACCOUNT_JSON` to `src/env.ts`, degrading to a clear 503 when absent (matching the existing convention). `src/i18n.ts` (the recipient-locale copy, en+pl) is reusable **verbatim**.

Cleanest option: replace the direct APNs sender with `firebase-admin` and use FCM HTTP v1's per-platform override blocks — one `token`, plus `android { … }` and `apns { payload: { aps … }, live_activity_token }`. That removes APNs JWT rotation from the Fly.io service and gives one retry/error path. Keeping the existing APNs code is also fine; the requirement is only the platform column and a send-time branch.

Two more one-liners: append the Android Web client id to `GOOGLE_CLIENT_IDS`; decide whether to widen the JWT audience (`auth/jwt.ts` hard-codes `aud: "notomorrow-ios"` — either widen it to a set or keep it as a product-wide constant). Send `User-Agent: NoTomorrow/0.1 Android`. Nothing else in the contract changes: every route, DTO and error code is platform-neutral. The client should send `registerPushToken(token: String, platform: "android")` — note the iOS signature takes raw `Data` and hex-encodes.

---

## 8. (e) Verified toolchain

All versions read from Maven metadata / POMs / official docs on **2026-09-04**. "Stable" = no `-alpha/-beta/-rc`.

### 8.1 Build toolchain

| Item | Version | Constraint | Source |
|---|---|---|---|
| Android Gradle Plugin | **9.4.0** | max API 37; min+default Gradle **9.6.0**; min+default JDK **17** | [AGP releases](https://developer.android.com/build/releases/gradle-plugin) |
| Gradle | **9.7.1** | runs on JVM 17–26 (27 untested) — **already the repo's wrapper** | [services.gradle.org](https://services.gradle.org/versions/current) |
| JDK | **21** to run; `jvmTarget`/`compileOptions` **17** | AGP min 17 | [Java versions in Android builds](https://developer.android.com/build/jdks) |
| Kotlin | **2.4.10** | 2.4.20 still RC | [kotlinlang releases](https://kotlinlang.org/docs/releases.html) |
| Compose Compiler plugin | `org.jetbrains.kotlin.plugin.compose` **2.4.10** | always == Kotlin | [setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) |
| KSP | **2.3.11** | independently versioned since KSP 2.3.0 | [KSP releases](https://github.com/google/ksp/releases) |

⚠️ **AGP 9 has built-in Kotlin, on by default.** Do **not** apply `org.jetbrains.kotlin.android` (it fails with *"Cannot add extension with name 'kotlin'"*). AGP 9.4.0's POM ships `kotlin-gradle-plugin:2.2.10` and `symbol-processing-gradle-plugin:2.2.10-2.0.2`; raise them through the root `buildscript` classpath (documented upgrade path). `kotlin-kapt` becomes `com.android.legacy-kapt`; `android.kotlinOptions{}` moves to `kotlin { compilerOptions { } }`; downgrading below the bundled version needs `android.builtInKotlin=false` + `android.newDsl=false`, removed in AGP 10.

⚠️ **Type-safe catalog accessors (`libs.x.y`) do not work inside `buildscript {}`** — the buildscript stage compiles before project extensions exist. Use literal coordinates there.

**Known-untested edges** (permitted by both vendors, warnings possible; smoke-test before committing): JetBrains' certified matrix caps KGP 2.4.0–2.4.10 at Gradle 9.5.0 / AGP 9.1.0, and Gradle's compatibility notes say Gradle is tested with AGP 9.0 through 9.4.0-alpha03. The conservative fallback is AGP 9.1.0 + Gradle 9.5.0 with the same KGP/KSP, which still supports API 37.

### 8.2 SDK levels

| | Value | Why |
|---|---|---|
| `compileSdk` | **37** | `androidx.core:core:1.19.0` and `compose.ui:ui-android:1.12.0` both declare `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0` in their AAR metadata. compileSdk 36 fails at configuration time. |
| `targetSdk` | **36** | Play requires target 36 for new apps and updates since **31 Aug 2026** (extension to 1 Nov 2026). compileSdk may exceed targetSdk. |
| `minSdk` | **26** | Health Connect SDK floor (the HC *app* needs 28). AndroidX floor is now uniformly 23 (Compose 1.12.0, Room 2.8.4, CameraX 1.6.2 all declare `minSdkVersion=23`); ML Kit documents 23. 26 additionally buys `java.time` without desugaring, unconditional notification channels, adaptive icons and variable fonts. |
| AGP | **≥ 9.1.0** | forced by the AAR metadata above; we use 9.4.0 |

Inherited at targetSdk 36: edge-to-edge is mandatory (`windowOptOutEdgeToEdgeEnforcement` deprecated and disabled); predictive back is on by default and `onBackPressed` is not called; **orientation/aspect-ratio/resizability restrictions are ignored on displays ≥ 600 dp** (§9.4). 16 KB page-size support is already required on Play for targetSdk 35+; only transitive `.so` files (ML Kit bundled model, Tink, Conscrypt) are exposed — verify them.

### 8.3 `gradle/libs.versions.toml`

```toml
[versions]
# ---- toolchain (§8.1) ----
agp                 = "9.4.0"      # needs Gradle >= 9.6.0, JDK >= 17, max API 37
kotlin              = "2.4.10"     # KGP + compose-compiler plugin
ksp                 = "2.3.11"     # independently versioned since 2.3.0

# ---- SDK levels (§8.2) ----
compileSdk          = "37"
targetSdk           = "36"         # Play requirement since 2026-08-31
minSdk              = "26"         # Health Connect SDK floor

# ---- Compose ----
composeBom          = "2026.08.00" # ui/foundation/runtime/animation 1.12.0, material3 1.4.0

# ---- AndroidX ----
activityCompose     = "1.13.0"
appcompat           = "1.8.0"      # AppCompatActivity + setApplicationLocales backport
lifecycle           = "2.11.0"
navigationCompose   = "2.10.0"
room                = "2.8.4"
datastore           = "1.2.1"
work                = "2.11.2"
coreKtx             = "1.19.0"     # NotificationCompat.ProgressStyle, setRequestPromotedOngoing
splashscreen        = "1.2.0"
healthConnect       = "1.1.0"
camerax             = "1.6.2"
credentials         = "1.6.0"
browser             = "1.10.0"     # Custom Tabs, only if SIWA lands later
graphicsShapes      = "1.1.0"      # continuous-corner squircle
profileinstaller    = "1.4.1"
benchmarkMacro      = "1.4.1"

# ---- Google / Play services ----
googleid            = "1.2.0"
firebaseBom         = "34.18.0"
googleServices      = "4.5.0"
mlkitBarcode        = "17.3.0"     # bundled

# ---- Kotlin ecosystem ----
coroutines          = "1.11.0"
serialization       = "1.11.0"
ktor                = "3.5.2"
tink                = "1.23.0"

# ---- test ----
junit4              = "4.13.2"
androidxTestExt     = "1.3.0"
turbine             = "1.2.1"
mockk               = "1.14.11"
robolectric         = "4.14.1"

[libraries]
# Compose (BOM-managed — no versions)
compose-bom                 = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui                  = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics         = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling          = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview  = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-test-junit4      = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest    = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-foundation          = { group = "androidx.compose.foundation", name = "foundation" }
compose-animation           = { group = "androidx.compose.animation", name = "animation" }
compose-material3           = { group = "androidx.compose.material3", name = "material3" }

# AndroidX
androidx-core-ktx           = { module = "androidx.core:core-ktx",                          version.ref = "coreKtx" }
androidx-core-splashscreen  = { module = "androidx.core:core-splashscreen",                 version.ref = "splashscreen" }
androidx-appcompat          = { module = "androidx.appcompat:appcompat",                    version.ref = "appcompat" }
androidx-activity-compose   = { module = "androidx.activity:activity-compose",              version.ref = "activityCompose" }
androidx-lifecycle-vm       = { module = "androidx.lifecycle:lifecycle-viewmodel-compose",  version.ref = "lifecycle" }
androidx-lifecycle-runtime  = { module = "androidx.lifecycle:lifecycle-runtime-compose",    version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose",          version.ref = "navigationCompose" }
androidx-room-runtime       = { module = "androidx.room:room-runtime",                      version.ref = "room" }
androidx-room-ktx           = { module = "androidx.room:room-ktx",                          version.ref = "room" }
androidx-room-compiler      = { module = "androidx.room:room-compiler",                     version.ref = "room" }
androidx-room-testing       = { module = "androidx.room:room-testing",                      version.ref = "room" }
androidx-datastore-prefs    = { module = "androidx.datastore:datastore-preferences",        version.ref = "datastore" }
androidx-work-ktx           = { module = "androidx.work:work-runtime-ktx",                  version.ref = "work" }
androidx-health-connect     = { module = "androidx.health.connect:connect-client",          version.ref = "healthConnect" }
androidx-camera-core        = { module = "androidx.camera:camera-core",                     version.ref = "camerax" }
androidx-camera-camera2     = { module = "androidx.camera:camera-camera2",                  version.ref = "camerax" }
androidx-camera-lifecycle   = { module = "androidx.camera:camera-lifecycle",                version.ref = "camerax" }
androidx-camera-view        = { module = "androidx.camera:camera-view",                     version.ref = "camerax" }
androidx-camera-compose     = { module = "androidx.camera:camera-compose",                  version.ref = "camerax" }
androidx-camera-mlkit       = { module = "androidx.camera:camera-mlkit-vision",             version.ref = "camerax" }
androidx-credentials        = { module = "androidx.credentials:credentials",                version.ref = "credentials" }
androidx-credentials-gms    = { module = "androidx.credentials:credentials-play-services-auth", version.ref = "credentials" }
androidx-browser            = { module = "androidx.browser:browser",                        version.ref = "browser" }
androidx-graphics-shapes    = { module = "androidx.graphics:graphics-shapes",               version.ref = "graphicsShapes" }
androidx-profileinstaller   = { module = "androidx.profileinstaller:profileinstaller",      version.ref = "profileinstaller" }
androidx-benchmark-macro    = { module = "androidx.benchmark:benchmark-macro-junit4",       version.ref = "benchmarkMacro" }

# Google / Play services
google-id                   = { module = "com.google.android.libraries.identity.googleid:googleid", version.ref = "googleid" }
firebase-bom                = { module = "com.google.firebase:firebase-bom",                version.ref = "firebaseBom" }
firebase-messaging          = { module = "com.google.firebase:firebase-messaging" }
mlkit-barcode               = { module = "com.google.mlkit:barcode-scanning",               version.ref = "mlkitBarcode" }

# Kotlin ecosystem
kotlinx-coroutines-android  = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-play     = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
kotlinx-serialization-json  = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-client-core            = { module = "io.ktor:ktor-client-core",                        version.ref = "ktor" }
ktor-client-okhttp          = { module = "io.ktor:ktor-client-okhttp",                      version.ref = "ktor" }
ktor-client-content-neg     = { module = "io.ktor:ktor-client-content-negotiation",         version.ref = "ktor" }
ktor-client-logging         = { module = "io.ktor:ktor-client-logging",                     version.ref = "ktor" }
ktor-client-mock            = { module = "io.ktor:ktor-client-mock",                        version.ref = "ktor" }
ktor-serialization-json     = { module = "io.ktor:ktor-serialization-kotlinx-json",         version.ref = "ktor" }
tink-android                = { module = "com.google.crypto.tink:tink-android",             version.ref = "tink" }

# test
junit4                      = { module = "junit:junit",                                     version.ref = "junit4" }
kotlin-test                 = { module = "org.jetbrains.kotlin:kotlin-test",                version.ref = "kotlin" }
coroutines-test             = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test",   version.ref = "coroutines" }
turbine                     = { module = "app.cash.turbine:turbine",                        version.ref = "turbine" }
mockk                       = { module = "io.mockk:mockk",                                  version.ref = "mockk" }
robolectric                 = { module = "org.robolectric:robolectric",                     version.ref = "robolectric" }
androidx-test-ext-junit     = { module = "androidx.test.ext:junit",                         version.ref = "androidxTestExt" }

# buildscript classpath — raise AGP 9's built-in Kotlin/KSP (literal coords in build.gradle.kts)
kotlin-gradle-plugin        = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin",              version.ref = "kotlin" }
ksp-gradle-plugin           = { module = "com.google.devtools.ksp:symbol-processing-gradle-plugin", version.ref = "ksp" }

[plugins]
android-application = { id = "com.android.application",                   version.ref = "agp" }
# NOTE: do NOT declare org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin.
compose-compiler    = { id = "org.jetbrains.kotlin.plugin.compose",       version.ref = "kotlin" }
kotlin-serialization= { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp                 = { id = "com.google.devtools.ksp",                   version.ref = "ksp" }
room                = { id = "androidx.room",                             version.ref = "room" }
google-services     = { id = "com.google.gms.google-services",            version.ref = "googleServices" }
```

`settings.gradle.kts` must include `google()` in `pluginManagement.repositories` (the `androidx.room` plugin marker is not on the Gradle Plugin Portal). Root `build.gradle.kts`:

```kotlin
buildscript {
  dependencies {                                   // literal coords: `libs.` is unavailable here
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
  }
}
plugins {
  alias(libs.plugins.android.application)  apply false
  alias(libs.plugins.compose.compiler)     apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp)                  apply false
  alias(libs.plugins.room)                 apply false
  alias(libs.plugins.google.services)      apply false
}
```

### 8.4 Deliberately excluded

| Not used | Why |
|---|---|
| `androidx.compose.material:material-icons-extended` | frozen at 1.7.8 (Feb 2025), old shapes, big build cost; Google recommends exporting Material Symbols vector drawables instead |
| Vico / any chart library | §5.7 |
| `androidx.security:security-crypto` | all APIs deprecated, no replacement (§6.3) |
| Haze / AGSL / `Modifier.blur` | §5.1 |
| `kotlinx-datetime` | `minSdk 26` gives `java.time` natively; avoids a version tug-of-war |
| Compose Multiplatform | §3.1(e) |
| `cupertino-icons-extended` | ships SF Symbols — licence-prohibited |

### 8.5 Networking: Ktor over Retrofit

Ktor 3.5.2's OkHttp engine already depends on OkHttp 5.3.2 + coroutines 1.11.0, and multipart is first-class (`submitFormWithBinaryData` / `MultiPartFormDataContent`) — exactly the `/ai/estimate` shape (image + meal + locale + optional `X-Anthropic-Key`, 90 s timeout). Retrofit 3.0.0 works but pins OkHttp 4.12.0 and its kotlinx-serialization converter pins serialization-core 1.8.1, and multipart needs `@Multipart`/`@Part` plumbing.

### 8.6 Continuous corners

49 `style: .continuous` sites. `RoundedCornerShape` is a circular arc; at radius 22 on a full-width card the difference is visible. Use `androidx.graphics:graphics-shapes:1.1.0` — `RoundedPolygon.rectangle(w, h, rounding = CornerRounding(radius, smoothing = 0.6f)).toPath()` wrapped in a custom `Shape` — and tune `smoothing` against a screenshot. Budget it as a real task; do not report layout parity as "100 %" until it lands.

---

## 9. (f) Making Compose look like iOS

### 9.1 Units

Numerically 1 pt → 1 dp; copy every number verbatim (20 pt gutter → `20.dp`, 44 pt hit target → `44.dp`). Physically an iOS point is ~1/153 in on modern @3x iPhones vs a dp's 1/160 in — a few percent, device-dependent, imperceptible. The canvas differs more than the unit: iPhone 16 is 393×852 pt, iPhone 16 Pro 402×874, 16 Plus 430×932, Pro Max 440×956; typical Android phones report 360–412 dp. Design fluid where the layout is fluid, dp where it is a token.

**Test harness:** an AVD at **1179 × 2556 px, 480 dpi** gives a logical canvas of exactly 393 × 852 dp at 3×, i.e. iPhone 16's. Screenshot diffs become directly comparable.

### 9.2 Typography

The iOS app uses fixed-size `Font.system(size:weight:)` everywhere — **no Dynamic Type**, with overflow handled by `.minimumScaleFactor` at 13 sites. To reproduce that, clamp Android's font scale at the composition root:

```kotlin
CompositionLocalProvider(
    LocalDensity provides Density(base.density, fontScale = base.fontScale.coerceIn(1f, 1.15f))
) { … }
```

A hard `fontScale = 1f` gives byte-exact parity but ignores a headline Android accessibility setting and is flagged by Play's pre-launch report; the 1.15 clamp is the recommended compromise, paired with `Text(autoSize = TextAutoSize.StepBased(minFontSize = size * f))` at the 13 `minimumScaleFactor` sites (material3 1.4.0's `Text` takes `autoSize` directly — no need to drop to `BasicText`). Note the clamp only affects Compose; View-based surfaces (CameraX preview, the system photo picker) still scale.

Keep `lineHeight` in `sp`, never `dp`.

**The ramp** — Apple's HIG values (Large/default), with SF Pro's own tracking table:

| iOS style | token | size | leading | weight | SF tracking (pt) | → `letterSpacing` |
|---|---|---|---|---|---|---|
| Large Title | `largeTitle` | 34 | 41 | Bold | **+0.40** | `+0.0118.em` |
| Title 1 | `title1` | 28 | 34 | Bold | **+0.38** | `+0.0136.em` |
| Title 2 | `title2` | 22 | 28 | Bold | **−0.26** | `−0.0118.em` |
| Title 3 | `title3` | 20 | 25 | Semibold | **−0.45** | `−0.0225.em` |
| Headline | `headline` | 17 | 22 | Semibold | **−0.43** | `−0.0253.em` |
| Body | `body` | 17 | 22 | Regular | −0.43 | `−0.0253.em` |
| Callout | `callout` | 16 | 21 | Regular | **−0.31** | `−0.0194.em` |
| Subhead | `subheadline`/`Bold` | 15 | 20 | Regular/Semibold | **−0.23** | `−0.0153.em` |
| Footnote | `footnote`/`Bold` | 13 | 18 | Regular/Semibold | **−0.08** | `−0.0062.em` |
| Caption 1 | `caption` | 12 | 16 | Medium | **0.0** | `0.em` |
| Caption 2 | `eyebrow` | 11 | 13 | Semibold | **+0.06** | `+0.0055.em` (+ the explicit 0.9, below) |

⚠️ **iOS tracking is negative only between ~13 and 23 pt.** At 12 pt it is zero, below that positive, and from 24 pt up positive again, peaking at +0.40 around 29–34 pt. Blanket negative tracking on a 34 pt title is wrong. Hero display sizes: 40 → +0.37, 44 → +0.37, 56 → +0.30, 64 → +0.22, 104 → ~0 (SF values; Big Shoulders has its own fitting — measure).

**Explicit `.tracking()` sites** (verified — use `em`, computed against the *actual* font size at that site):

| Site | iOS | → `letterSpacing` |
|---|---|---|
| `Theme.swift:81` eyebrow, 11 pt | `.tracking(0.9)` | `0.0818.em` |
| `FuelSummaryRow.swift:87` "LEFT", **8 pt** | `.tracking(0.6)` | `0.0750.em` |
| `BroUnpairedView.swift:128`, `SetupPairView.swift:139` code entry, **display(26)** | `.tracking(1)` | `0.0385.em` |
| `BroUnpairedView.swift:58` code, **display(56)** | `.tracking(2)` | `0.0357.em` |
| `SetupPairView.swift:91` code, **display(56)** | `.tracking(2.2)` | `0.0393.em` |

(The 2 vs 2.2 inconsistency between the two code cards is in the source; reproduce as-is or normalise deliberately.)

**Line boxes.** `includeFontPadding` has defaulted to false since Compose 1.6.0-alpha01 — leave it. A single-line SwiftUI `Text` is the font's *natural* line height (SF Pro 1.178 em ⇒ 20.0 pt at 17 pt), while the HIG "Leading" (22 pt) is the multi-line pitch. In Compose, `lineHeight = 22.sp` makes even one line 22 dp tall. Inside fixed-height rows this is invisible; inside a `Column(spacedBy(4.dp))` it shifts everything ~2 dp per line. Two tiers: wrapping paragraphs use the HIG leading with `LineHeightStyle(Alignment.Center, Trim.None)`; single-line labels in stacks use `round(1.178 × size).sp`.

Also note Android's `letterSpacing` distributes tracking half-before/half-after each glyph while iOS `.tracking` adds it after — sub-pixel at 11 sp, mentioned for completeness.

### 9.3 Icons

Google's Compose Icons page is explicit that `material-icons-extended` *"is no longer maintained or recommended for use in your apps"* and recommends downloading Material Symbols XML from the Android tab of fonts.google.com/icons. Do that: **export vector drawables** into `res/drawable/ic_*.xml`, one per symbol, with `wght`/`FILL`/`opsz` chosen at download time to match the SF weight. Zero dependencies, tree-shaken by definition, hand-editable. Material Symbols are Apache-2.0.

⛔ **Never ship SF Symbols.** The licence limits use to *"SOFTWARE PRODUCTS RUNNING ON APPLE'S iOS, iPadOS, macOS, tvOS OR watchOS OPERATING SYSTEMS"*, and Apple sells no other-platform licence. That rules out `cupertino-icons-extended` and any path data traced from SF. Trace from `design/*.dc.html` instead.

**The mapping — all 41 distinct symbols** (verified by grepping `systemName:`, `systemImage:` and the `AppTab.symbol` enum; the commonly quoted "24/25" misses the `Label(systemImage:)` and ternary forms):

| # | SF Symbol | Material Symbols Rounded | Fidelity |
|---|---|---|---|
| 1 | `house` | `home` | ✅ |
| 2 | `dumbbell` | `fitness_center` | ✅ |
| 3 | `fork.knife` | `restaurant` (not `fork_spoon`) | ✅ |
| 4 | `chart.line.uptrend.xyaxis` | `trending_up` | ✅ |
| 5 | `person.2` | `group` | ✅ |
| 6 | `chevron.right` | `chevron_right` | ✅ |
| 7 | `chevron.left` | `chevron_left` | ✅ |
| 8 | `chevron.down` | `keyboard_arrow_down` (dial `wght` down to ~300) | ✅ |
| 9 | `arrow.left` | `arrow_back` | ✅ |
| 10 | `arrow.up` | `arrow_upward` | ✅ |
| 11 | `arrow.down` | `arrow_downward` | ✅ |
| 12 | `plus` | `add` | ✅ |
| 13 | `minus` | `remove` | ✅ |
| 14 | `checkmark` | `check` | ✅ |
| 15 | `xmark` | `close` | ✅ |
| 16 | `magnifyingglass` | `search` | ✅ |
| 17 | `ellipsis` | `more_horiz` (horizontal — not `more_vert`) | ✅ |
| 18 | `square.and.arrow.up` | `ios_share` | ⚠️ `ios_share` *is* the iOS glyph; Android users expect the 3-node `share`. Design call — recommend `ios_share` for parity. |
| 19 | `doc.on.doc` | `content_copy` | ✅ |
| 20 | `lock` | `lock` | ✅ |
| 21 | `info.circle` | `info` (already circled) | ✅ |
| 22 | `timer` | `timer` | ✅ |
| 23 | `clock` | `schedule` | ✅ |
| 24 | `bolt` | `bolt` | ✅ |
| 25 | `bubble.left` | `chat_bubble` | ✅ |
| 26 | `pencil` | `edit` | ✅ |
| 27 | `trash` | `delete` | ✅ |
| 28 | `camera` | `photo_camera` | ✅ |
| 29 | `photo.on.rectangle` | `photo_library` | ✅ |
| 30 | `eye.slash` | `visibility_off` | ✅ |
| 31 | `forward.end.fill` | `skip_next` | ✅ |
| 32 | `medal` | `military_tech` | ✅ |
| 33 | `person.crop.circle.badge.exclamationmark` | `person_alert` | ⚠️ compose from `account_circle` + badge for exactness |
| 34 | `moon.zzz` | `bedtime` | ⚠️ crescent only, no "zzz" — add or accept |
| 35 | `g.circle` | **Google branded sign-in asset** | ⛔ not a Material Symbol — use Google's own branding assets, which carry their own rules |
| 36 | **`flame`** | `local_fire_department` | ❌ **custom vector** — SF is one teardrop, Material is a multi-lobed campfire. This is the streak icon; it is visible. |
| 37 | **`target`** | `target` | ❌ **custom vector** — Material `target` is concentric circles, SF is a fine crosshair reticle with gaps |
| 38 | **`barcode.viewfinder`** | `barcode_scanner` | ❌ **custom vector** — SF has corner brackets around the bars |
| 39 | **`calendar.badge.minus`** | `event_busy` | ❌ **custom vector** — the badge is an ✕, not a − |
| 40 | **`trophy`** (outline) | `trophy` FILL 0 / `emoji_events` | ❌ **custom vector** — silhouettes differ |
| 41 | **`trophy.fill`** | `trophy` FILL 1 | ❌ **custom vector**, same reason |

Six custom vectors (`flame`, `target`, `barcode.viewfinder`, `calendar.badge.minus`, `trophy`, `trophy.fill`) — all in ember/status positions the design leans on. Set icon sizes explicitly (`Modifier.size(20.dp)`); Compose's `Icon` defaults to 24 dp while SF Symbols at 17 pt medium render nearer 20–22 pt.

### 9.4 Tab bar, edge-to-edge, large screens

**Do not use `NavigationBar`.** Material3's is `Surface(tonalElevation = …)` over a `Row` with `defaultMinSize(minHeight = 80.dp)` and an item selection pill — three defaults to fight. Build a `Column` = 1-px top hairline + `Row(Modifier.height(49.dp))` on `NT.Colors.tabBar`, with `Modifier.windowInsetsPadding(WindowInsets.navigationBars)` (never hardcode 34 dp — Android gesture nav is ≈24 dp, 3-button ≈48 dp), each item `clickable(indication = null)`, icon tinted `ink`/`ink2`. Add `Modifier.selectableGroup()` and `role = Role.Tab` with real `contentDescription`s — the SwiftUI source already carries `.accessibilityAddTraits`, so don't regress it.

⚠️ `Dp.Hairline` is `Dp(0f)` — a `Canvas(Modifier.height(Dp.Hairline))` draws nothing. For the 1-px rule use `with(LocalDensity.current) { 1f.toDp() }`, or `1.dp` where matching iOS's 1 pt matters more than physical thinness.

**Edge-to-edge:**
```kotlin
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
)
```
Explicit `dark(...)` because the app is dark irrespective of the system theme. Keep the "no fake status bar" rule: let `NT.Colors.ground` fill the window and pad the *content* with `WindowInsets.safeDrawing.only(Top)` (which includes display cutouts — `systemBars` misses them, `safeContent` adds gesture insets you don't want on a bottom bar). If you use `Scaffold`, set `contentWindowInsets = WindowInsets(0)`, otherwise it consumes the insets and content stops scrolling under the tab bar.

**Large screens.** At targetSdk 36 `android:screenOrientation="portrait"` is ignored on displays ≥600 dp, and Android 17 removes the temporary opt-out. The iOS app is portrait-only iPhone. Decision required: either constrain content to a max width and let it letterbox on tablets/foldables, or ship the temporary resizability opt-out property with a plan to remove it. Practically: make the fixed sheet heights `heightIn(min = …)` rather than `height(…)`, and cap hero type. This has no iOS counterpart and must be scoped, not assumed away.

### 9.5 Dark-only theme

```kotlin
@Composable fun NTTheme(content: @Composable () -> Unit) {
    // no isSystemInDarkTheme(), no dynamicDarkColorScheme() — ever.
    MaterialTheme(colorScheme = NTColorScheme, typography = NTTypography) {
        CompositionLocalProvider(
            LocalIndication          provides NTPressScale,
            LocalOverscrollFactory   provides null,           // kills the Android edge glow
            LocalTonalElevationEnabled provides false,        // global kill switch for M3 tinting
            LocalContentColor        provides NT.Colors.ink,
            LocalDensity provides Density(base.density, fontScale = base.fontScale.coerceIn(1f, 1.15f)),
            content = content
        )
    }
}
```

Rules: never branch on dynamic colour; paint surfaces with `Modifier.background(NT.Colors.surface, shape)` rather than `Surface`/`Card` (M3 tints when `backgroundColor == colorScheme.surface` and tonal elevation is enabled — the `LocalTonalElevationEnabled provides false` line is the belt-and-braces global fix, and pass `tonalElevation = 0.dp` where a Material component forces a `Surface`, e.g. `ModalBottomSheet`); in `themes.xml` use `Theme.Material3.Dark.NoActionBar` with `android:forceDarkAllowed=false`, and `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)` so View-based surfaces don't flip. Alpha tokens composite identically in sRGB on both platforms — verify once with a colour picker; pre-composite to opaque RGB if they don't.

Overscroll: `LocalOverscrollFactory provides null` (the old `LocalOverscrollConfiguration` is deprecated). If an iOS-style bounce is ever wanted, implement `OverscrollEffect` (`applyToScroll` + `applyToFling`) and pass it to `LazyColumn(overscrollEffect = …)` — foundation 1.12.0 exposes that parameter, so the old `NestedScrollConnection`+offset hack is no longer the practical route. Apple's rubber band is `(1 - 1/(|x|·c/dim + 1)) · dim/c · sign(x)` with `c = 0.55`, springing back critically damped. Treat as polish, not day one.

### 9.6 Pickers, segmented control, keyboard

**Wheel time picker.** iOS uses `DatePicker(.wheel)` framed at **164 pt** in onboarding (`OnboardingComponents.OBTimeWheel`, plus `.padding(.vertical, 6)`) and **180 pt** in `ScheduleEditor.swift`. Do not derive the row height by dividing by 5 — UIKit's wheel row height is intrinsic (~32–35 pt) and the frame *crops*. Measure from a simulator screenshot, then build a `LazyColumn` + `rememberSnapFlingBehavior` with a `graphicsLayer { rotationX; alpha; scale }` cylinder, a `surface2` selection band, and a `SegmentFrequentTick` haptic on selection change. Derive the selection from the item nearest the viewport centre (`layoutInfo.visibleItemsInfo`), **not** `firstVisibleItemIndex`, and gate the haptic on `isScrollInProgress`. Libraries exist (`ai.asleep:compose-wheelpicker:0.1.0`, Apache-2.0, minSdk 28) but rolling it is ~80 lines and gives full control.

**Segmented controls.** Never `SingleChoiceSegmentedButtonRow` (outlined pills, leading check, 40 dp). The app already hand-builds three (`ProgressSegmented`, `OBSegmented`, `STSegmented` — the latter two byte-identical): port them verbatim, animating the thumb offset with `animateDpAsState(tween(150 or 180, EaseOut))`.

**Alerts.** M3's `AlertDialog` is the wrong shape (28 dp radius, left-aligned, text buttons bottom-right). Use `BasicAlertDialog` with `DialogProperties(usePlatformDefaultWidth = false)` and draw a 270 dp wide, 14 dp radius `surface2` panel: centred title (`headline`) + message (`footnote`, `ink2`), hairline, then 44 dp action rows — two actions side-by-side split by a vertical hairline, three or more stacked; destructive in `bad`. `confirmationDialog` renders as an iOS action sheet: a `ModalBottomSheet` with `containerColor = Transparent` holding two rounded 14 dp groups (actions, then Cancel) inset 8 dp with an 8 dp gap.

**Text input.** `KeyboardType.Decimal` for `.decimalPad`, `.Number` for `.numberPad`. Use `BasicTextField` with a `decorator` — no Material chrome. ⚠️ `.decimalPad` shows the *locale* separator (comma in Polish) while Compose's `Decimal` shows a period, so the parser must accept both — which the iOS parsers already do.

### 9.7 Parity ceiling

| Area | Ceiling | Effort |
|---|---|---|
| Colours, spacing, radii, layout | 100 % once continuous corners land | Low + one shape task |
| Type ramp, tracking, leading | ~98 % with Roboto Flex + Apple's tables | Low |
| Icons | ~90 % with Material Symbols; 100 % on the 6 hand-drawn | Medium |
| Tab bar, edge-to-edge | 100 % | Low |
| Sheets | ~90 % (no fixed detents, predictive-back dismissal differs) | Medium |
| Alerts / action sheets | ~95 % hand-built | Medium |
| Wheel picker | ~95 % | Medium |
| Press-scale, no ripple | 100 % | Low |
| Charts, rings, macro bars | 100 % (hand-drawn) | Medium |
| Swipe-to-delete | 100 % only with a custom `AnchoredDraggable` row | Medium |
| Overscroll bounce | ~85 % if attempted; recommend not attempting | High |
| Haptics | API mapping 100 %, felt result hardware-dependent | Low |
| Rest-timer reliability | ~90 % (Doze, OEM killers, revocable exact alarms) | High |

---

## 10. (g) Risks and open questions

### Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **Play Health-apps declaration** blocks release. Requires the form, Data safety answers, a matching privacy policy and per-data-type justification for all **five** health types (incl. `WRITE_ACTIVE_CALORIES_BURNED`). | **High** — schedule | File before the first internal-track upload; keep the Health Connect feature behind a flag so a rejection does not block the rest |
| 2 | **Rest-timer reliability** — the app's most behaviour-critical surface. Exact alarms can be denied/revoked; alarms die at reboot; Doze and OEM battery managers (Xiaomi/Oppo/Samsung) interfere; ongoing notifications are swipe-dismissible from Android 14. | **High** | DataStore truth + boot re-arm + `canScheduleExactAlarms()` gate + degraded inexact path + a Settings row; test on real Xiaomi/Samsung hardware, not just a Pixel emulator |
| 3 | **Backend FCM work is bigger than "one column"** — four hard blockers (§7.8) any of which silently breaks Android push. | Medium | Do the backend change first and verify with a real device before wiring the client |
| 4 | **Toolchain edges untested by vendors** — KGP 2.4.10 above JetBrains' certified AGP/Gradle ceiling; Gradle 9.7.1 tested only to AGP 9.4.0-alpha03. | Medium | Smoke-test the empty project first; conservative fallback AGP 9.1.0 + Gradle 9.5.0 is documented in §8.1 |
| 5 | **material3 sheet APIs in flux** — 1.4.0 vs 1.5.0-alpha have different state APIs; copying 1.5-era snippets does not compile. | Medium | Pin the BOM, write against 1.4.0, `@OptIn(ExperimentalMaterial3Api::class)` |
| 6 | **Large-screen/orientation** at targetSdk 36 — no portrait lock ≥600 dp; the iOS layouts have no tablet design. | Medium | Decision needed (§9.4); constrain max width and make sheet heights `heightIn` |
| 7 | **Font-scale decision** — hard `fontScale = 1f` gives exact parity but is an accessibility regression flagged by Play's pre-launch report. | Medium | Ship the 1.0–1.15 clamp + `TextAutoSize` at the 13 overflow sites |
| 8 | **Two codebases drift.** | Medium | strings are generated (already); add a screenshot-diff harness against the iPhone-16-geometry AVD; keep `docs/architecture.md` and `docs/android-architecture.md` edited together |
| 9 | **Haptic character varies by hardware.** | Low | route through `performHapticFeedback`; optionally gate on `Vibrator.areAllPrimitivesSupported(PRIMITIVE_TICK)` and fall back to no haptic rather than a bad one |
| 10 | **AI photo data-safety classification.** Photos leaving the device must be declared unless processing is genuinely ephemeral (*"retained for no longer than necessary to service the specific request in real-time"*). | Medium | Decide the Fly.io/Gemini retention policy first; the `fuel.ai.disclaimer` copy must name the destination |
| 11 | **16 KB page size** — required on Play for targetSdk 35+, and from 2027-02-01 non-compliant updates are blocked. Exposure is transitive `.so` (ML Kit bundled, Tink, Conscrypt). | Low | verify with the alignment check in CI |
| 12 | **`ios_share` vs `share` icon** reads as an iOS app to Android users. | Low | design call, §9.3 row 18 |

### Open questions

1. **Portrait/large-screen policy** — accept stretched layouts on ≥600 dp, design tablet layouts, or ship the temporary opt-out? (Blocks the navigation-shell agent.)
2. **Font scale** — hard lock at 1.0 for byte-exact parity, or the 1.0–1.15 clamp? (Blocks the design-system agent.)
3. **`ios_share` or `share`** for the 4 share affordances?
4. **Sign in with Apple** — confirmed deferred? The iOS app does not use it today, so there is no stranded account base; confirm before locking the auth scope.
5. **Google Web client id** for `GOOGLE_CLIENT_IDS` — needs a Firebase/GCP project decision before the Bro tab can be tested end-to-end.
6. **Demo mode** — port `MockBackendClient` (Tomek, 20 s auto-confirm, 10 s reply, fixed AI plate) and `MockAIEstimateService` verbatim? Recommended yes; it is what makes the flows demoable offline and testable in CI.
7. **`Exercise.localizedName` locale source** — iOS reads `Locale.current`, so it does *not* follow the in-app override until relaunch. Android should follow the override (better), which is a deliberate divergence to sign off.
8. **`AppState.pendingRoute`** — declared on iOS with zero readers. Implement deep links/notification routing on Android now, or mirror the dead code? Recommend implementing it (push notifications make it live).
9. **Continuous corners** — accept `RoundedCornerShape` for v1 and land the squircle later, or block the design system on it?
10. **Backup policy** — confirm the token DataStore is excluded from Auto Backup and that "undecryptable ⇒ signed out" is acceptable product behaviour.
11. **Big Shoulders TTF** — the iOS copy and `android/.../res/font/` copy are not byte-identical (same font, different bytes). Reconcile to one source.
12. **`aud: "notomorrow-ios"`** — widen the JWT audience or keep it as a product-wide constant?

---

## Sources

Apple — [HIG Typography](https://developer.apple.com/design/human-interface-guidelines/typography) ([data](https://developer.apple.com/tutorials/data/design/human-interface-guidelines/typography.json)) · [HIG Layout](https://developer.apple.com/design/human-interface-guidelines/layout) · [Apple Fonts licence](https://www.developer.apple.com/fonts/) · [Apple Design Resources licence](https://developer.apple.com/support/downloads/terms/apple-design-resources/Apple-Design-Resources-License-20230621-English.pdf)

Android platform — [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16) · [Android 17](https://developer.android.com/about/versions/17) · [Play target-SDK requirements](https://developer.android.com/google/play/requirements/target-sdk) · [Edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge) · [Predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture) · [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) · [Per-app languages](https://developer.android.com/guide/topics/resources/app-languages) · [String resources](https://developer.android.com/guide/topics/resources/string-resource) · [CLDR plural rules](https://www.unicode.org/cldr/charts/47/supplemental/language_plural_rules.html)

Notifications / alarms — [Live update notifications](https://developer.android.com/develop/ui/views/notifications/live-update) · [Progress-centric notifications](https://developer.android.com/about/versions/16/features/progress-centric-notifications) · [NotificationCompat.ProgressStyle](https://developer.android.com/reference/androidx/core/app/NotificationCompat.ProgressStyle) · [Notification channels](https://developer.android.com/develop/ui/views/notifications/channels) · [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission) · [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms/schedule) · [Android 14 exact alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms) · [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) · [Android 14 behavior changes](https://developer.android.com/about/versions/14/behavior-changes-all)

Compose — [Compose BOM](https://developer.android.com/develop/ui/compose/bom) · [compose-ui releases](https://developer.android.com/jetpack/androidx/releases/compose-ui) · [compose-foundation releases](https://developer.android.com/jetpack/androidx/releases/compose-foundation) · [compose-material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) · [Icons](https://developer.android.com/develop/ui/compose/graphics/images/material) · [Work with fonts](https://developer.android.com/develop/ui/compose/text/fonts) · [Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) · [Brush](https://developer.android.com/develop/ui/compose/graphics/draw/brush) · [Shadows](https://developer.android.com/develop/ui/compose/graphics/draw/shadows) · [Migrate to Indication and Ripple](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/migrate-indication-ripple) · [Bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets) · [Window insets](https://developer.android.com/develop/ui/compose/layouts/insets) · [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) · [Spring](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Spring) · [OverscrollEffect](https://developer.android.com/reference/kotlin/androidx/compose/foundation/OverscrollEffect) · [HapticFeedbackConstants](https://developer.android.com/reference/android/view/HapticFeedbackConstants) · [KeyboardType](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/input/KeyboardType) · [Density](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density)

Jetpack libraries — [Room](https://developer.android.com/jetpack/androidx/releases/room) · [Room relationships](https://developer.android.com/training/data-storage/room/relationships) · [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore) · [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) · [core](https://developer.android.com/jetpack/androidx/releases/core) · [CameraX](https://developer.android.com/jetpack/androidx/releases/camera) · [credentials](https://developer.android.com/jetpack/androidx/releases/credentials) · [security (deprecated)](https://developer.android.com/jetpack/androidx/releases/security) · [graphics-shapes](https://developer.android.com/jetpack/androidx/releases/graphics) · [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)

Health Connect — [Get started](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started) · [Data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types) · [WeightRecord](https://developer.android.com/reference/androidx/health/connect/client/records/WeightRecord) · [NutritionRecord](https://developer.android.com/reference/androidx/health/connect/client/records/NutritionRecord) · [ExerciseSessionRecord](https://developer.android.com/reference/androidx/health/connect/client/records/ExerciseSessionRecord) · [ActiveCaloriesBurnedRecord](https://developer.android.com/reference/androidx/health/connect/client/records/ActiveCaloriesBurnedRecord) · [Publish your health app](https://developer.android.com/health-and-fitness/guides/health-connect/publish/request-access)

ML Kit / Google — [ML Kit barcode (Android)](https://developers.google.com/ml-kit/vision/barcode-scanning/android) · [Google code scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) · [FCM Android client](https://firebase.google.com/docs/cloud-messaging/android/client) · [FCM HTTP v1 Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages) · [Credential Manager releases](https://developers.google.com/identity/android-credential-manager/releases) · [Play Integrity](https://developer.android.com/google/play/integrity/overview) · [Material Symbols](https://fonts.google.com/icons) ([licence](https://github.com/google/material-design-icons/blob/master/LICENSE))

Play policy — [Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/16558241) · [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) · [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111)

Build — [AGP releases](https://developer.android.com/build/releases/gradle-plugin) · [AGP 9.0 notes](https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes) · [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) · [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html) · [Kotlin releases](https://kotlinlang.org/docs/releases.html) · [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) · [KSP](https://github.com/google/ksp/releases) · [Java versions in Android builds](https://developer.android.com/build/jdks)

Alternatives — [Skip modes](https://skip.dev/docs/modes/) · [Skip FAQ](https://skip.dev/docs/faq/) · [Skip Swift support](https://skip.dev/docs/swiftsupport/) · [Skip modules](https://skip.dev/docs/modules/) · [SkipUI README](https://github.com/skiptools/skip-ui/blob/main/README.md) · [Swift 6.3 release](https://www.swift.org/blog/swift-6.3-released/) · [Swift Android Workgroup](https://www.swift.org/android-workgroup/) · [Compose Multiplatform 1.8.0](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/) · [React Native New Architecture](https://reactnative.dev/blog/2024/10/23/the-new-architecture-is-here) · [Capacitor](https://capacitorjs.com/docs) · [@capacitor-mlkit/barcode-scanning](https://www.npmjs.com/package/@capacitor-mlkit/barcode-scanning) · [Vico](https://github.com/patrykandpatrick/vico) · [Haze](https://github.com/chrisbanes/haze)

Repo — `docs/architecture.md`, `NoTomorrow/DesignSystem/{Theme,Components}.swift`, `NoTomorrow/Models/Models.swift`, `NoTomorrow/Services/*`, `NoTomorrow/Features/Progress/ProgressCharts.swift`, `NoTomorrow/Features/Workout/RestPillView.swift`, `NoTomorrow/Resources/Localizable.xcstrings`, `backend/src/{push,env,jobs}.ts`, `backend/src/routes/{push,auth,attendance}.ts`, `backend/migrations/001_init.sql`, `scripts/xcstrings_to_android.py`, `android/`.
