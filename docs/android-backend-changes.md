# Backend changes required by the Android client

Everything the Fly.io service in `backend/` must change before the Android app works end to end.
Nothing here is optional-but-nice: each item is a route the Android client already calls, or a
gate that rejects it today.

**Scope.** This document is written by the Android side and describes *what* must change and
*why*, referencing the exact file and line. No `backend/` code was touched. Every route, DTO,
error code and the `src/i18n.ts` push copy is otherwise platform-neutral and is reused verbatim —
the Android client is a second consumer of the same contract, not a fork of it.

Source of the analysis: `docs/android-research.md` §7.8, `docs/android-architecture.md`
("Push registration", "Backend contract"), and the Android call sites listed per item.

---

## 1. `POST /push/token` — accept Android tokens

**File:** `backend/src/routes/push.ts`
**Android call site:** `net/RemoteBackendClient.kt` → `transport.sendVoid("POST", "push/token", PushTokenBody(token, platform))`
**Body the client sends:** `{ "token": "<FCM registration token>", "platform": "android" }`

Four separate blockers, all in the same 35-line file. Fixing only the first three still leaves
Android tokens undeliverable.

### 1.1 The token schema rejects every FCM token

```ts
// routes/push.ts:8
token: z.string().regex(/^[0-9a-fA-F]{32,512}$/, 'token must be the APNs device token as hex'),
```

FCM registration tokens are not hex: they contain `:`, `-`, `_` and mixed case, and run to ~160+
characters. **Every Android registration 400s.** Branch the schema on `platform`:

```ts
const platformSchema = z.enum(['ios', 'android']).default('ios');
const tokenSchema = z.discriminatedUnion('platform', [
  z.object({ platform: z.literal('ios'),     token: z.string().regex(/^[0-9a-fA-F]{32,512}$/), environment: z.enum(['sandbox','production']).optional() }),
  z.object({ platform: z.literal('android'), token: z.string().min(64).max(4096).regex(/^[A-Za-z0-9_:\-.]+$/) }),
]);
```

An absent `platform` must keep meaning `ios`, so shipped iOS builds (which send no `platform`
field at all — `RemoteBackendClient.swift:88` posts `{token}`) keep working unchanged.

### 1.2 `toLowerCase()` corrupts FCM tokens

```ts
// routes/push.ts:22 (insert) and :30 (delete)
values (${body.token.toLowerCase()}, …)
```

Harmless for hex APNs tokens, **destructive** for case-sensitive FCM tokens: the row is stored
under a token FCM will not recognise, and the `DELETE` never matches the row it was given.
Lowercase only on the `ios` branch.

### 1.3 The `environment` column and the send-time filter exclude Android

```sql
-- migrations/001_init.sql:42-47
environment text not null check (environment in ('sandbox','production'))
```

```ts
// push.ts:63
select token from push_tokens where user_id = ${userId} and environment = ${environment}
```

`environment` is an APNs concept. Adding a `platform` column without touching this query still
filters every Android row out of every send. Migration:

```sql
-- migrations/003_push_platform.sql
alter table push_tokens add column if not exists platform text not null default 'ios'
  check (platform in ('ios','android'));
alter table push_tokens alter column environment drop not null;   -- null for android
alter table push_tokens drop constraint if exists push_tokens_environment_check;
alter table push_tokens add constraint push_tokens_environment_check
  check (environment is null or environment in ('sandbox','production'));
create index if not exists push_tokens_user_platform on push_tokens(user_id, platform);
```

and the send path selects `token, platform` for the user, filtering `environment` only on the
iOS rows.

### 1.4 The 503 gate blocks an FCM-only deployment

```ts
// routes/push.ts:17
if (!deps.env.apns) throw new HttpError(503, 'push_unavailable', …);
```

A deployment configured for FCM but not APNs cannot accept *any* token. Gate on
`deps.env.apns || deps.env.fcm`, and 503 only when the requested platform's provider is missing —
same "clear 503 when a provider is unconfigured" convention `src/env.ts` already uses.

---

## 2. `DELETE /push/token` — same fixes, and it is now actually called

**File:** `backend/src/routes/push.ts:29-33`
**Android call site:** `net/RemoteBackendClient.kt` → `unregisterPushToken(token)`, invoked by
`push/PushRegistrar.unregister()` on sign-out, **before** the session is cleared (the route needs
the bearer).

The route already exists and is correct in shape (`{token}` matched against the bearer's
`user_id`). It inherits both 1.1 and 1.2 — it reuses `tokenSchema.pick({ token: true })` and
lowercases — so the same schema branch must apply. Note the pick loses the discriminator: give
the delete its own body schema, e.g. `z.object({ token: z.string().min(32).max(4096) })` with no
case folding, since a delete is an exact-match lookup and does not need to know the platform.

iOS never calls this route (`Services/BackendClient.swift:29` declares `registerPushToken` alone);
Android must, because an FCM token survives sign-out until `FirebaseMessaging.deleteToken()`
completes and would otherwise keep delivering the previous account's pushes to the same install.

---

## 3. FCM sender — Firebase Admin / HTTP v1

**File:** `backend/src/push.ts` (currently `@parse/node-apn` only)

`createPushService` builds an `apn.Provider` and sends `apn.Notification`s. Android needs an FCM
sender for the `platform = 'android'` rows. Two acceptable shapes:

**(a) Preferred — replace the direct APNs sender with `firebase-admin`.** FCM HTTP v1 delivers to
both platforms from one call with per-platform override blocks:

```ts
await messaging.sendEach(tokens.map((t) => ({
  token: t.token,
  data: { type: msg.kind, ...extra },                    // the client's data contract, unchanged
  android: { priority: 'high', notification: { title, body, channelId: 'nt.headsup' } },
  apns:    { payload: { aps: { alert: { title, body }, sound: 'default', 'thread-id': 'bro' } } },
})));
```

This removes APNs JWT rotation from the Fly.io service and gives one retry and error path.

**(b) Keep `@parse/node-apn` and add a parallel FCM path.** Also fine; the requirement is only the
platform column and a send-time branch.

Either way:

- **Dead-token cleanup must learn the FCM vocabulary.** `push.ts:37` keys on
  `410 / BadDeviceToken / Unregistered / DeviceTokenNotForTopic`. FCM reports
  `messaging/registration-token-not-registered` (HTTP 404 `UNREGISTERED`) and
  `messaging/invalid-argument` (400 `INVALID_ARGUMENT`) — map both onto the existing
  `delete from push_tokens where token in …`.
- **The Android channel id is `nt.headsup`** (`NoTomorrowApp.kt` → `NtChannels.HEADS_UPS`, repeated
  in `AndroidManifest.xml` as `com.google.firebase.messaging.default_notification_channel_id`).
  A `notification` block without a matching `channelId` lands on the FCM SDK's fallback channel.
- **The client's data contract is unchanged:** `kind ∈ heads_up | partner_confirmed |
  partner_cancelled | pair_accepted`, plus `sessionDay`, `partnerName`, `text`
  (`push/PushPayload.kt`). Copy stays server-localised from `users.locale` via `src/i18n.ts` —
  **that file is reusable verbatim**, the Android client only routes.
- **Prefer a data-only payload** for the app-drawn notifications (`push/PushNotifier.kt` builds
  them). A `notification` block is rendered by the FCM SDK while the app is backgrounded, which
  bypasses `PushNotifier` and its deep-link `PendingIntent` extras.

**Config.** Add to `src/env.ts` alongside the existing APNs group:

| Var | Meaning |
|---|---|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Base64 or raw service-account JSON for the Firebase project. Absent ⇒ `fcm: null` ⇒ a clear 503 on the Android branch, matching the `providerGroup` convention already used for APNs and Apple. |

---

## 4. `GOOGLE_CLIENT_IDS` — append the Android client id

**File:** `backend/src/env.ts:29,148-150`; verifier `backend/src/auth/google.ts`
**Android call site:** `SignInSheet` → Credential Manager (`GetSignInWithGoogleOption`) →
`signInGoogle(idToken)` → `POST /auth/google`.

`verifyGoogleIdToken` checks `aud` against the `GOOGLE_CLIENT_IDS` allowlist. Google Sign-In on
Android issues an ID token whose `aud` is the **Web** client id of the same Google Cloud project
(the Android OAuth client is used for the SHA-1 binding, not as the audience), so:

- append that Web client id to the comma-separated `GOOGLE_CLIENT_IDS`, and
- keep the iOS client id in the list — the list is an allowlist, not a swap.

Without it every Android Google sign-in fails `401 invalid_identity_token`. This is a
configuration change, not a code change.

**Related, decide once:** `backend/src/auth/jwt.ts:5` hard-codes `AUDIENCE = 'notomorrow-ios'` for
the app's *own* access tokens. It is issued and verified by the same service, so Android works as
is — but the name is now wrong. Either widen it to a set (`['notomorrow-ios','notomorrow-android']`,
which needs the verifier to accept both **before** any client mints the new value) or rename it to
a product-wide `'notomorrow'` in a single deploy that changes both sides at once. Renaming
invalidates every live access token; refresh tokens are unaffected, so the cost is one extra
refresh round-trip per session. **Recommendation: leave it alone** — it is an internal constant,
not a contract.

---

## 5. Sign in with Apple on Android — no backend work

**Status: not shipped on Android; `backend/` needs no change.**

`POST /auth/apple` and `resolveAppleIdentity` (`src/auth/social.ts:35`) stay exactly as they are.

- The Android `SignInSheet` contract (`docs/android-architecture.md`, "Auth") ships
  **username/password + Google** only. Google is the mirror image of iOS: disabled at 0.4 opacity
  on iOS, working on Android.
- `BackendClient.signInApple(identityToken, authorizationCode)` remains in the Kotlin interface
  (`net/BackendClient.kt`) for 1:1 parity with the Swift protocol, and `RemoteBackendClient`
  implements it, but **nothing on Android calls it** — Sign in with Apple on Android would require
  the Apple JS web flow in a Custom Tab, a `services` identifier, and a `redirect_uri` the server
  would have to host. That is a product decision, not a port blocker.
- If it is ever wanted: the existing route needs a **web** `client_id` (the Apple *Services ID*,
  not `APPLE_BUNDLE_ID`) for the code exchange, because `exchangeAppleCode` sends
  `config.bundleId` as the `client_id` and Apple rejects a bundle id for a web-originated code.
  That is the only server change it would need.

An account created on iOS through Apple and one created on Android through Google are distinct
identities unless they resolve to the same verified email; the existing identity-merge behaviour in
`src/auth/social.ts` is unchanged by anything here.

---

## 6. Nice-to-haves (not blockers)

- **`User-Agent`.** The Android client should send `NoTomorrow/<version> Android`; no server
  change needed, but it makes the Fly logs readable per platform.
- **`/health` unchanged**, `/me`, `/pair*`, `/schedule`, `/attendance`, `/partner`, `/headsups`,
  `/ai/estimate` and every error code (`ai_daily_limit`, `ai_upstream_error`, `ai_unavailable`,
  `pair_*`, `auth_*`) are consumed as-is by `net/RemoteBackendClient.kt`. **No change.**
- **`POST /ai/estimate`** already accepts multipart `image` + `meal` + `locale` and the optional
  `X-Anthropic-Key` BYOK header; the tolerant `AIFood` decoder on Android
  (`net/dto/AiDto.kt`) accepts the `proteinG` keys the server emits. **No change.**

---

## Checklist

| # | Change | File | Blocker? |
|---|---|---|---|
| 1.1 | Per-platform token schema | `routes/push.ts:8` | **Yes** — every Android registration 400s |
| 1.2 | Drop `toLowerCase()` for FCM | `routes/push.ts:22,30` | **Yes** — corrupts the token |
| 1.3 | `platform` column + platform-aware send query | `migrations/`, `push.ts:63` | **Yes** — Android rows never selected |
| 1.4 | Gate on `apns \|\| fcm` | `routes/push.ts:17` | **Yes** for an FCM-only deploy |
| 2 | Delete-route body schema without case folding | `routes/push.ts:29` | **Yes** — sign-out leaves a live row |
| 3 | FCM sender + `GOOGLE_SERVICE_ACCOUNT_JSON` + FCM dead-token codes | `push.ts`, `env.ts` | **Yes** — nothing is delivered |
| 4 | Append the Android Web client id | `GOOGLE_CLIENT_IDS` (config) | **Yes** for Google sign-in |
| 5 | Sign in with Apple on Android | — | No — out of scope |
| 6 | `jwt.ts` audience name | `auth/jwt.ts:5` | No — internal constant |
