No photo? Describe the meal and the AI estimates it anyway.

## What's new

### Fuel
- **Describe a meal.** A new Describe button sits at the end of the add bar, next to AI photo, Barcode and Search. Type what you ate ("2 schabowe, ziemniaki z masłem, mizeria") and tap Estimate calories. You get the same editable list as an AI photo: fix the grams, remove or add items, recalculate with details, then log it.
- **More accurate with amounts.** Weights, counts and cooking fat in the description are taken as given. Anything you leave out is estimated from typical portions, and the confidence dots show how sure the estimate is.
- **Room for four buttons.** The add bar keeps its full-size buttons. In Polish the barcode button now reads "Skaner" so all four labels fit on small phones.

## Good to know

- Everything from 0.6.7 carries over. Your data is kept when you install over it.
- Describe uses the same AI provider as photos (Standard, or your own Gemini or Claude key). The first time, it asks before your description leaves the phone. If you already agreed to send photos, it doesn't ask again.
- With the Standard provider, a description counts toward the same daily AI limit as a photo.
- The feature is on iOS and Android. The server needs this release too for Standard; the bring-your-own-key paths work on their own.

## Downloads

- **Android:** `NoTomorrow-0.6.8-android.apk`. Sideload it on Android 8.0+ (enable "install unknown apps"). It installs over 0.6.7 and keeps your data. The `.aab` is for Play Console uploads.
- **iOS:** `NoTomorrow-0.6.8-ios-unsigned.ipa`. It's unsigned: install it with AltStore, Sideloadly or similar, which re-sign it with your Apple ID. HealthKit sync needs a paid developer certificate, so it isn't available in re-signed builds. Requires iOS 17+.

Binaries are built by the [release workflow](https://github.com/joxan2137/NoTomorrow/blob/main/.github/workflows/release.yml) from this tag.
