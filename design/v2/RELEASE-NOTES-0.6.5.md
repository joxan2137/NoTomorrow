Every food you add in Fuel is now saved to your foods, so you can find it and add it again later.

## What's new

### Fuel
- **Quick adds are saved.** Quick add has a new "Save to your foods" switch, on by default. The food is logged and also kept in your list, so it shows up under Recent and "Your foods" in search and opens the normal portion sheet next time.
- **Save for later.** A new "Save for later" button in quick add keeps the food in your list without logging it.
- **Optional weight.** Quick add now takes grams. With a weight the saved food scales to any portion later; without one, the whole portion counts as 100 g.
- **AI scan items are saved.** Every item the AI scan estimates is kept in your foods when you log the meal.
- **No duplicates.** Saving a food with the same name again (ignoring case and Polish accents) updates it instead of adding a second copy, and an AI estimate never overwrites numbers you typed yourself. Barcode and Open Food Facts products are never merged with these.

## Good to know

- Everything from 0.6.4 carries over. Your data is kept when you install over it.
- Only foods added from now on are saved; earlier quick adds and AI scans stay as they were in your diary.
- The change is on iOS and Android.

## Downloads

- **Android:** `NoTomorrow-0.6.5-android.apk`. Sideload it on Android 8.0+ (enable "install unknown apps"). It installs over 0.6.4 and keeps your data. The `.aab` is for Play Console uploads.
- **iOS:** `NoTomorrow-0.6.5-ios-unsigned.ipa`. It's unsigned: install it with AltStore, Sideloadly or similar, which re-sign it with your Apple ID. HealthKit sync needs a paid developer certificate, so it isn't available in re-signed builds. Requires iOS 17+.

Binaries are built by the [release workflow](https://github.com/joxan2137/NoTomorrow/blob/main/.github/workflows/release.yml) from this tag.
