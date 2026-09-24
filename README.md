# No Tomorrow

Train today. Track everything. Keep your bro honest.

No Tomorrow is a gym and nutrition app built around one idea: you pair with one gym partner, share a
weekly schedule, and each of you sees whether the other actually showed up. Around that sit
routines and set-by-set workout logging with rest timers (a workout in progress shrinks to a bar
above the tabs, and finished workouts can be edited), a food log with day-by-day history and a
calorie heatmap scored against your goal, barcode and text search (Open Food Facts), AI photo
estimates and nutrition-label reads, and progress charts per exercise.

There are two native apps and one small backend:

| Part | Where | Stack |
|---|---|---|
| iOS app | `NoTomorrow/`, `NoTomorrowWidgets/`, `Shared/` | Swift, SwiftUI, SwiftData, iOS 26 |
| Android app | `android/` | Kotlin, Jetpack Compose, Room, own Liquid Glass shader module |
| Backend | `backend/` | Node 22, TypeScript, Hono, Postgres 16, Fly.io |

The Android app is a port of the iOS one that aims to be visually identical, down to the measured
iOS 26 Liquid Glass tab bar. `docs/` holds the specs both apps are built from; `design/` the
reference captures they are checked against.

## Build

**iOS.** The Xcode project is generated: `brew install xcodegen`, then `xcodegen generate` in the
repo root and open `NoTomorrow.xcodeproj`. Signing is yours to set.

**Android.** `cd android && ./gradlew :app:assembleDebug` (JDK 21, Android SDK with API 36). Push
notifications need a `google-services.json` from your own Firebase project; without it the app
builds and simply does not register for push.

**Backend.** See [`backend/README.md`](backend/README.md) — a Postgres, a `.env` from
`.env.example`, `npm run dev`. Deploys to Fly.io with `fly deploy`.

**Releases.** Publishing a GitHub release runs
[`release.yml`](.github/workflows/release.yml), which builds both apps and attaches an unsigned
iOS `.ipa` (AltStore, Sideloadly and friends re-sign it on install) plus an Android `.apk` and
`.aab` to the release. The tag (`v1.2.3`) becomes the version string and the run number the build
number. The Android build is signed with your own keystore when the `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` repository secrets
exist, and with the debug key otherwise. The workflow can also be run by hand from the Actions tab.

Both apps talk to `https://notomorrow-api.fly.dev` by default (`AppConfig` in each app); point them
at your own deployment, or flip the in-app *demo data* switch to run fully offline.

## AI photo estimates

A photo of a plate becomes a list of foods with grams and macros, which you can correct and send
back for a better answer; a photo of a nutrition table fills in a product the barcode database
does not know. Three ways to run it:

- **Standard** — the app sends the photo to the backend, which calls Gemini with the server's key.
  The server owner decides who may use it: `AI_ALLOWED_USERS` (comma-separated usernames). Anyone
  not on the list is told to ask the owner for a spot, or to bring their own key.
- **Gemini with your API key** — the phone calls Google directly with a key from Google AI Studio.
  The key lives in the Keychain / Android Keystore and never touches the backend.
- **Claude with your API key** — the same, against Anthropic's Messages API.

Every path shows a consent step the first time: the photo (without location or other metadata),
the meal details you typed, the meal slot and your language leave the phone; nothing else does.
Prompts, JSON schemas and the generic food table live in `backend/data/ai/estimate-spec.json`; the
backend and both apps (which bundle it for the bring-your-own-key paths) finalize answers the same
way and are checked against the shared fixtures next to it.

## Repository map

```
NoTomorrow/          iOS app (Features/, Services/, Models/, Resources/Localizable.xcstrings)
android/app/src/     Android app (app.notomorrow: feature/, designsystem/, service/, data/, net/)
backend/src/         API (routes/, auth/, ai.ts, aiFinalize.ts, push.ts, jobs.ts), migrations/, test/
backend/data/ai/     AI spec (prompts, schemas, food table) + fixtures, shared with both apps
docs/                architecture.md, backend.md, android-*.md — the specs
design/              iOS reference captures, Android parity captures, and the v2 redesign (design/v2)
scripts/             string pipeline: xcstrings → Android res + typed keys; font fitting
```

Strings live once, in `NoTomorrow/Resources/Localizable.xcstrings` (English and Polish);
`python3 scripts/xcstrings_to_android.py && python3 scripts/gen_string_keys.py` regenerates the
Android resources. Never hand-edit the generated files.

## Tests

- Backend: `cd backend && npx vitest run` (`npm run ai:fixtures` checks the AI finalizer against
  the shared fixtures)
- Android: `cd android && ./gradlew :app:testDebugUnitTest`
- iOS: the `NoTomorrowTests` scheme in Xcode

## License

MIT — see [LICENSE](LICENSE).
