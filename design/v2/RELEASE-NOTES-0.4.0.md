A calmer No Tomorrow on both apps. The five tabs and the look haven't changed. Each screen now has one focal card, charts replace numbers where a shape says more, and your partner shows up where it matters. The design and a clickable prototype are at https://joxan2137.github.io/notomorrow/.

## What's new

- **Today.** The week strip shows who trained: an ember dot for you and, when paired, green for your partner (rose when they missed). Gym days that are still ahead get a thin ring. The next-session card is the screen's focal card. The Fuel row's ring is split into protein, carbs and fat, and the macro bars use the same colours.
- **Train.** An "Up next" card at the top shows the routine Today suggests, its exercises with sets × reps, and Start (or Resume while a workout runs). The other routines get a small play button. History is grouped into This week, Last week and Earlier.
- **Suggested weight.** If every set of your last session hit its target at the same weight, the exercise suggests the next step (+2.5 kg / +5 lb). "Use" fills it into your open sets. Nothing changes until you tap it.
- **Workout.**
  - A thin rail under the header shows how far along each exercise is. Tap a segment to jump to it.
  - The ticks are bigger and rounded.
  - Done sets get a faint ember tint instead of being dimmed, so they stay readable.
- **Fuel.** The kcal ring shows where the calories came from: protein blue, carbs amber, fat violet. The macro bars and the "protein to go" hint use the same colours.
- **Progress.** Lifts opens on a chart: pick a lift and see its estimated 1RM, the change over 1M / 3M / 1Y / All, and the trend. "Muscles this week" colours a body map by sets per muscle, lists your five most-trained muscles, and names the big ones you haven't trained yet. The body-weight card now lives on the Body tab only.
- **Bro.** The shared week is the screen's focal card.

## Downloads

- **Android:** `NoTomorrow-0.4.0-android.apk`. Sideload it on Android 8.0+ (enable "install unknown apps"). It's signed with the same key as 0.2.1 and 0.3.0, so it installs over them and keeps your data. The `.aab` is for Play Console uploads.
- **iOS:** `NoTomorrow-0.4.0-ios-unsigned.ipa`. It's unsigned: install it with AltStore, Sideloadly or similar, which re-sign it with your Apple ID. HealthKit sync needs a paid developer certificate, so it isn't available in re-signed builds. Requires iOS 17+.

Binaries are built by the [release workflow](https://github.com/joxan2137/NoTomorrow/blob/main/.github/workflows/release.yml) from this tag.
