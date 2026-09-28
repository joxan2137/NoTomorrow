A new Fuel calendar widget that looks like GitHub's contribution graph, and Android now tells you when an update is out.

## What's new

### Widgets
- **Fuel calendar.** Each column is a week and each small square is a day: the colour shows how close you got to your calorie goal, a white dot marks a day you worked out, and today has a white outline. The only text is the "No Tomorrow calendar" label. The medium widget shows about four months; the large one wraps about seven months into two rows. It now goes back as far as a year, so wider widgets always have history to fill.
- **Gym week.** On the wide widget the week's days sit in a subtle rounded tray.

### Updates on Android
- **Update banner.** When a newer release is on GitHub, a banner at the top of the app says so. Tap it to open the release page and download the new APK, or dismiss it until the next release. iOS has had this since 0.6.2.

## Good to know

- Everything from 0.6.3 carries over. Your data is kept when you install over it.
- The widget changes are on iOS and Android. On the smallest Android size the calendar drops its label to keep the squares big.

## Downloads

- **Android:** `NoTomorrow-0.6.4-android.apk`. Sideload it on Android 8.0+ (enable "install unknown apps"). It installs over 0.6.3 and keeps your data. The `.aab` is for Play Console uploads.
- **iOS:** `NoTomorrow-0.6.4-ios-unsigned.ipa`. It's unsigned: install it with AltStore, Sideloadly or similar, which re-sign it with your Apple ID. HealthKit sync needs a paid developer certificate, so it isn't available in re-signed builds. Requires iOS 17+.

Binaries are built by the [release workflow](https://github.com/joxan2137/NoTomorrow/blob/main/.github/workflows/release.yml) from this tag.
