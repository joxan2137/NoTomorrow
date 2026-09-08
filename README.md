# No Tomorrow

Train today. Track everything. Keep your bro honest.

No Tomorrow is a gym and nutrition app built around one idea: you pair with one gym partner, share a
weekly schedule, and each of you sees whether the other actually showed up. Around that sit
routines and set-by-set workout logging with rest timers, a food log with barcode and text search
(Open Food Facts) and AI photo estimates, and progress charts per exercise.

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

Both apps talk to `https://notomorrow-api.fly.dev` by default (`AppConfig` in each app); point them
at your own deployment, or flip the in-app *demo data* switch to run fully offline.

## AI photo estimates

A photo of a plate becomes a list of foods with grams and macros. Three ways to run it:

- **Standard** — the app sends the photo to the backend, which calls Gemini with the server's key.
  The server owner decides who may use it: `AI_ALLOWED_USERS` (comma-separated usernames). Anyone
  not on the list is told to ask the owner for a spot, or to bring their own key.
- **Gemini with your API key** — the phone calls Google directly with a key from Google AI Studio.
  The key lives in the Keychain / Android Keystore and never touches the backend.
- **Claude with your API key** — the same, against Anthropic's Messages API.

Every path shows a consent step the first time: the photo (without location or other metadata),
the meal slot and your language leave the phone; nothing else does.

## Repository map

```
NoTomorrow/          iOS app (Features/, Services/, Models/, Resources/Localizable.xcstrings)
android/app/src/     Android app (app.notomorrow: feature/, designsystem/, service/, data/, net/)
backend/src/         API (routes/, auth/, ai.ts, push.ts, jobs.ts), migrations/, test/
docs/                architecture.md, backend.md, android-*.md — the specs
design/              iOS reference captures and Android parity captures
scripts/             string pipeline: xcstrings → Android res + typed keys; font fitting
```

Strings live once, in `NoTomorrow/Resources/Localizable.xcstrings` (English and Polish);
`python3 scripts/xcstrings_to_android.py && python3 scripts/gen_string_keys.py` regenerates the
Android resources. Never hand-edit the generated files.

## Tests

- Backend: `cd backend && npx vitest run`
- Android: `cd android && ./gradlew :app:testDebugUnitTest`
- iOS: the `NoTomorrowTests` scheme in Xcode

## License

MIT — see [LICENSE](LICENSE).
