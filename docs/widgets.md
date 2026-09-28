# Home-screen widgets and the rest chime

Four widgets on both platforms, one look. iOS builds them with WidgetKit in `NoTomorrowWidgets/` (data through
the App Group, see below); Android with Jetpack Glance in `android/.../widget/` (same process as the app, reads
Room directly). The rest timer's Live Activity / ongoing notification is unchanged and keeps working alongside.

| Widget | iOS kind | iOS families | Android provider | Tap (outside buttons) |
|---|---|---|---|---|
| Quick log | `nt.widget.fuel` | small, medium | `QuickLogWidgetReceiver` (2×2 … 4×2) | `notomorrow://fuel` → Fuel on today |
| Fuel calendar | `nt.widget.history` | medium, large | `FuelCalendarWidgetReceiver` (4×2 … 4×4) | `notomorrow://fuel` |
| Gym week | `nt.widget.week` | small, medium, lock screen rectangular + inline | `WeekWidgetReceiver` (2×2 … 4×2) | `notomorrow://today` |
| Break timer | `nt.widget.rest` | small, medium | `BreakTimerWidgetReceiver` (2×2 … 4×2) | `notomorrow://workout/rest` (the workout, when one runs) |

## Look

Every widget: `ground` background (`#0A0A0B`, iOS `containerBackground`), 16 pt content margins, dark only.
Eyebrows are `11 pt semibold`, tracking 0.9, uppercase, `ink2`, except where ember is allowed by the app's rules
(the next-session eyebrow, the rest countdown). Hero numbers use the display face (Big Shoulders Display
ExtraBold) and are tabular. Macro hues only for food, ember only for progress / you / the heat scale.
Buttons inside widgets are `surface2` capsules with `ink` text (`subheadlineBold`), the primary one white on
`ground`; minimum 36 pt tall (the widget is the hit target on small sizes).

**Android look (v3, bolder).** On Android the widgets go further than the iOS baseline below: fewer
things per widget, each one bigger. Eyebrows are `12 pt bold` with a 14 dp glyph, titles `20 pt bold`,
buttons 40 dp tall (up to 48 on the medium Break timer) with `16 pt bold` labels. No explanatory captions:
the controls say what they do.
- The surface is a `#1A1A1D` → `ground` vertical gradient with a faint ember glow in the top-right corner
  and a 1 dp white-10 % hairline edge (`widget_background.xml`). Capsules get the same hairline over a
  `surface3` → `surface2` gradient.
- **Quick log small**: just the ring, as big as the widget allows, with kcal left + `dashboard.left`
  inside, and one full-width capsule for the first quick food (`+` and the name). With no quick foods
  the ring fills the widget. **Medium**: the ring at full height on the left; on the right protein /
  carbs / fat as three columns (a 5 dp bar in the macro hue, full at the goal; the macro's name; grams
  eaten in the display face; "/ 180 g" under it), then as many quick-food capsules (`+`, name, kcal)
  as fit under them (1 at 4×2, up to 3). With no quick foods the macros sit centred. Over the goal the
  ring shows "+250" and `widget.fuel.over` in `bad`.
- **Fuel calendar**: the squares described under "Fuel calendar" below, drawn as one bitmap
  (`WidgetCharts.contributions`) centred in the widget.
- **Gym week**: the eyebrow is the next session's day in ember (`day.today`, `day.tomorrow` or the
  weekday), then the routine the app suggests in the title style (`dashboard.nextSession` without one),
  then the time in the display face. **Small**: those three stacked over the 16 dp strip. **Medium**:
  day and routine on the left, the time (50 pt) on the right, and under them the full strip across
  the width in a tray (`widget_tray.xml`: white 5 %, the hairline edge, 16 dp radius, 6 × 8 dp padding): weekday letters (today's in `ink`), 30 dp circles with dates, who-trained dots only when
  paired. With no gym days: the `widget.week.name` eyebrow and `widget.week.noSchedule`, then the strip.
- **Break timer**: the ring uses a 9 dp line and fills the height. **Small idle**: eyebrow, the default
  length in the display face (60 pt), the three length capsules. **Small running**: the ring over
  `+15` / Skip. **Medium**: the ring, and beside it the eyebrow and the buttons (idle), or the eyebrow,
  the exercise (title style), the next set and `−15` / `+15` / Skip (running). For 2 min after a rest
  ends the widget glows ember from the bottom (`widget_background_go.xml`), the ring is full, the
  eyebrow is ember and `timer.notification.title` takes the default length's place in the title style.

Check Android layouts without a device: `./gradlew :app:testDebugUnitTest --tests '*WidgetScreenshots*'
-PwidgetShots=<dir>` renders every size and state through Glance's RemoteViews.

### Quick log

- **Small.** Top-left eyebrow `fuel.kcalLeft`. The kcal ring (`MacroRing`: a `surface2` track, the eaten kcal split
  into protein / carbs / fat arcs by where the calories came from, 4/4/9 kcal per gram, clockwise from 12
  o'clock, round caps, 8 pt line, 64 pt) with kcal left in the display face (26 pt) inside. At the bottom one
  full-width capsule button that logs the **first** quick food: `+` glyph, the name (1 line, truncating), kcal on
  the trailing side in `ink2`.
- **Medium.** Left column (≈ 42 %): the ring at 92 pt with kcal left (display 34) + `dashboard.left`, and under it
  `fuel.eatenGoal` in `footnote` `ink2`. Right column: up to **3** quick-food rows, 1 pt `hairline` dividers,
  each row a button: name (`subheadlineBold`, 1 line), `"<grams> g · <kcal> kcal"` (`footnote` `ink2`), and a
  30 pt `surface2` circle with a `+` on the trailing side.
- **Just logged.** For 4 s after a tap the row's `+` becomes an ember-free `good` (green) checkmark and the
  secondary line reads `widget.fuel.logged`; the ring and numbers already include the new entry.
- **Empty** (no quick foods yet): the right column / the button area shows `widget.fuel.empty` in `footnote`
  `ink2`; the whole widget opens Fuel.
- Kcal over goal: kcal left shows `0` and the ring is full (the app's Fuel hero rules).

**Quick foods** (`QuickFoods.pick`, same rule on both platforms): the meal entries of the last 60 days, grouped by
food id, or by folded `customName` for entries without a food. Rank by number of entries (desc), then by the
newest `loggedAt` (desc); take the top 4. Each carries the **newest** entry's name, grams, kcal, P/C/F and
`isAIEstimate`, and the food id when there is one.

**Logging** a quick food inserts a `MealEntry` for today (`day` = local midnight now, `loggedAt` = now) in the slot
`MealSlot.suggested(now)` (before 11 breakfast, before 15 lunch, before 18 snack, else dinner) with the quick
food's grams, figures and AI flag; a food-backed one links the food and bumps its `useCount` / `lastUsedAt`.
No app launch, no sheet: one tap is one entry. The widgets then refresh.

### Fuel calendar

The Fuel history heat as a GitHub contribution graph (`FuelCalendar`: levels, colours `heat[0…4]`), with training
on top of it, and nothing else: no header, month or weekday names, or legend. Same metrics on both platforms (iOS
`ContributionGrid`, Android `WidgetCharts.contributions`).

- **Grid**: one column per week, Monday on top, the current week at the right; days still ahead this week are left
  out. Cells are square with a gap of 0.22 of the pitch and a radius of 0.24 of the side, as big as the height
  allows but no more than a 22 pt pitch. When one band at that cap would leave height over and two bands still get
  a pitch of at least 15 pt, the weeks wrap into two bands (older half on top, 12 pt apart). As many weeks as fit
  the width (at most `WidgetSnapshot.CalendarGrid.weeks` = 53, which is also how far back the app publishes and
  Android reads). The grid is centred in the widget.
- **Trained days** (any finished workout with ≥ 1 completed set that started that day): a centred `ink` dot, 40 % of
  the cell side, on top of the heat colour (a trained day with nothing logged still gets its dot, on `heat[0]`).
  Today's cell has a 1.5 pt `ink` ring.
- Medium ≈ 16 weeks in one band, large ≈ 30 weeks in two.

### Gym week

- **Small.** Eyebrow `dashboard.nextSession` in ember; the routine the app suggests (`suggestedRoutine`) in
  `headline` (1 line); the session time in the display face (40 pt); the relative day (`day.today`,
  `day.tomorrow`, or the weekday's short name) in `footnote` `ink2`. Bottom: the 7-day strip as 12 pt circles.
- **Medium.** Left the same next-session block; right the full strip: weekday letters (`weekday.*`, `caption`,
  `ink3`), 26 pt circles, and under each a 5 pt row of who-trained dots (ember for you, green / rose for your
  partner, only when paired). Under the strip `widget.week.done %lld %lld` (attended of gym days this week).
- Circle states (the Dashboard's `WeekStripView`): attended = ember fill with a dark check; missed or cancelled
  = rose 1.5 pt ring with a rose ×; today = white fill with the date number in `ground`; a planned or confirmed
  gym day ahead = 1 pt `border` ring around an `ink` number; rest days = the number in `ink3`, no ring.
- No gym days: `widget.week.noSchedule` in place of the next session.
- **Lock screen (iOS).** Rectangular: `dashboard.nextSession` · time, routine, relative day. Inline:
  `"<relative day> <time> · <routine>"`.

### Break timer

- **Idle, small.** Eyebrow `timer.rest`; the default rest (`UserProfile.defaultRestSeconds`) as `m:ss` in the
  display face (44 pt); caption `widget.rest.start`; a row of three capsules: `1:00`, the default (white, primary)
  and `2:00` — when the default is 60 or 120 the row is `1:00 · 1:30 · 2:00` with the default white. Tapping one
  starts a rest of that length **with no app launch**: the same `RestTimerController.start` the workout uses
  (Live Activity / ongoing notification, the end alert and the chime), labelled with the workout's current
  exercise when a workout runs, else with `timer.rest`.
- **Running, small.** An ember progress ring (6 pt, `surface2` track, 84 pt) with the live countdown inside
  (system-rendered: iOS `Text(timerInterval:)` / `ProgressView(timerInterval:)`, Android `Chronometer`
  counting down), the exercise under it (`footnote`, 1 line), and two capsules: `+15` and `common.skip`.
- **Medium.** Ring 110 pt on the left; right: eyebrow, exercise, "up next" line (`nextSetLabel`), and the buttons
  (idle: the three lengths; running: `−15`, `+15`, `common.skip`).
- **Just ended** (up to 2 min after the end): the idle layout with `timer.notification.title` in place of the
  caption.

## Data flow

**iOS.** The widget extension cannot open the app's SwiftData store, so the app publishes a `WidgetSnapshot`
(JSON, `Shared/WidgetSnapshot.swift`) into the App Group container `group.app.notomorrow.ios` (`SharedStore`; a
sideloading tool that renames groups is honoured through the `ALTAppGroups` Info.plist key). `WidgetSync`
rebuilds it after every SwiftData save (debounced 0.5 s), on foreground / background and at midnight, and asks
WidgetKit to reload. Without a usable App Group every widget shows `widget.setup`.

- *Quick log* taps run `LogQuickFoodIntent` in the extension: it appends the entry to the pending queue
  (`nt.widget.pendingLogs` in the group defaults), patches the snapshot's today totals so the ring moves at once,
  and reloads. The app drains the queue into SwiftData on launch and every return to the foreground
  (`WidgetSync.ingestPendingLogs`), exactly once per entry (ids).
- *Break timer* buttons are `LiveActivityIntent`s, so iOS runs them **in the app's process** (launching it in the
  background when needed): they call `RestTimerController.shared`, which starts / adjusts / ends the Live Activity,
  the notification and the widget together. The controller now persists its state in the group defaults, so
  the widget reads the same end date.

**Android.** Glance widgets read Room through `AppContainer` in the app process. `WidgetUpdater` refreshes them from
a Room invalidation flow over `meal_entry`, `workout`, `set_entry`, `attendance_record`, `gym_schedule`,
`user_profile` (debounced 0.5 s), from the rest timer's state flow, and at midnight (`updatePeriodMillis` 30 min
as a floor). Quick-log and timer buttons are `ActionCallback`s that call the same code the app uses.

## The rest chime

`scripts/sounds/rest_over.py` renders one sound (≈ 1.9 s): an airy noise swell into a soft sub drop, a mallet tick
and a rising A–E–C♯–E arpeggio of FM "tine" notes (Rhodes / celesta family), chorused across the stereo field,
through a synthetic plate reverb. Outputs: `NoTomorrow/Resources/Sounds/rest_over.caf` (16-bit PCM, the
format iOS notification sounds need) and `android/app/src/main/res/raw/rest_over.ogg`.

- The "Rest is over" notification plays it on both platforms (iOS `UNNotificationSound(named:)`; Android the
  channel `nt.rest.chime`, which replaces `nt.rest.done` because a channel's sound is fixed at creation).
- With the workout full screen the notification is suppressed (haptic only, as before), so the app plays the
  chime itself: iOS `RestChime` (AVAudioPlayer, `.ambient`, so the ringer switch still silences it and music keeps
  playing), Android `RestChime` (`MediaPlayer`, `USAGE_NOTIFICATION_EVENT`).
