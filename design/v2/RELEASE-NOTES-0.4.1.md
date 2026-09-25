A bit of shine on both apps, using effects from [libraries.dev](https://libraries.dev/). Nothing about how the app works has changed.

## What's new

- **AI photo estimate.** While your plate is being analysed, a dotted "thinking" orb sits over the photo and a warm sunset glow runs round its edge, in place of the spinner.
- **Nutrition label and barcode lookups.** Reading a label and looking up a barcode show a small orb in place of the spinner.
- **Exercise search.**
  - The search field has a liquid-metal edge. It stays quiet until you tap into it.
  - Results are now separate cards, with the details button inside each card.
  - Exercises you pick get a silver liquid-metal edge, so your selection stands out while you scroll.
- The orbs stand still when reduced motion is on (Android: "Remove animations"). On Android 8 to 12 the glow round the photo is left out and the metal edge is drawn still.

## Downloads

- **Android:** `NoTomorrow-0.4.1-android.apk`. Sideload it on Android 8.0+ (enable "install unknown apps"). It installs over 0.4.0 and keeps your data. The `.aab` is for Play Console uploads.
- **iOS:** `NoTomorrow-0.4.1-ios-unsigned.ipa`. It's unsigned: install it with AltStore, Sideloadly or similar, which re-sign it with your Apple ID. HealthKit sync needs a paid developer certificate, so it isn't available in re-signed builds. Requires iOS 17+.

Binaries are built by the [release workflow](https://github.com/joxan2137/NoTomorrow/blob/main/.github/workflows/release.yml) from this tag.
