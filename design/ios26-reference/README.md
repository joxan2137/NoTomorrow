# iOS 26.1 reference captures (iPhone 17 Pro simulator, 402×874 pt @3x = 1206×2622 px)

Captured 2026-09-05 from the shipped iOS build (`build/DerivedData/.../NoTomorrow.app`) with `xcrun simctl io <udid> screenshot`. These are the ground truth for the Android port's Liquid Glass chrome (see `docs/android-glass.md`). `zoom-*.png` are crops (mostly 4 px/pt, see the file names). `probe/` holds the measurement harness (Swift probe app `LG.app`, engineered backdrops, view-tree dumps, PNG decoder `png.js`) used to derive the numbers in `docs/android-glass.md`.

| File | What it shows |
|---|---|
| 01-tabbar-dashboard.png | Floating glass tab bar over the dashboard (selected pill on Today); the app's opaque `UITabBarAppearance` is ignored by iOS 26 |
| 02-tabbar-train.png | Tab bar over the Train tab; text under the left edge is refracted (lensing) — see zoom-tabbar-train-lensing.png |
| 03-tabbar-fuel.png | Fuel tab with the pinned 3-tile bottom bar above the tab bar |
| 04-tabbar-progress.png | Progress tab (custom, non-glass segmented control) |
| 05-tabbar-bro.png | Bro tab, signed-out state |
| 06a/06b-tabbar-rubberband.png | Frames during a slow rubber-band drag (content moving under the bar) |
| 07-sheet-settings.png | `.large` sheet (opaque) with the glass "Done" capsule in the nav bar |
| 08-toggle-resttimer-editor.png | Rest-timer editor: glass back button, −/+ stepper, Toggle ON (white tint) |
| 09/10*-toggle-*.png | Failed OFF/drag attempts (all ON) — ignore |
| 11-wheel-schedule-editor.png | Wheel DatePicker (hour/minute) with the glass selection band; day tiles |
| 12-probe-grid-tabbar-by-agent.png | The probe app's grid backdrop under a 5-tab bar (measurement) |
| 13b/13c-toggle-*.png | Toggle OFF (13c-toggle-t1.png is the clean OFF frame) |
| 14*-toggle-*.png | Drag attempt: the switch flipped ON immediately; no mid-drag stretch was captured |
| 15-active-workout.png | Active workout full-screen cover (no tab bar) |
| 16-menu-setkind.png | SwiftUI `Menu` popover with glass background over the set rows |
| 17-confirmationdialog-finish.png | `confirmationDialog` on iOS 26: centred glass card, title, stacked capsule buttons (destructive in rose), no Cancel row |
| 18-foodsearch-sheet.png | Food search `.large` sheet with results |
| 19-portionsheet-partial.png | `PortionSheet` — fixed-height (376 pt) partial sheet: floating card inset from the edges, grabber, opaque surface over a dimmed scrim; see zoom-19-portionsheet.png |
| zoom-toggle-states.png / zoom-13-toggle.png / zoom-13bc.png / zoom-toggle-drag-sequence.png | Toggle crops (ON / OFF) |
| zoom-navbar-back-glass.png / zoom-navbar-done-glass.png | Nav-bar glass buttons |
| zoom-sheet-top-corners.png | `.large` sheet top corners |
| zoom-tabbar-dashboard.png / zoom-tabbar-train-lensing.png | Tab bar crops |
