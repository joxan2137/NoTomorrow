# Android push — what ships, what the backend still needs

The Android client's push code is complete and inert. `app/src/main/java/app/notomorrow/push/`
contains `NtMessagingService` (FCM receiver), `PushNotifier` (renders the alert on the
`nt.headsup` channel), `PushRegistrar` (token → `POST push/token`), `PushPayload` (payload
contract) and `PushPermission` (the `POST_NOTIFICATIONS` helper).

Two things are missing, and neither is in the app's source tree:

1. **`android/app/google-services.json`** — without it there is no default `FirebaseApp`,
   the FCM SDK never starts, `onNewToken`/`onMessageReceived` never fire, and
   `PushRegistrar` logs one line per call and returns. The app runs normally; push is simply
   off. See §3.
2. **An FCM sender in the backend.** `backend/src/push.ts` speaks APNs only. See §1.

Nothing in the HTTP contract changes: every route, DTO and error code is platform-neutral,
and `backend/src/i18n.ts` (the recipient-locale copy, en + pl) is reused **verbatim** — all
notification copy stays server-side, localized from `users.locale`. The client only displays
and routes.

---

## 1. Backend delta

### 1.1 Schema

`migrations/001_init.sql:42-47` has no platform and constrains `environment` to
`'sandbox' | 'production'`, which are APNs concepts. Add a migration
(`003_push_platform.sql`):

```sql
alter table push_tokens add column if not exists platform text not null default 'ios';
alter table push_tokens drop constraint if exists push_tokens_environment_check;
alter table push_tokens add constraint push_tokens_platform_check
  check (platform in ('ios', 'android'));
-- Android rows carry environment 'production'; the column stays meaningful for APNs only.
create index if not exists push_tokens_user_platform on push_tokens(user_id, platform);
```

### 1.2 `routes/push.ts` — four blockers, all in one file

| Line | Today | Change |
|---|---|---|
| 8 | `token: z.string().regex(/^[0-9a-fA-F]{32,512}$/)` | FCM registration tokens contain `:`, `-`, `_` and mixed case, so **every Android registration 400s**. Branch the schema on `platform`: APNs keeps the hex regex, Android takes `z.string().min(64).max(4096).regex(/^[A-Za-z0-9_:.\-]+$/)`. |
| 17 | `if (!deps.env.apns) throw new HttpError(503, 'push_unavailable', …)` | Gate on `apns || fcm`, otherwise an FCM-only deployment cannot accept any token. |
| 22, 30 | `body.token.toLowerCase()` | Harmless for hex APNs tokens, **destructive** for case-sensitive FCM tokens. Lowercase only when `platform === 'ios'`. |
| — | no `platform` in the body | Accept `platform: z.enum(['ios','android']).default('ios')` and store it. The Android client already sends `{ token, platform: "android" }`. |

The client's registration body is exactly:

```json
{ "token": "<FCM registration token>", "platform": "android" }
```

`DELETE push/token` should accept the same shape. The Android client does **not** call it on
sign-out today (see §4), it deletes the device token instead; server-side pruning of dead
tokens (§1.4) covers the orphan.

### 1.3 `push.ts` — the send path

`push.ts:63` selects `where user_id = … and environment = <sandbox|production>`, which
filters every Android row out of every send even after the column exists. Make the recipient
query platform-aware and fan out per platform.

Recommended shape — replace the direct APNs provider with `firebase-admin` and keep APNs as
one of two branches, or (cleaner) let `firebase-admin` do both through FCM HTTP v1's
per-platform override blocks:

```ts
import { initializeApp, cert } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';

const app = initializeApp({ credential: cert(JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON)) });

// per recipient, per token batch:
await getMessaging(app).sendEachForMulticast({
  tokens,                                   // android rows only, if you keep node-apn for iOS
  data: {                                   // DATA-ONLY — see the note below
    title, body,                            // already localized via pushText(locale, msg)
    type: msg.kind,                         // 'paired' | 'unpaired' | 'attendanceConfirmed'
                                            // | 'attendanceCancelled' | 'headsUp'
                                            // | 'reminder' | 'skipCheck'
    ...stringifyExtra(extra),               // every value must be a string
  },
  android: {
    priority: 'high',
    collapseKey: msg.kind,
    ttl: 3600 * 1000,                       // matches the APNs `expiry` already set
  },
});
```

**Send data-only messages.** If the message carries a `notification` block, the system
displays it itself while the app is backgrounded and `onMessageReceived` never runs — the
deep link and the group/collapse behaviour are then lost. The client handles a `notification`
block as a fallback, but the contract is data-only.

**Every FCM data value must be a string.** `extra` currently mixes strings and
booleans/nulls; JSON-stringify or drop nullish values.

### 1.4 Payload mapping — APNs today → FCM data keys

`buildApnsPayload` (`push.ts:15-24`) produces `{ ...extra, aps: { alert: { title, body },
sound: 'default', 'thread-id': 'bro' }, type: msg.kind }`. The Android client
(`PushPayload.from`) reads the flattened equivalent:

| APNs field | FCM data key | Client use |
|---|---|---|
| `aps.alert.title` | `title` | Notification title. Missing ⇒ `push_fallback_title` ("Gym bro" / "Ziomek z siłowni", matching `TITLE_BRO`). |
| `aps.alert.body` | `body` | Notification text (`BigTextStyle`). Alias accepted: `message`. |
| `aps.sound` | — | Channel `nt.headsup` (`IMPORTANCE_HIGH`) supplies sound and vibration. |
| `aps['thread-id']` | `thread-id` (optional) | Notification group; defaults to `"bro"`. |
| `type` | `type` | `PushKind`. Alias accepted: `kind`. |
| `extra.sessionDay` (heads-ups) | `sessionDay` | Passed to the activity as `EXTRA_SESSION_DAY`. |
| `extra.day` (attendance, jobs) | `day` | Same field; read as `sessionDay` when the former is absent. |
| `extra.partnerId` | `partnerId` | Part of the collapse identity. |
| `extra.headsUpId` | `headsUpId` | Collapse identity — a re-delivered heads-up replaces its notification. |
| `extra.headsUpKind` | `headsUpKind` | Reserved; not rendered. |
| — | `route` (optional) | Overrides the routing target. Otherwise every kind routes to `bro`. |

`type` values are `PushMessage['kind']` from `backend/src/i18n.ts`:
`paired`, `unpaired`, `attendanceConfirmed`, `attendanceCancelled`, `headsUp`, `reminder`,
`skipCheck`. The client additionally accepts the snake_case aliases named in
`docs/android-architecture.md` (`heads_up`, `partner_confirmed`, `partner_cancelled`,
`pair_accepted`), so either naming works — but pick one and keep it.

### 1.5 Dead-token cleanup

`push.ts` currently deletes tokens on APNs `410` / `BadDeviceToken` / `Unregistered` /
`DeviceTokenNotForTopic`. Map the FCM equivalents onto the same path:
`messaging/registration-token-not-registered`, `messaging/invalid-registration-token`,
`messaging/invalid-argument` (for a malformed token), and HTTP `404`/`400` from
`sendEachForMulticast`'s per-token responses.

### 1.6 `env.ts`

Add `GOOGLE_SERVICE_ACCOUNT_JSON` (a base64-encoded service-account JSON is the friendlier
Fly.io secret: `fly secrets set GOOGLE_SERVICE_ACCOUNT_B64=…`), degrading to a clear 503 when
absent — the same convention `ApnsConfig` already uses (`env.ts:156-167`). Expose
`env.fcm: FcmConfig | null` and gate `/push/token` on `apns || fcm`.

### 1.7 Two unrelated one-liners, needed before Android can be tested end-to-end

- Append the Android **Web client id** to `GOOGLE_CLIENT_IDS` (Credential Manager sends the
  Web client id as the audience, not the Android one).
- `auth/jwt.ts` hard-codes `aud: "notomorrow-ios"`. Either widen it to a set or keep it as a
  product-wide constant — decide explicitly.

---

## 2. What the client does

- **Token.** `PushRegistrar.register()` reads `FirebaseMessaging.getInstance().token` and
  POSTs `push/token` with `platform = "android"`. Called after a successful sign-in
  (`AuthSheet` → `BroService.didSignIn()`), and again from `NtMessagingService.onNewToken`
  whenever FCM rotates. Failures are logged, never surfaced — an unregistered device still
  works.
- **Availability.** Every entry point first checks `FirebaseApp.getApps(context).isNotEmpty()`.
  With no `google-services.json` this is false and the call is a logged no-op.
- **Display.** `PushNotifier` posts on channel `nt.headsup` (created in `NoTomorrowApp`, on
  the first *foreground* launch — a channel first created from a background process cannot
  show notifications). Small icon `ic_launcher_monochrome`, colour `NT.Colors.ember`,
  `BigTextStyle`, group `"bro"`, `autoCancel`. Notification ids start at 2700 (the rest timer
  owns 2601/2602) and are derived from the payload's collapse identity, so a re-delivery
  replaces rather than stacks.
- **Routing.** The content intent opens `MainActivity` (`singleTask`) with
  `NtPushIntents.EXTRA_ROUTE` (= `AppState.pendingRoute`, `"bro"` for every kind today),
  plus `EXTRA_KIND` and `EXTRA_SESSION_DAY`.
- **Permission.** `POST_NOTIFICATIONS` is a runtime permission from API 33.
  `rememberPushPermissionState()` wraps the request and re-reads on `ON_RESUME`; the
  onboarding Schedule step asks for it beside the reminder toggles, and Settings →
  Notifications reflects the live state.

---

## 3. Getting `google-services.json`

1. Firebase console → **Add project** (or reuse the GCP project that will own
   `GOOGLE_CLIENT_IDS`). One project, two apps: iOS and Android — the iOS app can keep using
   APNs directly.
2. **Add app → Android.** Package name **`app.notomorrow.android`** (the `applicationId`,
   *not* the `app.notomorrow` namespace). Add the debug and release signing SHA-1/SHA-256
   fingerprints — required for Credential Manager / Google sign-in, not for FCM.
3. Download `google-services.json` into `android/app/`. It is not a secret, but it is
   environment-specific; commit it or provision it in CI, consistently.
4. Enable the plugin, which is deliberately absent today:
   - `android/build.gradle.kts` (root `plugins` block): `alias(libs.plugins.google.services) apply false`
   - `android/app/build.gradle.kts`: add `alias(libs.plugins.google.services)` and delete the
     "No `com.google.gms.google-services`" comment.
   `libs.versions.toml` already pins `googleServices = "4.5.0"` and the
   `firebase-bom` / `firebase-messaging` dependencies are already declared, so no dependency
   change is needed.
5. Backend credential: Firebase console → **Project settings → Service accounts → Generate
   new private key**. That JSON becomes `GOOGLE_SERVICE_ACCOUNT_JSON` (§1.6). It **is** a
   secret: Fly.io secret only, never in the repo.
6. Verify: install a debug build, sign in, and check `adb logcat -s NtPush` for
   `push token registered`. Then trigger a heads-up from the paired account.

Adding the plugin makes `google-services.json` **mandatory** for every build — the plugin
fails the build when it is missing. Keep the plugin out of the tree until the file is in it.

---

## 4. Known gaps

- **Sign-out does not delete the server row.** `PushRegistrar.unregister()` calls
  `FirebaseMessaging.deleteToken()` so the device stops receiving the signed-out account's
  pushes, but `BackendClient` has no `unregisterPushToken`, and by the time sign-out runs the
  bearer is already gone. Either add `suspend fun unregisterPushToken(token: String)` to
  `BackendClient` (`DELETE push/token`) and call it *before* clearing the session, or rely on
  §1.5's dead-token cleanup. Pick one; today it is the latter.
- **`environment`** is meaningless for Android rows. They are written as `production`; do not
  filter Android sends on it.
- **No silent-data handling.** A push with no `title`/`body` is dropped with a debug log;
  the Bro tab reconciles from `partnerState()` when it next becomes visible.
