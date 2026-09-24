# No Tomorrow — Android architecture and implementation contract

Read this before touching any feature. `design/*.dc.html` is the visual spec; `docs/architecture.md` is the iOS code spec and the **behavioural source of truth**; this file is the Android code spec. `docs/android-research.md` records why every technology choice below was made.

The goal is an app that is **identical** to the iOS one: same screens, same tokens to the pixel, same behaviours, same backend. Where this document and `docs/architecture.md` disagree about a behaviour, the Swift source wins — read it.

## Stack

- Kotlin 2.4.10, Jetpack Compose (BOM `2026.08.00` → compose.ui/foundation 1.12.0, material3 **1.4.0**), Room for persistence, DataStore for preferences, Ktor for HTTP, coroutines + `StateFlow` for view state.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. AGP **9.4.0** (min Gradle 9.6.0), Gradle **9.7.1** (already the repo's wrapper), JDK 21 to build, `jvmTarget = 17`.
- AGP 9 has **built-in Kotlin**: never apply `org.jetbrains.kotlin.android`. Raise the bundled Kotlin/KSP through the root `buildscript` classpath with literal coordinates (`libs.` accessors do not resolve inside `buildscript {}`). Full `libs.versions.toml` is in `docs/android-research.md` §8.3.
- Dark-only, forced. Never `MaterialTheme.colorScheme.*` for anything the design names, never `isSystemInDarkTheme()`, never `dynamicDarkColorScheme()`. Use `NT.Colors.*`.
- No chart library, no icon library, no shader/blur library. See "Non-negotiables".
- Build: `./gradlew :app:assembleDebug`. Fast check: `./gradlew :app:compileDebugKotlin`. Unit tests: `./gradlew :app:testDebugUnitTest`.

## Folders

Everything lives under `android/`. Package root `app.notomorrow`.

```
android/app/src/main/java/app/notomorrow/
  NoTomorrowApp.kt          Application: DI container init, notification channels, night mode
  MainActivity.kt           AppCompatActivity + enableEdgeToEdge + setContent { NTTheme { RootScreen() } }
  app/                      AppState, AppLocale, AppTab, RootScreen, MainTabScaffold (+ workout layer, mini bar),
                            NTTabBar, StoreLoader, StoreErrorScreen, AppVisibility
  di/                       AppContainer (manual DI), Provides* factories
  designsystem/             NT.kt (tokens), NTTheme.kt, Components.kt, Charts.kt, Sheets.kt,
                            Alerts.kt, PressScale.kt, Wheel.kt, Segmented.kt, FlowLayout.kt, Icons.kt
  data/
    db/                     NoTomorrowDatabase, Converters, DatabaseModule, Migrations
    entity/                 14 @Entity classes (one file per entity group)
    dao/                    one @Dao per aggregate
    relation/               @Relation read DTOs
    prefs/                  AppPrefs, RestTimerPrefs, SecureStore (Keystore+Tink+DataStore)
  model/                    enums (TrainingGoal, WeightUnit, SetKind, MealSlot, FoodSource,
                            AttendanceStatus, HeadsUpKind, Participant, BodyWeightSource,
                            AIProvider, DayState) + domain value types
  service/                  interfaces + implementations, mirroring NoTomorrow/Services
  net/                      BackendClient interface, RemoteBackendClient, MockBackendClient,
                            RemoteTransport, TokenRefresher, dto/, BackendError
  feature/
    onboarding/  dashboard/  workout/  fuel/  progress/  bro/  settings/  auth/  health/
  nav/                      NtRoute, NtNavHost, navigation extensions
  push/                     NtMessagingService, PushRegistrar
  rest/                     RestTimerController, RestTimerNotifier, RestTimerReceiver,
                            BootRescheduleReceiver, RestAlarms
  util/                     Fmt, NtStrings, Parsing, ImageDownscaler, Csv, CurrentDay
android/app/src/main/res/
  values/strings.xml        GENERATED — never hand-edit
  values-pl/strings.xml     GENERATED — never hand-edit
  values/strings_<feature>.xml       hand-written, per feature, for keys not in the catalog
  values-pl/strings_<feature>.xml
  font/bigshouldersdisplay_extrabold.ttf   already present
  font/robotoflex_variable.ttf             add in the design-system task
  drawable/ic_*.xml         Material Symbols exports + 6 custom vectors
  xml/                      locale_config.xml, file_paths.xml, data_extraction_rules.xml
android/app/src/main/assets/  exercises.json, exercises_pl.json   already present
                              + backend/data/ai (estimate-spec.json, fixtures/) via assets.srcDir
android/app/src/test/java/app/notomorrow/   JVM tests mirroring NoTomorrowTests
android/app/src/test/resources/             fuel-calendar-vectors.json, off/*.json (OFF fixtures);
                                            backend/data/ai is a test resources.srcDir too
android/app/schemas/          Room exported schema JSON — commit it (one file per version)
```

Files stay under ~400 lines. Split composables into subviews **in the same package**.

## Environment / DI

There is no `@Environment`. A single hand-rolled container is created in `NoTomorrowApp` and read through one CompositionLocal. No Hilt — the graph is small and manual DI keeps build times and parallel-work friction down.

```kotlin
class AppContainer(app: Application) {
    val scope: CoroutineScope                 // process scope: attendance reports, prefs writes
    val store: StoreLoader                    // opens the Room file; Loading | Open | Failed(message)
    val db: NoTomorrowDatabase                // getter over store.database(); throws while not open
    val appPrefs: AppPrefs                    // see "Preferences" for the keys
    val secureStore: SecureStore              // Keystore-wrapped session + anthropic / gemini keys
    val authStore: AuthStore
    val appConfig: AppConfig                  // makeBackendClient(), rebuilt on url/mock change
    val restTimer: RestTimerController
    val workoutSession: WorkoutSessionController
    val broService: BroService                // process-global singleton, like BroShared.service
    val attendanceReporter: AttendanceReporter // launches broService.reportAttendance on `scope`
    val healthService: HealthService
    val exerciseLibrary: ExerciseLibrary
    val routineSeeder: RoutineSeeder
    val recordService: RecordService
    val attendanceService: AttendanceService
    val targetCalculator: TargetCalculator
    val foodSearchService: FoodSearchService  // isOnline = ConnectivityManager NET_CAPABILITY_INTERNET
    val aiEstimateService: AIEstimateProviders // make() resolves per call from appConfig.aiProvider
    val appState: AppState
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
```

View models are plain `ViewModel`s constructed by a `viewModelFactory` that reads `LocalAppContainer`. Every feature package owns a `<Feature>ViewModel` exposing one immutable `data class <Feature>UiState` through `StateFlow`, collected with `collectAsStateWithLifecycle()`. This is the 1:1 replacement for iOS's `@Observable` models.

Room queries return `Flow<…>` (the `@Query` analogue). One-shot reads are `suspend` DAO functions (the `FetchDescriptor` analogue). `UserProfile` and `GymSchedule` are singleton rows with `@PrimaryKey val id: Int = 0`; `null` means onboarding has not run.

`AppState` (`app/AppState.kt`) is a singleton holding `selectedTab: MutableStateFlow<AppTab>`, `hasOnboarded`, `languageOverride` (both mirrored into `AppPrefs`, same keys as iOS: `nt.hasOnboarded`, `nt.language`), `pendingRoute`, `showsSettings` (the Settings sheet, so a route can open it from any tab) and `fuelTodayRequests` + `openFuelToday()` (the Dashboard's Fuel row; the Fuel view model collects the counter with `drop(1)`, so an Activity recreation never replays it). The rest-sheet request lives on `WorkoutSessionController.wantsRestSheet`, not here.

## Design rules

Identical to `docs/architecture.md` §"Design rules". Restated with the Android mechanics:

- Screen background `NT.Colors.ground`; 20 dp horizontal padding (`NT.Spacing.screenH`); sections separated by whitespace or `Hairline()`, not by cards. One `NTCard` per screen for the object that matters.
- Type: `NT.Fonts.largeTitle` for tab titles with an `eyebrow()` line above; `headline` for section titles; `subheadline`/`footnote` in `ink2` for secondary; `NT.Fonts.display(n)` ONLY for hero numbers and the wordmark. Numbers in columns or that tick get `.tabular()` (`fontFeatureSettings = "tnum"` — a no-op on the display face, so display-face counters need fixed-width digit slots; see research §5.4).
- Buttons: `PrimaryButton` (white) is the one thing to do; `SecondaryButton` (surface2) beside it, equal widths in a card row (52 dp), 56 dp for full-width bottom CTAs; `GhostButton` for tertiary. Never an icon inside a card-row button.
- Ember only for: next-session eyebrow, gym-day dots/attended checks, progress lines and rings, PR badges/trophies, streak icon. `good` = confirmed, `bad` = missed/destructive. Everything else white/grey.
- Hit targets ≥ 44 dp (wrap small visuals in a 44 dp `Box` with the click modifier on the box). No fake status bar. `enableEdgeToEdge()`, pad content with `WindowInsets.safeDrawing.only(Top)`, content scrolls under the tab bar.
- Every string through resources: `stringResource(R.string.<name>)`. Never a hardcoded user-visible literal. Numbers/dates through `Fmt`.
- Empty states are designed, not blank: one line of copy + the action.
- Press feedback: `LocalIndication provides NTPressScale` app-wide (the 40 `PressScale` sites); `indication = null` at the 29 `.plain` sites. No Material ripple anywhere.
- Never `Surface`/`Card`; paint with `Modifier.background(color, shape)`. `NTTheme` also provides `LocalTonalElevationEnabled provides false` and `LocalOverscrollFactory provides null`.

### Tokens — `designsystem/NT.kt`

Values are literal, from `NoTomorrow/DesignSystem/Theme.swift`. 1 pt → 1 dp.

```kotlin
object NT {
  object Colors {
    val ground   = Color(0xFF0A0A0B)
    val surface  = Color(0xFF1C1C1E)
    val surface2 = Color(0xFF2A2A2E)
    val surface3 = Color(0xFF38383C)
    val tabBar   = Color(0xFF161618)
    val ink       = Color(0xFFF2F2F4)
    val ink2      = Color(0xFFEBEBF5).copy(alpha = 0.60f)
    val ink3      = Color(0xFFEBEBF5).copy(alpha = 0.42f)
    val onPrimary = ground
    val ember     = Color(0xFFFF6A2B)
    val emberTint = ember.copy(alpha = 0.12f)
    val good      = Color(0xFF30D158)
    val bad       = Color(0xFFFF375F)
    val badTint   = bad.copy(alpha = 0.12f)
    val hairline  = Color.White.copy(alpha = 0.12f)
    val border    = Color.White.copy(alpha = 0.20f)
  }
  object Spacing { val screenH = 20.dp; val cardPadding = 18.dp; val section = 22.dp; val row = 12.dp }
  object Radius  { val card = 22.dp; val tile = 16.dp; val field = 12.dp; val cell = 10.dp; val pill = 28.dp }
  object Size    { val primaryButton = 56.dp; val cardButton = 52.dp; val control = 44.dp
                   val chip = 40.dp; val tabBar = 49.dp }
  object Fonts { /* see below */ }
  object Anim  { /* see below */ }
}
```

`NT.Fonts`: `largeTitle` 34/41 Bold · `title1` 28/34 Bold · `title2` 22/28 Bold · `title3` 20/25 SemiBold · `headline` 17/22 SemiBold · `body` 17/22 Regular · `callout` 16/21 Regular · `subheadline` 15/20 Regular · `subheadlineBold` 15/20 SemiBold · `footnote` 13/18 Regular · `footnoteBold` 13/18 SemiBold · `caption` 12/16 **Medium** · `eyebrow` 11/13 SemiBold + `letterSpacing` and uppercase (see `eyebrow()` below) · `display(size)` = Big Shoulders ExtraBold. Apply Apple's tracking per style and the five explicit `.tracking()` overrides — the exact `em` table is in research §9.2. Declare the weight on the `Font(...)` itself and set `fontSynthesis = FontSynthesis.None`, or Roboto Flex gets synthetically double-bolded.

`NT.Anim` (never write a bare `tween(n)` — its default easing is Material's, not iOS's):

```kotlin
val easeOut12 = tween<Float>(120, easing = EaseOut)   // PressScale
val easeOut15 = tween<Float>(150, easing = EaseOut)   // field focus, chips, segmented (Progress)
val easeOut18 = tween<Float>(180, easing = EaseOut)   // OB/ST segmented
val easeOut20 = tween<Float>(200, easing = EaseOut)   // most common
val easeOut25 = tween<Float>(250, easing = EaseOut)
val easeOut30 = tween<Float>(300, easing = EaseOut)
val easeOut60 = tween<Float>(600, easing = EaseOut)   // ProgressRing trim
val easeInOut20/25/28/30 = tween<Float>(200/250/280/300, easing = EaseInOut)
val spring070 = spring<Float>(dampingRatio = 0.70f, stiffness = 322.3f)  // rest pill in/out
val spring085 = spring<Float>(dampingRatio = 0.85f, stiffness = 322.3f)  // PR hint
```

### Component contract — `designsystem/Components.kt`

Same names as iOS. Geometry is load-bearing; copy it exactly from `NoTomorrow/DesignSystem/Components.swift`.

| Composable | Geometry |
|---|---|
| `PrimaryButton(title, icon?, height = 56.dp, enabled = true, onClick)` | `Row(spacing 8)`, icon 16 sp SemiBold, `headline` text, `onPrimary` on `ink` capsule, `fillMaxWidth`, h-padding 16, `alpha 0.4` when disabled, `pressScale` |
| `SecondaryButton(title, icon?, height = 56.dp, tint = ink, onClick)` | same shape on `surface2` |
| `GhostButton(title, icon?, onClick)` | 44 dp, transparent, `Capsule` border `NT.Colors.border` 1 dp, icon 14 sp Bold, `subheadlineBold` |
| `Modifier.pressScale(onClick)` / `NTPressScale` indication | scale 0.97 **and** alpha 0.90, `easeOut12`, both applied in one `graphicsLayer` |
| `NTCard(padding = 18.dp, content)` | `surface`, `NtShapes.card` (22 dp continuous), `fillMaxWidth`, content aligned start |
| `StatTile(label, value)` | `Column(spacing 4)`: `eyebrow()` + `headline` value `.tabular()` 1 line; padding h14/v12; `surface`, 16 dp |
| `Hairline()` / `VHairline()` | 1 dp `NT.Colors.hairline` (`Dp.Hairline` is 0 dp — do not use it for layout) |
| `SectionHeader(title, trailing: String? = null)` | `headline` ink + `Spacer` + `subheadline` ink2 |
| `Chip(title: String, selected, icon?, tint?, onClick)` | 40 dp, h-pad 16, `ink`/`surface` capsule, `subheadlineBold` when selected, optional 1 dp `tint.copy(alpha=.4f)` border |
| `SheetChip(...)` | 40 dp, `ink`/`surface2`, `.tabular()` — for `surface`-backed sheets |
| `HeadsUpChip(...)` | **44 dp**, `surface` fill + 1 dp `borderTint` capsule stroke, icon 15 sp SemiBold |
| `Badge(text, color = ember)` | `caption` 12 Medium, h-pad 8, height 22, `color.copy(alpha=.12f)` on 6 dp continuous rect |
| `ProgressRing(progress, lineWidth = 6.dp, color = ember, track = surface2)` | `Canvas`; stroke **centred** so outer diameter is D+lineWidth (research §5.5); `startAngle = -90f`; animate with `easeOut60` |
| `MacroBar(label, value, goal, unit = "g", fill = ink2)` | `Column(spacing 4)`: `footnote` ink2 label + `footnote` ink `"${value.roundToInt()} / ${goal.roundToInt()} $unit"` `.tabular()` — **not localized on iOS, keep it that way**; then a 4 dp capsule track `surface2` with an `fill` capsule at `min(1f, value/goal)` |
| `Avatar(initial, size = 38.dp, background = surface2, dimmed = false)` | uppercase first char, `headline` when size ≥ 36 else `caption`, `ink3` when dimmed, circle |
| `Grabber()` | 36 × 5 dp `ink3` capsule, top padding 8 |
| `ValueRow(label, value, leading?, valueColor = ink2, chevron = true, onClick?)` | `Row(spacing 12)`, `body` label, `Spacer(min 8)`, `body` value `.tabular()` 1 line, optional `chevron_right` 13 sp SemiBold ink3, min height 44 |
| `FlowChips` / `NtFlowLayout(spacing = 8.dp)` | wrapping layout; chips 32 dp with `trophy.fill` 12 sp ember + `footnote` ink `.tabular()`, h-pad 12, `surface` capsule |
| `NtSegmented(options, selected, onSelect, segmentHeight = 32.dp, inset = 2.dp, radius = 8.dp, style = caption, width?)` | track `surface` at `radius + inset`, thumb `surface3` at `radius`, animate offset with `easeOut15`; the onboarding/settings variant is 44 dp segments / inset 3 / thumb 11 / track 14, `easeOut18` |
| `NtFieldChrome` (`Modifier.ntField(focused)`) | h-pad 16, height 52, `surface` at 14 dp, border `hairline` 1 dp → `ink` 1.5 dp when focused, `easeOut15` |
| `NtToggleRow(title, detail?, checked, onCheckedChange)` | `body` title + optional `footnote` ink2 detail, trailing `NtToggle` (never Material's `Switch`), min height 52 (44/56 in Settings) |
| `NtGroup(header, content)` | eyebrow header inset 16 + `surface` 16 dp card; a `Column` with `Hairline()` between children (not after the last) |
| `NtLinkRow` / `NtActionRow` / `NtCheckRow` / `NtInfoRow` | min height 44 (56 with a `detail` line, + 4 dp v-pad); optional 8 dp status dot before the value |
| `NtEditorScaffold(title, content)` | scroll + h-pad 20, top 12, bottom 32, inline title bar on `ground` |
| `ConfidenceDots(level)` | 3 × 10×4 dp rounded rects r2, spacing 2, `ink` / `ink @ 0.2` |
| `KcalLabel(value)` | `Row(alignment = FirstBaseline, spacing 3)`: `subheadline` ink `.tabular()` + `unit.kcal` `footnote` ink2 |
| `eyebrow(color = ink2)` text style | 11 sp SemiBold, tracking `0.0818.em`, `text.uppercase(appLocale)` |

`designsystem/Sheets.kt` wraps `ModalBottomSheet` once: `NtSheet(onDismiss, showsHandle = false, containerColor = surface, minHeight: Dp? = null, height: Dp? = null, skipPartiallyExpanded = true, shape, content)` — `height` is a single `.presentationDetents([.height(N)])`, `minHeight` a detent list that also allows `.large`; `ntMediumDetent()` is the shared `.medium` value and `Modifier.ntDismissKeyboardOnScroll()` is `.scrollDismissesKeyboard(.immediately)`. It applies `@OptIn(ExperimentalMaterial3Api::class)`, `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, `dragHandle = null` unless asked, `tonalElevation = 0.dp`, `scrimColor = Color.Black.copy(alpha = .48f)` (measured — `docs/android-glass.md` §1.5, not .40), bottom-only insets, and `imePadding()`. An optional `confirmDismiss: (() -> Boolean)?` vetoes a swipe-down, a scrim tap and system back alike (the workout editor uses it to ask "Discard your changes?" while its draft is dirty). **No feature may call `ModalBottomSheet` directly.** `NtMenuItem(enabled = false)` draws an item in `ink3` and ignores taps (SwiftUI `.disabled`).

`designsystem/Alerts.kt` provides `NtAlert(title, message?, actions, onDismiss)` and `NtActionSheet(actions, cancel, onDismiss)`. **Their geometry lives in `docs/android-glass.md` §1.6 / §1.7, which supersedes what this table used to say** (the old "270 dp, 14 dp radius, `surface2`, 44 dp rows, two-up split by a vertical hairline" and "two rounded groups, 8 dp inset" are iOS 18 shapes): `NtAlert` is 320 dp wide, r 34, left-aligned, 48 dp capsule buttons at an 8 dp gap, no separators, scrim black @ 0.48; `NtActionSheet` is a **centred** 240 dp popover at r ≈ 26 with 208 × 48 pt capsule rows, no scrim, and **no cancel row** — `cancel` is accepted for parity with the Swift call but is not drawn, and an outside tap dismisses. Both take an optional inline `TextField` slot — two iOS alerts need it.

`designsystem/Charts.kt` holds all four charts drawn in `Canvas`; see the Progress contract.

`designsystem/Icons.kt` maps a stable `NtIcon` enum to `R.drawable.ic_*`, so features never reference drawable ids directly and the icon set can be swapped in one place. All 41 symbols and their Material Symbols equivalents (and the 6 that need custom vectors) are tabulated in research §9.3.

### Shapes

`NtShapes.card/tile/field/cell` are **continuous-corner** superellipse shapes (`androidx.graphics:graphics-shapes`, `CornerRounding(radius, smoothing = 0.6f)`), matching SwiftUI's `style: .continuous` at 49 sites. A `RoundedCornerShape` fallback is acceptable for the first internal build but must be tracked; nothing outside `designsystem/` may construct corner shapes.

## Data layer

### Entities — `data/entity`

14 entities, mirroring `NoTomorrowSchema.models` in order. Enums are stored as their **iOS raw strings** (`TypeConverters`), UUIDs as `String`, dates as epoch-millis `Long`. Every "day" column is local midnight in the device `ZoneId`.

| Entity | Table | Key facts |
|---|---|---|
| `UserProfileEntity` | `user_profile` | singleton `id: Int = 0`; `name`, `bodyWeightKg: Double?`, `goal` (`buildMuscle`/`loseFat`/`maintain`), `units` (`kg`/`lb`), `calorieGoal = 2600`, `proteinGoalG = 180`, `carbsGoalG = 300`, `fatGoalG = 80`, `defaultRestSeconds = 90`, `createdAt` |
| `GymScheduleEntity` | `gym_schedule` | singleton `id: Int = 0`; `weekdays: List<Int>` **stored sorted**, default `[1,3,5]`; `defaultMinuteOfDay = 1080`; `overrides: Map<Int,Int>`; `remindHourBefore = true`; `askIfSkippedAt21 = true`; `updatedAt` |
| `ExerciseEntity` | `exercise` | `@PrimaryKey id: String` (free-exercise-db id or `custom-<uuid>`); `name`, `namePL: String?`, `primaryMuscles/secondaryMuscles: List<String>`, `equipment?`, `category = "strength"`, `force?`, `mechanic?`, `level?`, `instructions: List<String>`, `isCustom = false`, `lastUsedAt: Long?`. `images` from the JSON is parsed and **discarded** |
| `RoutineEntity` | `routine` | `id: String` UUID, `name`, `order`, `createdAt` |
| `RoutineItemEntity` | `routine_item` | autoGenerate `id: Long`; FK `routineId` **CASCADE**, FK `exerciseId` SET NULL; `order`, `targetSets = 3`, `targetReps = 8`, `restSeconds = 90`; index on `routineId` |
| `WorkoutEntity` | `workout` | `id: String` UUID, `name`, `startedAt`, `endedAt: Long?`, `notes = ""`; index on `endedAt`, `startedAt` |
| `WorkoutExerciseEntity` | `workout_exercise` | autoGenerate id; FK `workoutId` **CASCADE**, FK `exerciseId` **SET NULL** (nullable); `order`, `restSeconds = 90`, `notes = ""` |
| `SetEntryEntity` | `set_entry` | autoGenerate id; FK `workoutExerciseId` **CASCADE**; `order`, `kind` (`normal`/`warmup`/`drop`/`failure`), `weightKg = 0.0`, `reps = 0`, `completedAt: Long?`, `isPR = false`, `isSetRecord = false`, `rpe: Double?` |
| `FoodItemEntity` | `food_item` | `@PrimaryKey id: String` (`off:<code>` / `usda:<id>` / `custom:<uuid>`); `name`, `brand?`, `source`, `barcode?`, `kcalPer100/proteinPer100/carbsPer100/fatPer100: Double`, `fiberPer100: Double?`, `servingSizeG: Double?`, `servingLabel?`, `imageURL?`, `isFavorite = false`, `useCount = 0`, `lastUsedAt: Long?` |
| `MealEntryEntity` | `meal_entry` | `id: String` UUID; `day` (local midnight), `slot`, FK `foodId` **SET NULL** nullable, `customName?`, `grams/kcal/proteinG/carbsG/fatG: Double`, `isAIEstimate = false`, `confidence: Double?`, `loggedAt`; index on `(day, slot)` |
| `BodyWeightEntryEntity` | `body_weight_entry` | `@PrimaryKey day: Long` (local midnight — one per day, upsert); `kg`, `source` (`manual`/`healthKit`) |
| `BroPairingEntity` | `bro_pairing` | singleton `id: Int = 0`; `partnerId`, `partnerName`, `myCode`, `pairedAt` |
| `AttendanceRecordEntity` | `attendance_record` | `id: String` UUID; `day`, `participant` (`me`/`partner`), `scheduledMinuteOfDay`, `status`, `reason?`, `note?`, `makeUpDay: Long?`, `updatedAt`; **`@Index(value = ["day","participant"], unique = true)`** — iOS leaves this unenforced; Android enforces it |
| `HeadsUpEntity` | `heads_up` | `id: String` UUID; `fromMe`, `kind`, `text`, `sessionDay`, `sentAt`, `readAt: Long?`; index on `sentAt` |

### DAOs — `data/dao`

One DAO per aggregate: `ProfileDao`, `ScheduleDao`, `ExerciseDao`, `RoutineDao`, `WorkoutDao`, `FoodDao`, `MealDao`, `BodyWeightDao`, `AttendanceDao`, `HeadsUpDao`, `BroPairingDao`. Rules:

- Reactive reads return `Flow<…>`; one-shot reads are `suspend`.
- Multi-table reads use `@Transaction` + a `@Relation` DTO in `data/relation` (`WorkoutWithExercises`, `WorkoutExerciseWithSets`, `RoutineWithItems`, `MealEntryWithFood`). **`@Relation` does not cascade writes** — deletion relies on the declared foreign keys.
- Writes that touch more than one table go through `db.withTransaction { }`. Room enables `PRAGMA foreign_keys=ON` when FKs are declared, so inserting a child with a dangling parent throws — seed exercises before routines.
- `data/db/NoTomorrowDatabase`: `@Database(entities = [...14...], version = NoTomorrowDatabase.VERSION /* 1 */, exportSchema = true)`, built as `Room.databaseBuilder(ctx, …, "NoTomorrow")` by `DatabaseModule.configure`: `addMigrations(*NoTomorrowMigrations.ALL)`, the auto-migration specs, and `fallbackToDestructiveMigrationOnDowngrade(true)` **only** — an upgrade without a migration path fails to open instead of wiping the file. `KeepCorruptFileOpenHelperFactory` makes `onCorruption` throw (the framework default deletes a corrupt file) with `allowDataLossOnRecovery(false)`. There is **no in-memory fallback**: `StoreLoader` opens the database eagerly and a failure shows the store error screen (see "Store failure"). `inMemory` exists for tests only, with the same migration policy.
- **Migration policy** (`data/db/Migrations.kt`). Changing an entity: bump `VERSION`; build and commit `app/schemas/app.notomorrow.data.db.NoTomorrowDatabase/<n>.json` (never edit or delete an older one); add the step `n−1 → n` as an `AutoMigration` in `@Database(autoMigrations = …)` (added table, or added column that is nullable or has `@ColumnInfo(defaultValue)`; renames and deletes need a spec in `AUTO_MIGRATION_SPECS`) or a hand-written `Migration` in `NoTomorrowMigrations.ALL`; pin the new identity hash in `DatabaseMigrationsTest.PINNED_SCHEMAS`. That JVM test fails the build when an entity changed without a bump, a version has no committed schema, or a step has no migration. Add an `androidTest` `MigrationTestHelper` test with the first real migration (`room-testing` is already a dependency).
- Queries added for Fuel and workouts (no schema change): `MealDao.observeDayRange(from, to)` (`day >= :from AND day < :to`, replaces the exact-day query on Fuel), `observeKcalByDaySince`, `deleteByIds`; `FoodDao.preferredByBarcode(keys)` (`barcode IN (:keys) ORDER BY CASE WHEN source='custom' THEN 0 ELSE 1 END, lastUsedAt IS NULL, lastUsedAt DESC, id LIMIT 1`), `observeLibrary()`; `WorkoutDao.activeWorkouts`, `completedSetCount`, `lastCompletedAt`, `reopenWorkout`, `observeLastRoutineWorkoutName`, `countedWorkoutsBetween`, `clearOpenSetRecords`; `ExerciseDao.libraryCount()` (non-custom rows).
- `Converters`: `List<String>`, `List<Int>`, `Map<Int,Int>` (JSON — the wire form uses **String keys**), and each enum ⇄ raw string.

### Preferences — `data/prefs`

`AppPrefs` (DataStore Preferences) holds the same keys as iOS so the two ports stay legible together: `nt.hasOnboarded`, `nt.language`, `nt.aiProvider` (`standard`/`claudeBYOK`/`geminiBYOK`), `nt.backendBaseURL`, `nt.useMockBackend`, `nt.rest.autoStart` (**default true when absent**), `nt.activeWorkoutId` (workout UUID, not an opaque row id; iOS now uses `nt.workout.active` for the same value), `nt.workout.discarding` (string set of workout ids whose delayed delete is pending), `nt.routines.inheritRest` (the one-time rest migration ran), `nt.rest.permissionAsked` (the exact-alarm prompt was shown), `nt.mock.paired`, `nt.aiConsent.google`, `nt.aiConsent.anthropic`, `nt.attendance.outbox` (JSON `[{"day":"yyyy-MM-dd","status":…}]`, updated in one DataStore edit), `nt.attendance.sweptThrough` (Long epoch day; iOS stores a start-of-day date), `nt.exerciseLibrary.version` (Int).

`RestTimerPrefs`: `nt.rest.endDate` (epoch millis), `nt.rest.total`, `nt.rest.exercise`, `nt.rest.next`, `nt.rest.workout`.

`SecureStore`: Android Keystore AES-256-GCM key (`setUserAuthenticationRequired(false)`, StrongBox when available) + Tink or `Cipher("AES/GCM/NoPadding")` with a per-record IV, ciphertext base64 in its own DataStore file. Holds `session` (JSON `Session`), `anthropic-key` and `gemini-key`. Excluded from Auto Backup in `data_extraction_rules.xml`; an undecryptable blob is treated as **signed out**, never as an error.

### Store failure — `app/StoreLoader`, `app/StoreErrorScreen`

The port of iOS `StoreLoader` / `StoreErrorView`. `AppContainer.load()` opens the store before `appState.load()`, so the first frame knows what to show; `RootScreen` composes `NtNavHost` only while `store.state` is `Open`, `StoreErrorScreen` when it is `Failed(message)`, and nothing while `Loading` (the pending-route consumer, which reads the database, is composed only over an open store). The screen: a 56 dp `emberTint` circle with `ExclamationTriangleFill`, `store.error.title` in `title1`, the body in `subheadline` ink2, the raw error selectable in `caption` ink3; pinned `PrimaryButton` Try again (`retryStore()`), `SecondaryButton` Save a copy of the data (copies of `NoTomorrow`, `-wal`, `-shm` into `cache/export/store`, shared through the existing `FileProvider`), and a `bad` text button Start with empty data behind an `NtActionSheet` confirmation (`startFreshStore()`: move the files into `databases/Store backups/<yyyy-MM-dd HH.mm.ss>/` — `" 2"` appended when the folder exists — set `hasOnboarded = false`, reopen and seed). Nothing is ever deleted or renamed automatically; debug builds behave the same.

## Services — `service/`

Each is an interface plus one production implementation, so features can be tested against fakes.

- **`Fmt` (`util/Fmt.kt`)** — the number/date contract, exact port of `Services/Formatters.swift`. `locale` is read **on every call** from the app locale (`AppCompatDelegate.getApplicationLocales()`), never cached. `weight(kg, unit, withUnit)` 0…1 fraction digits + **U+00A0** + unit, lb factor `2.2046226218`; `kcal` rounded Int with grouping + NBSP + `kcal`; `grams`; `volume`; `set(kg, reps)` → `"85 × 7"` with **U+00D7**; `clock(seconds)` → `"%d:%02d"` **not localized**; `duration`; `time(minuteOfDay)`; `longDay` / `shortDay` / `weekdayShort` / `dayMonth`; `relativeDay`; `countdown(to)`; `signedWeight` / `signedPercent`; `elapsed` (m:ss, h:mm:ss from one hour; header, rest sheet, mini bar); `dayTitle(date, strings, locale, today)` (Today / Yesterday / `shortDay`, plus the year outside the current one); `monthShort` (`LLL`); `percent`; `volume(kg, unit, withUnit)` (kg default); `mediumDate`. Plus `isoWeekday(date)` (Sunday→7) and `startOfIsoWeek(date)` — **the app is Monday-first regardless of locale**. Parsing (`util/Parsing.kt`) is the mirror image and always comma-tolerant: `decimal` strips spaces, NBSP, U+202F/U+2009/U+2007 and tabs, turns `,` into `.` and requires the whole text to match one number (iOS `NumberInput` is the same rule); `nonNegative` / `positive` build on it. Polish durations use "h" ("1 h 12 min", "za 2 h 5 min", "za 1 dn. 3 h"), and `StatTile` values shrink with `TextAutoSize.StepBased(0.6 × headline … headline)` instead of truncating.
- **`TargetCalculator`** — `bmr = 10*kg + 6.25*178 - 5*30 + 5`, activity 1.55, kcal adj +300/−400/0, protein 2.2/2.4/1.8 g/kg, fat 0.9 g/kg, carbs = rest, weight defaults to 80 when null/≤0. **`kcal = max(0, round(((maintenance + adj)/50)) * 50)` with HALF_AWAY_FROM_ZERO** — Swift's `Double.rounded()`; on JVM use `kotlin.math.round` (half away from zero for positives) and assert against the iOS test vectors.
- **`RecordService`** — exact PR rules. `completedSets(exercise)` = completed, `kind != warmup`, `reps > 0`. `previousSets(set)` = other working sets with `completedAt < cutoff`; **on an exact timestamp tie only sets in the same `WorkoutExercise` with a lower `order` count**. `evaluate`: no previous sets ⇒ `isPR = true, isSetRecord = false` (the first working set of an exercise is a PR); otherwise `isPR = e1RM > max(prev e1RM) || weight > max(prev weight)`; `isSetRecord` is computed **only when `!isPR`** — among previous sets at exactly the same weight, `reps > maxReps`. Epley: 0 when `reps<=0 || weight<=0`, `weight` when `reps==1`, else `weight * (1 + reps/30.0)`. Also `bestSet`, `heaviestSet`, `mostRepsSet`, `lastSet(exercise, excluding:)`, `e1RMHistory` (best per workout, dated at `startedAt`, oldest first), `lastPRDate`, `records(in workout)` (PRs first, then set records, each in row order). Because Room has no lazy graph, implement these with explicit joins — no N+1. **`rebuild(exerciseIds)`** re-derives `isPR`/`isSetRecord` for every set of those exercises as if each completed working set had been ticked in `completedAt` order (pure `rebuildFlags`, same tie rule as `evaluate`), writes only changed rows and clears flags left on open sets (`clearOpenSetRecords`). It runs on Finish and after a finished workout is edited or deleted.
- **`AttendanceService`** — pure derivation + writes. `currentWeek(schedule, records, today)` → Mon…Sun with `myState`/`partnerState`. `state(...)`: a record ⇒ its status verbatim, except **`confirmed` or `planned` on a day before today collapses to `missed`**; no record + not a gym day ⇒ `rest`; **no record on a past gym day ⇒ `rest`** (the sweep writes explicit misses); otherwise `planned`. `nextSession(schedule, today)` scans offsets 0…7; **today's session counts while it is at most 2 h (7200 s) in the past**. `markConfirmed` / `markAttended` / `markMissed(day, reason, note, makeUp, status = cancelled)` / `markPlanned(day)` (the server's make-up rule: an empty, cancelled or missed day becomes planned with reason/note/makeUp cleared; planned, confirmed and attended are left alone) / `clearMine(day)` / `upsert(day, participant, scheduledMinuteOfDay = schedule's, fallback 1080)`. `markPastPlannedAsMissed` is **incremental** through a `SweepCursor` (`nt.attendance.sweptThrough`): the first run only sets the cursor to yesterday; later runs judge `max(cursor + 1, startOfDay(profile.createdAt), today − 30) … yesterday` — my planned/confirmed record on **any** day → missed, a gym day with no row → a missed row inserted — and move the cursor to yesterday (monotonic, also with no schedule). A schedule change therefore never rewrites the past. `workoutDayChanges(oldDay, newDay, oldDayStillAttended, isGymDay, myStatus, today)` (pure): the new counted day → `MarkAttended` unless it is in the future or already attended; the old one, unless another finished workout with a completed set started that day → `MarkMissed` on a past gym day, else `Clear`. `applyWorkoutDayChange` writes and returns them; `wireStatus(change, isGymDay)` maps them to what the server should hold (attended/missed as written, a cleared gym day → planned, a cleared rest day → nothing, since the server has no delete). `sessionsTogether`, `attendedCount`/`missedCount` (last 30 days, missed **or** cancelled), `currentStreak` (count back from the most recent past non-planned/non-confirmed row, stop at the first non-attended). Label keys `weekday.mon` / `weekday.mon.short`, clamped 1…7. **There is no 21:00 timer here** — that is a backend cron.
- **`ExerciseLibrary`** — loads `assets/exercises.json` (876 records) and `assets/exercises_pl.json` (876 id→Polish-name pairs). `importIfNeeded()` (mutex + once-per-process flag) runs only when `needsImport(stored, libraryRows)`: the `nt.exerciseLibrary.version` stamp differs from `LIBRARY_VERSION` (1) **or** `ExerciseDao.libraryCount()` is 0. It inserts with IGNORE (custom rows and history survive), backfills `namePL` (`namePL == null && !isCustom`, limit 2000), and writes the stamp only after both. Any parse failure aborts silently and leaves the stamp unset. Bump `LIBRARY_VERSION` (and iOS `libraryVersion`) whenever either JSON file changes. The picker's search fold is lowercase with `Locale.ROOT`, NFKD minus combining marks, then `ł` → `l` ("lawka" finds "Ławka"). `MuscleGroup` chips → `primaryMuscles` sets exactly as iOS (`all: []`, `chest`, `back: [lats, middle back, lower back, traps]`, `legs: [quadriceps, hamstrings, glutes, calves, adductors, abductors]`, `shoulders`, `arms: [biceps, triceps, forearms]`, `core: [abdominals]`; note `neck` is deliberately in no chip). `localizedName` follows the **app locale override** (a deliberate improvement over iOS, which reads the system locale).
- **`RoutineSeeder`** — runs only when `routine` is empty; looks up the 15 ids in one query; **if none are found it does nothing** (retried after import). Push A / Pull A / Legs, order 0/1/2, ids exactly as `docs/architecture.md`. `defaultSets 3`, `defaultReps 8`, rest **`INHERIT_REST = 0`** ("use the profile default at start"). `restSeconds(id, defaultRest)` = the user's default (`profile.defaultRestSeconds`, 90 s without a profile), **+30 s when the lowercased id contains `squat`, `deadlift` or `bench_press`** (`isHeavy`), capped at 600 s — 1:30 / 2:00 at the stock default. `inheritDefaultRest()` is a one-time pass from `AppContainer.seed()` (`nt.routines.inheritRest`) that turns the legacy seeded 90/120 into 0; any other non-zero item value is kept.
- **`FoodSearchService`** — Open Food Facts, the port of the iOS actor. `User-Agent: NoTomorrow/0.1 (markzaluben@proton.me)`, `Accept: application/json`, request timeout 15 s, one `FIELDS` list for every request (`code,product_name,product_name_pl,product_name_en,generic_name,generic_name_pl,abbreviated_product_name,lang,brands,quantity,serving_size,serving_quantity,nutriments,nutriments_estimated,image_front_small_url,countries_tags`). **Text search** goes to search-a-licious, `GET https://search.openfoodfacts.org/search?q=<terms>&langs=pl,en|en&page_size=24&fields=<FIELDS>` (`searchALiciousHits`), where `searchTerms` turns Lucene operators into spaces (nothing left ⇒ no request); on a 5xx, timeout, 4xx or undecodable body it falls back once to the legacy `GET https://world.openfoodfacts.org/cgi/search.pl?search_terms=<q>&search_simple=1&action=process&json=1&page_size=24&fields=<FIELDS>&lc=<pl|en>` (`fallsBack`: never after `RateLimited`, `Offline` or a cancel). A `RequestWindow` lets at most 10 searches start per 60 s (a new one waits ≤ 6 s for a slot, else `RateLimited`), and a search 429 starts a cooldown (Retry-After clamped to 5–120 s, 60 s by default). Minimum query 2 chars; the 500 ms debounce belongs to the view model. **Barcode** `lookup(barcode, locale)` returns `sealed interface BarcodeLookup { Found(candidate), Partial(ProductStub), NotFound }`: it reads `GET https://world.openfoodfacts.org/api/v2/product/<code>.json?fields=<FIELDS>&lc=<app language>` for each of `barcodeForms` (digits only; 8/12/13/14 digits; a 13- or 14-digit code starting with 0 also without it; a 12-digit code also with `0` in front), returns `Found` at the first form with usable nutrition, keeps the **first** name-only stub across forms (a later form failing does not lose it) and ends `Partial` or `NotFound`. A 429 or 5xx is retried once after `retryDelayMs` (1.5 s). In-flight guard (duplicate key ⇒ `AlreadyInFlight`, released under `NonCancellable`) + LRU caches (capacity 40, TTL 300 s) keyed `"<lc>|<query>"` / `"barcode|<lc>|<code>"`; all three lookup results are cached. **Mapping** (`outcome`, `candidate`): the top-level `code` fills an empty product code; name chain `displayName` — pl: `product_name_pl` → `product_name` (only when `lang == "pl"`) → `product_name_en` → `product_name` → `generic_name_pl` → `generic_name` → `abbreviated_product_name` → "firstBrand quantity"; en starts from `_en` — trimmed, empty = missing; `per100` uses `nutriments` when it has energy (`energy-kcal_100g`, else kJ / 4.184) and otherwise `nutriments_estimated` (sets `FoodCandidate.isEstimated`, never persisted); the block used must pass kcal 0…950 and P/C/F 0…100, or the product becomes a stub. `brands` may be a string or an array (joined with ", "); brand = first comma-separated; `servingSizeG` from `serving_quantity` else parsed from `serving_size` (comma→dot, first number, label must contain `g` or `ml`); non-positive ⇒ null. Numbers may arrive as JSON strings. **Errors** (`FoodSearchError`): `RateLimited` (429), `Busy` (5xx), `Offline` (a transport failure while `isOnline()` says there is no internet — `ConnectivityManager`, injected; a `SecurityException` counts as online) → `error_network`, `Unreachable` (any other transport failure) → `fuel_search_error_network`; `messageRes(error)` gives each its own copy. Local helpers: `BarcodeKey` (`isValidGTIN`, `storageKey` for RCN, `localKeys`, `preferred`), `GTINExtractor` and `FoodMatch` — see the Fuel contract.
- **`AIEstimateService`** — `suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String, notes: String = ""): AIEstimate` and `suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading`; every failure is an `AIEstimateError` (cancellation is rethrown). `AIEstimateProviders.upload()` picks an `AIUpload` — `None` (demo data: nothing leaves the device), `Google` (standard, the backend), `Gemini` / `Anthropic` (BYOK, key in `SecureStore`) — and `service(upload)` builds `MockAIEstimateService` (1.2 s delay, per-meal v2 plates with counted portions and localized units, and the fixed "Serek wiejski" 97 / 11 / 2 / 5 label), `BackendAIEstimateService` (`BackendClient.estimate` / `readLabel`; no key pass-through, the backend answers `X-Anthropic-Key` with `byok_is_device_direct`), `DirectGeminiEstimateService` (Interactions API first, generateContent on 400/404, key in a header, 60 s) or `DirectAnthropicEstimateService` (Messages API, `output_config` JSON schema, 8192/4096 tokens, 90 s estimate / 75 s label, `stop_reason` checked). The BYOK services share one Ktor client (`AIDirectTransport`), build bodies from **`AIEstimateSpec`** — `backend/data/ai/estimate-spec.json`, packaged as an asset, parsed once per process on IO and validated (version 2, unique keys, limits, regexes) — then run `AIFinalizer.parseModelJson` and the **finalizer**, and ground up to 8 items that carry a barcode with Open Food Facts (`AIBarcodeGrounding`). `AIFinalizer` (`AIEstimateFinalizer.kt`) is a 1:1 port of `backend/src/aiFinalize.ts` (`finalizeEstimate`, `finalizeLabel`, `computeTotals`, `groundWithDatabase`, JSON repair); the backend runs the same code on the standard path, so all three paths answer alike. Model text, request bodies and the spec go through the ordered, strict `AIJson` (JS `JSON.parse` / `JSON.stringify` semantics — key order is part of the contract), never kotlinx's lenient reader. `AIEstimateError` = `Offline, Timeout, Busy, DailyLimit, NotAllowed, SignedOut, MissingKey, MissingGeminiKey, KeyRejected, Unreadable, ProviderError(status, serverMessage)`, each with its own `messageRes`; `labelMessageRes` shows `fuel.label.unreadable` for `Unreadable`. Backend mapping checks the code first (`ai_not_allowed` → NotAllowed, `ai_daily_limit` → DailyLimit, `ai_busy` → Busy, `ai_timeout` → Timeout, `ai_unparseable` or a decode error → Unreadable, `ai_upstream_error` / `ai_unavailable` → ProviderError), then the status (504 → Timeout, 429/503 → Busy, 401 → SignedOut, else ProviderError); an app-side timeout (`BackendError.TimedOut`) is `Timeout`, any other transport failure `Offline`. The v2 wire model (`net/dto/AiDto.kt`): `AIFood` gains optional `per100`, `portionCount`, `portionUnit`, `gramsPerUnit`, `nutritionSource`, `cooking`, `genericKey`, `barcode`, `adjustments` and the local-only `databaseFood` (`Item(id)` / `Candidate`); `AIEstimate` gains `version`, `totals`, `skipped`; `LabelReading` is new. The decoder stays tolerant (v1 answers still decode; `isGuess` now defaults to **false**; `overallConfidence` defaults to the item mean). Rescaling lives on `AIFood`: `scaled(toGrams)` (with `per100`: grams = `round1`, totals from per-100; without: proportional; a counted item keeps its count and `gramsPerUnit = round1(grams / count)`), `withCount`, `withGramsPerUnit`, `withNutrition`.
- **`ImageDownscaler`** — `PLATE_LONG_EDGE = 1024` (estimate) and `LABEL_LONG_EDGE = 1600` (label read). `jpeg(uri, maxLongEdge, quality = 80)` for library picks (blocking, call on IO) and `jpeg(bytes, orientationDegrees, maxLongEdge)` for camera captures: bounds first, a sub-sampled decode, then scale → rotate → encode (scaling first keeps the rotation copy target-sized). Work in pixels; factor `maxLongEdge/longEdge` when larger; target dimensions **floored**; abort if either < 1. Honour `ExifInterface` rotation **before** re-encoding (which strips EXIF including GPS). Opaque `ARGB_8888`, `compress(JPEG, 80)`. `CameraCaptureScreen` binds `ImageCapture` at 2048×1536 (closest lower, then higher) and downscales on the capture executor, never as a full-resolution bitmap.
- **`HealthService`** (`feature/health` + `service/HealthService.kt`) — Health Connect. Availability via `HealthConnectClient.getSdkStatus()`. Writes: `WeightRecord`, `NutritionRecord` (one per meal entry, with `energy`, `protein`, `totalCarbohydrate`, `totalFat`, `name`, `mealType`), `ExerciseSessionRecord(EXERCISE_TYPE_STRENGTH_TRAINING)`, and **`ActiveCaloriesBurnedRecord`** over the session's time range (iOS writes `activeEnergyBurned` inside the `HKWorkoutBuilder`; do not drop it). Reads: `WeightRecord` latest. Errors map to `health.error.unavailable|notAuthorized|saveFailed`. Manifest permissions, rationale Activity and `activity-alias` per research §6.4. Every call must be a no-op that returns a typed failure when the SDK is unavailable — features never crash on a device without Health Connect.
- **`WorkoutSessionController`** — the single source of truth for the workout in progress (iOS parity), over an injectable `Store` (DataStore in the app, a map in tests; writes run in order under a mutex). State: `activeWorkoutId` (persisted to `nt.activeWorkoutId`), `showsActiveWorkout` (expanded vs collapsed), `wantsRestSheet`, `showsSummary`, the discarding set (`nt.workout.discarding`), and `isWorkoutInProgress` (active and not on the summary) as a flow and a synchronous value. `workout()` resolves the id with no side effects; `activeWorkout()` also adopts the newest unfinished workout, never a discarding one and never while a summary shows. `begin(id)`, `expand(restSheet)`, `collapse()`, `end()`, `discard(id)` (lets go at once, deletes 700 ms later; a pending discard is finished on the next launch), `restore()` and `repairOrphans()` (keep the session's unfinished workout, else the newest; the others end at their last `completedAt` when they have completed sets and are deleted when not; a workout killed on its summary is let go). The view model is keyed per workout, so it — not the session — owns the model state (see the Workout contract).
- **`RestTimerController`** — see "Rest timer" below.
- **`BroService`** and **`AttendanceSync`** (`AttendanceReporter`, `AttendanceOutbox`) — see the Bro contract.

## Navigation — `nav/`

Compose Navigation with a sealed `NtRoute`. Route names are part of the contract; do not rename them.

```
"root"                              RootScreen: onboarding vs tabs, decided by appState.hasOnboarded

onboarding graph  "onboarding"
  "onboarding/welcome"              WelcomeScreen
  "onboarding/you"                  SetupYouScreen
  "onboarding/schedule"             SetupScheduleScreen
  "onboarding/pair"                 SetupPairScreen

main graph  "main"                  MainTabScaffold (NtTabBar + a NavHost per tab)
  "today"                           DashboardScreen
  "train"                           TrainScreen
  "fuel"                            FuelHomeScreen
  "progress"                        ProgressHomeScreen
  "progress/exercise/{exerciseId}"  ExerciseProgressScreen
  "bro"                             BroScreen

full-screen destinations, hosted by the ROOT NavHost (not a tab), so they open from any tab
  "workout/active"                  NOT a destination any more (the name is kept in NtRoute): the
                                    workout is a layer of MainTabScaffold, see below
  "fuel/camera"                     CameraCaptureScreen

settings graph  "settings"          modal, its own NavHost
  "settings"                        SettingsScreen
  "settings/name" | "settings/bodyWeight" | "settings/dailyTarget" | "settings/schedule"
  "settings/restTimer" | "settings/units" | "settings/language" | "settings/notifications"
  "settings/health" | "settings/export" | "settings/ai" | "settings/partner"
```

The onboarding flow is a **state machine, not a back stack**: step order is mutable (`startStandard()` → `[you, schedule, pair]`, `startWithPair()` → `[pair, you, schedule]`), rendered by an `AnimatedContent` over `model.step` with the asymmetric transition from the iOS spec. `ActiveWorkoutScreen` swaps to `WorkoutDoneScreen` **in place** (not a new destination) with a `move(trailing)+opacity` transition, and offers an explicit "Edit sets" path back.

**The workout is a layer of the tab shell, not a destination.** `MainTabScaffold` hosts one `ActiveWorkoutViewModel` per workout (`ntViewModel(key = "activeWorkout/<id>")` in the `main` entry's store) and draws `ActiveWorkoutScreen` over the tabs when `session.showsActiveWorkout`, or the mini bar above the tab bar when collapsed; both read the same view model, so collapsing keeps the open exercise, PR hint, up next, the summary and the scroll position (the `ScrollState` is hoisted). Expanding slides the layer up for 300 ms inside the backdrop recording (glass menus and dialogs over it stay real glass) while the tab bar and mini bar fade and drop 40 dp; once it covers, the tabs are no longer placed and their back handlers are off. Collapse, Done and Discard slide it down; the layer keeps the last workout id and a frozen model so it never blanks while leaving. Other view models in that shared store use prefixed keys (`workoutDone/<id>`, `picker/<id>`, `workoutDetail/<host>`). A process killed while the workout was full screen comes back full screen (`rememberSaveable`); a fresh cold start shows the mini bar only, as on iOS.

`appState.pendingRoute` (`restTimer | activeWorkout | bro | settings`) is consumed by `RootScreen`'s `PendingRouteConsumer`, composed only over an open store. Routes arrive as the launch-intent extra `NtPushIntents.EXTRA_ROUTE` (`app.notomorrow.push.route`) from push notifications and from the rest notifications (the running countdown → `restTimer`, "Rest is over" → `activeWorkout`, separate request codes); there is **no** `notomorrow://` intent filter on Android (iOS has the URL scheme). `MainActivity` (`singleTask`) removes the extra once consumed and never replays a route on a restored or Recents launch. `restTimer` / `activeWorkout` adopt the running workout, then `expand(restSheet = route == restTimer && rest running)` over the current tab (the sheet opens 350 ms later); `bro` selects Bro; `settings` selects Today and sets `appState.showsSettings` (iOS only selects Today).

Predictive back is on (targetSdk 36): use `BackHandler`, never `onBackPressed`. Sheets are `NtSheet`, not nav destinations.

## Localization and strings

**The catalog is the single source of truth and the Android resources are generated.** `scripts/xcstrings_to_android.py` reads `NoTomorrow/Resources/Localizable.xcstrings` (669 keys, en + pl) and writes:

```
android/app/src/main/res/values/strings.xml       (en)   667 <string> + 2 <plurals>
android/app/src/main/res/values-pl/strings.xml    (pl)
android/strings-map.json                          iOS key -> Android resource name
```

Run it after every catalog change. **Never hand-edit the generated files.** Naming rule:

| iOS key | Android name |
|---|---|
| `dashboard.nextSession` | `dashboard_nextSession` |
| `progress.prOn %@` | `progress_prOn_s` |
| `in %lld h %lld min` | `in_n_h_n_min` |
| `bro.together %lld %lld` | `bro_together_n_n` |

Every `%lld`/`%d` in the key becomes `n`, every `%@` becomes `s`, other non-`[A-Za-z0-9_]` runs collapse to `_`, a leading digit gets a `k_` prefix. Format specifiers convert `%lld`/`%ld`/`%d` → `%d`, `%@` → `%s`, `%.1f` kept, `%1$lld` → `%1$d`; strings with more than one non-positional specifier are auto-indexed (aapt2 rejects mixed forms). Plural variations become `<plurals>` with `one/few/many/other`.

Consequences for feature code:

- iOS's four lookup styles all collapse to one: `stringResource(id)` / `stringResource(id, arg1, arg2)` / `pluralStringResource(id, count, count)`.
- Runtime-built iOS keys (`"meal." + rawValue`, `"cant.reason.\(rawValue)"`, `"goal.\(rawValue)"`, `"muscleName.\(slug)"`, `"equipment.\(slug)"`, `"weekday.\(suffix)"`, `"weekday.\(suffix).short"`, `"workout.exerciseCount.\(one|few|many)"`, `AttendanceService.labelKey`, `upload.providerNameKey`) become **exhaustive `when` maps to `R.string.*` ids** in `util/NtStrings.kt`. No reflection, no `getIdentifier`.
- `WorkoutStrings.pluralSuffix` (the hand-rolled Polish CLDR helper) is **replaced by real `<plurals>`** for `workout.exerciseCount`, `workout.setCount` and `fuel.streak`; add those plural entries to the catalog rather than reimplementing the helper.
- `MacroBar`'s `"<v> / <g> g"` string is **not localized on iOS**; keep it unlocalized for parity.
- If a feature needs a key that is not in the catalog, add it to `res/values/strings_<feature>.xml` **and** `res/values-pl/strings_<feature>.xml`, then raise it so the key is back-ported into `Localizable.xcstrings` and the file is deleted. Feature agents must not touch the generated files.

**Language override.** `AppLocale.effective(languageOverride)` keeps the device **region** and swaps only the language — "English" on a Polish phone is `en-PL` (24-hour clock, decimal comma). Apply with `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("$lang-$region"))`, empty list for "System". `MainActivity` must extend `AppCompatActivity`; declare `android:localeConfig="@xml/locale_config"` listing `en` and `pl`; on API 32 and below add the `AppLocalesMetadataHolderService` entry and call with the Activity context. The call recreates the Activity, so **drop the iOS "relaunch" hint from Settings**. `Fmt` and every `uppercase()` read the same locale on every call.

## Assets and resources

- `assets/exercises.json` (876 records) and `assets/exercises_pl.json` — already present; parsed by `ExerciseLibrary` with kotlinx-serialization (`ignoreUnknownKeys = true`; `images` is parsed and discarded).
- `res/font/bigshouldersdisplay_extrabold.ttf` — already present, the same static face iOS ships. Do not substitute a variable Big Shoulders download; the Google Fonts family was redrawn.
- `res/font/robotoflex_variable.ttf` — the system-font stand-in (OFL). Declare weights on `Font(...)` and set `fontSynthesis = FontSynthesis.None`.
- Icons: `res/drawable/ic_*.xml`, exported from Material Symbols Rounded (Apache-2.0) at the right `wght`/`FILL`, plus hand-drawn vectors (`flame`, `target`, `barcode_viewfinder`, `calendar_badge_minus`, `trophy`, `trophy_fill`, and for Fuel, the AI editor and the store error screen `calendar`, `plus_square_on_square`, `scalemass`, `exclamation_triangle_fill`). ⛔ Never ship SF Symbols or path data traced from them; trace from `design/*.dc.html`.
- Colours live in `NT.kt`, not `colors.xml`. `themes.xml` carries only `Theme.Material3.Dark.NoActionBar` + `android:forceDarkAllowed=false` + a `ground` window background.

## Rest timer — `rest/`

`docs/architecture.md`'s non-negotiable survives: **state is `RestTimerController` only; the UI never runs its own timer.** Four independent pieces, then the end-of-rest policy and the one-time exact-alarm prompt.

1. **State.** `RestTimerPrefs` holds an absolute `endAt` epoch-millis plus `totalSeconds`, `exerciseName`, `nextSetLabel`, `workoutName`. `RestTimerController` exposes `StateFlow`s and derives `isRunning = endAt > now`, `remaining`, `progress = 1 - remaining/total`. API: `start(seconds, exerciseName, nextSetLabel, workoutName)` (`total = max(5, seconds)`, `endAt = now + total`, persist, schedule, notify, `Haptics.tap()`), `adjust(delta)` (`endAt = max(now+1s, endAt+delta)`, `total = max(5, total+delta)`, same side effects), `skip()` (clear, cancel alarm, cancel notification, no haptic), `finishIfElapsed()` (only when `endAt <= now`: clear, cancel, `Haptics.success()`), and `restore()` on init (discard a past `endAt`, cancel a stray notification).
2. **Rendering.** `rememberSecondTicker()` in `designsystem/` — a `produceState` that sleeps `1000 - (now % 1000)` so it stays wall-clock aligned, wrapped in `repeatOnLifecycle(STARTED)`. Seven call sites replace the seven `TimelineView`s (5 × 1 s, 2 × 60 s). Never accumulate elapsed time; always recompute from `endAt`.
3. **Notification** (`RestTimerNotifier`). One ongoing `NotificationCompat` on channel `nt.rest` (`IMPORTANCE_LOW`) with `setUsesChronometer(true)`, `setChronometerCountDown(true)`, `setWhen(endAt)`, `setOngoing(true)`, `setOnlyAlertOnce(true)`, `CATEGORY_ALARM`, title `exerciseName`, text `nextSetLabel`, and two actions (`timer.plus15`, `timer.skip`) as `PendingIntent.getBroadcast(..., FLAG_IMMUTABLE)` into `RestTimerReceiver` — **never through an Activity**. Posted once per state change, not per second: the system renders the countdown. A second channel `nt.rest.done` (`IMPORTANCE_HIGH`, sound) delivers the end alert, the analogue of iOS's `interruptionLevel = .timeSensitive`. ⚠️ From Android 14 the user can swipe an ongoing notification away — **dismissal must not cancel the alarm**.
   Live Update promotion (progressive enhancement, API 36 only): `NotificationCompat.ProgressStyle` with one segment coloured `ember`, `setShortCriticalText("REST")`, `setRequestPromotedOngoing(true)`, and the `POST_PROMOTED_NOTIFICATIONS` manifest permission. Feature-detect with `canPostPromotedNotifications()`. The timer must be correct without it.
4. **Alarm** (`RestAlarms`). `setExactAndAllowWhileIdle(RTC_WAKEUP, endAt, pi)` when `Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()`, else `setAndAllowWhileIdle` (degraded). ⚠️ `setExact()` is **not** permission-free — it throws `SecurityException` for the same reason. Declare `SCHEDULE_EXACT_ALARM`, **never `USE_EXACT_ALARM`** (Play restricts it to alarm/timer/calendar apps, reviewed, and this app does not qualify). Listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to re-arm on grant, and surface the denied state in Settings. `BootRescheduleReceiver` (`RECEIVE_BOOT_COMPLETED`) re-arms the rest timer from persisted state after a reboot — iOS gets this free and Android does not. No foreground service, no full-screen intent.

5. **Rest end and taps.** A shell-level `RestExpiryWatcher` in `MainTabScaffold` calls `finishIfElapsed()` on the 1 s ticker, so a rest that runs out while the workout is collapsed still ends. `RestTimerController.connect(isAppInForeground, isWorkoutOnScreen)` feeds one policy, `restEndAlert`, used by both the watcher and `handleAlarmFired`: workout full screen → haptic only; app open but collapsed → haptic plus the "Rest is over" heads-up with sound; app not visible (`AppVisibility`, counted in `MainActivity.onStart/onStop`) → the notification alone. A delivered "Rest is over" is cleared when the next rest starts and on Skip. The ongoing countdown has `setTimeoutAfter(endAt − now)`, so it never counts negative while a late alarm is pending. Its content intent carries the `restTimer` route and the "Rest is over" one `activeWorkout` (see Navigation).
6. **Exact-alarm prompt** (`feature/workout/RestAlertPermissionPrompt`). The first time a rest starts in a workout with exact alarms or notifications off, one `NtAlert` asks once (`timer.exactAlarm.*`, Android-only catalog keys; remembered in `nt.rest.permissionAsked`): Allow requests notifications first, then opens "Alarms & reminders" (the app's notification settings when notifications were denied for good); Not now never asks again. The inexact fallback stays, and `ExactAlarmStateReceiver` re-arms the running rest when the permission is granted.

Auto-start rule (from `ActiveWorkoutModel.swift:143-152`): when a set is ticked, start rest **unless** the next set is a drop set or `nt.rest.autoStart` is false (default true).

`Haptics` (`util/`): `tap()` → `HapticFeedbackType.ContextClick`, `success()` → `Confirm`. Grab `LocalHapticFeedback.current` in composition, fire in the event callback (never in a composable body). Eight call sites: set tick, workout finish, rest start, rest ±15, rest complete, barcode recognised, AI foods logged, sign-in success. iOS now also plays `warning()` for a refused 0-rep tick and a failed store retry; Android uses `HapticFeedbackType.Reject` there (and `Confirm` for a store that reopened). Fuel's user day changes and the day stepper use `SegmentTick` (iOS `.selection`), the meal-entry long press `LongPress`.

## Backend client — `net/`

`BackendClient` is an interface with exactly the iOS surface. Two implementations: `RemoteBackendClient` and `MockBackendClient`. `AppConfig.makeBackendClient()` caches one instance per process and rebuilds it only when `useMockBackend` flips or `backendBaseURL` changes — so the Settings "Use demo data" toggle needs no relaunch, exactly as on iOS.

```kotlin
interface BackendClient {
    val baseUrl: Url
    suspend fun signInApple(identityToken: String, authorizationCode: String): Session
    suspend fun signInGoogle(idToken: String): Session
    suspend fun signIn(username: String, password: String): Session
    suspend fun register(username: String, password: String, email: String?): Session
    suspend fun logout(refreshToken: String)
    suspend fun me(): Me
    suspend fun updateMe(locale: String?, timeZone: String?)
    suspend fun createPairCode(): String
    suspend fun pair(code: String): Partner
    suspend fun unpair()
    suspend fun pushSchedule(schedule: ScheduleDto)
    suspend fun partnerState(): PartnerState
    suspend fun setAttendance(day: LocalDate, status: AttendanceStatus,
                              reason: String?, note: String?, makeUpDay: LocalDate?)
    suspend fun sendHeadsUp(kind: HeadsUpKind, text: String, sessionDay: LocalDate)
    suspend fun registerPushToken(token: String, platform: String = "android")   // iOS sends hex Data
    suspend fun unregisterPushToken(token: String)
    suspend fun estimate(imageJpeg: ByteArray, meal: MealSlot, locale: String,
                         anthropicKey: String? = null, notes: String = ""): AIEstimate
    suspend fun readLabel(imageJpeg: ByteArray, locale: String): LabelReading   // legible=false is a 200
    suspend fun deleteAccount()
}
```

Endpoints, unchanged from iOS (`baseUrl` default `https://notomorrow-api.fly.dev`; a stored legacy `https://api.notomorrow.app` is discarded on read):

| Method | Path | Auth | Body |
|---|---|---|---|
| POST | `auth/apple` \| `auth/google` \| `auth/login` \| `auth/register` \| `auth/logout` | none | as iOS |
| GET / PATCH / DELETE | `me` | bearer | `{locale?, tz?}` on PATCH |
| POST | `pair/code` → `{code}` · POST `pair` `{code}` → `Partner` at top level · DELETE `pair` | bearer | |
| PUT | `schedule` | bearer | `ScheduleDto` (`overrides` serialised with **String** keys) |
| GET | `partner/state` | bearer | → `PartnerState` |
| PUT | `attendance/<YYYY-MM-DD>` | bearer | `{status, reason?, note?, makeUpDay?}` |
| POST | `headsups` | bearer | `{kind, text, sessionDay}` |
| POST | `push/token` | bearer | `{token, platform: "android"}` |
| POST | `ai/estimate` | bearer | multipart `meal`, `locale`, `notes` (≤ 1500 UTF-16 units, `MAX_NOTES_LENGTH`), file `image` (`plate.jpg`, `image/jpeg`), **timeout 90 s**; v2 answer (see `docs/backend.md`) |
| POST | `ai/label` | bearer | multipart `locale`, file `image` (`label.jpg`), **timeout 75 s** (`AI_LABEL_TIMEOUT_MS`); shares the daily AI quota |

`RemoteTransport` rules, ported exactly:

- Every request sends `Accept: application/json` and **`User-Agent: NoTomorrow/0.1 Android`**.
- Day fields on the wire are `YYYY-MM-DD` in the device calendar. Instant decoding tries ISO-8601 internet date-time, then with fractional seconds, then bare `YYYY-MM-DD` as local midnight. Encoding is plain ISO-8601 without fractional seconds (the server emits `…Z` with no millis).
- **The 401 dance.** A `required`-auth request with no stored session throws `unauthorized` **without touching the network**. On 401: one coalesced refresh + one retry; a second 401 wipes the session and throws `unauthorized`. `TokenRefresher` is a `Mutex`-guarded single-flight: if the stored access token already differs from the failed one, return the current session with no round-trip; otherwise share one in-flight refresh across all callers. `POST auth/refresh` returning **400 or 401 ⇒ rejected (drop the session); anything else ⇒ unreachable (keep it, surface `network`)**. This matters: replaying a rotated refresh token revokes the whole family server-side.
- Non-2xx decodes `{error, message}`; code falls back to `http_<status>`, message to `error` then `HTTP <status>`.
- `BackendError`: `Unauthorized`, `Network`, `TimedOut`, `Server(String)`, `Http(status, code, message)`, `Decoding`, localized through `error.unauthorized|network|server|decoding` (`TimedOut` reuses `error.network`; `isTimeout(e)` walks the cause chain for Ktor/`java.net`/OkHttp timeouts, and only the AI flows tell it apart, as `fuel.ai.error.timeout`). The full server error-code list is in `docs/android-research.md`; treat any unknown code as `Server`.

`MockBackendClient` ports the offline demo verbatim: partner "Tomek" (`mock-tomek`, code `NT-7K4Q`), `nt.mock.paired` persists pairing, any 7-char code pairs, partner history = every gym day in the last 21 days attended **except index 2 of the descending list which is `missed` with reason `sick` and note `mock.partner.sickNote`**, today `planned` until confirmed, partner auto-confirms **20 s** after you confirm, replies **10 s** after a `cantMakeIt` heads-up with `mock.partner.reply`, `estimate` sleeps 1.2 s then returns the fixed 4-item plate (deliberately still the v1 shape, standing in for an old server; demo mode uses `MockAIEstimateService` instead), and `readLabel` returns the mock label after 1.2 s.

**Push registration** (`push/`): `NtMessagingService : FirebaseMessagingService` handles `onNewToken` and message receipt; `PushRegistrar` calls `registerPushToken` after sign-in and on token rotation. `POST_NOTIFICATIONS` is a runtime permission on API 33+ — request it in the onboarding Schedule step beside the reminder toggles, and create the channels on first **foreground** launch (a channel created in the background, which the FCM SDK will do on receipt, cannot show notifications). Data payload contract: `kind` ∈ `heads_up | partner_confirmed | partner_cancelled | pair_accepted`, plus `sessionDay`, `partnerName`, `text`. Notification copy is localized **server-side** from `users.locale` (`backend/src/i18n.ts`), so the client only routes.

⚠️ The backend is APNs-only today and needs a bounded change before Android push works at all (token regex, `toLowerCase()`, the `environment` filter, the `push_unavailable` gate). See `docs/android-research.md` §7.8. Also append the Android Web client id to `GOOGLE_CLIENT_IDS` before testing Google sign-in.

## Feature contracts

Behaviour is identical to `docs/architecture.md`. Each entry below states the Android composables, the view-model surface and the Android-specific details.

### Onboarding (`feature/onboarding`) — `OnboardingFlow`

`OnboardingFlow` = `AnimatedContent(model.step)` over `WelcomeScreen`, `SetupYouScreen`, `SetupScheduleScreen`, `SetupPairScreen` with `(slideInHorizontally { ±it } + fadeIn()) togetherWith fadeOut()`, `easeInOut28`. Step order mutable (`startStandard` / `startWithPair`); `stepCount = 3`.

`OnboardingComponents.kt` in this package: `ObProgressHeader` (back `arrow_back` 20 sp in 44 dp, N capsule segments 4 dp tall spacing 6 — `ink` for `i <= index` else `surface2`, `easeOut20` — plus `onboarding.step %lld %lld` footnote `.tabular()`; leading pad `screenH - 10`), `ObStepScaffold` (header + scroll + bottom bar on `ground`, footer pad top 12 / bottom 8), `ObStepTitle` (`title1` + `subheadline` ink2, top pad 28), `ObDayToggle` (44 dp circle with the day letter in `headline` + caption below, `easeOut15`), `ObTimeWheel` (**164 dp** tall, v-pad 6, `surface` at 22 dp; the settings variant is **180 dp** — do not derive the row height by dividing, measure it. `NtTimeWheel` is intrinsically `NtWheelRowHeight × NtWheelVisibleRows` = **32 × 7 = 224 dp**, the port of `UIDatePicker`'s 216 pt intrinsic size, so both frames deliberately crop it — **the container must clip**), `ObBigAvatar` (72 dp, `title1`, 3 dp `ground` ring, `HStack` overlap −14 dp).

Welcome: wordmark `display(132)` ember "NO" over `display(100)` ink "TOMORROW" (`minFontSize` at 0.5×), tagline, language segmented control writing `appState.languageOverride` — which must update the preview **live** (the app locale is applied immediately, so no `transformEnvironment` equivalent is needed).

You: name field (`.words` capitalisation, `ImeAction.Next`), body weight (`KeyboardType.Decimal`), goal chips (`OBL10n` has no analogue — read the chip titles from resources under the app locale), suggested target card (`display(56)` kcal, `easeOut20` on change) opening `ObTargetEditorSheet` (`NtSheet`, medium, own `Grabber`, four `KeyboardType.Number` fields).

Schedule: 7 day toggles Mon-first, **long-press 400 ms** on a day opens `ObDayTimeSheet` (per-day time override) with hint `onboarding.schedule.holdHint`, wheel time picker, two reminder toggles. Request `POST_NOTIFICATIONS` here.

Pair: code card `display(56)` with `.tracking(2.2)` (2.2 → `0.0393.em`), share (`ShareCompat`) and copy (icon swaps `content_copy` → `check` with a scale+fade `AnimatedContent`), "or enter your bro's code" (`KeyboardCapitalization.Characters`, `ImeAction.Go`), Continue, "Not now".

`finish()` writes nothing until the end: insert `UserProfile` + `GymSchedule` + optional `BodyWeightEntry`, seed routines, push the schedule when paired, then set `hasOnboarded = true`. `OnboardingViewModel` constructs its **own** `BroService` instance (mirroring iOS), not the singleton.

### Dashboard (`feature/dashboard`) — `DashboardScreen`

Keyed on the current day and rebuilt at midnight: observe `Intent.ACTION_DATE_CHANGED` (plus a resume check) and re-key the screen, replacing iOS's `.id(day)` + `NSCalendarDayChanged`. All eight day-scoped queries live in `DashboardViewModel` and take `day` as a parameter.

Content: eyebrow date + `dashboard.today` `largeTitle` + `Avatar(38.dp)` in a 44 dp hit box opening the Settings sheet. `WeekStrip` (Mon–Sun of the current ISO week): `Column(spacing 8)` = letter (`caption`, `ink` when today else `ink2`) → 36 dp state circle → 5 dp gym dot (`ember` or transparent). Today = filled `ink` circle + `subheadlineBold` `onPrimary` day number `.tabular()`; attended = 1.5 dp `ember` ring + `check` 14 sp Bold; missed/cancelled = same in `bad` with `close`; rest/planned/confirmed = `subheadline` ink2 day number.

`NextSessionCard` (`NTCard`): eyebrow + routine name; hero time `display(64)` `.tabular()` `minFontSize 0.6×`; relative day + countdown, both re-rendered by the **60 s** ticker; bro row as the `(isIn, partnerIsIn)` 2×2 state machine; button row as the 4-way branch — active workout → Primary "resume" (expands the running workout over Today; nothing on Today switches tabs any more, and a start goes through `WorkoutStarter`); session not today → Secondary "start"; already in → Primary "start"; else → Primary "I'm in"; plus Secondary "can't make it" whenever `sessionIsToday && !isOut`. Both at height 52. Rest day → `bedtime` 15 sp ink2 and the next gym day. Partner avatars are 28 dp with a 2 dp `good`/`bad`/`surface` ring, overlapped −8 dp.

`FuelSummaryRow`: 64 dp `ProgressRing` (lineWidth 6) with a 14 sp Bold `.tabular()` number (`minFontSize 0.7×`) and an **8 sp SemiBold, `0.0750.em` tracking, uppercase** "LEFT" micro-label — a hand-rolled mini-eyebrow, smaller than `NT.Fonts.eyebrow`; three `MacroBar`s (protein filled `ink`, others `ink2`). Tapping calls `appState.openFuelToday()`: Fuel opens on today, silently.

`LastSessionRow`: name · duration · "n PRs" ember + wrapping PR chips (32 dp, `trophy_fill` 12 sp ember + `footnote` `.tabular()`). Tapping opens that workout's detail sheet over Today (`WorkoutDetailPresenter(host = "today")`); with no finished workout it switches to Train.

Bro state refreshes every **15 s** via a `LaunchedEffect` loop; `isPaired` returns false when `authStore.needsSignIn`. `markPastPlannedAsMissed` (the incremental sweep) runs on appear. The current-day observer `rememberCurrentDay()` now lives in `util/CurrentDay.kt` (`ACTION_DATE_CHANGED`, `TIME_CHANGED`, `TIMEZONE_CHANGED`, plus `ON_RESUME`), shared with Fuel. "Can't make it" opens `CantMakeItSheet` (Bro package).

### Train / Workout (`feature/workout`)

`TrainScreen`: `SectionHeader(workout.routines)` + routine rows (subtitle `"5 exercises · Bench, Press, Raise"` — first three names, `maxLines 2`) with a white `StartPill` (44 dp, `subheadlineBold`, h-pad 18); `GhostButton(workout.startEmpty)`; `SectionHeader(workout.history, count)` + history rows opening `WorkoutDetailSheet` (`NtSheet`, **handle shown**) through `WorkoutDetailPresenter(host = "train")`. There is no resume banner any more (`ResumeWorkoutBanner` is deleted): a workout in progress is the mini bar.

**Starting** goes through `WorkoutStarter` from both Train and Today. `gate` allows one workout at a time: a Start or "Start empty" while one runs shows `AlreadyActiveDialog`, an `NtActionSheet` with title `workout.inProgress`, message `workout.alreadyActive.message`, Resume (`dashboard.resumeWorkout`), Discard it and start new (`workout.alreadyActive.discardAndStart`, destructive, only when the running workout has 0 completed sets — `discardAndStart` refuses otherwise) and the implicit Cancel; Today's CTA expands the running one without asking. Nothing switches tabs. Rows copy the same row of the last *finished* session of each exercise (`templateSets`); a never-done exercise gets the routine's `targetReps` at 0 kg. Today's suggestion is the routine after the last finished workout whose name matches a routine (`observeLastRoutineWorkoutName`). Rest per row: see `RoutineSeeder` (profile default, heavy + 30 s, 0 = inherit); exercises added mid-workout get the default too.

**Mini bar** (`WorkoutMiniBar`, stateless, hosted by `MainTabScaffold` via `WorkoutMiniBarHost` while `isWorkoutInProgress`): a Liquid Glass capsule drawn outside the recorded page like `NtTabBar` (flat fill on tiers without capture), 52 dp (`NT.Size.cardButton`) tall, 20 dp side margins, 8 dp above the tab bar; `LocalTabBarHeight` grows by the bar and its gap while it shows, so every screen, and Fuel's add bar, clears it. Leading an 8 dp ember dot, or a 24 dp `ProgressRing` (3 dp) while resting; line 1 the name (`subheadlineBold`); line 2 (`footnote` ink2, tabular) `Fmt.elapsed · current exercise`, or `timer.rest` + m:ss in ember · up next; trailing a rotated `chevron.down`, or while resting a 32 dp `common.skip` ink capsule (44 dp hit area) that calls `restTimer.skip()`. Tapping elsewhere expands. TalkBack: label `workout.miniBar.label` (name, `Fmt.duration`), state = exercise or rest, click label `workout.miniBar.hint`. Hidden on the summary and while the IME is up over the tabs.

`ActiveWorkoutScreen(model, scroll, onMinimize)` (the shell's workout layer): header with a leading `MinimizeButton` (`chevron.down` in a 36 dp `surface2` circle, 44 dp hit area pulled 4 dp into the margin, `workout.minimize`; not on the summary) + routine name + 1 s-ticker elapsed `Fmt.elapsed` + ember dot + "Exercise i of n". System back and predictive back (`PredictiveBackHandler`, the screen sinks and shrinks with the gesture) collapse; back on the summary means "Edit sets". The rest sheet a route asks for (`session.wantsRestSheet`) opens 350 ms after expanding; `Finish` (surface2, 44 dp) → `NtActionSheet` `workout.finishConfirm` with `workout.finish`, `workout.discard` (destructive, **only when `completedSetCount == 0`**), `common.cancel`. iOS 26 **does not draw the cancel row** for an inline confirmation dialog (`docs/android-glass.md` §1.7, measured on `17-confirmationdialog-finish.png`), so `NtActionSheet` filters it out and an outside tap cancels; keep passing it so the Kotlin reads like the Swift. One exercise expanded at a time (`expandedExerciseId`, `easeInOut20`); collapsed rows are 60 dp. Column header widths are load-bearing: **Set 36 / Previous flexible / kg 60 / Reps 60 / check 48** (the weight header shows the unit, kg or lb). Set rows 44 dp: set-number cell (36×44) opening a dropdown menu (`workout.warmup|dropset|failure|normalSet`, a divider, then the destructive `Usuń serię` / Delete set, which renumbers the rest and clears that row's PR hint), previous ghost value, two 60×44 `BasicTextField`s (`KeyboardType.Decimal` for weight, `Number` for reps) whose border is `ink` 1.5 dp when focused, `border` when it is the current (first uncompleted) row, transparent otherwise; a 28 dp check in a 48 dp column. Completed rows render at `alpha 0.55`.

Previous comes from the newest other **finished** `WorkoutExercise` entry of the exercise with a completed working set: warm-up rows take its warm-ups in order, other rows its working set with the same number, and past the end a working row takes the most recent completed working set and a warm-up nothing. `prefillFromPrevious` (model-side, on each row's appearance) fills only open rows' empty cells. Previous, "Last" and the unit reload every time the full screen appears (history may have been edited meanwhile); the caches hold values, never rows. Set writes run in call order under a `Mutex`.

Ticking a set: `valuesToLog` applies the Previous fallback; if reps are still 0 nothing is logged (Reject haptic, focus to the reps cell, reported through `complete(setId) { logged -> }`); otherwise stamp `completedAt = now` → `RecordService.mark` → on a PR/set-record with a `bestBefore`, show the ember `workout.beatsBest %@ %@` hint (`spring085`, `scaleIn(0.9, origin start) + fadeIn`) → auto-start rest unless the next set is a drop set or `nt.rest.autoStart` is false. `Haptics.tap()`. Add set duplicates the last row. `+ Add exercise` is a `GhostButton` with `add`.

Focus: a single `FocusRequester` map keyed by `(setId, isReps)`, hoisted in `ActiveWorkoutScreen` and passed down — the analogue of the shared `@FocusState`. Clear focus before the Finish dialog, before completing a set, and before finishing. iOS's keyboard toolbar "Done" has no Compose equivalent; use `ImeAction.Done` + `clearFocus()`.

The scroll reserves bottom space for the pill: `Spacer(height = if (isRunning) 96.dp else 24.dp)`.

`RestPill` floats above the bottom: 36 dp `ProgressRing` (lineWidth 3), mm:ss, +15, Skip, with the app's **only** shadow (`Modifier.dropShadow`, calibrate the radius — start ~20 dp, see research §5.3), animated in/out with `spring070` + `slideInVertically+fadeIn`. An invisible zero-size `RestExpiryWatcher` calls `finishIfElapsed()` on the 1 s ticker; the shell runs one too, for a collapsed workout.

`RestTimerSheet` (`NtSheet`, large, no handle, `chevron_down` close in a 36 dp `surface2` circle in a 44 dp box): 280 dp ring (lineWidth 12, track `surface`), `display(104)` countdown, −15 / **Skip rest** (56 dp white, `skip_next`) / +15 on 64×56 `surface2` capsules, "Up next" card (`NTCard` padding 16) with `display(32)` weight and reps, lock note with `lock` 12 sp ink2. Dismisses itself when the timer elapses and on appear if `!isRunning`.

`ExercisePickerSheet` (`NtSheet`, **handle shown**): search field (`ImeAction.Search`, autocorrect off, clear button = a 20 dp `ink3` circle containing a **9 sp Bold** `close` in `ground`), muscle chips, rows (name + muscles + last set or "Never done"), multi-select 26 dp rings, "In" for exercises already in the workout, a `CreateExerciseRow` (48 dp, `add` 14 sp + `subheadline` ink2) inserting a custom `Exercise` (`custom-<uuid>`, `primaryMuscles` from the selected chip), and a bottom `Add n` bar that appears only when `selectedCount > 0` (`slideInVertically+fadeIn`). On dismiss the caller reloads previous-set values.

Finish → raise the summary flag and stamp `endedAt` at once; when `completedSetCount > 0` mark **`startedAt`'s** day attended (any day, no schedule check) and report it (`AttendanceReporter`); `RecordService.rebuild` for the workout's exercises (stale flags after a kind change, an untick or a deleted set are fixed); `Haptics.success()`; swap in `WorkoutDoneScreen` (`easeInOut30`): "DONE." `display(72)`, volume `display(56)` + "kg moved" / "lb moved", delta vs the last workout with the same name, `StatTile`s Time/Sets/Exercises, a records list (60 dp rows, 36 dp icon circle — `ember @ 0.14` + `trophy` for PRs, `surface2` + `medal` for set records) or the no-records line, and Done. "Edit sets" clears `endedAt` and returns with `easeInOut25`; Done only releases the session (`session.end()`), and a view model the session has let go of cannot reopen, finish or discard anything. Discard goes through `session.discard` (never stamps `endedAt`; the delete is delayed 0.7 s after the exit animation).

`totalVolumeKg` = Σ over completed sets where `kind != warmup` of `weightKg * reps` (drop and failure sets **are** included); `completedSetCount` includes warm-ups.

**Units.** Weights are stored in kg. The whole workout area uses the profile unit (lb = kg × 2.2046226218): set cells (up to two decimals; `SetInput` converts and parses leniently with a cap, and typed text is compared with the shown text in display space, so 60 kg shown as 132,28 lb is never written back as 60,0012), the column header, Previous, "Last", the PR hint, the rest card and the rest notification label, the summary hero (`workout.done.kgMoved` / `lbMoved`), delta chip, share text and records, the history row, the detail volume tile and both Progress volume tiles (`Fmt.volume(kg, unit)`).

**Editing finished workouts** (`WorkoutDetailSheet` through `WorkoutDetailPresenter(workoutId, unit, host, onDismiss)`, whose view model is keyed `workoutDetail/<host>` and follows the workout by id; opened from Train history and Today's Last session). Read mode: name · Edit · Done (16 dp apart, one baseline), tiles (volume in the unit), PR line, a Notes block, set chips that skip warm-ups. Edit mode cross-fades in the same sheet (200 ms): Cancel · "Edit workout" (centred) · Save (`headline`; `ink` when it can save, `ink3` and inert otherwise), where Save needs `isDirty && start ≤ now && end ≤ now + 60 s` (otherwise the ember `workout.edit.endsInFuture` footnote). Details card (`StGroup`): Name (placeholder = the saved name; empty keeps it), Date and Start time as compact pills that open an inline wheel under their row (one at a time, ember pill while open: `NtWheelPicker` over the last 730 days up to today, plus the saved day if older, and the Settings `NtTimeWheel`), Duration −/+ snapping to the 5-minute grid, 5 min … 12 h (exact seconds until stepped, so a rename never moves a set; one adjustable TalkBack node). Notes (3–8 lines, `surface`). Every exercise expanded, drawn by the active table's composables in `editing` mode: a "…" menu (Move up / Move down, hidden at the ends; Remove exercise, no confirmation), a blank Previous column, rows never dimmed or locked, Delete set in the kind menu; the ✓ shows `isDone && reps > 0` and ticking a 0-rep row is refused (Reject haptic, reps focused); Add set copies the last row (a warm-up as normal) and starts ticked; Add exercise opens the picker (`alreadyIn` = the draft's ids) and gives each pick one ticked row from its last finished session (else an empty row) and the default rest. Delete workout: a red `StActionRow` in its own card → confirmation. Cancel with changes → "Discard your changes?" (Discard changes / Keep editing); while the draft is dirty a swipe, a scrim tap or system back is vetoed by `NtSheet(confirmDismiss)` and asks the same. `WorkoutEditor.save` (one `db.withTransaction`): name and notes trimmed, `endedAt = start + duration`, removed exercises deleted (sets cascade), rows re-indexed from 0 and updated or inserted, logged rows' `completedAt` remapped order-preserving (the identity when untouched) or borrowed from a neighbour, open rows cleared of time and flags; then `RecordService.rebuild(before ∪ after)` and the attendance correction (`AttendanceService.applyWorkoutDayChange`, with `oldDayStillAttended` from `WorkoutDao.countedWorkoutsBetween`); the changes are reported to the server **after** the transaction. `WorkoutEditor.delete`: the sheet closes first, then delete (cascade) → rebuild → attendance → report. Room flows re-emit, so Train, Today, Progress and Bro refresh by themselves (iOS needs `.workoutHistoryDidChange`).

### Fuel (`feature/fuel`) — `FuelHomeScreen`, `FuelCalendarSheet`, `FoodSearchSheet`, `PortionSheet`, `QuickAddSheet`, `BarcodeScannerScreen`, `ProductLabelSheet`, `AIScanScreen`

The only screen with swipeable rows (swipe to edit, swipe to delete), so it is the only `LazyColumn` with a swipeable row; every other scrolling screen is a plain `Column` in a scroll or a `LazyColumn` of static rows. Per-row insets encode the section rhythm (header top 4, entries 0, protein hint bottom 8, hairline top 8/bottom 4, last-slot spacer 24) — copy them from `FuelHomeSubviews.swift:52-108`.

Home: a two-row `FuelHeader`. Row 1: `‹ [▦ day ▾] ›` — `DayChevron`s 44 × 44 with a 15 pt glyph in `ink` (disabled `ink3 @ 0.4` on today), and `DateButton`, a 32 dp `surface` capsule in a 44 dp target (`Calendar` 12 pt `ink2`, `Fmt.dayTitle` in `footnoteBold` shrinking to 0.8, `ChevronDown` 10 pt `ink3`, content description `fuel.chooseDay`, state `Fmt.longDay`) that opens the History sheet; the group is pulled 12 dp to the start by a `pullStart` layout modifier (Compose has no negative padding); then a spacer and `TodayPill` (32 dp `surface2` capsule, `day.today`, `fuel.goToToday`) fading in (`easeOut20`) on past days. Row 2: `fuel.title` + protein streak chip (`target`, ember). Hero 132 dp `ProgressRing` (lineWidth 10, track **`surface`**) with `display(44)` kcal left (`minFontSize 0.6×`); three `MacroBar`s; "eaten · goal"; meal sections in `MealSlot.ordered = [breakfast, lunch, snack, dinner]`; AI entries carry an `est.` `Badge` in `ink2`; the ember "protein to go" hint shows **only under the first empty slot**. Bottom bar: three 56 dp tiles (AI photo white, Barcode, Search) over the app's **only other gradient** — `Brush.verticalGradient(0f to ground.copy(alpha=0f), 0.5f to ground, 1f to ground)` — with `navigationBarsPadding()`.

**Day navigation** (`FuelDayNavigator` in `FuelSupport.kt`, a pure state machine owned by `FuelViewModel`; `today` and `day` travel in one `FuelDays` flow). `goPreviousDay` / `goNextDay(now)` / `goTo(date, now)` (clamped to today) / `goToday(now)` return whether the day moved, and the `SegmentTick` haptic fires only for user moves that moved (chevrons, swipe, Today pill, a History cell) — never for midnight, the snap-back or the Dashboard jump. Day swipe: a horizontal drag with `minimumDistance ≈ 40.dp` requiring `|dx| > |dy| * 1.5`, `easeOut20`, on both the header and the hero row, keyed only on the threshold (callbacks through `rememberUpdatedState`). Midnight: `syncToday(now)` applies `rolledDay(selected, previousToday, today)` (follow when the old today or a future day was showing, else keep the browsed day), driven by `rememberCurrentDay()`. Snap-back: the **activity's** `ON_STOP` stamps and `ON_START` applies `resumedDay` — a past day returns to today after strictly more than 30 min away (`SNAP_BACK_MS`), the stamp is used once, and rollover runs first. It uses the activity lifecycle (`LocalActivity`), not the nav entry's, so only time outside the app counts; a system activity on top, such as the photo picker, also stops it. The Dashboard's Fuel row lands on today via `AppState.fuelTodayRequests`.

**Day key.** `FuelCalendar.dayKey(storedMillis)` = local date of `stored + 12 h`; `storedDayBounds(day)` = `[midnight − 12 h, next midnight − 12 h)`, the exact inverse (DST included). The day list uses `MealDao.observeDayRange(bounds)`, and the protein streak buckets by `dayKey` (summing, following `today`), so a time-zone change under ±12 h keeps entries on their day.

**History sheet** (`FuelCalendarSheet`, `NtSheet(height = 450.dp)` on `ground`, own `Grabber`): "Historia" title + Done (44 dp, plain), the goal note in a `ShrinkingText` (0.8). Grid: a 22 dp weekday column + 6 dp, then a `LazyRow` of 26 Monday-first week columns (a 14 dp month slot, a 6 dp gap, 7 cells on a 28 dp pitch; month labels may overflow into the next empty slot); cells are a 24 dp fill with radius 6, a 30 dp ring (2 dp `ink`, radius 9) on the selected day and a 5 dp dot on today (`ground` on level ≥ 3); future days are empty spacers. It opens with the current week flush at the right edge and centres the selected day (`firstVisibleColumn`) only when it would be off screen; older days leave it at the current week. Tapping a cell closes the sheet and `goTo`s the day (the selected cell only closes, no tick). Legend `[■0] Not logged … Off target [■1■2■3■4] On target` as one merged accessibility node, scaled together down to 0.8 through a `TextMeasurer`. Three `StatTile`s of equal height (`IntrinsicSize.Min`, eyebrow may wrap to 2 lines, values at the bottom): 7-day and 30-day averages over logged days before today (`KcalLabel(numberStyle = headline)`, "–" in `ink3` when none) and `"%d z %d"` days at level 4 in the last 30. Data: `FuelViewModel.calendarKcal` (`observeKcalByDaySince(layout.start − 12 h)` → `FuelCalendar.kcalByDay`, `WhileSubscribed(5 s)`, collected only while the sheet is open); layout and stats are computed from `state.today`. Colours `NT.Colors.heat = [surface2, #693927, #98492B, #CA592C, ember]`.

**Heat scoring** is `FuelCalendar.kt`, a pure port of `FuelCalendar.swift` with the identical rule — integer permille `p` of the kcal goal (`Fmt.roundHalfAwayFromZero`, 0…10 000, 0 for a goal ≤ 0), `d = |p − 1000|` against the goal's `under` / `over` bands (lose fat 100/200/350 under, 30/80/150 over; build muscle the mirror; maintain 50/100/200 both sides) for levels 4/3/2/1, level 0 when nothing is logged, 1 for a goal ≤ 0, and today reading `clamp(p / 250, 1, 3)` while `p < 1000 − under[0]`; see `docs/architecture.md` for the table. `FuelCalendarTest` asserts every section of `test/resources/fuel-calendar-vectors.json`, the same file the iOS tests are generated from.

**Meal entries.** `EntrySwipeRow`: a leading swipe past half the row opens the edit sheet and springs back (`FuelEditBackground`: `surface3` with a pencil and "Edit"); a trailing swipe past half the row deletes (`bad` with `delete`). **Speed never commits** (`FuelDerive.swipeCommits`, `SWIPE_COMMIT_FRACTION = 0.5`): the veto reads the release offset in `confirmValueChange`, which only the `SwipeToDismissBoxState` constructor Material 1.4 deprecated still has (`@Suppress("DEPRECATION")`, reason in its KDoc; replace it with an `anchoredDraggable` row later). The state is `remember`ed, not saveable, so a row restored by Undo is not drawn swiped off. `FuelEntryRow`: a trailing `ChevronRight` (11 pt `ink3`, hidden from TalkBack), `combinedClickable` — tap edits (click label "Edit"), long press plays `LongPress` and opens an `NtMenu` with Edit · Log again today (past days only) · Delete (`separatorBefore`, destructive) anchored to the row's content — and TalkBack custom actions for Log again today and Delete. On a past day a non-empty slot header also shows a 44 dp Copy to today icon button (`PlusSquareOnSquare`, 15 pt `ink2`, trailing-aligned, `fuel.copyToToday`).

**Editing** (`PortionSheet` for food-backed rows, `QuickAddSheet` for custom and AI rows): both show `EntryDayStepper(day, today, onChange, height, background)` above `MealSlotPicker` — "Day" (`subheadline` ink2), `‹` 44 × 44, `Fmt.dayTitle` (`footnoteBold`, min 88 dp, shrinks to 0.8), `›` disabled on today; 44 dp on `surface2` in `PortionSheet`, 52 dp on `surface` in `QuickAddSheet`; no lower bound; `SegmentTick` per change; one TalkBack node with previous/next-day custom actions. The sheet starts on the day the entry is listed under (`dayKey`); Save writes `day = local midnight` only when that changed (`FuelDerive.moved`), keeping `loggedAt`, slot, figures and AI flags. `PortionSheet` re-derives the figures only when the grams changed (`editedPortion`); edit height `PORTION_EDIT_SHEET_HEIGHT` = 376 + 60 + 48 = **484 dp**. `QuickAddViewModel` keeps the prefill texts (`EntryEditTexts.of`): a field still equal to its prefill saves the exact stored figure (`editedFigure`), so a rename or move keeps an AI row's badge and confidence; `writtenFigures` holds what the sheet itself wrote, and while kcal/P/C/F still show it, `setGrams` rescales them (`FuelDerive.rescaledTexts`) and Save stores the exact rescaled figures (`figuresAt`); typing a figure stops that. Prefills use `FuelText.fieldText` (`DecimalFormat`, 0–1 fraction digits, no grouping, half-even); every number in both sheets parses with `Parsing.nonNegative` (a negative now reads as nothing).

**Log again today / Copy to today / Undo** (`FuelEntryActions`, framework-free over the two DAOs, owned by `FuelViewModel` and exposed as `pendingUndo` outside the `FuelUiState` combine). Past days only. A copy gets a new id, today's midnight, the same slot, figures and AI flags and `loggedAt = now + i ms`; a food-backed copy bumps the food's usage. `FuelUndoToast`: a 44 dp `surface2` capsule (18 dp leading, 4 dp trailing), the message (`fuel.entryDeleted` / `fuel.addedToToday`, `footnote` ink2, a polite live region) and `common.undo` (`footnoteBold` ink, 44 dp), 80 dp above the tab bar with the barcode lookup pill 8 dp under it, sliding up with a fade; 4 s (`UNDO_MS`), 10 s with touch exploration on, passed through `calculateRecommendedTimeoutMillis`. Only the last action is undoable and each timer expires by id (`UndoTimerEffect`); undoing a delete re-inserts the stored row as it was (a food deleted meanwhile comes back as a custom row with its name), undoing a copy calls `MealDao.deleteByIds`; the usage bump stays.

`FoodSearchSheet` ("Add to <meal>"): search field (`autoCapitalize = None`, `ImeAction.Search`, autofocus), barcode button, then saved foods from `FoodDao.observeLibrary()` — with no query "Recent" (10 most recently used), with a query up to 10 `FoodMatch` hits over name + brand under `fuel_yourFoods` (`FoodMatch.fold`: lowercase `Locale.ROOT`, NFKD minus combining marks, `ł` → `l`; every word must match) — then result rows 62 dp with a trailing 32 dp `add` circle in a 44 dp box and a bottom `Hairline`, and `FoodStateRow` in four states (loading / notFound / error — `FoodSearchError.messageRes` — / hint). Quick add keeps the typed query as the name. Debounce **500 ms**, minimum 2 chars, retry after **700 ms** on `alreadyInFlight`. An optional `FoodSearchPick(mode = Add | Replace, title, onPick)` turns it into a picker for the AI result screen: no Quick add anywhere, and a tap, scan or saved label hands the food back (`Replace`) or opens `PortionPickSheet` (`Add`); nothing is written except a label the user saves. Pick sheets use their own view-model keys (`foodSearch-pick`, `portion-pick`).

`PortionSheet` (`NtSheet`, min height **376 dp**, own `Grabber`, `surface`): name/brand/source (`maxLines 2`), then, when `food.isEstimated`, the `fuel_estimatedNutrition` caption (`footnote` ink2, ≤ 2 lines; +48 dp, `PORTION_ESTIMATE_ROW_HEIGHT`), kcal `display(40)` with a digit-slide `AnimatedContent` (`easeOut15`), −/+ 48 dp stepper (10 g), quick-portion chips (100 g, serving if known) in a horizontally scrolling row, grams box tappable to focus (`KeyboardType.Decimal`), macros row, `Add to <meal>` 56 dp. `PortionPickSheet(food, onPicked, onDismiss)` is the same sheet with `fuel.ai.addMissed.pick`; it returns the grams and writes nothing.

**Barcode pipeline.** `BarcodeScannerScreen`: CameraX `PreviewView` + ML Kit `BarcodeScanning` (`FORMAT_EAN_13`, `EAN_8`, `UPC_E`, `UPC_A`, `QR_CODE`, `DATA_MATRIX`, `enableAllPotentialBarcodes()`), analysis at **1920×1080** (`ResolutionSelector`, 16:9, `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`, set before binding) with ML Kit auto-zoom (`setZoomSuggestionOptions`, ceiling 5×, each request clamped to `zoomState.maxZoomRatio`), rear camera. Every barcode in a frame goes through `GTINExtractor.gtin(rawValue, symbology)` and the first product code fires (`Haptics.success()`): EAN digits pass, UPC-E expands to "0" + UPC-A (a valid 12/13-digit value under `FORMAT_UPC_E` passes through), QR/DataMatrix accept a GS1 Digital Link (`/01/` or `/gtin/`, via `java.net.URI`) or an element string with a valid (01), a GTIN-14 normalises (six leading zeros → GTIN-8, one → EAN-13), and promo QRs or bad check digits are skipped. **No GS1 DataBar** — ML Kit has no decoder (the parsing exists and is tested for parity). A `ground @ 0.85` hint pill, a `surface2 @ 0.9` Cancel, an "Enter barcode manually" (`fuel_scan_manual`) button, and the manual-code field (`KeyboardType.Number`, `ImeAction.Done`, autofocus), shown straight away when `CAMERA` is denied or `FEATURE_CAMERA_ANY` is absent: `FuelDerive.manualBarcode` needs a valid check digit (or an 8-digit valid UPC-E, expanded), and a complete code that fails shows the ember `fuel_scan_checkDigits` with Use code disabled. `BarcodeLookupFlow` (a class owned by `FuelViewModel` and, separately, by `FoodSearchViewModel`; `StateFlow<State(isLookingUp, prompt, labelRequest)>`) starts at once — Compose sheets have no presentation race, so there is no iOS-style `pendingCode` hand-off — and cancels a still-running lookup on a new scan, a retry or `reset()`: the saved food first (`FoodDao.preferredByBarcode(BarcodeKey.localKeys(code))`: every OFF form plus the RCN key; the user's `custom` label first, then latest `lastUsedAt`, never-used last, then `id`), then `FoodSearchService.lookup` with the app language. `BarcodeLookupPrompts(flow, state, onFood, onQuickAdd?)` draws the one `NtAlert` — `Partial(stub, code)`: title "name · brand" (`partialTitle`), message `fuel_barcodePartial`, Add from label (prefilled) / Quick add (name prefilled) / Cancel; `NotFound(code)`: `fuel_barcodeNotFound`, Add from label / Quick add / Cancel; `Failed(messageRes, code)`: the error as the title, Try again (after 400 ms, the whole lookup again) / Add from label / Cancel — and the label sheet; a null `onQuickAdd` (pick mode) hides Quick add. **RCN:** a 13-digit code with prefix 23–29 and a value field (digits 8–12) other than `00000` is saved under its first 7 digits (`BarcodeKey.storageKey`); OFF is still asked with the full code.

`ProductLabelSheet(code, stub, onDismiss, onSave)`: QuickAdd-style rows on a `ground` `NtSheet` (never `OutlinedTextField`) — header with title and Cancel, the product line "brand · quantity · code" with the ember `fuel_label_storeCode` note when key ≠ code, the label-photo row, name, a "per 100 g" eyebrow, kcal, P/C/F, fibre and serving (placeholder "–"), the hint, `fuel_label_saveFailed` in `bad`, Save; focus starts on the name when it is empty, else on kcal. `LabelValues.parse`: name, kcal, P/C/F required; kcal ≤ 950, P + C + F ≤ 105, fibre ≤ 100, serving 0…5000 g, and an optional field holding text that is not a number blocks Save. `onSave` → `BarcodeLookupFlow.saveLabel` → `ProductLabel.save(key, name, stub, values, dao, brand)`, which upserts `label:<storageKey>` (`source custom`, `barcode = key`; keeps brand, image and usage stats; brand = stub brand, else the one read from the label, else the existing one; `servingLabel = null`), closes the form and opens the portion sheet. **Label read** (`LabelPhotoReader`, a plain state holder on the sheet's scope with function collaborators, not a ViewModel): "Photograph the label" (`fuel.label.photo`) (the camera at `LABEL_LONG_EDGE` via `CameraCaptureScreen(maxLongEdge)`, or the library without a camera) + a round 52 dp library button (`fuel.ai.chooseLibrary`), dimmed while reading; the same `AIUpload` and consent `NtAlert` as the estimate; `Status.Filled(needsReview)` / `Unreadable` (`legible == false` or no per-100 values) / `Failed(labelMessageRes)`. `LabelFill.from(reading, currentName, locale)` fills kcal/P/C/F, fibre and serving only when printed, the name only into an empty field (an illegible reading included), the brand when printed. Status line: spinner + `fuel.label.reading`, then `fuel.label.aiFilled` (ink2) or `checkMacros` / `unreadable` / the error (ember).

`AIScanScreen`: a `when (phase)` over `pickSource` (→ signed-out view when the provider is `standard` and `authStore.needsSignIn`, else the source view), `analyzing`, `result`, `failed`. Source view: 210 dp `surface` 22 dp frame with two concentric strokes (150 dp `surface3` 2 dp, 118 dp `surface2` 2 dp) + `restaurant` 30 sp `ink3`; PhotoPicker (`PickVisualMedia`, **no permission**) and camera (`TakePicture` + `FileProvider`, or the CameraX capture screen). Downscale to `PLATE_LONG_EDGE` (1024 px), JPEG 80; the optional notes are cut at a character boundary by the view model. Consent is per destination (`nt.aiConsent.google` for standard and Gemini, `nt.aiConsent.anthropic`), asked once with a runtime-formatted title; the mock path uploads nothing and asks nothing.

Result view: `AIScanPhoto` (210 dp, 22 dp) with detection tags on the **fixed 6-point anchor grid** `(0.11,0.13) (0.62,0.21) (0.34,0.78) (0.66,0.60) (0.08,0.48) (0.40,0.42)`, positioned at `x = w*ax + 50.dp`, `y = h*ay + 13.dp`, only the first 6 foods; tags are an ember 6 dp dot + `caption` ink, h-pad 10, height 26, max width 150, on a `ground @ 0.85` capsule with a 1 dp `border` stroke. Estimated total `display(56)` with the digit animation (`easeOut25`), `ConfidenceDots` (≥0.75 → 3, 0.45–0.75 → 2, else 1), editable rows (`AIScanFoodRow`, min 58 dp, 8 dp vertical padding) with a portion line under the name for counted items (`AIScanFormat.portionBasis` → `fuel.ai.portionBasis`, "2 kromka × 35 g"), a tappable name/macros area (click label `fuel.ai.editItem`) opening `AIScanItemSheet`, and a grams pill (36 dp, `surface2`, 8 dp, in a 44 dp box) whose `NtMenu` is, in iOS order, `-25% / -10% / +10% / +25%` │ +1 unit, −1 unit (only with a unit; −1 disabled at ≤ 0.5), Custom… (`Scalemass`, an alert with a `KeyboardType.Decimal` field, `Parsing.positive`), Edit… (`Pencil`) │ Remove (destructive); the "guess" badge shows when `isGuess || confidence < 0.4`. `AIScanItemSheet`: header (title + Cancel), live kcal `display(40)` + macros + "%s kcal per 100 g", name, a count stepper (one TalkBack node with −1/+1 actions; +1 from below 1 goes to 1, max 99; −1 stops at 0.5), grams per unit (counted items only) and total grams — the field being typed drives the other (`withGramsPerUnit` / `scaled`) — Find in food database (`FoodSearchSheet` pick `Replace` → `replacedBy`: name and per-100 values from the product, grams and count kept, `confidence 1`, `nutritionSource = "database"`), a red Remove and a primary Done. "Add something it missed" opens `FoodSearchSheet` in pick `Add` mode and `PortionPickSheet`, then appends `AIScanCorrections.fromDatabase(food, grams ?: serving ?: 100)`. Edits live in a pure reducer, `AIScanEdits` (`foods`, `kept`, `added`, `removedNames`; `update`, `remove`, `append`, `setGrams`, `scale`, `stepCount`), held in `AIScanUiState.edits`. **Refine** (the analyze button on the result screen) snapshots the result, sends `edits.outgoingNotes(typed, refining)` — the typed details, then `User corrections (authoritative): <name> = <g> g (<count> <unit>); …; removed: <name>` (≤ 1500 UTF-16 units, typed part cut first at a grapheme boundary) — and merges the answer back (`AIScanCorrections.merge`: a kept item replaces the first untaken refined item whose `FoodMatch.fold` name matches any name it has had, refined items matching a removed name are dropped); a failed refine restores the snapshot and shows the error in the toast (two centred lines, 16 dp gutters); an empty answer shows `fuel.ai.failed`. `LogToMealButton` is a 56 dp white capsule with `keyboard_arrow_down`: **tap logs, long-press opens the slot menu** (one `Button` per `MealSlot`, the current one prefixed with `check`). Logging (`AIScanLog.entries`) inserts one `MealEntry` per model item with `isAIEstimate = true`; database items log as ordinary food entries (a saved item's usage bumped, an OFF candidate cached with `cacheOnTap`, figures from per-100 × grams, `isAIEstimate = false`; a saved item deleted meanwhile falls back to an AI row) — then `Haptics.success()`.

### Progress (`feature/progress`) — `ProgressHomeScreen`, `ExerciseProgressScreen`

No visible top bar on either screen; `ExerciseProgressScreen` draws its own `arrow_back` in a 44 dp box.

Home: eyebrow last-PR date, `progress.title`, a segmented control **at width 150, segmentHeight 34, inset 3, radius 11, `subheadline`** — a different configuration from `ExerciseProgressScreen`'s range picker at **width 168 with the defaults (32/2/8/`caption`)**. Lifts tab shows the body card **above** the lifts list; Body tab shows the full body view.

Body card: latest weight `display(40)`, 4-week delta (`minFontSize 0.85×`), 120×48 `BodyWeightChart`; the whole card is tappable (when a weight exists) to open `LogWeightSheet` (`NtSheet`, min height **340 dp**, own `Grabber`, `ground`, `display(44)` field, autofocus, `KeyboardType.Decimal`).

Lifts list: every exercise with ≥1 completed set, sorted by last PR date desc; name, context, a 72×24 sparkline (**ember when `prInLast30Days`, else `ink2`**), current e1RM + delta.

`ExerciseProgressScreen`: hero e1RM `display(64)`, delta chip (`arrow_upward`/`arrow_downward`/`remove` 12 sp Bold), `E1RMChart` 172 dp, `StatTile`s (Last PR / This week volume / Sessions — two of them are hand-rolled with identical geometry because their value needs multiple text runs), `WeeklyVolumeChart` 86 dp, and a records list (heaviest set, most reps) with dates. The "this week" tile and the weekly volume use the profile unit; both screens follow edits and deletes of finished workouts through their Room flows.

**Charts — `designsystem/Charts.kt`, all four drawn in `Canvas`:**

- `SparklineChart` — linear path, `Stroke(2.dp, cap = Round, join = Round)`, optional trailing point (symbol size 24), no axes, y-domain `ChartScale.padded(values)` (pad by a fraction of `max(hi-lo, max(hi*0.04, 1))`, 15 %/15 % by default), x-domain `0..max(1, count-1)`.
- `BodyWeightChart` — raw series `Stroke(1.5.dp, Round, Round)` in `ink2 @ 0.6`; smoothed 7-day trailing MA `Stroke(2.dp)` in `ember`; trailing point = `ember` circle with a 2 dp `surface` ring, symbol size 56. Axes only when `showsAxes`: X at `[0, count-1]` anchored topLeading/topTrailing with `Fmt.dayMonth`; Y trailing, 3 marks, 1 dp `hairline` grid lines, `Fmt.weight(withUnit = false)`.
- `E1RMChart` — `AreaMark` fill = `Brush.verticalGradient(ember @ 0.22 → ember @ 0)` (the app's second and last gradient), line `Stroke(2.dp, Round, Round)` ember; PR markers: the **latest** PR is a filled ember circle with a 2 dp `ground` border at symbol size 110, earlier PRs are hollow (`ground` fill, 2 dp ember border) at size 60. Y-domain padded 8 % bottom / 6 % top. X axis 4 marks with `Fmt.dayMonth`.
- `WeeklyVolumeChart` — last 8 ISO weeks, bars at `width = .ratio(0.66)` clipped to a 3 dp continuous rect, `ember` for the current week else `surface2`; y hidden, domain `0..max(1, maxVolume * 1.05)`; exactly two X labels (first and last week start) anchored topLeading/topTrailing, the last rendering the localized `progress.thisWeek` at 12 sp `ink2` instead of a date; a 1 dp `hairline` baseline overlaid at the bottom of the plot.

Chart axis text is **12 sp Regular** `ink2` `.tabular()` — deliberately lighter than `NT.Fonts.caption` (12 Medium).

### Bro (`feature/bro`) — `BroScreen`, `CantMakeItSheet`

Three-way state: `needsSignIn` → `BroSignedOutView` (`group` 22 sp ink2 in a 44 dp `surface2` circle, `NTCard`, `SecondaryButton` opening `SignInSheet`); `isPaired` → the paired content; else `BroUnpairedView`.

Paired: eyebrow "Kuba & Tomek", `bro.title`, streak chip (`local_fire_department` 13 sp ember); `BroSharedWeekCard` (`NTCard` padding 0, inner insets 14/16/16/16) with header letters, two rows of `BroStatusCell(size = 30.dp)`, hairline, today line and a `good` dot when both are in; a heads-up chip row **bleeding to the screen edge** (inner `padding(horizontal = screenH)` inside an outer `-screenH` offset) with `HeadsUpChip`s — Can't make it (`event_busy`, `bad` outline), 15 min late (`schedule`), Let's go (`bolt`), Custom (`chat_bubble`, ≤ 80 chars, enforced both while typing and on send, opening an alert with a text field); a log section with `BroStatusCell(size = 22.dp)`.

`BroStatusCell(state, size)`: `rest` → nothing; `planned` → 1.5 dp `border` ring; `confirmed` → filled `ink` circle + `bro.in` at `if (size >= 30) 12 else 9` sp Bold `onPrimary` (`minFontSize 0.6×`); `attended` → 1.5 dp ember ring + `check` at `round(size * 0.43)` Bold; `missed`/`cancelled` → same in `bad` with `close` at `iconSize - 1`.

Unpaired: title, subtitle, a big code card (`display(56)`, `.tracking(2)` → `0.0357.em`, `minFontSize 0.6×`) with share (48 dp `ios_share` on white) and copy (48 dp `surface2`, icon swaps to `check` in `good`, `easeOut15` in / `easeOut30` out), an "or" divider flanked by `Hairline`s, an entry field (`display(26)`, `.tracking(1)`, `Characters`, `ImeAction.Go`) and Pair.

Pull-to-refresh — the only one in the app. A `LaunchedEffect` loop refreshes every **15 s** while the screen is resumed. The heads-up "sent" confirmation uses a token so a later send cancels an earlier hide (`easeOut20` in, `easeOut30` out).

`BroService` (`service/BroService.kt`, the process-global singleton): `client` resolves through `AppConfig.makeBackendClient()` on **every** access so the demo toggle needs no relaunch. `refresh()` → `me()` → set partner/pairCode; if unpaired, clear `bro_pairing` and return; else `partnerState()` → `sync()` → upsert the pairing. `unauthorized` is a **state** (`isSignedOut = true`), not an error. `didSignIn()` → push the schedule → `updateMe(locale, timeZone)` → refresh → create a pair code if missing. **`normalizedCode`**: uppercase, strip non-alphanumerics; if it starts with `NT` and is 6 chars, drop the `NT`; 4 remaining chars → `"NT-XXXX"`; otherwise return the trimmed uppercase input — and `pair` requires the result to be exactly 7 chars. `confirmToday` and `cantMakeIt` write locally **first**, then call the network (optimistic). `sync` diffs partner rows by day (write only when status/reason/note/makeUpDay changed) and de-dupes incoming heads-ups by (**±1 s `sentAt`**, equal `text`).

**Attendance sync** (`service/AttendanceSync.kt`, the port of iOS `AttendanceSync` + `AttendanceOutbox`). The user's own attended/missed/planned writes reach the server whenever `broService.canSync` (`!authStore.needsSignIn`; the demo backend counts), paired or not, so a partner sees them and the backend's reminder and 21:00 check stay quiet on a trained day. `AttendanceReporter` (`fun interface`; `container.attendanceReporter` launches on the process scope so Done or closing a sheet cannot cancel it; `None` in tests) → `BroService.reportAttendance(day, status)` queues into `AttendanceOutbox` (`nt.attendance.outbox`, one entry per day, last write wins; `remove` keeps a newer status for the same day; unreadable entries are dropped) and then `flushAttendanceOutbox()`: oldest first, stop at the first failure that may clear up, drop an `Http` 4xx other than 401/408/429 (`isRejected`), never set `lastError`, re-entry guarded by `Mutex.tryLock`. `refresh` flushes right after `me()` succeeds; nothing is queued while signed out; Delete account calls `clearAttendanceOutbox()`. Sources: Finish (`attended` for `startedAt`'s day) and `WorkoutEditor.save`/`delete` (their `WorkoutDayChange`s through `wireStatus`). The Dashboard "I'm in" still reaches the server only when paired.

`CantMakeItSheet` (`NtSheet`, min height 660 dp, own `Grabber`, `surface`, 24 dp corners; also opened from Dashboard): title, session line, Why chips (`SheetChip`), a note field with a live `"n/80"` counter and an 80-char cap, "Propose a make-up" chips (the next 2 non-gym days + "Skip this one") in an edge-bleeding row, a summary box (`event_busy` 16 sp `bad`), and a primary button whose copy switches between `cant.send %@` and `cant.sendSolo`, plus "Never mind, I'm going". Sending writes `AttendanceRecord(.cancelled, reason, note, makeUpDay)` + a local `HeadsUp(.cantMakeIt)`, marks the make-up day planned (`AttendanceService.markPlanned`; a make-up day that passes untrained becomes missed), and calls `BroService.cantMakeIt` whenever `bro.canSync` — signed in, paired or not (the heads-up itself still goes only to a partner).

### Auth (`feature/auth`) — `SignInSheet`

`NtSheet` large, own `Grabber`, `surface`, 24 dp corners. Username (`TextContentType.Username` equivalent: `KeyboardType.Text`, autocorrect off, `ImeAction.Next`) → password (`PasswordVisualTransformation`, `ImeAction.Go`). Mode toggle sign-in / create account (`easeOut20`). `auth.google` is a `SecondaryButton` with a Google branded asset — wire it to **Credential Manager** (`GetGoogleIdOption` / `GetSignInWithGoogleOption` → `POST /auth/google`); on iOS it is disabled at 0.4 opacity, on Android it works. Client-side validation mirrors the server: username 3…24 matching `^[a-z0-9_.]{3,24}$`, password ≥ 10 for registration and non-empty for sign-in; error map `auth.error.usernameTaken` / `auth.hint.username` / `auth.error.password` / `auth.error.tooMany` / `auth.error.generic` / `auth.error.register`. `Haptics.success()` on success, then `BroService.didSignIn()` and `PushRegistrar.register()`. Sign-out best-effort revokes the refresh token, clears the secure store and resets bro state — **local workouts/meals/schedule stay**.

Credential Manager also handles the password path (`GetPasswordOption` / `CreatePasswordRequest`), so the user's password manager saves the credential.

### Settings (`feature/settings`) — `SettingsSheet`

`NtSheet` large (default handle) hosting its own `NavHost` over the 12 `settings/*` routes, with a large title on `ground` and a `common.done` confirmation action. Groups per the canvas: You (Name, Body weight, Daily target), Training (Gym days · time, Rest timer · auto-start, Units), App (Language, Notifications, Health app, Export my data, AI estimates), Gym bro (partner row, Your code with share), Account (Sign out, Delete account and data in `bad`).

- **Language** writes `appState.languageOverride` and calls `AppCompatDelegate.setApplicationLocales` — no relaunch hint (drop the iOS `settings.language.footnote` copy or replace it).
- **Notifications** reflects the real permission state; observe lifecycle resume to re-read it after a trip to system settings, and surface the **exact-alarm** state here too (`canScheduleExactAlarms()`, with a button launching `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`).
- **Health app** has three states driven by `HealthConnectClient.getSdkStatus()`: unavailable / needs provider update (deep-link to Play) / available (request permissions).
- **Export** writes `NoTomorrow-export-<YYYY-MM-DD>/workouts.csv` and `meals.csv` into `cacheDir`, shared via `FileProvider` + `ShareCompat`. Headers are byte-for-byte:
  `workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps,completed_at,pr,set_record`
  `day,slot,food,brand,grams,kcal,protein_g,carbs_g,fat_g,ai_estimate,logged_at`
  Numbers use `Locale.ROOT` dot decimals, integers unsuffixed, dates ISO-8601, RFC-4180 quoting.
- **AI estimates** picks `standard` / `claudeBYOK` / `geminiBYOK` (raw values); a BYOK key goes to `SecureStore` and is displayed masked (`sk-ant-…7Yq2`: prefix `sk-ant-` when present else the first `min(3, count-4)` chars, always the last 4). A developer group hides a "Use demo data (offline)" toggle that swaps the whole backend.
- **Partner editor** unpair confirmation is `settings.unpair.confirm %@`; on success pop to the settings root.
- **Rest timer** edits the rest length (`profile.defaultRestSeconds`, the default of every new workout row; heavy compounds get + 30 s) and auto-start.
- **Delete account** deletes remotely, then wipes every user-owned table **except non-custom `Exercise` rows**, clears the secure-store items, resets bro state, clears the attendance outbox, removes `nt.rest.autoStart`, and sets `hasOnboarded = false`. Play also requires a public web deletion link — track it as a launch task.
- **Version footer** (`VersionFooter`, after the Account group): `settings.version %@` with `versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)` = "0.2.0 (1)" (just the name without a positive code, "–" without a name; CI stamps the tag and the run number), centred `footnote` `ink3` tabular, 12 dp top padding, in a `SelectionContainer`.

## Test plan — `app/src/test/java/app/notomorrow/`

Pure-JVM tests (no Robolectric unless a test genuinely needs Android classes). Mirror `NoTomorrowTests` first, then add the coverage iOS is missing.

**Port of the existing suite (must pass identically):**
- `FmtTest`: `clock(72) == "1:12"`, `clock(0) == "0:00"`.
- `EpleyTest`: `SetEntry(weightKg = 85.0, reps = 6).estimatedOneRepMax ≈ 102` (± 0.01).
- `TargetCalculatorTest`: build muscle @80 kg → kcal **3050**, protein **176**, fat **72**, carbs **425**; lose fat → **2350 / 192 / 72**; maintain → **2750 / 144**; `targets(null, maintain) == targets(80.0, maintain)`; for every weight 50…130 step 2.5 × every goal, `kcal % 50 == 0` and all four ≥ 0; `targets(0.1, loseFat)` gives kcal ≥ 0 and carbs ≥ 0.

**New tests (behaviour that is load-bearing and currently untested):**
- `RecordServiceTest` — first working set is a PR with `isSetRecord = false`; warm-ups never count either way; `isSetRecord` only when `!isPR` and only at exactly the same weight; the same-timestamp tie-break falls back to `WorkoutExercise.order`.
- `AttendanceServiceTest` — past `confirmed`/`planned` collapses to `missed`; a past gym day with no row is `rest`, not `missed`; `nextSession` honours the **2 h grace**; `markPastPlannedAsMissed` respects the profile creation date and the 30-day floor; `currentStreak` stops at the first non-attended; `isoWeekday`/`startOfIsoWeek` are Monday-first.
- `BroCodeTest` — `normalizedCode`: `"abcd"`, `"nt-abcd"`, `"NTABCD "`, `"NT-ABCD"` all → `"NT-ABCD"`; a 5-char input is returned uppercase and rejected by `pair`.
- `FoodSearchTest` — `barcodeForms` for 13-digit-leading-zero, 12-digit and 8-digit inputs; `grams(fromLabel:)` accepts `"30 g"`, `"1,5 g"`, `"250 ml"` and rejects `"1 piece"`; a product with no `energy-kcal_100g` is dropped; `LooseNumber` accepts `"12,5"` and `12.5`.
- `AiDecodeTest` — the tolerant decoder against `proteinG` / `protein_g` / `protein` / string numbers / missing fields, v1 and v2 answers and label readings; `isGuess` defaults to **false**; `overallConfidence` defaults to the item mean and clamps to 0…1; `AIFinalizer.extractJsonObject` against fenced and prosy output; rescaling.
- `WireDayTest` — `YYYY-MM-DD` round-trip in the device calendar; the three instant-decoding fallbacks.
- `ImageDownscalerTest` — a 4032×3024 input yields exactly **1024×768** (floored), and an already-small image is untouched.
- `FormatConversionTest` — every generated `<string>` with specifiers formats without `IllegalFormatException` under both `en` and `pl`, and each plural resolves for counts 0,1,2,5,22,112.
- `TokenRefresherTest` — concurrent 401s trigger exactly one refresh; a 400/401 refresh drops the session; a 500 keeps it and surfaces `Network`.
- `RestTimerControllerTest` — `endAt` arithmetic across `start`/`adjust`/`skip`/`finishIfElapsed`; `restore()` discards a past `endAt`; a rebuilt controller reads the persisted state back.
- `DaoTest` (Robolectric or instrumented, in-memory Room; **not written yet** — the project has no Room test harness, so the new `preferredByBarcode` / `observeLibrary` ordering is covered only through fakes) — cascade deletes for Routine→Item, Workout→WorkoutExercise→SetEntry; `SET NULL` for Exercise→WorkoutExercise; the `(day, participant)` unique index; the `BodyWeightEntry` day upsert.

**Suites added with the Fuel and workout work** (pure JVM; fakes in `FuelFakeDaos.kt`, `FakeWorkoutDao.kt`, `FakeDaos.kt`):
- Fuel: `FuelCalendarTest` (every section of `fuel-calendar-vectors.json`, shared with iOS, plus the per-goal properties), `FuelDayNavigatorTest`, `FuelEntryActionsTest` (undo, log again, copy), `FuelMealEditTest` (moves, prefills, the AI-badge rule), `FuelRescaleTest`, `ParsingTest` (the iOS `NumberInput` vectors), `SettingsFormatTest` (`versionLabel`).
- Barcode: `BarcodeLookupMappingTest` and `FoodSearchTransportTest` (Ktor `MockEngine`) over the live OFF fixtures in `test/resources/off/`, `BarcodeKeyTest`, `BarcodeLookupFlowTest`.
- AI: `AIEstimateFinalizerTest` (runs all shared fixtures from `backend/data/ai/fixtures`; the prompt fixtures pin the strings and both schema variants, key order included), `AIProviderTest` (request bodies, fallbacks, error tables), `AIScanCorrectionsTest`, `AIScanViewModelTest`, `LabelPhotoReaderTest`, `ImageDownscalerSizeTest`.
- Workout: `WorkoutSessionControllerTest`, `WorkoutStartFlowTest`, `ActiveWorkoutFlowTest`, `ActiveWorkoutViewModelTest`, `SetTableTest`, `RecordRebuildTest`, `WorkoutEditSupportTest`, `WorkoutEditorTest`, `WorkoutEditViewModelTest`, plus extensions of `WorkoutStarterTest`, `RoutineSeederTest`, `AttendanceServiceTest`.
- Quick wins: `AttendanceSyncTest`, `ExerciseLibraryTest` (fold, version stamp), `StoreLoaderTest`, `DatabaseMigrationsTest` (the generated `NoTomorrowDatabase_Impl` against `PINNED_SCHEMAS` and the committed schemas, and a migration path for every step).

Compose UI tests are optional per feature but a **screenshot-diff harness is required before the first release build**: run the app on the 1179×2556 @480 dpi AVD (an exact iPhone-16 logical canvas at 3×) and diff against iOS simulator captures screen by screen.

## Rules for parallel work

8–10 agents work simultaneously. These rules exist so nobody has to talk to anybody.

1. **Own your package.** A feature agent creates and edits files **only** under `feature/<x>/` — plus, if it needs strings that are not in the catalog, `res/values/strings_<x>.xml` and `res/values-pl/strings_<x>.xml`. Nothing else.
2. **Never edit shared files.** `designsystem/`, `data/`, `model/`, `service/`, `net/`, `nav/`, `di/`, `util/`, `AndroidManifest.xml`, `build.gradle.kts`, `libs.versions.toml`, the generated `strings.xml`, and this document are owned by the platform/design-system agents. If you need a change there — a new component, a new DAO query, a new route, a new permission, a new dependency — **report it** in your final message as a precise request (file, symbol, signature, why) and code against the interface as if it existed, stubbing locally only if you must and flagging the stub.
3. **Read before you write.** The iOS source is the behavioural truth. Before implementing a screen, read the corresponding files under `NoTomorrow/Features/<X>/` in full; geometry numbers in this document are a summary, not a replacement.
4. **Name types after iOS** where it is sensible: `NT`, `PrimaryButton`, `SecondaryButton`, `GhostButton`, `NTCard`, `ProgressRing`, `MacroBar`, `Chip`, `Badge`, `ValueRow`, `Hairline`, `StatTile`, `Avatar`, `Grabber`, `SectionHeader`, `RestTimerController`, `WorkoutSessionController`, `RecordService`, `AttendanceService`, `TargetCalculator`, `ExerciseLibrary`, `RoutineSeeder`, `FoodSearchService`, `AIEstimateService`, `BroService`, `BackendClient`, `Fmt`. A reviewer must be able to diff the two ports by name.
5. **Screens are `@Composable fun <Name>Screen(...)`** with a `<Name>ViewModel` and one `<Name>UiState`. No business logic in composables; no Android framework types in view models beyond `Application` where unavoidable.
6. **No new dependencies** without a shared-change request. The dependency set is closed (research §8.3–§8.4).
7. **Every user-visible string is a resource.** A hardcoded literal in a composable is a review failure.
8. **Every number goes through `Fmt`.** Do not call `String.format` or `NumberFormat` in a feature.
9. **No `ModalBottomSheet`, `AlertDialog`, `NavigationBar`, `Surface`, `Card`, `Button`, `OutlinedTextField` or `Icons.Filled.*` in feature code.** Use `NtSheet`, `NtAlert`/`NtActionSheet`, `NtTabBar`, `Modifier.background`, `PrimaryButton`/`SecondaryButton`/`GhostButton`, `BasicTextField`, `NtIcon`.
10. **Report at the end**: files created (absolute paths), shared changes needed, strings added to your `strings_<x>.xml`, and any behaviour you could not reproduce with a reason.

## Screen inventory — the checklist

Every item must exist and behave as the iOS original. Grouped by owner package.

**Shell** — `RootScreen` (+ `PendingRouteConsumer`) · `StoreErrorScreen` · `MainTabScaffold` + `NtTabBar` (5 tabs: today `home`, train `fitness_center`, fuel `restaurant`, progress `trending_up`, bro `group`) · the workout layer (`ActiveWorkoutScreen` expanded, `WorkoutMiniBar` collapsed) hosted by the scaffold.

**Onboarding** — `WelcomeScreen` · `SetupYouScreen` · `ObTargetEditorSheet` · `SetupScheduleScreen` · `ObDayTimeSheet` · `SetupPairScreen`.

**Dashboard** — `DashboardScreen` · `WeekStrip` · `NextSessionCard` · `FuelSummaryRow` · `LastSessionRow` (+ `FlowChips`).

**Workout** — `TrainScreen` + `AlreadyActiveDialog` · `RoutineRow` + `StartPill` · `WorkoutHistoryRow` · `WorkoutDetailPresenter` + `WorkoutDetailSheet` (read) + `WorkoutEditContent` / `WorkoutEditHeader` (edit) · `ActiveWorkoutScreen` + `MinimizeButton` · `WorkoutMiniBar` · `WorkoutExerciseSection` · `SetRow` · `RestPill` + `RestExpiryWatcher` · `RestTimerSheet` · `RestAlertPermissionPrompt` · `ExercisePickerSheet` + `ExercisePickerRow` + `CreateExerciseRow` · `WorkoutDoneScreen` + `WorkoutRecordRow`.

**Fuel** — `FuelHomeScreen` (+ subviews: `FuelHeader` with `DayChevron` / `DateButton` / `TodayPill`, hero ring, meal sections with `FuelMealHeaderRow` / `FuelEntryRow` / `EntrySwipeRow`, `FuelUndoToast`, add bar) · `FuelCalendarSheet` · `FoodSearchSheet` (+ `FoodRow`, `FoodResultRow`, `FoodRecentRow`, `FoodSectionLabel`, `FoodStateRow`) · `PortionSheet` + `PortionPickSheet` · `QuickAddSheet` · `EntryDayStepper` · `BarcodeScannerScreen` · `BarcodeLookupPrompts` · `ProductLabelSheet` · `AIScanScreen` (`AIScanSourceView`, `AIScanAnalyzingView`, `AIScanResultView`, `AIScanFailedView`, `AIScanSignedOutView`) · `AIScanFoodRow` + `ConfidenceDots` + `AIScanPhoto`/`DetectionTag` + `LogToMealButton` · `AIScanItemSheet`. (`AIScanAddMissedSheet` is gone: "add missed" is the search sheet in pick mode.) (`KcalLabel` and `ConfidenceDots` are **design-system** components — see the table above — not files in `feature/fuel/`: `KcalLabel` has two callers, `FoodSearchRows.kt` and `AIScanFoodRow.kt`.)

**Progress** — `ProgressHomeScreen` · `ProgressLiftRow` · `BodyTab` + `BodyDeltaLine` · `LogWeightSheet` · `ExerciseProgressScreen` · `SparklineChart` · `BodyWeightChart` · `E1RMChart` · `WeeklyVolumeChart`.

**Bro** — `BroScreen` · `BroSignedOutView` · `BroUnpairedView` · `BroSharedWeekCard` · `BroStatusCell` · `BroHeadsUpRow` + `HeadsUpChip` · `BroLogSection` + `BroLogRow` · `CantMakeItSheet` + `SheetChip` + `NtFlowLayout`.

**Auth** — `SignInSheet`.

**Settings** — `SettingsSheet` (+ `VersionFooter`) · `NameEditor` · `BodyWeightEditor` · `DailyTargetEditor` · `ScheduleEditor` · `RestTimerEditor` · `UnitsEditor` · `LanguageEditor` · `NotificationsEditor` · `HealthEditor` · `ExportEditor` · `AIProviderEditor` · `PartnerEditor` · `SettingsCodeRow` · the `NtGroup`/`NtLinkRow`/`NtActionRow`/`NtCheckRow`/`NtInfoRow`/`NtEditorScaffold` family.

## Things that are NOT negotiable

- **Rest timer state = `RestTimerController` only**, whose truth is an absolute `endAt` in DataStore. Never run a `Timer`/`Handler` loop that owns the countdown; render from a wall-clock-aligned 1 s ticker and always recompute from `endAt`. The notification's countdown is rendered by the system, not by the app.
- **No health data leaves the device** except the AI photos (plate or nutrition label, with the typed details; explicit user action) and the user's own attendance and schedule (to the backend while signed in).
- **Never wipe or swap the store.** No in-memory fallback, no destructive migration on upgrade (only on downgrade), no deletion of a corrupt file; a store that fails to open shows `StoreErrorScreen`, and only the user's "Start with empty data" moves the files. Every entity change follows the migration policy in `data/db/Migrations.kt` and keeps `DatabaseMigrationsTest` green.
- **AI prompts, schemas and limits come from `backend/data/ai/estimate-spec.json`** (packaged as an asset). Change them there, never in Kotlin, and keep `AIFinalizer` passing every shared fixture.
- **Every user-visible string localized in both languages, every number through `Fmt`.** The generated `strings.xml` files are never hand-edited; new keys go into `Localizable.xcstrings` and get regenerated.
- **Dark-only.** No `isSystemInDarkTheme()`, no dynamic colour, no `forceDark`, no tonal elevation. Tokens only.
- **No Material chrome.** No ripple (`LocalIndication provides NTPressScale`), no `NavigationBar`, no `Surface`/`Card` tinting, no `AlertDialog`, no default drag handle, no Android overscroll glow.
- **Never write a bare `tween(n)`** — always pass `easing`. Its default is Material's curve, not iOS's.
- **Liquid Glass is part of "identical".** On iOS 26 the SYSTEM chrome this app uses (TabView tab bar, Toggle switches, wheel DatePicker, Menu, sheets, alerts, confirmationDialogs, navigation-bar buttons, share sheet) renders with Apple's Liquid Glass material even though the app's own code has zero shaders. Android must replicate that look 1:1 through ONE shared module, `designsystem/glass/` (`Modifier.liquidGlass(shape, style)`: backdrop capture + AGSL refraction/lensing + blur + specular rim + adaptive tint on API 33+, RenderEffect blur fallback on 31–32, translucent tint below), used ONLY by the NT chrome components that mirror those system components: `NtTabBar`, `NtToggle`, `NtSheet`, `NtAlert`, `NtActionSheet`, `NtMenu`, `NtWheelPicker` highlight, nav-bar `NtGlassButton`. Feature code never calls blur/shader APIs directly, and non-chrome surfaces (cards, rows, chips, rings, charts) stay flat exactly like iOS. See docs/android-glass.md for the visual spec and reference screenshots. No chart library, no icon library.
- **Never ship SF Symbols or SF Pro**, or path data traced from them.
- **`SCHEDULE_EXACT_ALARM`, never `USE_EXACT_ALARM`.** No foreground service for the rest timer. No full-screen intents.
- **Declare no media-storage permission.** The photo picker needs none. `CAMERA` is the only camera-related permission.
- **`compileSdk 37`, `targetSdk 36`, `minSdk 26`, AGP ≥ 9.1.0.** Do not lower them; compose.ui 1.12.0 and androidx.core 1.19.0 hard-require compileSdk 37 + AGP 9.1.0, and Play requires target 36.
- **Keep files under ~400 lines**; split composables into subviews in the same package.
