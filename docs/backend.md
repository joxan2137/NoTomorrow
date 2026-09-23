# No Tomorrow backend — spec

Small API on Fly.io (region `ams`) that does four things: accounts, gym-bro pairing + schedule/attendance sync, push notifications, and the AI photo proxy. Nothing else lives here; workout and food logs stay on the phone.

## Stack

- Node 22, TypeScript (ESM), **Hono** 4.x, `postgres` (porsager) with hand-written SQL migrations (folder `migrations/`, applied at boot in order, tracked in a `schema_migrations` table), `zod` for input validation, `jose` for JWT/JWKS, `argon2` (node-argon2, argon2id m=19456 t=2 p=1), `@parse/node-apn` 8.x for APNs, `pg-boss` for scheduled jobs, `pino` logging. Dev: `tsx watch`; build: `tsc`; test: `vitest`.
- Config from env (validated with zod at boot): `PORT` (8080), `DATABASE_URL`, `JWT_SECRET` (HS256 for 15-min access tokens), `REFRESH_PEPPER`, `TOKEN_ENC_KEY` (32-byte base64, AES-256-GCM for stored Apple refresh tokens), `APPLE_TEAM_ID`, `APPLE_BUNDLE_ID` (= `aud`), `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY_P8_B64`, `GOOGLE_CLIENT_IDS` (comma-separated allowlist for `aud`), `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_P8_B64`, `APNS_TOPIC` (bundle id), `APNS_PRODUCTION` (bool), `GEMINI_API_KEY`, `GEMINI_MODEL` (default `gemini-3.8-flash`), `GEMINI_FALLBACK_MODELS` (comma-separated), `GEMINI_THINKING_LEVEL` (`low` default, `minimal|medium|high`), `AI_DAILY_LIMIT` (default 30, shared by `/ai/estimate` and `/ai/label`), `AI_ALLOWED_USERS` (comma-separated usernames, case-insensitive, allowed to use the server's Gemini key; empty = every signed-in user). Missing optional providers (Apple/Google/APNs/Gemini) must degrade to a clear 503 on those routes, not a crash at boot.
- `fly.toml`: `primary_region = "ams"`, `internal_port = 8080`, `force_https`, `auto_stop_machines = "stop"`, `auto_start_machines = true`, `min_machines_running = 1`, `[http_service.http_options] idle_timeout = 600`, health check `GET /healthz`, `[env] TZ = "UTC"`. Dockerfile: multi-stage, `node:22-slim`, non-root user.

## Schema (Postgres 16)

```sql
create extension if not exists citext;
create table users (
  id uuid primary key default gen_random_uuid(),
  username citext unique,
  password_hash text,
  email citext,
  email_verified boolean not null default false,
  display_name text not null default '',
  locale text not null default 'en',
  tz text not null default 'Europe/Warsaw',
  created_at timestamptz not null default now()
);
create table identities (
  user_id uuid not null references users(id) on delete cascade,
  provider text not null check (provider in ('apple','google','password')),
  subject text not null,
  email_at_link citext,
  apple_refresh_token_enc bytea,
  linked_at timestamptz not null default now(),
  primary key (provider, subject)
);
create table refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash text not null unique,          -- sha256(token + pepper)
  family uuid not null,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  replaced_by uuid,
  created_at timestamptz not null default now()
);
create table push_tokens (
  token text primary key,
  user_id uuid not null references users(id) on delete cascade,
  environment text not null check (environment in ('sandbox','production')),
  updated_at timestamptz not null default now()
);
create table pair_codes (
  code text primary key,                     -- "NT-7K4Q"
  user_id uuid not null references users(id) on delete cascade,
  expires_at timestamptz not null,
  used_at timestamptz
);
create table pairings (
  id uuid primary key default gen_random_uuid(),
  user_a uuid not null references users(id) on delete cascade,
  user_b uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  ended_at timestamptz,
  check (user_a <> user_b)
);
create unique index pairings_active_a on pairings(user_a) where ended_at is null;
create unique index pairings_active_b on pairings(user_b) where ended_at is null;
create table schedules (
  user_id uuid primary key references users(id) on delete cascade,
  weekdays int[] not null default '{1,3,5}',
  default_minute int not null default 1080,
  overrides jsonb not null default '{}',
  remind_hour_before boolean not null default true,
  ask_if_skipped_at_21 boolean not null default true,
  updated_at timestamptz not null default now()
);
create table attendance (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  status text not null check (status in ('planned','confirmed','attended','missed','cancelled')),
  scheduled_minute int not null,
  reason text, note text, make_up_day date,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);
create table heads_ups (
  id uuid primary key default gen_random_uuid(),
  from_user uuid not null references users(id) on delete cascade,
  to_user uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('cantMakeIt','runningLate','letsGo','custom','makeUpProposal')),
  text text not null default '' check (char_length(text) <= 80),
  session_day date not null,
  sent_at timestamptz not null default now(),
  read_at timestamptz
);
create table ai_usage (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  count int not null default 0,
  primary key (user_id, day)
);
```

## Auth

- Access token: HS256 JWT, `sub` = user id, 15 min. Refresh token: 32 random bytes base64url, stored as `sha256(token + REFRESH_PEPPER)`, 90 days, **rotated on every use**; presenting an already-rotated token revokes the whole `family` (reuse detection).
- `POST /auth/apple` `{identityToken, authorizationCode, fullName?, nonce?}` → verify the identity token with `jose.createRemoteJWKSet("https://appleid.apple.com/auth/keys")` (`iss` `https://appleid.apple.com`, `aud` = `APPLE_BUNDLE_ID`, `exp`), exchange the code at `https://appleid.apple.com/auth/token` with an ES256 client secret (`iss` team id, `sub` bundle id, `aud` `https://appleid.apple.com`, `exp` ≤ 6 months), encrypt and store the refresh token on the identity, upsert user (key on `sub`; store `fullName` if given — it only arrives once). Returns `{accessToken, refreshToken, user}`.
- `POST /auth/google` `{idToken}` → verify against `https://www.googleapis.com/oauth2/v3/certs`, `iss` in {`https://accounts.google.com`, `accounts.google.com`}, `aud` in `GOOGLE_CLIENT_IDS`, alg RS256 only. Key on `sub`.
- `POST /auth/register` `{username, password, email?}`: username 3–24 chars `[a-z0-9_.]` case-insensitive unique; password 10–128 chars, any characters, no composition rules, rejected if in the bundled top-10k breached list or containing the username; argon2id. Email optional (stored unverified, used for recovery later). Returns a session. `POST /auth/login` `{username, password}`: always run argon2 (dummy hash when the user is missing) and return the same generic 401 for unknown user / wrong password. Rate limit per username: 5 failures → exponential delay, hard lock after 100.
- `POST /auth/refresh` `{refreshToken}`, `POST /auth/logout` `{refreshToken}`.
- `POST /account/identities` (authenticated) links Apple/Google to the current user; refuses if the identity belongs to another user (409).
- `DELETE /me`: for each Apple identity call `https://appleid.apple.com/auth/revoke` with the stored refresh token; end pairings; delete the user row (cascades); 200 even if revoke fails (log it).
- `POST /apple/notifications`: Apple server-to-server events (JWS verified with the same JWKS): `consent-revoked` / `account-deleted` → run the delete path for that `sub`.
- Middleware `requireAuth` reads `Authorization: Bearer`, sets `c.get('userId')`.

## Pairing, schedule, attendance, heads-ups

- `POST /pair/code` → `{code, expiresAt}`; code `NT-` + 4 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, valid 24 h, one active code per user (regenerating replaces it).
- `POST /pair` `{code}` → creates the pairing (both must be unpaired; you cannot pair with yourself), marks the code used, pushes "<name> paired with you" to the code owner, returns `{partner: {id, displayName, pairedAt}}`.
- `DELETE /pair` → ends the active pairing, pushes to the partner.
- `PUT /schedule` upserts the caller's schedule (also used by the 21:00 and reminder jobs).
- `GET /partner` → `{partner, schedule, attendance: [...] (partner's rows from Monday of last week through Sunday of next week), headsUps: [...] (last 20 to/from the caller), myAttendance: [...]}` or 404 when unpaired.
- `PUT /attendance/:day` (`YYYY-MM-DD`) `{status, reason?, note?, makeUpDay?, scheduledMinute?}` upserts the caller's row and pushes to the partner: confirmed → "Tomek is in for 18:00"; cancelled → "Tomek can't make it today · <reason>" (+ "proposes Saturday" when `makeUpDay`); attended → no push. If `makeUpDay` is given, also insert a `planned` row for that day for the caller.
- `POST /headsups` `{kind, text, sessionDay}` (text ≤ 80 chars, trimmed) → stores, pushes to the partner with the text as body (`runningLate` → "Tomek: 15 min late", `letsGo` → "Tomek: Let's go", custom → the text). `POST /headsups/:id/read`.
- `GET /events`: SSE stream of `{type: "attendance"|"headsUp"|"pairing", ...}` for changes made by the partner (in-process pub/sub keyed by user id is enough for one machine), comment heartbeat every 25 s.
- `POST /push/token` `{token, environment}` upserts the APNs token.

## Push (APNs)

`@parse/node-apn` Provider with token auth (`key` from the base64 p8, `keyId`, `teamId`), `production` per env, topic `APNS_TOPIC`. Alerts: `apns-push-type: alert`, priority 10, payload `{aps: {alert: {title, body}, sound: "default", "thread-id": "bro"}, type, ...}`; localize title/body using the **recipient's** `locale` (en/pl strings live in `src/i18n.ts`). Remove tokens that come back `BadDeviceToken`/`Unregistered`.

Jobs (pg-boss, one cron every 5 minutes that scans due work, idempotent):
- Reminder: for every user with `remind_hour_before` whose schedule has a session today at minute M in their `tz`, and no attendance row with status attended/cancelled, send at M−60 (once; record in a `notifications_sent(user_id, day, kind)` table you add in a migration).
- 21:00 local check: for every user with `ask_if_skipped_at_21` and a gym day today whose attendance is still planned/confirmed, send "Did you skip today? Tap to log it." at 21:00 local (once). No auto-marking — the phone marks misses.

## AI proxy

Two routes spend the server's Gemini key: `POST /ai/estimate` (meal photo → foods) and `POST /ai/label` (photo of a nutrition table → per-100 g values). Prompts, JSON schemas, Atwater constants, limits and the generic Polish food table are **not in code**: they live in `backend/data/ai/estimate-spec.json`, which the iOS and Android apps bundle for their bring-your-own-key (BYOK) paths. `backend/src/aiFinalize.ts` is the reference finalizer; the apps port it 1:1 and all three run the shared fixtures in `backend/data/ai/fixtures/*.json` (`npm run ai:fixtures` checks the backend; see the README).

**Guards, identical for both routes, in this order.** `X-Anthropic-Key` header → `400 byok_is_device_direct` (BYOK always goes device → provider). No `GEMINI_API_KEY` → `503 ai_unavailable`. Not multipart → `400 multipart_required`. `AI_ALLOWED_USERS` set and the caller's username not on it → `403 ai_not_allowed`, before the body is read. Then field validation (`400 invalid_body`), the image (`400 image_required`, `413 image_too_large` over 4 MB, `400 image_not_jpeg`), then one unit of the shared `ai_usage` quota (`AI_DAILY_LIMIT` per user per UTC day, `429 ai_daily_limit` with `Retry-After: 3600`). The unit is refunded only when Gemini certainly did not bill the call (429/5xx or unreachable); an unusable answer or a timeout after the request left keeps it. Consent is collected by the apps before anything is sent (same consent keys for both routes).

**`POST /ai/estimate`** multipart: `image` (JPEG), `meal` (breakfast|lunch|snack|dinner), `locale` (en|pl, default en), `notes` (≤ 1500 characters; user details, "User corrections…" and "Measured reference…" lines). Response 200 (v2, backward compatible):

```jsonc
{ "version": 2,
  "foods": [{ "name": "Pierogi ruskie", "grams": 210,
              "kcal": 420, "protein": 13.7, "carbs": 63, "fat": 12.6,          // totals for grams
              "proteinG": 13.7, "carbsG": 63, "fatG": 12.6,                   // legacy aliases
              "confidence": 0.65, "isGuess": false,
              "per100": { "kcal": 200, "protein": 6.5, "carbs": 30, "fat": 6, "alcohol": 0 },
              "portionCount": 6, "portionUnit": "szt.", "gramsPerUnit": 35,
              "nutritionSource": "generic_table",   // estimated | generic_table | visible_label | user_notes | open_food_facts
              "cooking": "boiled", "genericKey": "pierogi_ruskie", "barcode": "",
              "adjustments": ["generic_table", "confidence_capped"] }],
  "totals": { "kcal": 420, "protein": 13.7, "carbs": 63, "fat": 12.6 },
  "overallConfidence": 0.65, "scaleReferenceUsed": "talerz obiadowy", "assumptions": ["…"], "questions": ["…"],
  "skipped": [{ "index": 1, "name": "Okrasa", "reason": "invalid_grams" }] }
```
Old app builds keep working: every v1 field (`name, grams, kcal, protein/proteinG, carbs/carbsG, fat/fatG, confidence, isGuess`, `overallConfidence`, `assumptions`, `questions`, `scaleReferenceUsed`) is still present with the same meaning. The model never returns totals any more: the finalizer computes them from `per100 × grams`, grounds `per100` in the generic table when the model picked a `genericKey` for an estimated item, repairs kcal that disagree with 4/4/9/7 (protein/carbs/fat/alcohol) for estimated values, keeps label values, skips a bad item instead of failing the estimate, and caps confidence at 0.65 unless the notes give a weight or a measured length. A readable barcode is then grounded with Open Food Facts (`nutritionSource: open_food_facts`).

**`POST /ai/label`** multipart: `image` (JPEG, the apps send up to 1600 px), `locale`. Response 200 — also when the table is unreadable (`legible: false`, `per100: null`):

```jsonc
{ "version": 1, "legible": true, "unreadableReason": null,   // illegible | no_energy | incomplete | no_serving_size | implausible
  "basis": "per100g", "energyFrom": "kcal",                  // per100g | per100ml | perServing ; kcal | kj
  "name": "Serek wiejski", "brand": "Piątnica",
  "per100": { "kcal": 97, "protein": 11, "carbs": 2, "fat": 5, "fiber": null, "sugar": 2, "salt": 0.63 },
  "servingSizeG": null, "packageSizeG": 200, "barcode": "", "confidence": 0.93, "needsReview": false }
```
kJ-only labels are converted (kcal = kJ / 4.184); per-portion-only labels are scaled by 100 / `servingSizeG`; per 100 ml is stored as per 100 g. `needsReview` flags numbers that do not add up (energy vs 4/4/9 + 2·fibre, sugar above carbs, confidence below 0.6).

**Errors (both routes)** beyond the guards: `503 ai_busy` (Gemini 429/5xx on every model, or unreachable), `504 ai_timeout` (35 s per attempt, 65 s in total), `502 ai_upstream_error` (other provider error, or an answer without text), `502 ai_unparseable` (no usable JSON). Body: `{"error": "<code>", "message": "<human>"}`.

**Gemini call.** Interactions API first:
```
POST https://generativelanguage.googleapis.com/v1beta/interactions      x-goog-api-key: GEMINI_API_KEY
{ "model": GEMINI_MODEL, "store": false,
  "system_instruction": <static prompt from the spec, one per language>,
  "generation_config": { "thinking_level": GEMINI_THINKING_LEVEL (default "low") },
  "input": [ {"type":"image","data":<base64>,"mime_type":"image/jpeg","resolution":"high"}, {"type":"text","text":<request text>} ],
  "response_format": { "type":"text", "mime_type":"application/json", "schema": <spec schema without additionalProperties> } }
```
On 404 (model not served there) or 400 (request shape refused; logged as a warning), the same model is retried on `POST /v1beta/models/{model}:generateContent` with `systemInstruction`, `contents: [{role:"user", parts:[{inlineData}, {text}]}]` and `generationConfig: {responseMimeType:"application/json", responseJsonSchema, thinkingConfig:{thinkingLevel}, mediaResolution:"MEDIA_RESOLUTION_HIGH"}`. No temperature (Gemini 3 wants the default). 429, 5xx and timeouts move on to the next model of `GEMINI_FALLBACK_MODELS`. Every successful call logs `ai usage` with model, API, input/output/thought/cached tokens and latency.

## Tests (vitest)

Pure-function tests that need no database: password policy, pair-code generation/charset, refresh-token rotation logic (against an in-memory fake of the token table), JWT issue/verify, APNs payload localization, the Gemini request shapes, fallbacks and quota refunds, and the AI finalizer against the shared fixtures in `data/ai/fixtures`. Integration tests may run only when `DATABASE_URL` is set.

## Repo layout

```
backend/
  package.json  tsconfig.json  Dockerfile  fly.toml  .env.example  README.md
  migrations/001_init.sql  002_notifications_sent.sql
  src/index.ts (boot: env, migrations, jobs, serve)  src/app.ts (Hono app + routes)
  src/env.ts  src/db.ts  src/auth/{jwt,apple,google,password,tokens}.ts  src/middleware/auth.ts
  src/routes/{auth,account,pair,schedule,attendance,headsups,events,push,ai,apple-notifications}.ts
  src/push.ts  src/ai.ts  src/aiSpec.ts  src/aiFinalize.ts  src/jobs.ts  src/i18n.ts  src/events.ts
  data/ai/estimate-spec.json  data/ai/fixtures/*.json  scripts/ai-fixtures.ts
  test/*.test.ts
```
README: local run (`docker run postgres`, `npm run dev`), migrations, `fly launch --no-deploy`, `fly postgres create`/`attach`, `fly secrets set` list, deploy, how the app points at the URL (`AppConfig.backendBaseURL`).
