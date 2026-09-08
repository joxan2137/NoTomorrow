# No Tomorrow — backend

Small API on Fly.io (`ams`) for the No Tomorrow iOS app. It does four things and nothing else:
accounts (Apple / Google / username+password), gym-bro pairing with schedule + attendance +
heads-up sync, APNs push, and the Gemini food-photo proxy. Workout and food logs stay on the phone.

Stack: Node 22, TypeScript (ESM), Hono 4, `postgres` (porsager) with hand-written SQL migrations,
zod, jose, argon2id, `@parse/node-apn`, pg-boss, pino. Full spec: `../docs/backend.md`.

## Local run

```bash
# 1. Postgres 16
docker run --name notomorrow-pg -e POSTGRES_USER=notomorrow -e POSTGRES_PASSWORD=notomorrow \
  -e POSTGRES_DB=notomorrow -p 5432:5432 -d postgres:16

# 2. Config
cp .env.example .env         # fill JWT_SECRET, REFRESH_PEPPER, TOKEN_ENC_KEY (see comments inside)
set -a; source .env; set +a  # or use your favourite dotenv loader

# 3. Run (migrations are applied automatically at boot)
npm install
npm run dev                  # tsx watch src/index.ts → http://localhost:8080
```

Generate secrets with `openssl rand -base64 48` (JWT_SECRET), `openssl rand -base64 32`
(REFRESH_PEPPER, TOKEN_ENC_KEY — the latter must decode to exactly 32 bytes).

## Who may use the Gemini proxy

The server's own `GEMINI_API_KEY` is only spent on the usernames in `AI_ALLOWED_USERS`
(comma-separated, case-insensitive; e.g. `fly secrets set AI_ALLOWED_USERS=test123`). Everyone
else gets `403 {"error": "ai_not_allowed"}` and the apps tell them to ask you for a spot on the
list or to add their own Gemini or Claude API key in Settings — those keys never touch this server:
the app then calls Google or Anthropic directly from the phone. Leave the variable empty to let
every signed-in user through (the boot log warns when Gemini is configured and the list is empty).

Optional providers (Apple, Google, APNs, Gemini) may be left blank: the server boots, logs a
warning, and the routes that need them answer `503 {"error": "..._unavailable"}`. A *partially*
configured provider is a boot error (it names the missing variables, never values).

`npm install` needs `legacy-peer-deps=true` (already in `.npmrc`) because of an npm/arborist bug
with vitest's optional peer dependencies.

### Scripts

| Command | What |
| --- | --- |
| `npm run dev` | `tsx watch` with restart on change |
| `npm run build` | `tsc` → `dist/` |
| `npm start` | `node dist/index.js` |
| `npm test` | vitest (pure tests always; integration tests only with `DATABASE_URL`) |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run breached:build` | regenerate `data/breached-passwords.txt` (top-10k list) |

### Tests

```bash
npm test                                                     # unit tests, no database needed
DATABASE_URL=postgres://notomorrow:notomorrow@localhost:5432/notomorrow npm test   # + integration
```

Integration tests run the real Hono app against Postgres (register → pair → schedule →
attendance → heads-ups → SSE → jobs → refresh rotation → logout → delete) with a fake push
service and a `fetch` that throws, so nothing ever touches the network.

## Migrations

`migrations/*.sql` are applied in lexical order at boot, each exactly once, tracked in
`schema_migrations`, under a Postgres advisory lock (two booting machines cannot race). Every
statement is `if not exists` so a half-applied file can be re-run. To add one: create
`migrations/003_something.sql`; it runs on the next boot / deploy. pg-boss keeps its own tables in
the `pgboss` schema.

## Deploy to Fly.io

```bash
fly auth login

# App (uses the committed fly.toml: region ams, port 8080, /healthz check, min 1 machine)
fly launch --no-deploy --copy-config --name notomorrow-api --region ams

# Database: unmanaged single-node Postgres in the same region
fly postgres create --name notomorrow-db --region ams --vm-size shared-cpu-1x \
  --initial-cluster-size 1 --volume-size 1
fly postgres attach notomorrow-db --app notomorrow-api      # sets DATABASE_URL

# Secrets (all at once; blank optional groups can simply be omitted)
fly secrets set --app notomorrow-api \
  JWT_SECRET="$(openssl rand -base64 48)" \
  REFRESH_PEPPER="$(openssl rand -base64 32)" \
  TOKEN_ENC_KEY="$(openssl rand -base64 32)" \
  APPLE_TEAM_ID=XXXXXXXXXX \
  APPLE_BUNDLE_ID=app.notomorrow.ios \
  APPLE_KEY_ID=XXXXXXXXXX \
  APPLE_PRIVATE_KEY_P8_B64="$(base64 -i AuthKey_SIWA.p8 | tr -d '\n')" \
  GOOGLE_CLIENT_IDS=xxxx.apps.googleusercontent.com \
  APNS_KEY_ID=XXXXXXXXXX \
  APNS_TEAM_ID=XXXXXXXXXX \
  APNS_P8_B64="$(base64 -i AuthKey_APNS.p8 | tr -d '\n')" \
  APNS_TOPIC=app.notomorrow.ios \
  APNS_PRODUCTION=true \
  GEMINI_API_KEY=... \
  GEMINI_MODEL=gemini-3.8-flash \
  AI_DAILY_LIMIT=30

fly deploy --app notomorrow-api
fly status --app notomorrow-api
fly logs --app notomorrow-api
```

Full secrets list (required in bold): **`DATABASE_URL`** (set by `fly postgres attach`),
**`JWT_SECRET`**, **`REFRESH_PEPPER`**, **`TOKEN_ENC_KEY`**, `APPLE_TEAM_ID`, `APPLE_BUNDLE_ID`,
`APPLE_KEY_ID`, `APPLE_PRIVATE_KEY_P8_B64`, `GOOGLE_CLIENT_IDS`, `APNS_KEY_ID`, `APNS_TEAM_ID`,
`APNS_P8_B64`, `APNS_TOPIC`, `APNS_PRODUCTION`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `AI_DAILY_LIMIT`.
Non-secret env (`PORT`, `TZ`, `LOG_LEVEL`, `NODE_ENV`, `JOBS_ENABLED`) lives in `fly.toml`.

Also register `https://<app>.fly.dev/apple/notifications` as the Sign in with Apple
server-to-server notification endpoint in the Apple developer portal.

## Pointing the app at it

`NoTomorrow/Services/AppConfig.swift` → `AppConfig.defaultBackendURL` is
`https://api.notomorrow.app`; put a CNAME to `notomorrow-api.fly.dev` behind it (`fly certs add
api.notomorrow.app`) or override at runtime: `AppConfig.backendBaseURL` is persisted in
`UserDefaults` under `nt.backendBaseURL`, so a debug setting (or `defaults write`) can point a
build at `http://localhost:8080` or the `.fly.dev` URL. `AppConfig.useMockBackend` switches the
in-memory mock off.

## API

All bodies are JSON; errors are `{"error": "<code>", "message": "<human>", "details"?: [...]}`.
Every route except the ones marked *public* needs `Authorization: Bearer <accessToken>`.

| Route | Notes |
| --- | --- |
| `GET /healthz` | public, `{ok: true}` (checks the DB) |
| `POST /auth/register` | public, `{username, password, email?}` → Session (201) |
| `POST /auth/login` / `POST /auth/password` | public, `{username, password}` → Session; generic 401; 429 + `Retry-After` when throttled |
| `POST /auth/apple` | public, `{identityToken, authorizationCode, fullName?, nonce?}` → Session |
| `POST /auth/google` | public, `{idToken}` → Session |
| `POST /auth/refresh` | public, `{refreshToken}` → Session (token rotated; reuse revokes the family) |
| `POST /auth/logout` | public, `{refreshToken}` |
| `GET /me` | Me |
| `PATCH /me` | `{displayName?, locale?, tz?}` → Me (locale/tz drive push copy and job timing) |
| `DELETE /me` / `DELETE /account` | revokes Apple tokens, ends pairings, deletes everything |
| `POST /account/identities` | `{provider: "apple"\|"google", ...sign-in body}`; 409 if owned by someone else |
| `POST /pair/code` | `{code, expiresAt}` — `NT-XXXX`, 24 h, one active per user |
| `POST /pair` | `{code}` → Partner (`{id, name, displayName, pairedAt, partner: {…}}`) |
| `DELETE /pair` | ends the pairing, notifies the partner |
| `GET /partner` | `{partner, schedule, attendance (partner's), myAttendance, headsUps}` or 404 `not_paired` |
| `GET /partner/state` | iOS `PartnerState`: `{partnerName, partnerSchedule, attendance (both, tagged `participant`), headsUps}` |
| `PUT /schedule` / `GET /schedule` | `{weekdays, defaultMinuteOfDay, overrides, remindHourBefore?, askIfSkippedAt21?}` |
| `PUT /attendance/:day` / `PUT /attendance` | `{status, reason?, note?, makeUpDay?, scheduledMinute?}` (+ `day` in the body form) |
| `POST /headsups` / `POST /headsup` | `{kind, text (≤ 80), sessionDay}` (201) |
| `POST /headsups/:id/read` | marks read |
| `GET /events` | SSE: `event: attendance\|headsUp\|pairing`, `data: {type, …}`; `: heartbeat` every 25 s |
| `POST /push/token` / `DELETE /push/token` | `{token (hex), environment?}`; 503 when APNs is not configured |
| `POST /ai/estimate` | multipart `image` (JPEG ≤ 4 MB), `meal`, `locale` → `{foods, overallConfidence}`; 429 past `AI_DAILY_LIMIT`; 400 `byok_is_device_direct` if `X-Anthropic-Key` is present |
| `POST /apple/notifications` | public, Apple server-to-server `{payload: <JWS>}` |

Session = `{accessToken (15 min JWT), refreshToken (90 d, rotating), userId, user: Me}`.

Dates on the wire are ISO-8601 UTC without fractional seconds (what Swift's `.iso8601` decoder
accepts). Day-valued fields (`day`, `sessionDay`, `makeUpDay`) are emitted as the instant of local
midnight in the caller's `tz`; on input they accept either `YYYY-MM-DD` or an ISO instant.

## Operational notes

- Push copy is localized with the **recipient's** `locale` (`en`/`pl`, `src/i18n.ts`); tokens that
  come back `BadDeviceToken`/`Unregistered`/410 are deleted.
- Jobs: one pg-boss cron every 5 minutes scans due reminders (session − 60 min) and the 21:00
  local "did you skip?" check; `notifications_sent` makes both idempotent. Set `JOBS_ENABLED=false`
  on any extra instance.
- Login throttle is in-memory (one machine): 5 failures → exponential delay, 100 → 24 h lock.
- Logs never contain bodies, headers, query strings or bound SQL parameters (custom pino error
  serializer + redaction).
- Graceful shutdown on SIGTERM: stop accepting, drop SSE connections after 2 s, stop pg-boss,
  close APNs and Postgres, exit (10 s hard deadline).
